#!/usr/bin/env python3
"""
Azure Queue to Sikka Appointment API Worker
Reads appointment messages from an Azure Storage Queue and creates the
appointment in Sikka via POST /v4/appointment. Writes the outcome back
to SQL Server. Supports both connection string and User Managed Identity
authentication for the queue.
"""

import json
import logging
import os
import sys
import time
from base64 import b64decode
from datetime import datetime
from typing import Optional

# Load .env file for local development (ignored if not present)
try:
    from dotenv import load_dotenv
    load_dotenv()
except ImportError:
    pass

import pyodbc
import requests
from azure.storage.queue import QueueClient

from common import GracefulShutdown, connect_queue, connect_db, SikkaTokenManager

# Configure logging
logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s - %(levelname)s - %(message)s',
    handlers=[logging.StreamHandler(sys.stdout)]
)
logger = logging.getLogger(__name__)

# Suppress verbose Azure SDK HTTP logs
logging.getLogger('azure.core.pipeline.policies.http_logging_policy').setLevel(logging.WARNING)


class SikkaAppointmentWorker:
    """Worker that reads appointment requests from Azure Queue and creates them in Sikka."""

    UPDATE_SQL = """
        UPDATE trace_appt_requests
        SET status = ?, updated_dt = ?
        WHERE patient_key = ? AND practice_id = ?
    """

    UPDATE_WRITEBACK_SQL = """
        UPDATE trace_appt_writeback_requests
        SET status = ?, writeback_status_id = ?, updated_dt = ?
        WHERE id = ?
    """

    # Fields required to build a valid Sikka appointment request
    REQUIRED_FIELDS = ('patientId', 'practiceId', 'date', 'time')

    def __init__(self):
        self.client_id = os.getenv('CLIENT_ID', 'default')
        self.queue_client: Optional[QueueClient] = None
        self.db_connection: Optional[pyodbc.Connection] = None
        self.token_manager: Optional[SikkaTokenManager] = None

        # Queue configuration
        self.batch_size = int(os.getenv('BATCH_SIZE', '16'))
        self.visibility_timeout = int(os.getenv('VISIBILITY_TIMEOUT', '30'))
        self.max_dequeue_count = int(os.getenv('SIKKA_MAX_DEQUEUE_COUNT', '5'))

        # Retry configuration (queue connect, DB, and Sikka HTTP calls)
        self.max_retries = int(os.getenv('MAX_RETRIES', '3'))
        self.retry_delay = float(os.getenv('RETRY_DELAY', '1.0'))

        # Sikka API configuration
        self.sikka_api_url = os.getenv('SIKKA_API_URL', 'https://api.sikkasoft.com/v4/appointment')
        self.http_timeout = float(os.getenv('SIKKA_HTTP_TIMEOUT', '15'))

        # Connection health check settings (default: 5 minutes)
        self.health_check_interval = float(os.getenv('DB_HEALTH_CHECK_INTERVAL', '300'))
        self.last_health_check = time.time()

    def connect(self):
        """Establish connections to queue and database, and the per-practice token manager."""
        self.token_manager = SikkaTokenManager(
            app_id=os.getenv('SIKKA_APP_ID'),
            app_key=os.getenv('SIKKA_APP_KEY'),
            max_retries=self.max_retries,
            retry_delay=self.retry_delay,
            http_timeout=self.http_timeout,
        )
        self._connect_queue()
        self._connect_db()

    def _connect_queue(self):
        """Connect to Azure Queue Storage. Prefers Managed Identity, falls back to connection string."""
        queue_name = os.getenv('SIKKA_QUEUE_NAME') or f"{self.client_id}-sikka-appointment-queue"
        self.queue_client = connect_queue(self.client_id, queue_name)

    def _connect_db(self):
        """Connect to SQL Server with retry logic."""
        self.db_connection = connect_db(self.client_id, self.max_retries, self.retry_delay)

    def process_batch(self) -> int:
        """Process a batch of appointment messages. Returns count of appointments created."""
        if not self.queue_client:
            return 0

        messages = list(self.queue_client.receive_messages(
            messages_per_page=self.batch_size,
            visibility_timeout=self.visibility_timeout
        ))

        if not messages:
            return 0

        logger.info(f"[{self.client_id}] Received {len(messages)} messages")
        processed_count = 0

        for msg in messages:
            try:
                if msg.dequeue_count and msg.dequeue_count > self.max_dequeue_count:
                    logger.error(
                        f"[{self.client_id}] Message {msg.id} exceeded max dequeue count "
                        f"({msg.dequeue_count}), giving up and deleting"
                    )
                    self._record_outcome(self._parse_message(msg.content), success=False)
                    self.queue_client.delete_message(msg.id, msg.pop_receipt)
                    continue

                data = self._parse_message(msg.content)
                if not data:
                    logger.error(f"[{self.client_id}] Failed to parse message content, deleting: {msg.id}")
                    self.queue_client.delete_message(msg.id, msg.pop_receipt)
                    continue

                missing_fields = self._validate_message(data)
                if missing_fields:
                    logger.error(
                        f"[{self.client_id}] Missing mandatory fields {missing_fields} in message, "
                        f"deleting: {msg.id}"
                    )
                    self._record_outcome(data, success=False)
                    self.queue_client.delete_message(msg.id, msg.pop_receipt)
                    continue

                office_id = data.get('officeId') or data.get('office_id')
                if not office_id:
                    logger.error(
                        f"[{self.client_id}] Missing office_id in message, deleting: {msg.id}"
                    )
                    self._record_outcome(data, success=False)
                    self.queue_client.delete_message(msg.id, msg.pop_receipt)
                    continue

                try:
                    request_key = self.token_manager.get_request_key(office_id)
                except RuntimeError as e:
                    logger.error(f"[{self.client_id}] Could not obtain Sikka request_key for message {msg.id}: {e}")
                    self._record_outcome(data, success=False)
                    # Leave message in queue; retried until max_dequeue_count is hit.
                    continue

                payload = self._build_payload(data)
                success, result = self._call_sikka(payload, request_key)
                self._record_outcome(data, success=success, detail=result)

                if success:
                    self.queue_client.delete_message(msg.id, msg.pop_receipt)
                    processed_count += 1
                else:
                    logger.error(f"[{self.client_id}] Sikka appointment failed for message {msg.id}: {result}")
                    # Leave message in queue; it becomes visible again after the
                    # visibility timeout and is retried until max_dequeue_count is hit.

            except Exception as e:
                logger.error(f"[{self.client_id}] Failed to process message {msg.id}: {e}")

        if processed_count > 0:
            logger.info(f"[{self.client_id}] Created {processed_count} Sikka appointments")

        return processed_count

    def _parse_message(self, content: str) -> Optional[dict]:
        """Parse message content, handling Base64 encoding and Python-style values."""
        if not content:
            return None

        text = content
        # Decode base64 if not JSON
        if not content.startswith('{'):
            try:
                text = b64decode(content).decode('utf-8')
            except Exception:
                return None

        # Convert Python-style values to JSON-style
        text = text.replace(': True', ': true').replace(': False', ': false').replace(': None', ': null')

        try:
            return json.loads(text)
        except Exception:
            return None

    def _validate_message(self, data: dict) -> list:
        """Validate mandatory fields. Returns list of missing fields."""
        return [field for field in self.REQUIRED_FIELDS if not data.get(field)]

    def _build_payload(self, data: dict) -> dict:
        """Map queue message fields to the Sikka POST /v4/appointment request body."""
        return {
            'patient_id': str(data.get('patientId', '')),
            'date': data.get('date', ''),
            'description': data.get('description', ''),
            'time': data.get('time', ''),
            'provider_id': data.get('providerId', ''),
            'length': str(data.get('length', '')),
            'operatory': data.get('operatory', ''),
            'practice_id': "1",
            'type': data.get('type', ''),
            'user': data.get('user', ''),
            'status': data.get('status', ''),
            'note': data.get('note', ''),
            'procedure_code': data.get('procedureCode', ''),
            'amount': data.get('amount', ''),
            'tooth': data.get('tooth', ''),
            'surface': data.get('surface', ''),
            'root': data.get('root', ''),
            'quadrant': data.get('quadrant', ''),
            'is_guarantor_exist': data.get('isGuarantorExist', ''),
            'gender': data.get('gender', ''),
            'workphone': data.get('workPhone', ''),
            'cell': data.get('cell', ''),
            'other_phone': data.get('otherPhone', ''),
            'address_line1': data.get('addressLine1', ''),
            'address_line2': data.get('addressLine2', ''),
            'city': data.get('city', ''),
            'state': data.get('state', ''),
            'zipcode': data.get('zipcode', ''),
        }

    def _call_sikka(self, payload: dict, request_key: str) -> tuple:
        """POST the appointment to Sikka with retry. Returns (success, response body or error message)."""
        headers = {
            'Content-Type': 'application/json',
            'Request-Key': request_key,
        }

        for attempt in range(self.max_retries):
            try:
                response = requests.post(
                    self.sikka_api_url,
                    json=payload,
                    headers=headers,
                    timeout=self.http_timeout
                )

                if response.status_code < 300:
                    return True, self._safe_json(response)

                # 4xx (other than 429) is a permanent rejection - retrying won't help
                if response.status_code < 500 and response.status_code != 429:
                    logger.error(
                        f"[{self.client_id}] Sikka rejected appointment "
                        f"({response.status_code}): {response.text}"
                    )
                    return False, self._safe_json(response) or response.text

                logger.warning(
                    f"[{self.client_id}] Sikka transient error ({response.status_code}), "
                    f"attempt {attempt + 1}: {response.text}"
                )
            except requests.RequestException as e:
                logger.warning(f"[{self.client_id}] Sikka request failed, attempt {attempt + 1}: {e}")

            if attempt < self.max_retries - 1:
                time.sleep(self.retry_delay * (attempt + 1))

        return False, "Sikka API request failed after retries"

    def _safe_json(self, response: requests.Response):
        try:
            return response.json()
        except ValueError:
            return response.text

    def _extract_writeback_status_id(self, detail) -> Optional[int]:
        """Parse the numeric id out of a Sikka response's long_message, e.g. 'Id:4507702'."""
        if not isinstance(detail, dict):
            return None
        long_message = detail.get('long_message', '')
        if long_message and ':' in long_message:
            try:
                return int(long_message.split(':', 1)[1].strip())
            except ValueError:
                return None
        return None

    def _record_outcome(self, data: Optional[dict], success: bool, detail=None):
        """Write the outcome of the Sikka call back to SQL Server."""
        if not data:
            return

        patient_key = data.get('patientKey')
        practice_id = data.get('practiceId')
        request_id = data.get('requestId')
        if not (patient_key and practice_id) and not request_id:
            return

        status = 'SIKKA_SCHEDULED' if success else 'SIKKA_FAILED'
        writeback_status_id = self._extract_writeback_status_id(detail) if success else None
        updated_dt = datetime.utcnow()

        for attempt in range(self.max_retries):
            try:
                cursor = self.db_connection.cursor()
                if patient_key and practice_id:
                    cursor.execute(self.UPDATE_SQL, (status, updated_dt, patient_key, practice_id))
                if request_id:
                    cursor.execute(self.UPDATE_WRITEBACK_SQL, (status, writeback_status_id, updated_dt, request_id))
                self.db_connection.commit()
                cursor.close()
                return
            except pyodbc.Error as e:
                self.db_connection.rollback()
                if attempt < self.max_retries - 1:
                    logger.warning(f"[{self.client_id}] Status update retry {attempt + 1}: {e}")
                    time.sleep(self.retry_delay * (attempt + 1))
                    self._reconnect_if_needed()
                else:
                    logger.error(f"[{self.client_id}] Status update failed: {e}")

    def _reconnect_if_needed(self):
        """Reconnect to database if connection is broken."""
        try:
            self.db_connection.cursor().execute("SELECT 1")
        except Exception:
            logger.info(f"[{self.client_id}] Reconnecting to database...")
            self._connect_db()

    def check_connection_health(self):
        """Periodically check and refresh database connection to prevent idle timeout."""
        now = time.time()
        if now - self.last_health_check >= self.health_check_interval:
            try:
                cursor = self.db_connection.cursor()
                cursor.execute("SELECT 1")
                cursor.close()
                logger.debug(f"[{self.client_id}] Connection health check passed")
            except Exception:
                logger.info(f"[{self.client_id}] Connection stale, reconnecting...")
                self._connect_db()
            self.last_health_check = now

    def close(self):
        """Close all connections."""
        if self.db_connection:
            try:
                self.db_connection.close()
            except Exception:
                pass
        logger.info(f"[{self.client_id}] Worker stopped")


def main():
    """Main entry point."""
    client_id = os.getenv('CLIENT_ID', 'default')
    poll_interval = float(os.getenv('POLL_INTERVAL', '1.0'))
    logger.info(f"Starting Sikka Appointment Worker for client: {client_id}")

    shutdown = GracefulShutdown()
    worker = SikkaAppointmentWorker()
    worker.connect()
    logger.info(f"[{client_id}] Polling started, interval: {poll_interval}s")

    try:
        while not shutdown.shutdown_requested:
            try:
                processed = worker.process_batch()
                worker.check_connection_health()
                if processed == 0:
                    time.sleep(poll_interval)
            except Exception as e:
                logger.error(f"[{client_id}] Worker error: {e}")
                time.sleep(poll_interval)
    finally:
        worker.close()


if __name__ == '__main__':
    main()