#!/bin/bash
# Deploy Sikka appointment worker for a specific client
# Usage: ./deploy.sh

set -e

# Required variables
: "${CLIENT_ID:?Set CLIENT_ID}"
: "${STORAGE_ACCOUNT:?Set STORAGE_ACCOUNT}"
: "${MANAGED_IDENTITY_CLIENT_ID:?Set MANAGED_IDENTITY_CLIENT_ID}"
: "${ACR_NAME:?Set ACR_NAME}"
: "${SQL_SERVER:?Set SQL_SERVER}"
: "${SQL_DATABASE:?Set SQL_DATABASE}"
: "${SQL_USER:?Set SQL_USER}"
: "${SQL_PASSWORD:?Set SQL_PASSWORD}"
: "${SIKKA_REQUEST_KEY:?Set SIKKA_REQUEST_KEY}"

echo "Deploying Sikka appointment worker for client: $CLIENT_ID"
echo "  Storage: $STORAGE_ACCOUNT"
echo "  SQL Server: $SQL_SERVER"

# Apply shared configmap
kubectl apply -f k8s/configmap.yaml

# Apply per-client secret
envsubst < k8s/secret.yaml | kubectl apply -f -

# Apply per-client deployment
envsubst < k8s/deployment.yaml | kubectl apply -f -

echo ""
echo "Deployed sikka-appointment-worker-$CLIENT_ID"
kubectl get pods -l client=$CLIENT_ID