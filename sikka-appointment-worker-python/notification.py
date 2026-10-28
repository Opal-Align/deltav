#!/usr/bin/env python3
"""Appointment-status notifications. Prepares the SMS message/payload here and POSTs it to an
external notification API (SIKKA_NOTIFICATION_API_URL) which actually sends it.

Disabled by default - set SMS_NOTIFICATIONS_ENABLED=true to turn on.
"""

import logging
import os
import time

import requests

from common import EVENT_SCHEDULED, EVENT_RESCHEDULED, EVENT_CANCELLED

logger = logging.getLogger(__name__)

MESSAGE_TEMPLATES = {
    EVENT_SCHEDULED: (
        "Hi {patient_first_name}, your appointment with {practice_name} on {appointment_date} "
        "at {appointment_time} is confirmed.\n\n"
        "To cancel or reschedule, reply to this message or call us at {contact_phone_number}.\n\n"
        "We look forward to seeing you."
    ),
    EVENT_RESCHEDULED: (
        "Hi {patient_first_name}, your appointment with {practice_name} has been rescheduled to "
        "{appointment_date} at {appointment_time}.\n\n"
        "If this time doesn’t work, reply to this message or call us at {contact_phone_number}.\n\n"
        "Thank you."
    ),
    EVENT_CANCELLED: (
        "Hi {patient_first_name}, your appointment with {practice_name} on {appointment_date} "
        "at {appointment_time} has been cancelled.\n\n"
        "To book a new appointment, reply to this message or call us at {contact_phone_number}.\n\n"
        "Thank you."
    ),
}

# Identifies this caller to the notification API.
NOTIFICATION_SOURCE = 'SCHEDULER_REQUEST'

# TODO: Metadata_ClientPractice doesn't yet have a dedicated patient-facing contact number -
# point this at that column once it's added (collect_phone_number is the *sending* number).
PRACTICE_CONTACT_PHONE_COLUMN = 'patient_contact_phone'

_SELECT_NOTIFICATION_CONTEXT_SQL = f"""
    SELECT r.patient_first_name, r.cell_phone, r.patient_phone, w.[date], w.[time],
           w.patient_id, w.practice_id,
           p.PracticeName, p.{PRACTICE_CONTACT_PHONE_COLUMN}, p.collect_phone_number
    FROM trace_appt_writeback_requests w
    JOIN trace_appt_requests r ON r.id = w.trace_appt_req_id
    JOIN DB_Metadata.dbo.Metadata_ClientPractice p ON p.PracticeID = w.practice_id
    WHERE w.writeback_status_id = ?
"""

MAX_RETRIES = 3
RETRY_DELAY_SECONDS = 2.0
HTTP_TIMEOUT = 15.0


def is_enabled() -> bool:
    return os.getenv('SMS_NOTIFICATIONS_ENABLED', 'false').strip().lower() in ('1', 'true', 'yes')


def notify(client_id: str, db_connection, writeback_status_id, event: str) -> None:
    """Best-effort appointment-status notification. Looks up patient/practice context for
    writeback_status_id, prepares the message for `event`, and POSTs it to the notification API.
    Never raises - failures are logged and swallowed so they can't affect a writeback status
    that already committed."""
    if not is_enabled():
        return

    try:
        _send(client_id, db_connection, writeback_status_id, event)
    except Exception as e:
        logger.error(f"[{client_id}] Failed to send {event} notification for "
                     f"writeback_status_id {writeback_status_id}: {e}")


def _send(client_id, db_connection, writeback_status_id, event):
    cursor = db_connection.cursor()
    cursor.execute(_SELECT_NOTIFICATION_CONTEXT_SQL, (writeback_status_id,))
    row = cursor.fetchone()
    cursor.close()
    if not row:
        logger.warning(f"[{client_id}] No notification context found for writeback_status_id "
                        f"{writeback_status_id}, skipping")
        return

    (first_name, cell_phone, patient_phone, appt_date, appt_time, patient_id, practice_id,
     practice_name, contact_phone_number, collect_phone_number) = row

    to_number = _normalize_phone(cell_phone) or _normalize_phone(patient_phone)
    if not to_number:
        logger.warning(f"[{client_id}] No usable patient phone for writeback_status_id "
                        f"{writeback_status_id}, skipping notification")
        return

    from_number = _normalize_phone(collect_phone_number)
    if not from_number:
        logger.warning(f"[{client_id}] No usable sending number for writeback_status_id "
                        f"{writeback_status_id}, skipping notification")
        return

    message = MESSAGE_TEMPLATES[event].format(
        patient_first_name=first_name or '',
        practice_name=practice_name or '',
        appointment_date=_format_date(appt_date),
        appointment_time=_format_time(appt_time),
        contact_phone_number=contact_phone_number or collect_phone_number or '',
    )

    api_url = os.getenv('SIKKA_NOTIFICATION_API_URL')
    if not api_url:
        logger.error(f"[{client_id}] SIKKA_NOTIFICATION_API_URL not set, "
                      f"cannot send {event} notification")
        return

    payload = {
        'to_number': to_number,
        'from_number': from_number,
        'message': message,
        'patient_id': str(patient_id) if patient_id is not None else '',
        'practice_id': str(practice_id) if practice_id is not None else '',
        'client_id': client_id,
        'source': NOTIFICATION_SOURCE,
    }
    headers = {
        'Content-Type': 'application/json',
        'Practice-Id': str(practice_id) if practice_id is not None else '',
    }

    _post_with_retry(client_id, api_url, payload, headers, event, writeback_status_id)


def _post_with_retry(client_id, url, payload, headers, event, writeback_status_id):
    for attempt in range(1, MAX_RETRIES + 1):
        try:
            response = requests.post(url, json=payload, headers=headers, timeout=HTTP_TIMEOUT)

            if response.status_code < 300:
                logger.info(f"[{client_id}] Sent {event} notification for writeback_status_id "
                            f"{writeback_status_id} to {payload['to_number']}")
                return

            if response.status_code < 500 and response.status_code != 429:
                logger.error(f"[{client_id}] {event} notification rejected for writeback_status_id "
                             f"{writeback_status_id} ({response.status_code}): {response.text}")
                return

            logger.warning(f"[{client_id}] {event} notification transient error for writeback_status_id "
                            f"{writeback_status_id} ({response.status_code}), attempt {attempt}/{MAX_RETRIES}")
        except requests.RequestException as e:
            logger.warning(f"[{client_id}] {event} notification attempt {attempt}/{MAX_RETRIES} failed "
                            f"for writeback_status_id {writeback_status_id}: {e}")

        if attempt < MAX_RETRIES:
            time.sleep(RETRY_DELAY_SECONDS * attempt)

    logger.error(f"[{client_id}] Giving up on {event} notification for writeback_status_id "
                 f"{writeback_status_id} after {MAX_RETRIES} attempts")


def _normalize_phone(raw) -> str:
    """Best-effort conversion to E.164. Returns '' if the number can't be normalized."""
    if not raw:
        return ''
    digits = ''.join(ch for ch in str(raw) if ch.isdigit() or ch == '+')
    if not digits:
        return ''
    if digits.startswith('+'):
        return digits
    if len(digits) == 10:
        return f'+1{digits}'
    if len(digits) == 11 and digits.startswith('1'):
        return f'+{digits}'
    return ''


def _format_date(value) -> str:
    try:
        return value.strftime('%B %d, %Y')
    except AttributeError:
        return str(value) if value else ''


def _format_time(value) -> str:
    try:
        return value.strftime('%I:%M %p').lstrip('0')
    except AttributeError:
        return str(value) if value else ''
