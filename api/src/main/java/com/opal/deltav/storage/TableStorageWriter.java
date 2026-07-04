package com.opal.deltav.storage;

import com.azure.data.tables.TableClient;
import com.azure.data.tables.TableServiceClient;
import com.azure.data.tables.TableServiceClientBuilder;
import com.azure.data.tables.models.TableEntity;
import com.azure.identity.DefaultAzureCredentialBuilder;
import com.opal.deltav.model.RegistrationData;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.logging.Logger;

public class TableStorageWriter implements StorageWriter {

    private static final String TABLE_NAME = "PatientRegistrations";
    private static volatile TableClient tableClient;
    private static final Object lock = new Object();

    @Override
    public String write(RegistrationData data, Logger logger) throws StorageException {
        try {
            TableClient client = getTableClient(logger);

            String monthYear = LocalDate.now().format(DateTimeFormatter.ofPattern("MM-yyyy"));
            String partitionKey = data.getPracticeId() + "_" + monthYear;
            String rowKey = data.getId();

            logger.info("Storing registration: partitionKey=" + partitionKey + ", rowKey=" + rowKey);

            TableEntity entity = new TableEntity(partitionKey, rowKey)
                    .addProperty("practice", data.getPracticeId())
                    .addProperty("registrant", data.getRegistrant())
                    .addProperty("patientType", data.getPatientType())
                    .addProperty("firstName", data.getFirstName())
                    .addProperty("lastName", data.getLastName())
                    .addProperty("dob", data.getDob())
                    .addProperty("confirmAccurate", data.isConfirmAccurate())
                    .addProperty("agreePrivacy", data.isAgreePrivacy())
                    .addProperty("redirectUrl", data.getRedirectUrl())
                    .addProperty("relationship", data.getRelationship())
                    .addProperty("relationshipOther", data.getRelationshipOther())
                    .addProperty("submittedAt", data.getSubmittedAt().toString());

            client.createEntity(entity);
            logger.info("Registration stored successfully in Table Storage: " + partitionKey + "/" + rowKey);

            return rowKey;
        } catch (Exception e) {
            throw new StorageException("Failed to write to Table Storage: " + e.getMessage(), e);
        }
    }

    @Override
    public StorageType getType() {
        return StorageType.TABLE_STORAGE;
    }

    private TableClient getTableClient(Logger logger) {
        if (tableClient == null) {
            synchronized (lock) {
                if (tableClient == null) {
                    String storageAccountName = System.getenv("STORAGE_ACCOUNT_NAME");
                    String connStr = System.getenv("AzureWebJobsStorage");

                    TableServiceClient serviceClient;
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
                        throw new StorageException("Neither STORAGE_ACCOUNT_NAME nor AzureWebJobsStorage is configured");
                    }

                    serviceClient.createTableIfNotExists(TABLE_NAME);
                    tableClient = serviceClient.getTableClient(TABLE_NAME);
                    logger.info("Table Storage client initialized successfully");
                }
            }
        }
        return tableClient;
    }
}