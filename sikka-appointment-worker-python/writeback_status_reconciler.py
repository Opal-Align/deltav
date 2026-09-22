#!/usr/bin/env python3
"""
Writeback Status Reconciler
Run-once batch job (scheduled every 5 minutes via a k8s CronJob): finds
trace_appt_writeback_requests rows that have a writeback_status_id but no
resolved status yet, queries Sikka's GET /v4/writeback_status?id=<id> for
each, and applies the result:
  - status "Success" -> sets appointment_sr_no and status = "Success"
  - completed but not successful -> sets status = "Failed"
  - not yet completed on the PMS side -> left alone, picked up next run
"""

import logging
import os
import sys
import time
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

from common import connect_db, SikkaTokenManager

logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s - %(levelname)s - %(message)s',
    handlers=[logging.StreamHandler(sys.stdout)]
)
logger = logging.getLogger(__name__)


class WritebackStatusReconciler:
    """Reconciles pending writeback_status_id rows against Sikka's writeback_status API."""

    SELECT_PENDING_SQL = """
        SELECT TOP (?) id, writeback_status_id, sikka_office_id
        FROM trace_appt_writeback_requests
        WHERE writeback_status_id IS NOT NULL AND status = 'PENDING'
        ORDER BY created_dt ASC
    """

    UPDATE_SUCCESS_SQL = """
        UPDATE trace_appt_writeback_requests
        SET status = ?, appointment_sr_no = ?, updated_dt = ?
        WHERE id = ?
    """

    UPDATE_FAILED_SQL = """
        UPDATE trace_appt_writeback_requests
        SET status = ?, updated_dt = ?
        WHERE id = ?
    """

    def __init__(self):
        self.client_id = os.getenv('CLIENT_ID', 'default')
        self.db_connection: Optional[pyodbc.Connection] = None
        self.token_manager: Optional[SikkaTokenManager] = None

        self.batch_size = int(os.getenv('WRITEBACK_RECONCILE_BATCH_SIZE', '100'))
        self.max_retries = int(os.getenv('MAX_RETRIES', '3'))
        self.retry_delay = float(os.getenv('RETRY_DELAY', '1.0'))

        self.sikka_api_url = os.getenv(
            'SIKKA_WRITEBACK_STATUS_API_URL', 'https://api.sikkasoft.com/v4/writeback_status'
        )
        self.http_timeout = float(os.getenv('SIKKA_HTTP_TIMEOUT', '15'))

    def connect(self):
        self.token_manager = SikkaTokenManager(
            app_id=os.getenv('SIKKA_APP_ID'),
            app_key=os.getenv('SIKKA_APP_KEY'),
            max_retries=self.max_retries,
            retry_delay=self.retry_delay,
            http_timeout=self.http_timeout,
        )
        self.db_connection = connect_db(self.client_id, self.max_retries, self.retry_delay)

    def run(self) -> int:
        """Drain every currently-pending row (in batches). Returns count resolved."""
        resolved_count = 0
        while True:
            pending = self._fetch_pending()
            if not pending:
                break

            logger.info(f"[{self.client_id}] Reconciling {len(pending)} pending writeback status(es)")
            for row_id, writeback_status_id, office_id in pending:
                try:
                    if not office_id:
                        logger.error(
                            f"[{self.client_id}] Row {row_id} has no sikka_office_id, cannot look up "
                            f"writeback_status_id {writeback_status_id}, skipping"
                        )
                        continue
                    if self._reconcile_one(row_id, writeback_status_id, office_id):
                        resolved_count += 1
                except Exception as e:
                    logger.error(
                        f"[{self.client_id}] Failed to reconcile writeback_status_id "
                        f"{writeback_status_id}: {e}"
                    )

            if len(pending) < self.batch_size:
                break  # fewer than a full batch - nothing left pending

        if resolved_count > 0:
            logger.info(f"[{self.client_id}] Resolved {resolved_count} writeback status(es)")

        return resolved_count

    def _reconcile_one(self, row_id, writeback_status_id, office_id) -> bool:
        try:
            request_key = self.token_manager.get_request_key(office_id)
        except RuntimeError as e:
            logger.error(f"[{self.client_id}] Could not obtain Sikka request_key for office {office_id}: {e}")
            return False

        item = self._fetch_writeback_status(writeback_status_id, request_key)
        if item is None:
            return False  # not found / not yet completed - retry next run
        return self._apply_result(row_id, item)

    def _fetch_pending(self) -> list:
        """Fetch (id, writeback_status_id, sikka_office_id) rows still awaiting a resolved status."""
        for attempt in range(self.max_retries):
            try:
                cursor = self.db_connection.cursor()
                cursor.execute(self.SELECT_PENDING_SQL, (self.batch_size,))
                rows = [(row[0], row[1], row[2]) for row in cursor.fetchall()]
                cursor.close()
                return rows
            except pyodbc.Error as e:
                if attempt < self.max_retries - 1:
                    logger.warning(f"[{self.client_id}] Fetch pending retry {attempt + 1}: {e}")
                    time.sleep(self.retry_delay * (attempt + 1))
                    self._reconnect_if_needed()
                else:
                    logger.error(f"[{self.client_id}] Failed to fetch pending writeback statuses: {e}")
                    return []
        return []

    def _fetch_writeback_status(self, writeback_status_id, request_key: str) -> Optional[dict]:
        """GET /v4/writeback_status?id=<id> and return the matching item, or None if not resolvable yet."""
        headers = {'Request-Key': request_key}
        params = {'id': writeback_status_id}

        for attempt in range(self.max_retries):
            try:
                response = requests.get(
                    self.sikka_api_url,
                    params=params,
                    headers=headers,
                    timeout=self.http_timeout
                )

                if response.status_code >= 500 or response.status_code == 429:
                    logger.warning(
                        f"[{self.client_id}] Sikka transient error ({response.status_code}) for id "
                        f"{writeback_status_id}, attempt {attempt + 1}: {response.text}"
                    )
                elif response.status_code >= 400:
                    logger.error(
                        f"[{self.client_id}] Sikka rejected writeback_status lookup for id "
                        f"{writeback_status_id} ({response.status_code}): {response.text}"
                    )
                    return None
                else:
                    items = (response.json() or {}).get('items') or []
                    return items[0] if items else None
            except requests.RequestException as e:
                logger.warning(
                    f"[{self.client_id}] Sikka request failed for id {writeback_status_id}, "
                    f"attempt {attempt + 1}: {e}"
                )
            except ValueError as e:
                logger.error(f"[{self.client_id}] Invalid Sikka response for id {writeback_status_id}: {e}")
                return None

            if attempt < self.max_retries - 1:
                time.sleep(self.retry_delay * (attempt + 1))

        return None

    def _apply_result(self, row_id, item: dict) -> bool:
        """Apply a resolved writeback_status item to its trace_appt_writeback_requests row."""
        status = (item.get('status') or '').strip()
        is_completed = str(item.get('is_completed', '1')) in ('1', 'true', 'True')

        if not is_completed:
            return False  # still in progress on the PMS side - retry next run

        updated_dt = datetime.utcnow()

        if status.lower() == 'success':
            appointment_sr_no = item.get('appointment_sr_no') or None
            return self._execute_update(
                self.UPDATE_SUCCESS_SQL, ('SCHEDULED', appointment_sr_no, updated_dt, row_id)
            )

        logger.error(f"[{self.client_id}] Writeback failed for row {row_id}: {item}")
        return self._execute_update(self.UPDATE_FAILED_SQL, ('FAILED', updated_dt, row_id))

    def _execute_update(self, sql: str, params: tuple) -> bool:
        for attempt in range(self.max_retries):
            try:
                cursor = self.db_connection.cursor()
                cursor.execute(sql, params)
                self.db_connection.commit()
                cursor.close()
                return True
            except pyodbc.Error as e:
                self.db_connection.rollback()
                if attempt < self.max_retries - 1:
                    logger.warning(f"[{self.client_id}] Update retry {attempt + 1}: {e}")
                    time.sleep(self.retry_delay * (attempt + 1))
                    self._reconnect_if_needed()
                else:
                    logger.error(f"[{self.client_id}] Update failed: {e}")
                    return False
        return False

    def _reconnect_if_needed(self):
        try:
            self.db_connection.cursor().execute("SELECT 1")
        except Exception:
            logger.info(f"[{self.client_id}] Reconnecting to database...")
            self.db_connection = connect_db(self.client_id, self.max_retries, self.retry_delay)

    def close(self):
        if self.db_connection:
            try:
                self.db_connection.close()
            except Exception:
                pass
        logger.info(f"[{self.client_id}] Reconciler finished")


def main():
    client_id = os.getenv('CLIENT_ID', 'default')
    logger.info(f"Starting Sikka Writeback Status Reconciler for client: {client_id}")

    reconciler = WritebackStatusReconciler()
    reconciler.connect()
    try:
        reconciler.run()
    finally:
        reconciler.close()


if __name__ == '__main__':
    main()
