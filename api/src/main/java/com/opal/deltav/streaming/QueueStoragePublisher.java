package com.opal.deltav.streaming;

import com.azure.identity.DefaultAzureCredentialBuilder;
import com.azure.storage.queue.QueueClient;
import com.azure.storage.queue.QueueServiceClient;
import com.azure.storage.queue.QueueServiceClientBuilder;
import com.google.gson.Gson;
import com.opal.deltav.model.RegistrationData;

import java.util.Base64;
import java.nio.charset.StandardCharsets;
import java.util.logging.Logger;

public class QueueStoragePublisher implements MessagePublisher {

    private static final String QUEUE_NAME = "patient-registrations";
    private static final Gson gson = new Gson();
    private static volatile QueueClient queueClient;
    private static final Object lock = new Object();

    @Override
    public void publish(RegistrationData data, Logger logger) throws StreamingException {
        try {
            QueueClient client = getQueueClient(logger);

            String jsonMessage = gson.toJson(data);
            // Azure Queue Storage requires Base64 encoding for the message
            String encodedMessage = Base64.getEncoder().encodeToString(
                    jsonMessage.getBytes(StandardCharsets.UTF_8));

            logger.info("Publishing registration to Queue Storage: id=" + data.getId());

            client.sendMessage(encodedMessage);

            logger.info("Registration published successfully to Queue Storage: " + data.getId());
        } catch (Exception e) {
            throw new StreamingException("Failed to publish to Queue Storage: " + e.getMessage(), e);
        }
    }

    @Override
    public StreamingType getType() {
        return StreamingType.QUEUE_STORAGE;
    }

    private QueueClient getQueueClient(Logger logger) {
        if (queueClient == null) {
            synchronized (lock) {
                if (queueClient == null) {
                    String storageAccountName = System.getenv("STORAGE_ACCOUNT_NAME");
                    String connStr = System.getenv("AzureWebJobsStorage");
                    String queueName = System.getenv("STREAMING_QUEUE_NAME");
                    if (queueName == null || queueName.isBlank()) {
                        queueName = QUEUE_NAME;
                    }

                    QueueServiceClient serviceClient;
                    if (storageAccountName != null && !storageAccountName.isBlank()) {
                        logger.info("Initializing Queue Storage client with managed identity");
                        String endpoint = "https://" + storageAccountName + ".queue.core.windows.net";
                        serviceClient = new QueueServiceClientBuilder()
                                .endpoint(endpoint)
                                .credential(new DefaultAzureCredentialBuilder().build())
                                .buildClient();
                    } else if (connStr != null && !connStr.isBlank()) {
                        logger.info("Initializing Queue Storage client with connection string");
                        serviceClient = new QueueServiceClientBuilder()
                                .connectionString(connStr)
                                .buildClient();
                    } else {
                        throw new StreamingException("Neither STORAGE_ACCOUNT_NAME nor AzureWebJobsStorage is configured");
                    }

                    queueClient = serviceClient.getQueueClient(queueName);
                    queueClient.createIfNotExists();
                    logger.info("Queue Storage client initialized successfully for queue: " + queueName);
                }
            }
        }
        return queueClient;
    }
}