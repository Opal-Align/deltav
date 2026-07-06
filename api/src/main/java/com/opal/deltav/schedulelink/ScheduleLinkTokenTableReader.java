package com.opal.deltav.schedulelink;

import com.azure.data.tables.TableClient;
import com.azure.data.tables.TableServiceClient;
import com.azure.data.tables.TableServiceClientBuilder;
import com.azure.data.tables.models.TableEntity;
import com.azure.data.tables.models.TableEntityUpdateMode;
import com.azure.identity.DefaultAzureCredentialBuilder;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.logging.Logger;

/** Reads schedule link token rows from Azure Table {@code ScheduleLinkTokens} (pairs with batch {@code ScheduleLinkTokenTableWriter}). */
public class ScheduleLinkTokenTableReader {

    static final String ROW_KEY = "token";
    private static final String DEFAULT_TABLE_NAME = "ScheduleLinkTokens";

    private static volatile TableClient tableClient;
    private static final Object lock = new Object();

    public Optional<ScheduleLinkTokenRecord> findByTokenHash(String tokenHash, Logger logger) {
        try {
            TableEntity entity = tableClient(logger).getEntity(tokenHash, ROW_KEY);
            return Optional.of(mapEntity(entity));
        } catch (Exception ex) {
            logger.info("Schedule link token not found for hash lookup");
            return Optional.empty();
        }
    }

    public void incrementAttemptCount(String tokenHash, int currentCount, Logger logger) {
        try {
            TableEntity update = new TableEntity(tokenHash, ROW_KEY)
                    .addProperty("attempt_count", currentCount + 1);
            tableClient(logger).updateEntity(update, TableEntityUpdateMode.MERGE);
        } catch (Exception ex) {
            logger.warning("Failed to increment attempt_count: " + ex.getMessage());
        }
    }

    /**
     * Marks an active token as used (OTP verify success). Returns false if not active or missing.
     */
    public boolean markUsedIfActive(String tokenHash, Logger logger) {
        try {
            TableEntity existing = tableClient(logger).getEntity(tokenHash, ROW_KEY);
            String status = stringProperty(existing, "status");
            if (status == null || !"active".equalsIgnoreCase(status)) {
                return false;
            }
            TableEntity update = new TableEntity(tokenHash, ROW_KEY)
                    .addProperty("status", "used")
                    .addProperty("used_at", OffsetDateTime.now());
            tableClient(logger).updateEntity(update, TableEntityUpdateMode.MERGE);
            return true;
        } catch (Exception ex) {
            logger.warning("Failed to mark schedule link token used: " + ex.getMessage());
            return false;
        }
    }

    private static ScheduleLinkTokenRecord mapEntity(TableEntity entity) {
        String status = stringProperty(entity, "status");
        OffsetDateTime expiresAt = entity.getProperty("expires_at") instanceof OffsetDateTime odt
                ? odt
                : null;
        int attemptCount = 0;
        Object attempt = entity.getProperty("attempt_count");
        if (attempt instanceof Number number) {
            attemptCount = number.intValue();
        }
        return new ScheduleLinkTokenRecord(
                entity.getPartitionKey(),
                status,
                expiresAt,
                attemptCount,
                stringProperty(entity, "practice_name"),
                stringProperty(entity, "logo_blob_path"));
    }

    private static String stringProperty(TableEntity entity, String name) {
        Object value = entity.getProperty(name);
        return value == null ? null : value.toString();
    }

    private static TableClient tableClient(Logger logger) {
        if (tableClient == null) {
            synchronized (lock) {
                if (tableClient == null) {
                    String tableName = env("SCHEDULE_LINK_TABLE_NAME", DEFAULT_TABLE_NAME);
                    String storageAccountName = System.getenv("STORAGE_ACCOUNT_NAME");
                    String connStr = System.getenv("AzureWebJobsStorage");

                    TableServiceClient serviceClient;
                    if (storageAccountName != null && !storageAccountName.isBlank()) {
                        logger.info("Initializing schedule link token Table reader with managed identity");
                        String endpoint = "https://" + storageAccountName + ".table.core.windows.net";
                        serviceClient = new TableServiceClientBuilder()
                                .endpoint(endpoint)
                                .credential(new DefaultAzureCredentialBuilder().build())
                                .buildClient();
                    } else if (connStr != null && !connStr.isBlank()) {
                        logger.info("Initializing schedule link token Table reader with connection string");
                        serviceClient = new TableServiceClientBuilder()
                                .connectionString(connStr)
                                .buildClient();
                    } else {
                        throw new IllegalStateException(
                                "Neither STORAGE_ACCOUNT_NAME nor AzureWebJobsStorage is configured");
                    }

                    serviceClient.createTableIfNotExists(tableName);
                    tableClient = serviceClient.getTableClient(tableName);
                    logger.info("Schedule link token Table reader initialized: table=" + tableName);
                }
            }
        }
        return tableClient;
    }

    private static String env(String name, String defaultValue) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        return value;
    }
}
