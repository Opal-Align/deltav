#!/usr/bin/env python3
"""Shared helpers for Sikka worker processes: graceful shutdown, queue/DB connection, token cache."""

import logging
import os
import re
import signal
import threading
import time
from datetime import datetime, timedelta
from typing import Optional

import pyodbc
import requests
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


class SikkaTokenManager:
    """Caches Sikka request_keys per office_id in memory, refreshing shortly before expiry.

    Only the practice (office_id) a given request actually needs is ever fetched -
    never the full authorized_practices list.
    """

    def __init__(self, app_id: str, app_key: str, max_retries: int = 3, retry_delay: float = 1.0,
                 http_timeout: float = 15.0):
        if not app_id or not app_key:
            raise ValueError("SIKKA_APP_ID and SIKKA_APP_KEY are required")

        self.app_id = app_id
        self.app_key = app_key
        self.max_retries = max_retries
        self.retry_delay = retry_delay
        self.http_timeout = http_timeout

        self.refresh_margin_seconds = float(os.getenv('SIKKA_TOKEN_REFRESH_MARGIN_SECONDS', '300'))
        self.default_ttl_seconds = float(os.getenv('SIKKA_TOKEN_DEFAULT_TTL_SECONDS', '3600'))
        self.authorized_practices_url = os.getenv(
            'SIKKA_AUTHORIZED_PRACTICES_API_URL', 'https://api.sikkasoft.com/v4/authorized_practices'
        )
        self.request_key_url = os.getenv('SIKKA_REQUEST_KEY_API_URL', 'https://api.sikkasoft.com/v4/request_key')

        self._cache = {}
        self._lock = threading.Lock()

    def get_request_key(self, office_id: str) -> str:
        """Return a valid request_key for office_id, generating or refreshing it as needed."""
        office_id = str(office_id)

        with self._lock:
            entry = self._cache.get(office_id)
            now = datetime.utcnow()

            if entry and entry['expires_at'] - now > timedelta(seconds=self.refresh_margin_seconds):
                return entry['request_key']

            if entry and entry.get('refresh_key'):
                logger.info(f"Sikka request_key for office {office_id} is expiring, refreshing")
                refreshed = self._refresh(office_id, entry['refresh_key'])
                if refreshed:
                    self._cache[office_id] = refreshed
                    return refreshed['request_key']
                logger.warning(f"Refresh failed for office {office_id}, regenerating from scratch")

            generated = self._generate(office_id)
            if not generated:
                raise RuntimeError(f"Failed to obtain Sikka request_key for office_id={office_id}")
            self._cache[office_id] = generated
            return generated['request_key']

    def _generate(self, office_id: str) -> Optional[dict]:
        secret_key = self._fetch_secret_key(office_id)
        if not secret_key:
            return None

        payload = {
            'grant_type': 'request_key',
            'app_id': self.app_id,
            'app_key': self.app_key,
            'office_id': office_id,
            'secret_key': secret_key,
        }
        return self._post_request_key(payload, office_id)

    def _refresh(self, office_id: str, refresh_key: str) -> Optional[dict]:
        payload = {
            'grant_type': 'refresh_token',
            'app_id': self.app_id,
            'app_key': self.app_key,
            'refresh_key': refresh_key,
        }
        return self._post_request_key(payload, office_id)

    def _fetch_secret_key(self, office_id: str) -> Optional[str]:
        """GET /v4/authorized_practices/{office_id} and return its secret_key."""
        url = f"{self.authorized_practices_url}/{office_id}"
        headers = {
            'App-Id': self.app_id,
            'App-Key': self.app_key,
            'Content-Type': 'application/json',
        }

        for attempt in range(self.max_retries):
            try:
                response = requests.get(url, headers=headers, timeout=self.http_timeout)

                if response.status_code < 300:
                    practice = self._extract_practice(self._safe_json(response), office_id)
                    if practice and practice.get('secret_key'):
                        return practice['secret_key']
                    logger.error(f"authorized_practices/{office_id} response missing secret_key")
                    return None

                if response.status_code < 500 and response.status_code != 429:
                    logger.error(
                        f"authorized_practices/{office_id} rejected ({response.status_code}): {response.text}"
                    )
                    return None

                logger.warning(
                    f"authorized_practices/{office_id} transient error ({response.status_code}), "
                    f"attempt {attempt + 1}"
                )
            except requests.RequestException as e:
                logger.warning(f"authorized_practices/{office_id} request failed, attempt {attempt + 1}: {e}")

            if attempt < self.max_retries - 1:
                time.sleep(self.retry_delay * (attempt + 1))

        return None

    def _post_request_key(self, payload: dict, office_id: str) -> Optional[dict]:
        for attempt in range(self.max_retries):
            try:
                response = requests.post(self.request_key_url, json=payload, timeout=self.http_timeout)

                if response.status_code < 300:
                    parsed = self._parse_token_response(self._safe_json(response))
                    if parsed:
                        logger.info(
                            f"Sikka request_key ready for office {office_id}, expires at {parsed['expires_at']}"
                        )
                    return parsed

                if response.status_code < 500 and response.status_code != 429:
                    logger.error(
                        f"request_key rejected for office {office_id} ({response.status_code}): {response.text}"
                    )
                    return None

                logger.warning(
                    f"request_key transient error for office {office_id} ({response.status_code}), "
                    f"attempt {attempt + 1}"
                )
            except requests.RequestException as e:
                logger.warning(f"request_key call failed for office {office_id}, attempt {attempt + 1}: {e}")

            if attempt < self.max_retries - 1:
                time.sleep(self.retry_delay * (attempt + 1))

        return None

    @staticmethod
    def _safe_json(response: requests.Response):
        try:
            return response.json()
        except ValueError:
            return None

    @staticmethod
    def _extract_practice(data, office_id: str) -> Optional[dict]:
        if not isinstance(data, dict):
            return None
        if 'items' in data:
            items = data.get('items') or []
            for item in items:
                if str(item.get('office_id')) == str(office_id):
                    return item
            return items[0] if items else None
        return data

    def _parse_token_response(self, data) -> Optional[dict]:
        if not isinstance(data, dict) or not data.get('request_key'):
            logger.error(f"request_key response missing request_key: {data}")
            return None

        return {
            'request_key': data['request_key'],
            'refresh_key': data.get('refresh_key'),
            'expires_at': self._parse_expiry(data),
        }

    def _parse_expiry(self, data: dict) -> datetime:
        expires_in = data.get('expires_in')
        if expires_in:
            match = re.match(r'\d+', str(expires_in))
            if match:
                return datetime.utcnow() + timedelta(seconds=int(match.group()))

        end_time = data.get('end_time')
        if end_time:
            try:
                return datetime.strptime(end_time, '%Y-%m-%dT%H:%M:%S')
            except ValueError:
                pass

        logger.warning(f"Could not determine expiry from request_key response, using default TTL")
        return datetime.utcnow() + timedelta(seconds=self.default_ttl_seconds)
