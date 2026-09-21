package com.opal.deltav.function;

import com.microsoft.azure.functions.*;
import com.microsoft.azure.functions.annotation.*;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.opal.deltav.streaming.MessagePublisher;
import com.opal.deltav.streaming.MessagePublisherFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Logger;

/**
 * Receives asynchronous writeback_status callbacks from Sikka once the PMS
 * confirms (or rejects) an appointment write-back, and forwards the raw
 * payload to the client's writeback-status queue for
 * writeback_status_worker.py to apply.
 */
public class SikkaWritebackStatusFunction {

    private static final String QUEUE_NAME_SUFFIX = "-sikka-writeback-status-queue";
    private static final Gson gson = new Gson();

    @FunctionName("sikkaWritebackStatus")
    public HttpResponseMessage run(
            @HttpTrigger(
                    name = "req",
                    methods = {HttpMethod.POST},
                    authLevel = AuthorizationLevel.ANONYMOUS,
                    route = "api/sikka/writeback-status"
            ) HttpRequestMessage<Optional<String>> request,
            final ExecutionContext context) {

        Logger logger = context.getLogger();

        String expectedApiKey = System.getenv("SIKKA_CALLBACK_API_KEY");
        if (expectedApiKey == null || expectedApiKey.isBlank()) {
            logger.severe("SIKKA_CALLBACK_API_KEY is not configured");
            return jsonResponse(request, HttpStatus.INTERNAL_SERVER_ERROR,
                    Map.of("error", "Server configuration error"));
        }

        String providedApiKey = getHeaderIgnoreCase(request.getHeaders(), "callback-key");
        if (!constantTimeEquals(providedApiKey, expectedApiKey)) {
            logger.warning("Sikka writeback_status callback rejected: invalid or missing API key");
            return jsonResponse(request, HttpStatus.UNAUTHORIZED, Map.of("error", "Invalid API key"));
        }

        String clientId = System.getenv("SIKKA_CALLBACK_CLIENT_ID");
        if (clientId == null || clientId.isBlank()) {
            logger.severe("SIKKA_CALLBACK_CLIENT_ID is not configured");
            return jsonResponse(request, HttpStatus.INTERNAL_SERVER_ERROR,
                    Map.of("error", "Server configuration error"));
        }

        String body = request.getBody().orElse(null);
        if (body == null || body.isBlank()) {
            logger.warning("Sikka writeback_status callback rejected: empty body");
            return jsonResponse(request, HttpStatus.BAD_REQUEST, Map.of("error", "Request body is required"));
        }

        JsonElement root;
        try {
            // The queue consumer accepts a single item, a bare list, or a
            // paginated {"items": [...]} response - all are valid shapes here.
            root = gson.fromJson(body, JsonElement.class);
        } catch (JsonParseException e) {
            logger.warning("Sikka writeback_status callback rejected: invalid JSON - " + e.getMessage());
            return jsonResponse(request, HttpStatus.BAD_REQUEST, Map.of("error", "Invalid JSON"));
        }

        JsonElement toPublish = dropPendingItems(root);
        if (toPublish == null) {
            logger.info("Sikka writeback_status callback is Pending only, nothing to do");
            return jsonResponse(request, HttpStatus.OK, Map.of("success", true));
        }

        String queueName = clientId + QUEUE_NAME_SUFFIX;
        try {
            MessagePublisher publisher = MessagePublisherFactory.getPublisher();
            publisher.publishRaw(queueName, gson.toJson(toPublish), logger);
            logger.info("Sikka writeback_status callback published to queue '" + queueName + "'");
            return jsonResponse(request, HttpStatus.OK, Map.of("success", true));
        } catch (Exception e) {
            logger.severe("Failed to publish Sikka writeback_status callback: " + e.getClass().getName() + " - " + e.getMessage());
            return jsonResponse(request, HttpStatus.INTERNAL_SERVER_ERROR, Map.of("error", "Internal server error"));
        }
    }

    /**
     * Removes items whose status is "Pending" - nothing to apply for those yet,
     * Sikka will call back again once the PMS resolves them. Returns null when
     * nothing is left to publish.
     */
    private JsonElement dropPendingItems(JsonElement root) {
        if (root.isJsonObject()) {
            JsonObject obj = root.getAsJsonObject();
            if (obj.has("items") && obj.get("items").isJsonArray()) {
                JsonArray filtered = filterPending(obj.getAsJsonArray("items"));
                if (filtered.isEmpty()) return null;
                obj.add("items", filtered);
                return obj;
            }
            return isPending(obj) ? null : obj;
        }
        if (root.isJsonArray()) {
            JsonArray filtered = filterPending(root.getAsJsonArray());
            return filtered.isEmpty() ? null : filtered;
        }
        return root;
    }

    private JsonArray filterPending(JsonArray items) {
        JsonArray result = new JsonArray();
        for (JsonElement item : items) {
            if (item.isJsonObject() && isPending(item.getAsJsonObject())) continue;
            result.add(item);
        }
        return result;
    }

    private boolean isPending(JsonObject item) {
        JsonElement status = item.get("status");
        return status != null && status.isJsonPrimitive()
                && "Pending".equalsIgnoreCase(status.getAsString());
    }

    private boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) return false;
        return MessageDigest.isEqual(
                a.getBytes(StandardCharsets.UTF_8),
                b.getBytes(StandardCharsets.UTF_8));
    }

    private String getHeaderIgnoreCase(Map<String, String> headers, String name) {
        if (headers == null || name == null) return null;
        for (Map.Entry<String, String> e : headers.entrySet()) {
            if (e.getKey() != null && e.getKey().equalsIgnoreCase(name)) return e.getValue();
        }
        return null;
    }

    private HttpResponseMessage jsonResponse(HttpRequestMessage<?> request, HttpStatus status, Map<String, ?> body) {
        return request.createResponseBuilder(status)
                .body(gson.toJson(body))
                .header("Content-Type", "application/json")
                .build();
    }
}
