package com.opal.deltav.config;

import com.azure.data.tables.TableClient;
import com.azure.data.tables.TableServiceClient;
import com.azure.data.tables.TableServiceClientBuilder;
import com.azure.data.tables.models.TableEntity;
import com.azure.identity.DefaultAzureCredentialBuilder;
import com.opal.deltav.model.PracticeMetadata;

import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Loads practice metadata from Azure Table Storage into an in-memory cache.
 * Key: Base64 encoded string of "clientId:practiceId" (stored as RowKey in table)
 * Value: PracticeMetadata POJO
 * <p>
 * Call initialize() from WarmupFunction to load metadata during function startup.
 */
public class PracticeMetadataLoader {

    private static final long CACHE_TTL_MS = 10 * 60 * 1000L; // 10 minutes

    private static volatile Map<String, PracticeMetadata> metadataMap;
    private static volatile Instant cacheExpiry = Instant.EPOCH;
    private static final Object lock = new Object();
    private static volatile boolean initialized = false;

    private PracticeMetadataLoader() {
    }

    /**
     * Initialize and load metadata from Azure Table Storage.
     * Called from WarmupFunction during function app startup.
     */
    public static void initialize(Logger logger) {
        if (!initialized) {
            synchronized (lock) {
                if (!initialized) {
                    logger.info("PracticeMetadataLoader: initializing...");
                    metadataMap = loadFromTable(logger);
                    cacheExpiry = Instant.now().plusMillis(CACHE_TTL_MS);
                    initialized = true;
                    logger.info("PracticeMetadataLoader: initialization complete");
                }
            }
        }
    }

    /**
     * Get the metadata map, loading from Azure Table Storage if cache is expired.
     */
    public static Map<String, PracticeMetadata> getMetadataMap(Logger logger) {
        if (metadataMap == null || Instant.now().isAfter(cacheExpiry)) {
            synchronized (lock) {
                if (metadataMap == null || Instant.now().isAfter(cacheExpiry)) {
                    metadataMap = loadFromTable(logger);
                    cacheExpiry = Instant.now().plusMillis(CACHE_TTL_MS);
                }
            }
        }
        return metadataMap;
    }

    /**
     * Get practice metadata by Base64 encoded key (clientId:practiceId).
     */
    public static PracticeMetadata getMetadata(String base64Key, Logger logger) {
        if (!isValidKey(base64Key, logger)) return null;
        return getMetadataMap(logger).get(base64Key);
    }

    /**
     * Check if a key is valid (8 characters and exists in metadata).
     */
    public static boolean isValidKey(String base64Key, Logger logger) {
        if (base64Key == null || base64Key.length() < 8 || base64Key.length() > 14) return false;
        return getMetadataMap(logger).containsKey(base64Key);
    }

    /**
     * Check if a key exists in metadata (without length validation).
     */
    public static boolean exists(String base64Key, Logger logger) {
        if (base64Key == null || base64Key.isBlank()) return false;
        return getMetadataMap(logger).containsKey(base64Key);
    }

    private static Map<String, PracticeMetadata> loadFromTable(Logger logger) {
        String storageAccountName = System.getenv("STORAGE_ACCOUNT_NAME");
        String connStr = System.getenv("AzureWebJobsStorage");
        String tableName = System.getenv("PRACTICE_METADATA_TABLE");

        if (tableName == null || tableName.isBlank()) {
            tableName = "PracticeMetadata";
        }

        try {
            TableServiceClient tableServiceClient;
            if (storageAccountName != null && !storageAccountName.isBlank()) {
                String endpoint = "https://" + storageAccountName + ".table.core.windows.net";
                tableServiceClient = new TableServiceClientBuilder()
                        .endpoint(endpoint)
                        .credential(new DefaultAzureCredentialBuilder().build())
                        .buildClient();
            } else if (connStr != null && !connStr.isBlank()) {
                tableServiceClient = new TableServiceClientBuilder()
                        .connectionString(connStr)
                        .buildClient();
            } else {
                logger.warning("PracticeMetadataLoader: no storage credentials configured");
                return Collections.emptyMap();
            }

            TableClient tableClient = tableServiceClient.getTableClient(tableName);
            Map<String, PracticeMetadata> map = new HashMap<>();

            for (TableEntity entity : tableClient.listEntities()) {
                // RowKey is the Base64 encoded "clientId:practiceId"
                String base64Key = entity.getRowKey();
                String clientId = (String) entity.getProperty("client_id");
                String practiceId = (String) entity.getProperty("practice_id");
                String practiceName = (String) entity.getProperty("practice_name");
                String smsFromNumber = (String) entity.getProperty("sms_from_number");
                String logoName = (String) entity.getProperty("logo_name");
                Integer isActiveInt = (Integer) entity.getProperty("is_active");
                boolean isActive = isActiveInt != null && isActiveInt == 1;

                logger.info("PracticeMetadataLoader: rowKey=" + base64Key + ", practiceId=" + practiceId);

                // Only load active records
                if (base64Key != null && !base64Key.isBlank() && isActive) {
                    String trimmedKey = base64Key.trim();
                    PracticeMetadata metadata = new PracticeMetadata(
                            trimmedKey, clientId, practiceId, practiceName,
                            smsFromNumber, logoName, isActive);
                    map.put(trimmedKey, metadata);
                }
            }

            logger.info("PracticeMetadataLoader: loaded " + map.size() + " entries from table " + tableName);
            return Collections.unmodifiableMap(map);

        } catch (Exception e) {
            logger.warning("PracticeMetadataLoader: failed to load from table. Error: " + e.getMessage());
            return Collections.emptyMap();
        }
    }

    /**
     * Force refresh the cache on next access.
     */
    public static void invalidateCache() {
        synchronized (lock) {
            cacheExpiry = Instant.EPOCH;
        }
    }
}
