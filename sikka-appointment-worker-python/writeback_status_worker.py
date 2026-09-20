#!/usr/bin/env python3
"""
Azure Queue to SQL Server Writeback-Status Worker
Reads Sikka /v4/writeback_status notifications from an Azure Storage Queue —
pushed by another process once the PMS confirms an appointment write-back —
and updates trace_appt_writeback_requests with the resulting appointment_sr_no
and status, matched by writeback_status_id.
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

from common import GracefulShutdown, connect_queue, connect_db

# Configure logging
logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s - %(levelname)s - %(message)s',
    handlers=[logging.StreamHandler(sys.stdout)]
)
logger = logging.getLogger(__name__)

logging.getLogger('azure.core.pipeline.policies.http_logging_policy').setLevel(logging.WARNING)


class WritebackStatusWorker:
    """Worker that applies Sikka writeback_status notifications to trace_appt_writeback_requests."""

    UPDATE_SQL = """
        UPDATE trace_appt_writeback_requests
        SET status = ?, appointment_sr_no = ?, updated_dt = ?
        WHERE writeback_status_id = ?
    """

    # Fields required to identify and apply a writeback_status item
    REQUIRED_FIELDS = ('id', 'status')

    def __init__(self):
        self.client_id = os.getenv('CLIENT_ID', 'default')
        self.queue_client = None
        self.db_connection: Optional[pyodbc.Connection] = None

        # Queue configuration
        self.batch_size = int(os.getenv('WRITEBACK_BATCH_SIZE', '16'))
        self.visibility_timeout = int(os.getenv('WRITEBACK_VISIBILITY_TIMEOUT', '30'))
        self.max_dequeue_count = int(os.getenv('WRITEBACK_MAX_DEQUEUE_COUNT', '5'))

        # Retry configuration (queue connect, DB)
        self.max_retries = int(os.getenv('MAX_RETRIES', '3'))
        self.retry_delay = float(os.getenv('RETRY_DELAY', '1.0'))

        # Connection health check settings (default: 5 minutes)
        self.health_check_interval = float(os.getenv('DB_HEALTH_CHECK_INTERVAL', '300'))
        self.last_health_check = time.time()

    def connect(self):
        """Establish connections to queue and database."""
        queue_name = os.getenv('SIKKA_WRITEBACK_QUEUE_NAME') or f"{self.client_id}-sikka-writeback-status-queue"
        self.queue_client = connect_queue(self.client_id, queue_name)
        self.db_connection = connect_db(self.client_id, self.max_retries, self.retry_delay)

    def process_batch(self) -> int:
        """Process a batch of writeback_status messages. Returns count of rows updated."""
        if not self.queue_client:
            return 0

        messages = list(self.queue_client.receive_messages(
            messages_per_page=self.batch_size,
            visibility_timeout=self.visibility_timeout
        ))

        if not messages:
            return 0

        logger.info(f"[{self.client_id}] Received {len(messages)} messages")
        updated_count = 0

        for msg in messages:
            try:
                if msg.dequeue_count and msg.dequeue_count > self.max_dequeue_count:
                    logger.error(
                        f"[{self.client_id}] Message {msg.id} exceeded max dequeue count "
                        f"({msg.dequeue_count}), giving up and deleting"
                    )
                    self.queue_client.delete_message(msg.id, msg.pop_receipt)
                    continue

                items = self._parse_message(msg.content)
                if not items:
                    logger.error(f"[{self.client_id}] Failed to parse message content, deleting: {msg.id}")
                    self.queue_client.delete_message(msg.id, msg.pop_receipt)
                    continue

                # Evaluate every item (no short-circuit) so a bad item doesn't
                # block the rest of the batch from being applied.
                results = [self._apply_item(item) for item in items]

                if all(results):
                    self.queue_client.delete_message(msg.id, msg.pop_receipt)
                    updated_count += len(items)
                else:
                    logger.error(
                        f"[{self.client_id}] Failed to apply one or more writeback statuses "
                        f"for message {msg.id}"
                    )
                    # Leave message in queue; it becomes visible again after the
                    # visibility timeout and is retried until max_dequeue_count is hit.
                    # Re-applying already-succeeded items on retry is safe (idempotent UPDATE).

            except Exception as e:
                logger.error(f"[{self.client_id}] Failed to process message {msg.id}: {e}")

        if updated_count > 0:
            logger.info(f"[{self.client_id}] Applied {updated_count} writeback status updates")

        return updated_count

    def _parse_message(self, content: str) -> Optional[list]:
        """Parse message content into a list of writeback_status items.

        Accepts a single item object, a bare list of items, or a full
        paginated /v4/writeback_status response (an object with "items").
        """
        if not content:
            return None

        text = content
        if not content.startswith('{') and not content.startswith('['):
            try:
                text = b64decode(content).decode('utf-8')
            except Exception:
                return None

        try:
            parsed = json.loads(text)
        except Exception:
            return None

        if isinstance(parsed, dict) and 'items' in parsed:
            return parsed['items']
        if isinstance(parsed, list):
            return parsed
        if isinstance(parsed, dict):
            return [parsed]
        return None

    def _apply_item(self, item: dict) -> bool:
        """Update trace_appt_writeback_requests for a single writeback_status item."""
        missing_fields = [field for field in self.REQUIRED_FIELDS if not item.get(field)]
        if missing_fields:
            logger.error(f"[{self.client_id}] Item missing mandatory fields {missing_fields}, skipping: {item}")
            return False

        try:
            writeback_status_id = int(item['id'])
        except (TypeError, ValueError):
            logger.error(f"[{self.client_id}] Invalid writeback_status id, skipping: {item}")
            return False

        status = item['status']
        appointment_sr_no = item.get('appointment_sr_no') or None
        updated_dt = datetime.utcnow()

        for attempt in range(self.max_retries):
            try:
                cursor = self.db_connection.cursor()
                cursor.execute(self.UPDATE_SQL, (status, appointment_sr_no, updated_dt, writeback_status_id))
                self.db_connection.commit()
                cursor.close()
                return True
            except pyodbc.Error as e:
                self.db_connection.rollback()
                if attempt < self.max_retries - 1:
                    logger.warning(f"[{self.client_id}] Writeback status update retry {attempt + 1}: {e}")
                    time.sleep(self.retry_delay * (attempt + 1))
                    self._reconnect_if_needed()
                else:
                    logger.error(
                        f"[{self.client_id}] Writeback status update failed for id {writeback_status_id}: {e}"
                    )
                    return False

        return False

    def _reconnect_if_needed(self):
        """Reconnect to database if connection is broken."""
        try:
            self.db_connection.cursor().execute("SELECT 1")
        except Exception:
            logger.info(f"[{self.client_id}] Reconnecting to database...")
            self.db_connection = connect_db(self.client_id, self.max_retries, self.retry_delay)

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
                self.db_connection = connect_db(self.client_id, self.max_retries, self.retry_delay)
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
    poll_interval = float(os.getenv('WRITEBACK_POLL_INTERVAL', os.getenv('POLL_INTERVAL', '1.0')))
    logger.info(f"Starting Sikka Writeback Status Worker for client: {client_id}")

    shutdown = GracefulShutdown()
    worker = WritebackStatusWorker()
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
