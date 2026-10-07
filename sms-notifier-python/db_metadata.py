#!/usr/bin/env python3
"""Shared DB_Metadata.dbo.Metadata_ClientPractice access, used by send_sms.py and send_email.py."""

import logging
import os
import time

import pyodbc

logger = logging.getLogger(__name__)

DB_MAX_RETRIES = 3
DB_RETRY_DELAY_SECONDS = 2.0

_SELECT_CLIENT_PRACTICE_SQL = "SELECT * FROM DB_Metadata.dbo.Metadata_ClientPractice"


def connect_db(max_retries: int = DB_MAX_RETRIES, retry_delay: float = DB_RETRY_DELAY_SECONDS) -> pyodbc.Connection:
    """Connect to the SQL Server hosting DB_Metadata, with retry logic."""
    connection_string = os.getenv('SQL_CONNECTION_STRING')
    if not connection_string:
        raise ValueError("SQL_CONNECTION_STRING environment variable is required")

    for attempt in range(1, max_retries + 1):
        try:
            connection = pyodbc.connect(connection_string, timeout=30)
            logger.info("Connected to SQL Server")
            return connection
        except pyodbc.Error as e:
            logger.warning(f"DB connection attempt {attempt}/{max_retries} failed: {e}")
            if attempt < max_retries:
                time.sleep(retry_delay * attempt)
            else:
                raise


def load_client_practice_map(connection: pyodbc.Connection) -> dict:
    """Load DB_Metadata.dbo.Metadata_ClientPractice into a dict keyed by PracticeID.

    Each value is a dict of column name -> value for that practice's row.
    """
    cursor = connection.cursor()
    cursor.execute(_SELECT_CLIENT_PRACTICE_SQL)
    columns = [col[0] for col in cursor.description]

    practice_map = {}
    for row in cursor.fetchall():
        record = dict(zip(columns, row))
        practice_id = record['PracticeID']
        if practice_id in practice_map:
            logger.warning(f"Duplicate PracticeID {practice_id} in Metadata_ClientPractice, "
                            f"keeping the last row seen")
        practice_map[practice_id] = record

    cursor.close()
    logger.info(f"Loaded {len(practice_map)} practice(s) from Metadata_ClientPractice")
    return practice_map
