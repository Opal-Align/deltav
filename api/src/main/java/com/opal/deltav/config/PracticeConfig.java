package com.opal.deltav.config;

import com.azure.identity.DefaultAzureCredentialBuilder;
import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobServiceClient;
import com.azure.storage.blob.BlobServiceClientBuilder;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Collections;
import java.util.Map;
import java.util.logging.Logger;

public class PracticeConfig {

    private static final long CACHE_TTL_MS = 10 * 60 * 1000L; // 10 minutes
    private static final Logger logger = Logger.getLogger(PracticeConfig.class.getName());

    private static volatile Map<String, String> redirectMap;
    private static volatile Instant cacheExpiry = Instant.EPOCH;
    private static final Object lock = new Object();

    private PracticeConfig() {}

    public static Map<String, String> getRedirectMap() {
        if (redirectMap == null || Instant.now().isAfter(cacheExpiry)) {
            synchronized (lock) {
                if (redirectMap == null || Instant.now().isAfter(cacheExpiry)) {
                    Map<String, String> loaded = loadFromBlob();
                    if (loaded == null) {
                        loaded = loadFromClasspath();
                    }
                    redirectMap = loaded;
                    cacheExpiry = Instant.now().plusMillis(CACHE_TTL_MS);
                }
            }
        }
        return redirectMap;
    }

    public static String getRedirectUrl(String practiceId) {
        if (practiceId == null || practiceId.isBlank()) return null;
        return getRedirectMap().get(practiceId.trim());
    }

    private static Map<String, String> loadFromBlob() {
        String storageAccountName = System.getenv("STORAGE_ACCOUNT_NAME");
        String connStr = System.getenv("AzureWebJobsStorage");
        String container = System.getenv("PRACTICE_CONFIG_CONTAINER");
        String blobName = System.getenv("PRACTICE_CONFIG_BLOB");

        if (container == null || container.isBlank()) container = "practice-redirect";
        if (blobName == null || blobName.isBlank()) blobName = "practice-redirects.json";

        try {
            BlobServiceClient blobServiceClient;
            if (storageAccountName != null && !storageAccountName.isBlank()) {
                String endpoint = "https://" + storageAccountName + ".blob.core.windows.net";
                blobServiceClient = new BlobServiceClientBuilder()
                        .endpoint(endpoint)
                        .credential(new DefaultAzureCredentialBuilder().build())
                        .buildClient();
            } else if (connStr != null && !connStr.isBlank()) {
                blobServiceClient = new BlobServiceClientBuilder()
                        .connectionString(connStr)
                        .buildClient();
            } else {
                logger.warning("PracticeConfig: no storage credentials configured, skipping blob load");
                return null;
            }

            BlobContainerClient containerClient = blobServiceClient.getBlobContainerClient(container);
            BlobClient blobClient = containerClient.getBlobClient(blobName);

            try (InputStream is = blobClient.openInputStream()) {
                Type type = new TypeToken<Map<String, String>>() {}.getType();
                Map<String, String> map = new Gson().fromJson(
                        new InputStreamReader(is, StandardCharsets.UTF_8), type);
                logger.info("PracticeConfig: loaded " + map.size() + " entries from blob " + container + "/" + blobName);
                return Collections.unmodifiableMap(map);
            }
        } catch (Exception e) {
            logger.warning("PracticeConfig: failed to load from blob, will fall back to classpath. Error: " + e.getMessage());
            return null;
        }
    }

    private static Map<String, String> loadFromClasspath() {
        try (InputStream is = PracticeConfig.class.getClassLoader()
                .getResourceAsStream("practice-redirects.json")) {
            if (is == null) return Collections.emptyMap();
            Type type = new TypeToken<Map<String, String>>() {}.getType();
            return Collections.unmodifiableMap(
                    new Gson().fromJson(new InputStreamReader(is, StandardCharsets.UTF_8), type));
        } catch (Exception e) {
            return Collections.emptyMap();
        }
    }
}