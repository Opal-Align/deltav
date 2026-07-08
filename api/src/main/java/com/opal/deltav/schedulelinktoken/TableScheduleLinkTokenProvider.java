package com.opal.deltav.schedulelinktoken;

import com.azure.data.tables.TableClient;
import com.azure.data.tables.TableServiceClient;
import com.azure.data.tables.TableServiceClientBuilder;
import com.azure.data.tables.models.TableEntity;
import com.azure.identity.DefaultAzureCredentialBuilder;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;
import java.util.logging.Logger;

/**
 * Fetches schedule link token data from Azure Table Storage for a given key.
 */
public class TableScheduleLinkTokenProvider implements ScheduleLinkTokenProvider {

    private static final String TABLE_NAME = "schedulelinktokens";
    private static final String PARTITION_KEY = "token";
    private static volatile TableServiceClient serviceClient;
    private static volatile TableClient tableClient;
    private static final Object lock = new Object();

    @Override
    public Map<String, Object> getData(String key, Logger logger) {
        try {
            TableClient client = getTableClient(logger);
            TableEntity entity = client.getEntity(PARTITION_KEY, key);
            if (entity == null) {
                return Map.of();
            }
            return new HashMap<>(entity.getProperties());
        } catch (Exception e) {
            e.printStackTrace();
            logger.warning("Failed to get data for key '" + key + "': " + e.getMessage());
            logger.info(e.toString());
            return Map.of();
        }
    }

    @Override
    public <T> T getData(String key, Function<Map<String, Object>, T> converter, Logger logger) {
        Map<String, Object> data = getData(key, logger);
        if (data.isEmpty()) {
            return null;
        }
        return converter.apply(data);
    }

    @Override
    public Object getValue(String key, String fieldKey, Logger logger) {
        try {
            TableClient client = getTableClient(logger);
            TableEntity entity = client.getEntity(PARTITION_KEY, key);

            if (entity == null) {
                return null;
            }

            return entity.getProperties().get(fieldKey);

        } catch (Exception e) {
            logger.warning("Failed to get value for key '" + key + "', field '" + fieldKey + "': " + e.getMessage());
            return null;
        }
    }

    @Override
    public boolean exists(String key, Logger logger) {
        try {
            TableClient client = getTableClient(logger);
            TableEntity entity = client.getEntity(PARTITION_KEY, key);
            return entity != null;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public ScheduleLinkTokenProviderType getType() {
        return ScheduleLinkTokenProviderType.TABLE_STORAGE;
    }

    @Override
    public void markAsUsed(String key, String clientIp, Logger logger) {
        try {
            TableClient client = getTableClient(logger);

            // Create entity with only the fields to update
            Map<String, Object> properties = new HashMap<>();
            properties.put("status", "used");
            properties.put("last_seen_ip", clientIp);
            properties.put("used_at", OffsetDateTime.now());

            TableEntity entity = new TableEntity(PARTITION_KEY, key);
            entity.setProperties(properties);

            // Use upsert with MERGE mode - only updates specified fields
            client.upsertEntity(entity);
            logger.info("Token marked as used: " + key + ", ip=" + clientIp);

        } catch (Exception e) {
            logger.warning("Failed to mark token as used '" + key + "': " + e.getMessage());
        }
    }

    /**
     * Increment attempt count for a token.
     */
    public void incrementAttemptCount(String key, Logger logger) {
        try {
            TableClient client = getTableClient(logger);
            TableEntity entity = client.getEntity(PARTITION_KEY, key);

            if (entity == null) {
                return;
            }

            Object attemptObj = entity.getProperties().get("attempt_count");
            int currentAttempts = 0;
            if (attemptObj instanceof Number) {
                currentAttempts = ((Number) attemptObj).intValue();
            }

            Map<String, Object> properties = new HashMap<>();
            properties.put("attempt_count", currentAttempts + 1);

            TableEntity updateEntity = new TableEntity(PARTITION_KEY, key);
            updateEntity.setProperties(properties);
            client.upsertEntity(updateEntity);

            logger.info("Incremented attempt count for token: " + key + ", new count=" + (currentAttempts + 1));

        } catch (Exception e) {
            logger.warning("Failed to increment attempt count for '" + key + "': " + e.getMessage());
        }
    }

    private TableClient getTableClient(Logger logger) {
        if (tableClient == null) {
            synchronized (lock) {
                if (tableClient == null) {
                    TableServiceClient svcClient = getServiceClient(logger);
                    tableClient = svcClient.getTableClient(TABLE_NAME);
                    logger.info("Table client initialized for table: " + TABLE_NAME);
                }
            }
        }
        return tableClient;
    }

    private TableServiceClient getServiceClient(Logger logger) {
        if (serviceClient == null) {
            synchronized (lock) {
                if (serviceClient == null) {
                    String storageAccountName = System.getenv("STORAGE_ACCOUNT_NAME");
                    String connStr = System.getenv("AzureWebJobsStorage");

                    if (storageAccountName != null && !storageAccountName.isBlank()) {
                        logger.info("Initializing Table Storage client with managed identity");
                        String endpoint = "https://" + storageAccountName + ".table.core.windows.net";
                        serviceClient = new TableServiceClientBuilder()
                                .endpoint(endpoint)
                                .credential(new DefaultAzureCredentialBuilder().build())
                                .buildClient();
                        serviceClient.listTables().forEach(table -> {
                            logger.info("Table name: " + table.getName());
                        });
                    } else if (connStr != null && !connStr.isBlank()) {
                        logger.info("Initializing Table Storage client with connection string");
                        serviceClient = new TableServiceClientBuilder()
                                .connectionString(connStr)
                                .buildClient();
                    } else {
                        throw new ScheduleLinkTokenException("Neither STORAGE_ACCOUNT_NAME nor AzureWebJobsStorage is configured");
                    }
                }
            }
        }
        return serviceClient;
    }
}