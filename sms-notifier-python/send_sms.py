#!/usr/bin/env python3
"""Send templated SMS reminders to patients listed in a CSV, via Azure Communication Services.

Usage:
    python send_sms.py --csv patients.csv
    python send_sms.py --csv patients.csv --dry-run
    python send_sms.py --csv patients.csv --batch-size 10 --batch-pause-seconds 10

CSV must have a header row with columns: first_name, last_name, phone_number, practice_id, client_id
(column names are matched case-insensitively). The recipient (to) number comes from phone_number.
practice_id/client_id are looked up against DB_Metadata.dbo.Metadata_ClientPractice to resolve the
sending practice for each row, and the sender (from) number comes from that practice's
collect_phone_number column.

Every outcome (success or failure) is appended to --log-file (default: sent_log.csv). On the next
run, any (practice_id, phone_number) already logged as 'success' is skipped automatically so the
same patient doesn't get texted twice; pass --resend to ignore the log and send to everyone again.

Required environment variables (e.g. via a .env file):
    SQL_CONNECTION_STRING                 - connection string for the server hosting DB_Metadata
    AZURE_COMMUNICATION_CONNECTION_STRING - connection string for the ACS resource
"""

import argparse
import csv
import logging
import os
import sys
import time
from dataclasses import dataclass
from datetime import datetime, timezone
from typing import Optional

from azure.communication.sms import SmsClient
from dotenv import load_dotenv

from db_metadata import connect_db, load_client_practice_map

logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s - %(levelname)s - %(message)s',
    handlers=[logging.StreamHandler(sys.stdout)]
)
logger = logging.getLogger(__name__)
logging.getLogger('azure.core.pipeline.policies.http_logging_policy').setLevel(logging.WARNING)

# Edit this to change the outgoing message. {first_name}/{last_name}/{practice_name} are filled in per row.
MESSAGE_TEMPLATE = (
    "Thank you for being a part of {practice_name}. If you recently received a message about a balance, "
    "please disregard it. It was sent in error due to a system issue. We’re reviewing your account and "
    "will follow up if anything is needed. Thank you for your understanding."
)

REQUIRED_COLUMNS = ('first_name', 'last_name', 'phone_number', 'practice_id', 'client_id')
MAX_RETRIES = 3
RETRY_DELAY_SECONDS = 2.0

# After every BATCH_SIZE messages, pause BATCH_PAUSE_SECONDS to avoid overwhelming ACS/carriers.
# Many rows share the same practice (same sending number), and unregistered/low-trust 10DLC numbers
# are commonly throttled to ~1 msg/sec by carriers, so this averages out to roughly that rate.
BATCH_SIZE = 50
BATCH_PAUSE_SECONDS = 5.0

# Record of patients already texted, so re-running the same CSV doesn't message them again.
DEFAULT_SENT_LOG_PATH = 'sent_log.csv'
SENT_LOG_FIELDS = ('timestamp', 'practice_id', 'practice_name', 'from_number', 'to_number', 'status', 'message_id')


@dataclass
class Recipient:
    first_name: str
    last_name: str
    phone_number: str
    practice_id: int
    client_id: int


def normalize_phone_number(raw: str) -> Optional[str]:
    """Best-effort conversion to E.164. Returns None if the number can't be normalized."""
    digits = ''.join(ch for ch in raw if ch.isdigit() or ch == '+')
    if not digits:
        return None
    if digits.startswith('+'):
        return digits
    if len(digits) == 10:
        return f'+1{digits}'
    if len(digits) == 11 and digits.startswith('1'):
        return f'+{digits}'
    return None


def load_recipients(csv_path: str) -> list:
    with open(csv_path, newline='', encoding='utf-8-sig') as f:
        reader = csv.DictReader(f)
        fieldnames = {name.strip().lower(): name for name in (reader.fieldnames or [])}

        missing = [col for col in REQUIRED_COLUMNS if col not in fieldnames]
        if missing:
            raise ValueError(f"CSV is missing required column(s): {', '.join(missing)}")

        recipients = []
        for line_num, row in enumerate(reader, start=2):
            first_name = (row.get(fieldnames['first_name']) or '').strip()
            last_name = (row.get(fieldnames['last_name']) or '').strip()
            phone_raw = (row.get(fieldnames['phone_number']) or '').strip()
            practice_id_raw = (row.get(fieldnames['practice_id']) or '').strip()
            client_id_raw = (row.get(fieldnames['client_id']) or '').strip()

            if not phone_raw or phone_raw.lower() in ('null', 'none', 'n/a', 'na'):
                logger.warning(f"Line {line_num}: skipping row with null/empty phone_number")
                continue

            phone_number = normalize_phone_number(phone_raw)
            if not phone_number:
                logger.warning(f"Line {line_num}: skipping unparseable phone_number '{phone_raw}'")
                continue

            if not practice_id_raw.isdigit() or not client_id_raw.isdigit():
                logger.warning(f"Line {line_num}: skipping row with invalid practice_id/client_id "
                               f"('{practice_id_raw}', '{client_id_raw}')")
                continue

            recipients.append(Recipient(
                first_name=first_name,
                last_name=last_name,
                phone_number=phone_number,
                practice_id=int(practice_id_raw),
                client_id=int(client_id_raw),
            ))

        return recipients


def resolve_practice(recipient: Recipient, practice_map: dict) -> Optional[dict]:
    """Look up and validate the practice for a recipient. Returns None if it can't be resolved."""
    practice = practice_map.get(recipient.practice_id)
    if not practice:
        logger.error(f"No Metadata_ClientPractice row for practice_id={recipient.practice_id} "
                     f"({recipient.phone_number}), skipping")
        return None

    if practice['ClientID'] != recipient.client_id:
        logger.error(f"client_id mismatch for practice_id={recipient.practice_id}: "
                     f"CSV has {recipient.client_id}, Metadata_ClientPractice has {practice['ClientID']} "
                     f"({recipient.phone_number}), skipping")
        return None

    if not practice.get('is_active'):
        logger.warning(f"practice_id={recipient.practice_id} is not active, skipping ({recipient.phone_number})")
        return None

    from_number = normalize_phone_number((practice.get('collect_phone_number') or '').strip())
    if not from_number:
        logger.error(f"practice_id={recipient.practice_id} has no usable collect_phone_number, "
                     f"skipping ({recipient.phone_number})")
        return None
    practice['collect_phone_number'] = from_number

    return practice


def render_message(recipient: Recipient, practice: dict) -> str:
    return MESSAGE_TEMPLATE.format(
        first_name=recipient.first_name,
        last_name=recipient.last_name,
        practice_name=practice['PracticeName'],
    )


def load_sent_log(log_path: str) -> set:
    """Return the set of (practice_id, phone_number) already successfully texted, per log_path."""
    if not os.path.exists(log_path):
        return set()

    sent = set()
    with open(log_path, newline='', encoding='utf-8') as f:
        for row in csv.DictReader(f):
            if row.get('status') == 'success':
                sent.add((int(row['practice_id']), row['to_number']))

    return sent


def append_sent_log(log_path: str, practice: dict, recipient: Recipient, status: str, message_id: str = ''):
    """Append one outcome row to log_path, writing the header first if the file is new."""
    is_new = not os.path.exists(log_path)
    with open(log_path, 'a', newline='', encoding='utf-8') as f:
        writer = csv.DictWriter(f, fieldnames=SENT_LOG_FIELDS)
        if is_new:
            writer.writeheader()
        writer.writerow({
            'timestamp': datetime.now(timezone.utc).isoformat(),
            'practice_id': recipient.practice_id,
            'practice_name': practice['PracticeName'],
            'from_number': practice['collect_phone_number'],
            'to_number': recipient.phone_number,
            'status': status,
            'message_id': message_id,
        })
        f.flush()


def send_with_retry(sms_client: SmsClient, practice: dict, recipient: Recipient, message: str) -> tuple:
    """Returns (success, message_id). message_id is '' when the send failed."""
    from_number = practice['collect_phone_number']
    practice_label = f"{practice['PracticeID']}:{practice['PracticeName']}"

    for attempt in range(1, MAX_RETRIES + 1):
        try:
            results = sms_client.send(from_=from_number, to=[recipient.phone_number], message=message)
            result = results[0]
            if result.successful:
                logger.info(f"SMS sent | practice={practice_label} | from={from_number} | "
                            f"to={recipient.phone_number} | status=success | message_id={result.message_id}")
                return True, result.message_id
            logger.error(f"SMS failed | practice={practice_label} | from={from_number} | "
                         f"to={recipient.phone_number} | status=failed | "
                         f"{result.http_status_code} {result.error_message}")
            return False, ''
        except Exception as e:
            logger.warning(f"SMS attempt {attempt}/{MAX_RETRIES} failed | practice={practice_label} | "
                           f"from={from_number} | to={recipient.phone_number} | status=retrying | error={e}")
            if attempt < MAX_RETRIES:
                time.sleep(RETRY_DELAY_SECONDS * attempt)

    logger.error(f"SMS giving up | practice={practice_label} | from={from_number} | "
                 f"to={recipient.phone_number} | status=failed | attempts={MAX_RETRIES}")
    return False, ''


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('--csv', required=True,
                         help='Path to CSV file with first_name, last_name, phone_number, practice_id, client_id')
    parser.add_argument('--dry-run', action='store_true', help='Render messages without sending them')
    parser.add_argument('--batch-size', type=int, default=BATCH_SIZE,
                         help=f'Pause after this many messages sent (default: {BATCH_SIZE})')
    parser.add_argument('--batch-pause-seconds', type=float, default=BATCH_PAUSE_SECONDS,
                         help=f'Seconds to pause between batches (default: {BATCH_PAUSE_SECONDS})')
    parser.add_argument('--log-file', default=DEFAULT_SENT_LOG_PATH,
                         help=f'CSV file tracking sent messages, to skip re-sending on reruns '
                              f'(default: {DEFAULT_SENT_LOG_PATH})')
    parser.add_argument('--resend', action='store_true',
                         help='Ignore --log-file and resend to everyone in the CSV')
    args = parser.parse_args()

    load_dotenv()

    recipients = load_recipients(args.csv)
    if not recipients:
        logger.warning("No valid recipients found in CSV, nothing to do")
        return

    logger.info(f"Loaded {len(recipients)} recipient(s) from {args.csv}")

    db_connection = connect_db()
    try:
        practice_map = load_client_practice_map(db_connection)
    finally:
        db_connection.close()

    resolved = []
    for recipient in recipients:
        practice = resolve_practice(recipient, practice_map)
        if practice:
            resolved.append((recipient, practice))

    if not resolved:
        logger.warning("No recipients resolved to an active practice, nothing to do")
        return

    if args.resend:
        already_sent = set()
    else:
        already_sent = load_sent_log(args.log_file)

    to_send = []
    skipped = 0
    for recipient, practice in resolved:
        if (recipient.practice_id, recipient.phone_number) in already_sent:
            skipped += 1
            continue
        to_send.append((recipient, practice))

    if skipped:
        logger.info(f"Skipping {skipped} recipient(s) already in {args.log_file}")

    resolved = to_send
    if not resolved:
        logger.warning("Nothing left to send after filtering against the sent log")
        return

    if args.dry_run:
        for recipient, practice in resolved:
            print(f"[DRY RUN] {practice['collect_phone_number']} -> {recipient.phone_number}: "
                  f"{render_message(recipient, practice)}")
        return

    connection_string = os.getenv('AZURE_COMMUNICATION_CONNECTION_STRING')
    if not connection_string:
        raise ValueError("AZURE_COMMUNICATION_CONNECTION_STRING environment variable is required")

    sms_client = SmsClient.from_connection_string(connection_string)

    sent, failed = 0, 0
    for i, (recipient, practice) in enumerate(resolved, start=1):
        message = render_message(recipient, practice)
        success, message_id = send_with_retry(sms_client, practice, recipient, message)
        append_sent_log(args.log_file, practice, recipient, 'success' if success else 'failed', message_id)
        if success:
            sent += 1
        else:
            failed += 1

        if args.batch_size > 0 and i % args.batch_size == 0 and i < len(resolved):
            logger.info(f"Sent {i}/{len(resolved)}, pausing {args.batch_pause_seconds}s before continuing")
            time.sleep(args.batch_pause_seconds)

    logger.info(f"Done. Sent: {sent}, Failed: {failed}, Total: {len(resolved)}")


if __name__ == '__main__':
    main()
