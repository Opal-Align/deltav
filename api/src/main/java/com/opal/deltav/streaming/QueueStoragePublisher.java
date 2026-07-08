package com.opal.deltav.streaming;

import com.azure.identity.DefaultAzureCredentialBuilder;
import com.azure.storage.queue.QueueClient;
import com.azure.storage.queue.QueueServiceClient;
import com.azure.storage.queue.QueueServiceClientBuilder;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.opal.deltav.model.QueueMessage;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.nio.charset.StandardCharsets;
import java.util.logging.Logger;

public class QueueStoragePublisher implements MessagePublisher {

    private static final String QUEUE_NAME_SUFFIX = "-schedule-queue";
    private static final Gson gson = new GsonBuilder()
            .registerTypeAdapter(OffsetDateTime.class, (com.google.gson.JsonSerializer<OffsetDateTime>)
                    (src, typeOfSrc, context) -> new com.google.gson.JsonPrimitive(src.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)))
            .create();
    private static volatile QueueServiceClient serviceClient;
    private static final Map<String, QueueClient> queueClients = new ConcurrentHashMap<>();
    private static final Object lock = new Object();

    @Override
    public void publish(QueueMessage message, String clientId, Logger logger) throws StreamingException {
        if (clientId == null || clientId.isBlank()) {
            throw new StreamingException("Client ID is required for queue routing");
        }

        String queueName = clientId + QUEUE_NAME_SUFFIX;
        String jsonMessage = gson.toJson(message);
        String encodedMessage = Base64.getEncoder().encodeToString(
                jsonMessage.getBytes(StandardCharsets.UTF_8));

        // Retry once if connection is stale
        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                QueueClient client = getQueueClient(queueName, logger);

                logger.info("Publishing registration to queue '" + queueName + "': patientKey=" + message.getPatientKey());
                client.sendMessage(encodedMessage);
                logger.info("Registration published successfully to queue '" + queueName + "': patientKey=" + message.getPatientKey());
                return;

            } catch (Exception e) {
                if (attempt == 1) {
                    logger.warning("Failed to publish, refreshing client for queue '" + queueName + "': " + e.getMessage());
                    queueClients.remove(queueName);
                } else {
                    throw new StreamingException("Failed to publish to Queue Storage: " + e.getMessage(), e);
                }
            }
        }
    }

    @Override
    public StreamingType getType() {
        return StreamingType.QUEUE_STORAGE;
    }

    private QueueClient getQueueClient(String queueName, Logger logger) {
        return queueClients.computeIfAbsent(queueName, name -> {
            QueueServiceClient svc = getServiceClient(logger);
            QueueClient client = svc.getQueueClient(name);
            client.createIfNotExists();
            logger.info("Queue client initialized for queue: " + name);
            return client;
        });
    }

    private QueueServiceClient getServiceClient(Logger logger) {
        if (serviceClient == null) {
            synchronized (lock) {
                if (serviceClient == null) {
                    String storageAccountName = System.getenv("STORAGE_ACCOUNT_NAME");
                    String connStr = System.getenv("AzureWebJobsStorage");

                    if (storageAccountName != null && !storageAccountName.isBlank()) {
                        logger.info("Initializing Queue Storage service client with managed identity");
                        String endpoint = "https://" + storageAccountName + ".queue.core.windows.net";
                        serviceClient = new QueueServiceClientBuilder()
                                .endpoint(endpoint)
                                .credential(new DefaultAzureCredentialBuilder().build())
                                .buildClient();
                    } else if (connStr != null && !connStr.isBlank()) {
                        logger.info("Initializing Queue Storage service client with connection string");
                        serviceClient = new QueueServiceClientBuilder()
                                .connectionString(connStr)
                                .buildClient();
                    } else {
                        throw new StreamingException("Neither STORAGE_ACCOUNT_NAME nor AzureWebJobsStorage is configured");
                    }
                }
            }
        }
        return serviceClient;
    }
}