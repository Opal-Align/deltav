#!/usr/bin/env python3
"""Shared helpers for Sikka worker processes: graceful shutdown, queue/DB connection."""

import logging
import os
import signal
import time

import pyodbc
from azure.identity import DefaultAzureCredential, ManagedIdentityCredential
from azure.storage.queue import QueueClient

logger = logging.getLogger(__name__)


class GracefulShutdown:
    """Handle graceful shutdown on SIGTERM/SIGINT."""

    def __init__(self):
        self.shutdown_requested = False
        signal.signal(signal.SIGTERM, self._handle_signal)
        signal.signal(signal.SIGINT, self._handle_signal)

    def _handle_signal(self, signum, frame):
        logger.info(f"Received signal {signum}, initiating graceful shutdown...")
        self.shutdown_requested = True


def _get_managed_identity_credential(client_id: str):
    """Get Managed Identity credential (User-assigned or System-assigned)."""
    managed_identity_client_id = os.getenv('AZURE_CLIENT_ID')
    if managed_identity_client_id:
        logger.info(f"[{client_id}] Using User Managed Identity: {managed_identity_client_id}")
        return ManagedIdentityCredential(client_id=managed_identity_client_id)
    else:
        logger.info(f"[{client_id}] Using System Managed Identity")
        return DefaultAzureCredential()


def connect_queue(client_id: str, queue_name: str) -> QueueClient:
    """Connect to Azure Queue Storage. Prefers Managed Identity, falls back to connection string."""
    account_url = os.getenv('AZURE_STORAGE_ACCOUNT_URL')
    if account_url:
        try:
            credential = _get_managed_identity_credential(client_id)
            queue_client = QueueClient(
                account_url=account_url,
                queue_name=queue_name,
                credential=credential
            )
            # Test connection
            queue_client.get_queue_properties()
            logger.info(f"[{client_id}] Connected to queue via Managed Identity: {queue_name}")
            return queue_client
        except Exception as e:
            logger.warning(f"[{client_id}] Managed Identity failed, falling back to connection string: {e}")

    connection_string = os.getenv('AZURE_STORAGE_CONNECTION_STRING')
    if not connection_string:
        raise ValueError("AZURE_STORAGE_CONNECTION_STRING required (Managed Identity unavailable)")

    queue_client = QueueClient.from_connection_string(connection_string, queue_name=queue_name)
    logger.info(f"[{client_id}] Connected to queue via connection string: {queue_name}")
    return queue_client


def connect_db(client_id: str, max_retries: int, retry_delay: float) -> pyodbc.Connection:
    """Connect to SQL Server with retry logic."""
    connection_string = os.getenv('SQL_CONNECTION_STRING')
    if not connection_string:
        raise ValueError("SQL_CONNECTION_STRING environment variable is required")

    for attempt in range(max_retries):
        try:
            connection = pyodbc.connect(connection_string, timeout=30)
            connection.autocommit = False
            logger.info(f"[{client_id}] Connected to SQL Server")
            return connection
        except pyodbc.Error as e:
            logger.warning(f"[{client_id}] DB connection attempt {attempt + 1} failed: {e}")
            if attempt < max_retries - 1:
                time.sleep(retry_delay * (attempt + 1))
            else:
                raise
