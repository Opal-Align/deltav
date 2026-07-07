package com.opal.deltav.schedulelinktoken;

import com.azure.data.tables.TableClient;
import com.azure.data.tables.TableServiceClient;
import com.azure.data.tables.TableServiceClientBuilder;
import com.azure.data.tables.models.TableEntity;
import com.azure.identity.DefaultAzureCredentialBuilder;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;
import java.util.logging.Logger;

/**
 * Fetches schedule link token data from Azure Table Storage for a given key.
 */
public class TableScheduleLinkTokenProvider implements ScheduleLinkTokenProvider {

    private static final String TABLE_NAME = "schedulelinktoken";
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

            Map<String, Object> result = new HashMap<>(entity.getProperties());
            return result;

        } catch (Exception e) {
            logger.warning("Failed to get data for key '" + key + "': " + e.getMessage());
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