#!/usr/bin/env python3
"""
Azure Queue to SQL Server Worker
Reads messages from Azure Storage Queue and writes to SQL Server.
Supports both connection string and User Managed Identity authentication.
"""

import json
import logging
import os
import signal
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
from azure.identity import DefaultAzureCredential, ManagedIdentityCredential
from azure.storage.queue import QueueClient

# Configure logging
logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s - %(levelname)s - %(message)s',
    handlers=[logging.StreamHandler(sys.stdout)]
)
logger = logging.getLogger(__name__)

# Suppress verbose Azure SDK HTTP logs
logging.getLogger('azure.core.pipeline.policies.http_logging_policy').setLevel(logging.WARNING)


class GracefulShutdown:
    """Handle graceful shutdown on SIGTERM/SIGINT."""

    def __init__(self):
        self.shutdown_requested = False
        signal.signal(signal.SIGTERM, self._handle_signal)
        signal.signal(signal.SIGINT, self._handle_signal)

    def _handle_signal(self, signum, frame):
        logger.info(f"Received signal {signum}, initiating graceful shutdown...")
        self.shutdown_requested = True


class QueueWorker:
    """Worker that processes messages from Azure Queue and writes to SQL Server."""

    INSERT_SQL = """
        INSERT INTO trace_appt_requests (
            patient_key, patient_id, practice_id,
            preferred_date1, preferred_time1,
            preferred_date2, preferred_time2,
            preferred_date3, preferred_time3,
            comments, created_dt
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
    """

    def __init__(self):
        self.client_id = os.getenv('CLIENT_ID', 'default')
        self.queue_client: Optional[QueueClient] = None
        self.db_connection: Optional[pyodbc.Connection] = None

        # Configuration
        self.batch_size = int(os.getenv('BATCH_SIZE', '16'))
        self.visibility_timeout = int(os.getenv('VISIBILITY_TIMEOUT', '30'))
        self.max_retries = int(os.getenv('MAX_RETRIES', '3'))
        self.retry_delay = float(os.getenv('RETRY_DELAY', '1.0'))

    def connect(self):
        """Establish connections to queue and database."""
        self._connect_queue()
        self._connect_db()

    def _connect_queue(self):
        """Connect to Azure Queue Storage. Prefers Managed Identity, falls back to connection string."""
        queue_name = os.getenv('QUEUE_NAME') or f"{self.client_id}-schedule-queue"

        # Try Managed Identity first
        account_url = os.getenv('AZURE_STORAGE_ACCOUNT_URL')
        if account_url:
            try:
                credential = self._get_managed_identity_credential()
                self.queue_client = QueueClient(
                    account_url=account_url,
                    queue_name=queue_name,
                    credential=credential
                )
                # Test connection
                self.queue_client.get_queue_properties()
                logger.info(f"[{self.client_id}] Connected to queue via Managed Identity: {queue_name}")
                return
            except Exception as e:
                logger.warning(f"[{self.client_id}] Managed Identity failed, falling back to connection string: {e}")

        # Fallback to connection string
        connection_string = os.getenv('AZURE_STORAGE_CONNECTION_STRING')
        if not connection_string:
            raise ValueError("AZURE_STORAGE_CONNECTION_STRING required (Managed Identity unavailable)")

        self.queue_client = QueueClient.from_connection_string(
            connection_string,
            queue_name=queue_name
        )
        logger.info(f"[{self.client_id}] Connected to queue via connection string: {queue_name}")

    def _get_managed_identity_credential(self):
        """Get Managed Identity credential (User-assigned or System-assigned)."""
        managed_identity_client_id = os.getenv('AZURE_CLIENT_ID')
        if managed_identity_client_id:
            logger.info(f"[{self.client_id}] Using User Managed Identity: {managed_identity_client_id}")
            return ManagedIdentityCredential(client_id=managed_identity_client_id)
        else:
            logger.info(f"[{self.client_id}] Using System Managed Identity")
            return DefaultAzureCredential()

    def _connect_db(self):
        """Connect to SQL Server with retry logic."""
        connection_string = os.getenv('SQL_CONNECTION_STRING')
        if not connection_string:
            raise ValueError("SQL_CONNECTION_STRING environment variable is required")

        for attempt in range(self.max_retries):
            try:
                self.db_connection = pyodbc.connect(connection_string, timeout=30)
                self.db_connection.autocommit = False
                logger.info(f"[{self.client_id}] Connected to SQL Server")
                return
            except pyodbc.Error as e:
                logger.warning(f"[{self.client_id}] DB connection attempt {attempt + 1} failed: {e}")
                if attempt < self.max_retries - 1:
                    time.sleep(self.retry_delay * (attempt + 1))
                else:
                    raise

    def process_batch(self) -> int:
        """Process a batch of messages. Returns count of processed messages."""
        if not self.queue_client:
            return 0

        messages = list(self.queue_client.receive_messages(
            messages_per_page=self.batch_size,
            visibility_timeout=self.visibility_timeout
        ))

        if not messages:
            return 0

        logger.info(f"[{self.client_id}] Received {len(messages)} messages")

        records = []
        valid_messages = []

        for msg in messages:
            try:
                logger.info(f"[{self.client_id}] Raw message: id={msg.id}, content={msg.content}")
                data = self._parse_message(msg.content)

                # Check if message was parsed successfully
                if not data:
                    logger.error(f"[{self.client_id}] Failed to parse message content, deleting: {msg.id}")
                    self.queue_client.delete_message(msg.id, msg.pop_receipt)
                    continue

                # Validate mandatory fields
                missing_fields = self._validate_message(data)
                if missing_fields:
                    logger.error(f"[{self.client_id}] Missing mandatory fields {missing_fields} in message {data.get('id')}, deleting: {msg.id}")
                    self.queue_client.delete_message(msg.id, msg.pop_receipt)
                    continue

                records.append(self._to_record(data))
                valid_messages.append(msg)

            except Exception as e:
                logger.error(f"[{self.client_id}] Failed to process message {msg.id}: {e}")
                self.queue_client.delete_message(msg.id, msg.pop_receipt)

        if not records:
            return 0

        if self._batch_insert(records):
            for msg in valid_messages:
                try:
                    self.queue_client.delete_message(msg.id, msg.pop_receipt)
                except Exception as e:
                    logger.warning(f"[{self.client_id}] Failed to delete message {msg.id}: {e}")

            logger.info(f"[{self.client_id}] Processed {len(records)} records")
            return len(records)

        return 0

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

    def _parse_slot(self, slot: str) -> tuple:
        """Parse slot string like '2026-07-08 09:00 AM' into (date, time)."""
        if not slot:
            return None, None
        # Split date from time (first space separates date from time)
        parts = slot.split(' ', 1)
        if len(parts) == 2:
            date_str, time_str = parts
            # Convert date string to date object for SQL Server
            date_obj = datetime.strptime(date_str, '%Y-%m-%d').date()
            return date_obj, time_str  # time_str is "09:00 AM"
        return None, None

    def _validate_message(self, data: dict) -> list:
        """Validate mandatory fields. Returns list of missing fields."""
        missing = []

        # Check mandatory fields
        if not data.get('patientKey'):
            missing.append('patientKey')
        if not data.get('patientId'):
            missing.append('patientId')
        if not data.get('practiceId'):
            missing.append('practiceId')

        # Check at least one preferred slot exists
        slots = data.get('preferredSlots') or []
        if not slots or len(slots) == 0:
            missing.append('preferredSlots')
        else:
            # Validate first slot (mandatory)
            date1, time1 = self._parse_slot(slots[0]) if slots else (None, None)
            if not date1:
                missing.append('preferredSlots[0].date')
            if not time1:
                missing.append('preferredSlots[0].time')

        return missing

    def _to_record(self, data: dict) -> tuple:
        """Convert dict to SQL record tuple."""
        slots = data.get('preferredSlots') or []

        # Parse up to 3 preferred slots
        date1, time1 = self._parse_slot(slots[0]) if len(slots) > 0 else (None, None)
        date2, time2 = self._parse_slot(slots[1]) if len(slots) > 1 else (None, None)
        date3, time3 = self._parse_slot(slots[2]) if len(slots) > 2 else (None, None)

        # Parse submittedAt as created_dt
        created_dt = data.get('submittedAt')
        if created_dt and isinstance(created_dt, str):
            created_dt = created_dt.replace('Z', '').replace('+00:00', '')
            created_dt = datetime.fromisoformat(created_dt)

        return (
            data.get('patientKey'),
            data.get('patientId'),
            data.get('practiceId'),
            date1,
            time1,
            date2,
            time2,
            date3,
            time3,
            data.get('comments'),
            created_dt
        )

    def _batch_insert(self, records: list[tuple]) -> bool:
        """Insert records in batch with retry logic."""
        for attempt in range(self.max_retries):
            try:
                cursor = self.db_connection.cursor()
                cursor.fast_executemany = True
                cursor.executemany(self.INSERT_SQL, records)
                self.db_connection.commit()
                cursor.close()
                return True
            except pyodbc.Error as e:
                self.db_connection.rollback()
                error_code = e.args[0] if e.args else ''

                if self._is_transient_error(error_code) and attempt < self.max_retries - 1:
                    logger.warning(f"[{self.client_id}] Transient error, retry {attempt + 1}: {e}")
                    time.sleep(self.retry_delay * (attempt + 1))
                    self._reconnect_if_needed()
                else:
                    logger.error(f"[{self.client_id}] Batch insert failed: {e}")
                    return False

        return False

    def _is_transient_error(self, error_code: str) -> bool:
        """Check if error is transient and worth retrying."""
        transient_codes = {'08001', '08S01', '40001', '40197', '40501', '40613', '49918', '49919', '49920'}
        return str(error_code) in transient_codes

    def _reconnect_if_needed(self):
        """Reconnect to database if connection is broken."""
        try:
            self.db_connection.cursor().execute("SELECT 1")
        except Exception:
            logger.info(f"[{self.client_id}] Reconnecting to database...")
            self._connect_db()

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
    logger.info(f"Starting Queue Worker for client: {client_id}")

    poll_interval = float(os.getenv('POLL_INTERVAL', '1.0'))

    worker = QueueWorker()
    worker.connect()

    shutdown = GracefulShutdown()
    logger.info(f"[{client_id}] Polling started, interval: {poll_interval}s")

    try:
        while not shutdown.shutdown_requested:
            try:
                processed = worker.process_batch()
                if processed == 0:
                    time.sleep(poll_interval)
            except Exception as e:
                logger.error(f"[{client_id}] Error processing batch: {e}")
                time.sleep(poll_interval)
    finally:
        worker.close()
        logger.info("Shutdown complete")


if __name__ == '__main__':
    main()