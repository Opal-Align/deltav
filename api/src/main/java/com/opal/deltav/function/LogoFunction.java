package com.opal.deltav.function;

import com.azure.identity.DefaultAzureCredentialBuilder;
import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobServiceClient;
import com.azure.storage.blob.BlobServiceClientBuilder;
import com.microsoft.azure.functions.*;
import com.microsoft.azure.functions.annotation.*;
import com.opal.deltav.config.PracticeMetadataLoader;
import com.opal.deltav.model.PracticeMetadata;
import com.opal.deltav.util.CookieUtil;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URLConnection;
import java.util.Optional;
import java.util.logging.Logger;

/**
 * Serves practice logo images from Azure Blob Storage.
 * URL format: /api/logo/{id}.png
 * The actual logo filename is fetched from practice metadata using the id.
 */
public class LogoFunction {

    private static volatile BlobContainerClient containerClient;
    private static final Object lock = new Object();

    private static final String DEFAULT_CONTAINER = "logos";

    @FunctionName("logo")
    public HttpResponseMessage getLogo(
            @HttpTrigger(
                    name = "req",
                    methods = {HttpMethod.GET, HttpMethod.HEAD},
                    authLevel = AuthorizationLevel.ANONYMOUS,
                    route = "api/logo/{*logoPath}"
            ) HttpRequestMessage<Optional<String>> request,
            @BindingName("logoPath") String logoPath,
            final ExecutionContext context) {

        Logger logger = context.getLogger();

        if (logoPath == null || logoPath.isBlank()) {
            return addCors(request, request.createResponseBuilder(HttpStatus.BAD_REQUEST)
                    .body("Logo path is required")).build();
        }

        // Extract id from path (e.g., "ABC12345.png" -> "ABC12345")
        String id = logoPath;
        if (logoPath.contains(".")) {
            id = logoPath.substring(0, logoPath.lastIndexOf('.'));
        }

        // Verify cookie and get context key
        String contextKey = CookieUtil.getContextKey(request.getHeaders(), logger);
        if (contextKey == null) {
            logger.warning("Logo request rejected: invalid or missing context cookie");
            return addCors(request, request.createResponseBuilder(HttpStatus.FORBIDDEN)
                    .body("Invalid session")).build();
        }

        // Verify the requested id matches the context (security check)
        if (!id.equals(contextKey)) {
            logger.warning("Logo request rejected: id mismatch. Requested: " + id + ", Context: " + contextKey);
            return addCors(request, request.createResponseBuilder(HttpStatus.FORBIDDEN)
                    .body("Access denied")).build();
        }

        // Get practice metadata to find actual logo filename
        PracticeMetadata metadata = PracticeMetadataLoader.getMetadata(id, logger);
        if (metadata == null) {
            logger.warning("Logo request rejected: practice not found for id: " + id);
            return addCors(request, request.createResponseBuilder(HttpStatus.NOT_FOUND)
                    .body("Logo not found")).build();
        }

        String actualLogoName = metadata.getLogoName();
        if (actualLogoName == null || actualLogoName.isBlank()) {
            logger.info("No logo configured for practice: " + id);
            return addCors(request, request.createResponseBuilder(HttpStatus.NOT_FOUND)
                    .body("Logo not found")).build();
        }

        logger.info("Fetching logo: " + actualLogoName + " for practice id: " + id);

        try {
            BlobContainerClient container = getContainerClient(logger);
            if (container == null) {
                logger.severe("Blob storage not configured");
                return addCors(request, request.createResponseBuilder(HttpStatus.INTERNAL_SERVER_ERROR)
                        .body("Storage not configured")).build();
            }

            BlobClient blobClient = container.getBlobClient(actualLogoName);
            if (!blobClient.exists()) {
                logger.warning("Logo not found in blob: " + actualLogoName);
                return addCors(request, request.createResponseBuilder(HttpStatus.NOT_FOUND)
                        .body("Logo not found")).build();
            }

            // Get content type from blob properties or guess from filename
            String contentType = blobClient.getProperties().getContentType();
            if (contentType == null || contentType.isBlank() || "application/octet-stream".equals(contentType)) {
                contentType = URLConnection.guessContentTypeFromName(actualLogoName);
                if (contentType == null) {
                    contentType = "image/png"; // default for logos
                }
            }

            HttpResponseMessage.Builder builder = request.createResponseBuilder(HttpStatus.OK)
                    .header("Content-Type", contentType)
                    .header("Cache-Control", "public, max-age=86400"); // Cache for 24 hours

            addCors(request, builder);

            if (request.getHttpMethod() == HttpMethod.HEAD) {
                return builder.build();
            }

            // Read blob content
            try (InputStream is = blobClient.openInputStream();
                 ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                int bytesRead;
                while ((bytesRead = is.read(buffer)) != -1) {
                    baos.write(buffer, 0, bytesRead);
                }
                return builder.body(baos.toByteArray()).build();
            }

        } catch (Exception e) {
            logger.severe("Error fetching logo '" + actualLogoName + "': " + e.getMessage());
            return addCors(request, request.createResponseBuilder(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Error fetching logo")).build();
        }
    }

    private static BlobContainerClient getContainerClient(Logger logger) {
        if (containerClient != null) {
            return containerClient;
        }

        synchronized (lock) {
            if (containerClient != null) {
                return containerClient;
            }

            String storageAccountName = System.getenv("STORAGE_ACCOUNT_NAME");
            String connStr = System.getenv("AzureWebJobsStorage");
            String container = System.getenv("LOGO_CONTAINER_NAME");

            if (container == null || container.isBlank()) {
                container = DEFAULT_CONTAINER;
            }

            try {
                BlobServiceClient blobServiceClient;
                if (storageAccountName != null && !storageAccountName.isBlank()) {
                    String endpoint = "https://" + storageAccountName + ".blob.core.windows.net";
                    blobServiceClient = new BlobServiceClientBuilder()
                            .endpoint(endpoint)
                            .credential(new DefaultAzureCredentialBuilder().build())
                            .buildClient();
                    logger.info("LogoFunction: using managed identity for storage account: " + storageAccountName);
                } else if (connStr != null && !connStr.isBlank()) {
                    blobServiceClient = new BlobServiceClientBuilder()
                            .connectionString(connStr)
                            .buildClient();
                    logger.info("LogoFunction: using connection string for storage");
                } else {
                    logger.warning("LogoFunction: no storage credentials configured");
                    return null;
                }

                containerClient = blobServiceClient.getBlobContainerClient(container);
                logger.info("LogoFunction: initialized container client for: " + container);
                return containerClient;
            } catch (Exception e) {
                logger.severe("LogoFunction: failed to initialize blob client: " + e.getMessage());
                return null;
            }
        }
    }

    private static HttpResponseMessage.Builder addCors(HttpRequestMessage<?> req, HttpResponseMessage.Builder builder) {
        String origin = req.getHeaders() != null ? req.getHeaders().get("Origin") : null;
        if (origin == null || origin.isBlank()) origin = "*";
        builder.header("Access-Control-Allow-Origin", origin)
                .header("Vary", "Origin")
                .header("Access-Control-Allow-Methods", "GET, HEAD, OPTIONS")
                .header("Access-Control-Allow-Headers", "Content-Type");
        return builder;
    }
}