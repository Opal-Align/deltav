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
 * payload to the owning client's writeback-status queue for
 * writeback_status_worker.py to apply. The client is resolved from the
 * Sikka office_id in the payload via OFFICE_ID_TO_CLIENT_ID.
 */
public class SikkaWritebackStatusFunction {

    private static final String QUEUE_NAME_SUFFIX = "-sikka-writeback-status-queue";
    private static final Gson gson = new Gson();

    // Sikka office_id -> client_id
    private static final Map<String, String> OFFICE_ID_TO_CLIENT_ID = Map.ofEntries(
            Map.entry("D14699", "101"),
            Map.entry("D42331", "101"),
            Map.entry("D42333", "101"),
            Map.entry("D21563", "101"),
            Map.entry("D45500", "101"),
            Map.entry("D21469", "101"),
            Map.entry("D54423", "101"),
            Map.entry("D42301", "101"),
            Map.entry("D41491", "101"),
            Map.entry("D42058", "101"),
            Map.entry("D42332", "101"),
            Map.entry("D44443", "101"),
            Map.entry("D52011", "100")
    );

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
        //openssl rand -hex 32
        String expectedApiKey = System.getenv("SIKKA_CALLBACK_API_KEY");
        if (expectedApiKey == null || expectedApiKey.isBlank()) {
            expectedApiKey = "2417985fbcbd867f904d1601335e087d0ae400f26fdd7f49783a1d53ff9d5d7d";
        }

        String providedApiKey = getHeaderIgnoreCase(request.getHeaders(), "callback-key");
        if (!constantTimeEquals(providedApiKey, expectedApiKey)) {
            logger.warning("Sikka writeback_status callback rejected: invalid or missing API key");
            return jsonResponse(request, HttpStatus.UNAUTHORIZED, Map.of("error", "Invalid API key"));
        }

        String body = request.getBody().orElse(null);
        if (body == null || body.isBlank()) {
            logger.warning("Sikka writeback_status callback rejected: empty body");
            return jsonResponse(request, HttpStatus.BAD_REQUEST, Map.of("error", "Request body is required"));
        }

        JsonArray items;
        try {
            JsonElement root = gson.fromJson(body, JsonElement.class);
            if (root == null || !root.isJsonObject() || !root.getAsJsonObject().has("items")
                    || !root.getAsJsonObject().get("items").isJsonArray()) {
                logger.warning("Sikka writeback_status callback rejected: body has no \"items\" array");
                return jsonResponse(request, HttpStatus.BAD_REQUEST, Map.of("error", "Expected an \"items\" array"));
            }
            items = root.getAsJsonObject().getAsJsonArray("items");
        } catch (JsonParseException e) {
            logger.warning("Sikka writeback_status callback rejected: invalid JSON - " + e.getMessage());
            return jsonResponse(request, HttpStatus.BAD_REQUEST, Map.of("error", "Invalid JSON"));
        }

        MessagePublisher publisher = MessagePublisherFactory.getPublisher();
        int published = 0;
        int skipped = 0;
        boolean anyFailure = false;

        for (JsonElement element : items) {
            if (!element.isJsonObject()) {
                logger.warning("Sikka writeback_status callback item skipped: not a JSON object");
                skipped++;
                continue;
            }
            JsonObject item = element.getAsJsonObject();

            String officeId = getStr(item, "office_id");
            if (officeId == null) officeId = getStr(item, "officeId");
            if (officeId == null || officeId.isBlank()) {
                logger.warning("Sikka writeback_status callback item skipped: missing office_id - " + item);
                skipped++;
                continue;
            }

            String clientId = OFFICE_ID_TO_CLIENT_ID.get(officeId);
            if (clientId == null) {
                logger.severe("Sikka writeback_status callback item skipped: unknown office_id " + officeId);
                skipped++;
                continue;
            }

            if (isPending(item)) {
                logger.info("Sikka writeback_status callback item for office_id " + officeId + " is Pending, nothing to do");
                skipped++;
                continue;
            }

            String queueName = clientId + QUEUE_NAME_SUFFIX;
            try {
                publisher.publishRaw(queueName, gson.toJson(item), logger);
                logger.info("Sikka writeback_status item (office_id=" + officeId + ") published to queue '" + queueName + "'");
                published++;
            } catch (Exception e) {
                anyFailure = true;
                logger.severe("Failed to publish Sikka writeback_status item (office_id=" + officeId + "): "
                        + e.getClass().getName() + " - " + e.getMessage());
            }
        }

        logger.info("Sikka writeback_status callback processed: " + published + " published, " + skipped + " skipped");

        if (anyFailure) {
            return jsonResponse(request, HttpStatus.INTERNAL_SERVER_ERROR, Map.of("error", "Internal server error"));
        }
        return jsonResponse(request, HttpStatus.OK, Map.of("success", true, "published", published, "skipped", skipped));
    }

    /**
     * Nothing to apply yet for a Pending status - Sikka will call back again
     * once the PMS resolves it.
     */
    private boolean isPending(JsonObject item) {
        JsonElement status = item.get("status");
        return status != null && status.isJsonPrimitive()
                && "Pending".equalsIgnoreCase(status.getAsString());
    }

    private String getStr(JsonObject j, String field) {
        return j.has(field) && !j.get(field).isJsonNull() ? j.get(field).getAsString() : null;
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
