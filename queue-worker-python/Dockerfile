# Build stage - install dependencies
FROM python:3.11-alpine AS builder

# Install build dependencies for pyodbc
RUN apk add --no-cache \
    gcc \
    musl-dev \
    unixodbc-dev

WORKDIR /app
COPY requirements.txt .
RUN pip install --no-cache-dir --target=/app/deps -r requirements.txt


# Runtime stage
FROM python:3.11-alpine

# Install ODBC Driver for SQL Server
RUN apk add --no-cache \
    curl \
    gnupg \
    unixodbc

# Install Microsoft ODBC Driver 18
RUN curl -O https://download.microsoft.com/download/1/f/f/1fffeb70-1dd2-4ae3-85c8-5eb25789d82e/msodbcsql18_18.3.3.1-1_amd64.apk && \
    curl -O https://download.microsoft.com/download/1/f/f/1fffeb70-1dd2-4ae3-85c8-5eb25789d82e/mssql-tools18_18.3.1.1-1_amd64.apk && \
    apk add --allow-untrusted msodbcsql18_18.3.3.1-1_amd64.apk && \
    apk add --allow-untrusted mssql-tools18_18.3.1.1-1_amd64.apk && \
    rm -f *.apk

WORKDIR /app

# Copy dependencies from builder
COPY --from=builder /app/deps /app/deps
ENV PYTHONPATH=/app/deps

# Copy application
COPY worker.py .

# Create non-root user
RUN addgroup -g 1001 appgroup && \
    adduser -u 1001 -G appgroup -D appuser && \
    chown -R appuser:appgroup /app

USER appuser

# Environment defaults
ENV POLL_INTERVAL=1.0 \
    BATCH_SIZE=16 \
    VISIBILITY_TIMEOUT=30 \
    MAX_RETRIES=3 \
    PYTHONUNBUFFERED=1

CMD ["python", "worker.py"]