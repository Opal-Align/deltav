package com.opal.deltav.schedulelink;

import com.azure.identity.DefaultAzureCredentialBuilder;
import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobServiceClient;
import com.azure.storage.blob.BlobServiceClientBuilder;
import com.azure.storage.blob.models.UserDelegationKey;
import com.azure.storage.blob.sas.BlobSasPermission;
import com.azure.storage.blob.sas.BlobServiceSasSignatureValues;

import java.time.OffsetDateTime;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Read-only SAS URLs for practice logos snapshotted on schedule link tokens. */
public class LogoBlobService {

    private static final String BLOB_ENDPOINT_FORMAT = "https://%s.blob.core.windows.net";

    private volatile BlobServiceClient logoBlobServiceClient;
    private volatile boolean useConnectionStringAuth;

    public String generateReadSasUrl(String blobPath, Logger logger) {
        if (blobPath == null || blobPath.isBlank()) {
            return null;
        }

        String containerName = env("AZURE_STORAGE_CONTAINER_LOGO", null);
        if (containerName == null) {
            logger.warning("Logo container is not configured; skipping logoUrl");
            return null;
        }

        BlobServiceClient serviceClient = logoServiceClient(logger);
        if (serviceClient == null) {
            logger.warning("Logo storage is not configured; skipping logoUrl");
            return null;
        }

        try {
            BlobContainerClient containerClient = serviceClient.getBlobContainerClient(containerName);
            BlobClient blobClient = containerClient.getBlobClient(blobPath.trim());

            if (!blobClient.exists()) {
                logger.warning("Logo blob not found: " + containerName + "/" + blobPath);
                return null;
            }

            String sasToken = useConnectionStringAuth
                    ? generateAccountKeySas(blobClient)
                    : generateUserDelegationSas(blobClient, serviceClient);
            return blobClient.getBlobUrl() + "?" + sasToken;
        } catch (Exception ex) {
            logger.log(Level.WARNING, "Failed to generate logo SAS for path " + blobPath + ": " + ex.getMessage());
            return null;
        }
    }

    private static String generateAccountKeySas(BlobClient blobClient) {
        BlobSasPermission permission = new BlobSasPermission().setReadPermission(true);
        OffsetDateTime sasExpiryTime = OffsetDateTime.now().plusHours(24);
        OffsetDateTime sasStartTime = OffsetDateTime.now().minusMinutes(5);
        BlobServiceSasSignatureValues sasSignatureValues = new BlobServiceSasSignatureValues(
                sasExpiryTime, permission).setStartTime(sasStartTime);
        return blobClient.generateSas(sasSignatureValues);
    }

    private static String generateUserDelegationSas(BlobClient blobClient, BlobServiceClient serviceClient) {
        OffsetDateTime keyStartTime = OffsetDateTime.now();
        OffsetDateTime keyExpiryTime = OffsetDateTime.now().plusHours(24).plusMinutes(5);
        UserDelegationKey userDelegationKey = serviceClient.getUserDelegationKey(keyStartTime, keyExpiryTime);

        BlobSasPermission permission = new BlobSasPermission().setReadPermission(true);
        OffsetDateTime sasExpiryTime = OffsetDateTime.now().plusHours(24);
        OffsetDateTime sasStartTime = OffsetDateTime.now().minusMinutes(5);

        BlobServiceSasSignatureValues sasSignatureValues = new BlobServiceSasSignatureValues(
                sasExpiryTime, permission).setStartTime(sasStartTime);

        return blobClient.generateUserDelegationSas(sasSignatureValues, userDelegationKey);
    }

    private BlobServiceClient logoServiceClient(Logger logger) {
        if (logoBlobServiceClient == null) {
            synchronized (this) {
                if (logoBlobServiceClient == null) {
                    String connectionString = env("AZURE_STORAGE_CONNECTION_STRING_LOGO", null);
                    if (connectionString != null) {
                        logoBlobServiceClient = new BlobServiceClientBuilder()
                                .connectionString(connectionString)
                                .buildClient();
                        useConnectionStringAuth = true;
                        return logoBlobServiceClient;
                    }

                    String storageAccount = env("AZURE_STORAGE_ACCOUNT_LOGO", null);
                    if (storageAccount == null) {
                        return null;
                    }

                    logoBlobServiceClient = new BlobServiceClientBuilder()
                            .endpoint(String.format(BLOB_ENDPOINT_FORMAT, storageAccount))
                            .credential(new DefaultAzureCredentialBuilder().build())
                            .buildClient();
                    useConnectionStringAuth = false;
                }
            }
        }
        return logoBlobServiceClient;
    }

    private static String env(String name, String defaultValue) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        return value;
    }
}
