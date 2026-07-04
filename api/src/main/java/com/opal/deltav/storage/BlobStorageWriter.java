package com.opal.deltav.storage;

import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobServiceClient;
import com.azure.storage.blob.BlobServiceClientBuilder;
import com.azure.identity.DefaultAzureCredentialBuilder;
import com.google.gson.Gson;
import com.opal.deltav.model.RegistrationData;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.logging.Logger;

public class BlobStorageWriter implements StorageWriter {

    private static final String CONTAINER_NAME = "patient-registrations";
    private static final Gson gson = new Gson();
    private static volatile BlobContainerClient containerClient;
    private static final Object lock = new Object();

    @Override
    public String write(RegistrationData data, Logger logger) throws StorageException {
        try {
            BlobContainerClient client = getContainerClient(logger);

            String monthYear = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy/MM"));
            String blobName = monthYear + "/" + data.getPracticeId() + "/" + data.getId() + ".json";

            logger.info("Storing registration to blob: " + blobName);

            String jsonContent = gson.toJson(data);
            byte[] bytes = jsonContent.getBytes(StandardCharsets.UTF_8);

            BlobClient blobClient = client.getBlobClient(blobName);
            blobClient.upload(new ByteArrayInputStream(bytes), bytes.length, true);

            logger.info("Registration stored successfully in Blob Storage: " + blobName);

            return data.getId();
        } catch (Exception e) {
            throw new StorageException("Failed to write to Blob Storage: " + e.getMessage(), e);
        }
    }

    @Override
    public StorageType getType() {
        return StorageType.BLOB_STORAGE;
    }

    private BlobContainerClient getContainerClient(Logger logger) {
        if (containerClient == null) {
            synchronized (lock) {
                if (containerClient == null) {
                    String storageAccountName = System.getenv("STORAGE_ACCOUNT_NAME");
                    String connStr = System.getenv("AzureWebJobsStorage");

                    BlobServiceClient serviceClient;
                    if (storageAccountName != null && !storageAccountName.isBlank()) {
                        logger.info("Initializing Blob Storage client with managed identity");
                        String endpoint = "https://" + storageAccountName + ".blob.core.windows.net";
                        serviceClient = new BlobServiceClientBuilder()
                                .endpoint(endpoint)
                                .credential(new DefaultAzureCredentialBuilder().build())
                                .buildClient();
                    } else if (connStr != null && !connStr.isBlank()) {
                        logger.info("Initializing Blob Storage client with connection string");
                        serviceClient = new BlobServiceClientBuilder()
                                .connectionString(connStr)
                                .buildClient();
                    } else {
                        throw new StorageException("Neither STORAGE_ACCOUNT_NAME nor AzureWebJobsStorage is configured");
                    }

                    containerClient = serviceClient.getBlobContainerClient(CONTAINER_NAME);
                    if (!containerClient.exists()) {
                        containerClient.create();
                        logger.info("Created blob container: " + CONTAINER_NAME);
                    }
                    logger.info("Blob Storage client initialized successfully");
                }
            }
        }
        return containerClient;
    }
}