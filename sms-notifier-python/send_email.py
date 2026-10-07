#!/usr/bin/env python3
"""Send templated email reminders to patients listed in a CSV, via Azure Communication Services.

Usage:
    python send_email.py --csv patients.csv
    python send_email.py --csv patients.csv --dry-run
    python send_email.py --csv patients.csv --batch-size 10 --batch-pause-seconds 10

CSV must have a header row with columns: first_name, last_name, email, practice_id, client_id
(column names are matched case-insensitively). The recipient (to) address comes from email.
practice_id/client_id are looked up against DB_Metadata.dbo.Metadata_ClientPractice to resolve the
sending practice for each row, and the sender (from) address comes from that practice's
email_sender_id column.

Every outcome (success or failure) is appended to --log-file (default: sent_email_log.csv). On the
next run, any (practice_id, email) already logged as 'success' is skipped automatically so the same
patient doesn't get emailed twice; pass --resend to ignore the log and send to everyone again.

Required environment variables (e.g. via a .env file):
    SQL_CONNECTION_STRING                 - connection string for the server hosting DB_Metadata
    AZURE_COMMUNICATION_CONNECTION_STRING - connection string for the ACS resource
"""

import argparse
import csv
import logging
import os
import re
import sys
import time
from dataclasses import dataclass
from datetime import datetime, timezone
from typing import Optional

from azure.communication.email import EmailClient
from dotenv import load_dotenv

from db_metadata import connect_db, load_client_practice_map

logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s - %(levelname)s - %(message)s',
    handlers=[logging.StreamHandler(sys.stdout)]
)
logger = logging.getLogger(__name__)
logging.getLogger('azure.core.pipeline.policies.http_logging_policy').setLevel(logging.WARNING)

# Edit this to change the outgoing email. {first_name}/{last_name}/{practice_name} are filled in per row.
EMAIL_SUBJECT_TEMPLATE = "Update Regarding Recent Balance Message – No Action Needed"
EMAIL_BODY_TEMPLATE = (
    "Thank you for being a part of {practice_name}. If you recently received a message about a balance, "
    "please disregard it. It was sent in error due to a system issue. We’re reviewing your account and "
    "will follow up if anything is needed. Thank you for your understanding."
)

_EMAIL_RE = re.compile(r'^[^@\s]+@[^@\s]+\.[^@\s]+$')

REQUIRED_COLUMNS = ('first_name', 'last_name', 'email', 'practice_id', 'client_id')
MAX_RETRIES = 3
RETRY_DELAY_SECONDS = 2.0

# After every BATCH_SIZE messages, pause BATCH_PAUSE_SECONDS to avoid overwhelming ACS/mailbox providers.
BATCH_SIZE = 50
BATCH_PAUSE_SECONDS = 5.0

# Record of patients already emailed, so re-running the same CSV doesn't message them again.
DEFAULT_SENT_LOG_PATH = 'sent_email_log.csv'
SENT_LOG_FIELDS = ('timestamp', 'practice_id', 'practice_name', 'from_email', 'to_email', 'status', 'message_id')


@dataclass
class Recipient:
    first_name: str
    last_name: str
    email: str
    practice_id: int
    client_id: int


def normalize_email(raw: str) -> Optional[str]:
    """Lowercase/trim and validate basic email shape. Returns None if it doesn't look like an email."""
    email = raw.strip().lower()
    if not _EMAIL_RE.match(email):
        return None
    return email


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
            email_raw = (row.get(fieldnames['email']) or '').strip()
            practice_id_raw = (row.get(fieldnames['practice_id']) or '').strip()
            client_id_raw = (row.get(fieldnames['client_id']) or '').strip()

            if not email_raw or email_raw.lower() in ('null', 'none', 'n/a', 'na'):
                logger.warning(f"Line {line_num}: skipping row with null/empty email")
                continue

            email = normalize_email(email_raw)
            if not email:
                logger.warning(f"Line {line_num}: skipping unparseable email '{email_raw}'")
                continue

            if not practice_id_raw.isdigit() or not client_id_raw.isdigit():
                logger.warning(f"Line {line_num}: skipping row with invalid practice_id/client_id "
                               f"('{practice_id_raw}', '{client_id_raw}')")
                continue

            recipients.append(Recipient(
                first_name=first_name,
                last_name=last_name,
                email=email,
                practice_id=int(practice_id_raw),
                client_id=int(client_id_raw),
            ))

        return recipients


def resolve_practice(recipient: Recipient, practice_map: dict) -> Optional[dict]:
    """Look up and validate the practice for a recipient. Returns None if it can't be resolved."""
    practice = practice_map.get(recipient.practice_id)
    if not practice:
        logger.error(f"No Metadata_ClientPractice row for practice_id={recipient.practice_id} "
                     f"({recipient.email}), skipping")
        return None

    if practice['ClientID'] != recipient.client_id:
        logger.error(f"client_id mismatch for practice_id={recipient.practice_id}: "
                     f"CSV has {recipient.client_id}, Metadata_ClientPractice has {practice['ClientID']} "
                     f"({recipient.email}), skipping")
        return None

    if not practice.get('is_active'):
        logger.warning(f"practice_id={recipient.practice_id} is not active, skipping ({recipient.email})")
        return None

    from_email = normalize_email((practice.get('email_sender_id') or '').strip())
    if not from_email:
        logger.error(f"practice_id={recipient.practice_id} has no usable email_sender_id, "
                     f"skipping ({recipient.email})")
        return None
    practice['email_sender_id'] = from_email

    return practice


def render_subject(practice: dict) -> str:
    return EMAIL_SUBJECT_TEMPLATE.format(practice_name=practice['PracticeName'])


def render_body(recipient: Recipient, practice: dict) -> str:
    return EMAIL_BODY_TEMPLATE.format(
        first_name=recipient.first_name,
        last_name=recipient.last_name,
        practice_name=practice['PracticeName'],
    )


def load_sent_log(log_path: str) -> set:
    """Return the set of (practice_id, email) already successfully emailed, per log_path."""
    if not os.path.exists(log_path):
        return set()

    sent = set()
    with open(log_path, newline='', encoding='utf-8') as f:
        for row in csv.DictReader(f):
            if row.get('status') == 'success':
                sent.add((int(row['practice_id']), row['to_email']))

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
            'from_email': practice['email_sender_id'],
            'to_email': recipient.email,
            'status': status,
            'message_id': message_id,
        })
        f.flush()


def send_with_retry(email_client: EmailClient, practice: dict, recipient: Recipient, subject: str,
                     body: str) -> tuple:
    """Returns (success, message_id). message_id is '' when the send failed."""
    from_email = practice['email_sender_id']
    practice_label = f"{practice['PracticeID']}:{practice['PracticeName']}"

    message = {
        "senderAddress": from_email,
        "recipients": {
            "to": [{"address": recipient.email, "displayName": f"{recipient.first_name} {recipient.last_name}".strip()}]
        },
        "content": {
            "subject": subject,
            "plainText": body,
        },
    }

    for attempt in range(1, MAX_RETRIES + 1):
        try:
            poller = email_client.begin_send(message)
            result = poller.result()
            if result.get('status') == 'Succeeded':
                message_id = result.get('id', '')
                logger.info(f"Email sent | practice={practice_label} | from={from_email} | "
                            f"to={recipient.email} | status=success | message_id={message_id}")
                return True, message_id
            logger.error(f"Email failed | practice={practice_label} | from={from_email} | "
                         f"to={recipient.email} | status=failed | result={result}")
            return False, ''
        except Exception as e:
            logger.warning(f"Email attempt {attempt}/{MAX_RETRIES} failed | practice={practice_label} | "
                           f"from={from_email} | to={recipient.email} | status=retrying | error={e}")
            if attempt < MAX_RETRIES:
                time.sleep(RETRY_DELAY_SECONDS * attempt)

    logger.error(f"Email giving up | practice={practice_label} | from={from_email} | "
                 f"to={recipient.email} | status=failed | attempts={MAX_RETRIES}")
    return False, ''


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('--csv', required=True,
                         help='Path to CSV file with first_name, last_name, email, practice_id, client_id')
    parser.add_argument('--dry-run', action='store_true', help='Render messages without sending them')
    parser.add_argument('--batch-size', type=int, default=BATCH_SIZE,
                         help=f'Pause after this many messages sent (default: {BATCH_SIZE})')
    parser.add_argument('--batch-pause-seconds', type=float, default=BATCH_PAUSE_SECONDS,
                         help=f'Seconds to pause between batches (default: {BATCH_PAUSE_SECONDS})')
    parser.add_argument('--log-file', default=DEFAULT_SENT_LOG_PATH,
                         help=f'CSV file tracking sent emails, to skip re-sending on reruns '
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
        if (recipient.practice_id, recipient.email) in already_sent:
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
            print(f"[DRY RUN] {practice['email_sender_id']} -> {recipient.email}: "
                  f"{render_subject(practice)} | {render_body(recipient, practice)}")
        return

    connection_string = os.getenv('AZURE_COMMUNICATION_CONNECTION_STRING')
    if not connection_string:
        raise ValueError("AZURE_COMMUNICATION_CONNECTION_STRING environment variable is required")

    email_client = EmailClient.from_connection_string(connection_string)

    sent, failed = 0, 0
    for i, (recipient, practice) in enumerate(resolved, start=1):
        subject = render_subject(practice)
        body = render_body(recipient, practice)
        success, message_id = send_with_retry(email_client, practice, recipient, subject, body)
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
