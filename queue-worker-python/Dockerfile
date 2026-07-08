# Build stage - install dependencies
FROM python:3.11-slim AS builder

WORKDIR /app
COPY requirements.txt .
RUN pip install --no-cache-dir --target=/app/deps -r requirements.txt


# Runtime stage
FROM python:3.11-slim

# Install ODBC Driver for SQL Server
RUN apt-get update && apt-get install -y --no-install-recommends \
    curl \
    gnupg \
    unixodbc \
    && curl -fsSL https://packages.microsoft.com/keys/microsoft.asc | gpg --dearmor -o /usr/share/keyrings/microsoft-prod.gpg \
    && curl -fsSL https://packages.microsoft.com/config/debian/12/prod.list | tee /etc/apt/sources.list.d/mssql-release.list \
    && apt-get update \
    && ACCEPT_EULA=Y apt-get install -y --no-install-recommends msodbcsql18 \
    && apt-get clean \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

# Copy dependencies from builder
COPY --from=builder /app/deps /app/deps
ENV PYTHONPATH=/app/deps

# Copy application
COPY worker.py .

# Create non-root user
RUN groupadd -g 1001 appgroup && \
    useradd -u 1001 -g appgroup -m appuser && \
    chown -R appuser:appgroup /app

USER appuser

# Environment defaults
ENV POLL_INTERVAL=1.0 \
    BATCH_SIZE=16 \
    VISIBILITY_TIMEOUT=30 \
    MAX_RETRIES=3 \
    PYTHONUNBUFFERED=1

CMD ["python", "worker.py"]