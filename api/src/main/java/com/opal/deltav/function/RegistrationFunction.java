package com.opal.deltav.function;

import com.microsoft.azure.functions.*;
import com.microsoft.azure.functions.annotation.*;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.opal.deltav.config.PracticeConfig;
import com.opal.deltav.model.QueueMessage;
import com.opal.deltav.streaming.MessagePublisher;
import com.opal.deltav.streaming.MessagePublisherFactory;
import com.opal.deltav.util.CookieUtil;
import com.opal.deltav.util.TokenUtil;

import java.util.*;
import java.util.logging.Logger;

public class RegistrationFunction {

    private static final Gson gson = new Gson();

    @FunctionName("register")
    public HttpResponseMessage run(
            @HttpTrigger(
                    name = "req",
                    methods = {HttpMethod.POST, HttpMethod.OPTIONS},
                    authLevel = AuthorizationLevel.ANONYMOUS,
                    route = "api/register"
            ) HttpRequestMessage<Optional<String>> request,
            final ExecutionContext context) {

        Logger logger = context.getLogger();
        logger.info("Registration request received");

        if (request.getHttpMethod() == HttpMethod.OPTIONS) {
            return corsResponse(request, HttpStatus.NO_CONTENT, null);
        }

        String body = request.getBody().orElse(null);
        if (body == null || body.isBlank()) {
            logger.warning("Request rejected: empty or missing body");
            return jsonResponse(request, HttpStatus.BAD_REQUEST,
                    Map.of("error", "Request body is required"));
        }

        JsonObject json;
        try {
            json = gson.fromJson(body, JsonObject.class);
        } catch (Exception e) {
            logger.warning("Request rejected: invalid JSON - " + e.getMessage());
            return jsonResponse(request, HttpStatus.BAD_REQUEST,
                    Map.of("error", "Invalid JSON"));
        }

        // Validate CSRF token (uses the same secret as StaticFileFunction)
        String secret = System.getenv("REGISTRATION_TOKEN_SECRET");
        if (secret == null || secret.isBlank()) {
            logger.severe("REGISTRATION_TOKEN_SECRET is not configured");
            return jsonResponse(request, HttpStatus.INTERNAL_SERVER_ERROR,
                    Map.of("error", "Server configuration error"));
        }

        String headerToken = getHeaderIgnoreCase(request.getHeaders(), "X-Registration-Token");
        String bodyToken = getStr(json, "registration_token");
        String csrfToken = (headerToken != null && !headerToken.isBlank()) ? headerToken : bodyToken;

        TokenUtil.ValidationResult tokenResult = TokenUtil.validate(csrfToken, secret);
        if (!tokenResult.valid) {
            logger.warning("Registration rejected due to token error: " + tokenResult.error);
            return jsonResponse(request, HttpStatus.FORBIDDEN,
                    Map.of("error", "Invalid or expired form. Please refresh the page and try again."));
        }

        // Validate request fields
        List<String> errors = validate(json);
        if (!errors.isEmpty()) {
            logger.warning("Validation failed: " + String.join(", ", errors));
            return jsonResponse(request, HttpStatus.BAD_REQUEST,
                    Map.of("errors", errors));
        }

        try {
            // Get client ID and practice ID from signed DELTAV_CONTEXT cookie
            String clientId = CookieUtil.getClientIdFromContext(request.getHeaders());
            Long practiceId = CookieUtil.getPracticeIdFromContext(request.getHeaders());

            // Validate cookie - return FORBIDDEN if missing or tampered
            if (clientId == null || practiceId == null) {
                logger.warning("Registration rejected: invalid or missing context cookie");
                return jsonResponse(request, HttpStatus.FORBIDDEN, Map.of("error", "Invalid session context"));
            }

            // Get patient info from request
            String patientFirstName = getStr(json, "first_name");
            String patientLastName = getStr(json, "last_name");
            String dateOfBirth = getStr(json, "dob");
            String mobileNumber = getStr(json, "mobile_number");
            String patientMiddleName = getStr(json, "middle_name");

            // Get preferred slots and comments from request
            List<String> preferredSlots = getStringList(json, "preferred_slots");
            String comments = getStr(json, "comments");

            // Build queue message with patient info
            QueueMessage queueMessage = QueueMessage.builder()
                    .practiceId(practiceId)
                    .patientFirstName(patientFirstName)
                    .patientMiddleName(patientMiddleName)
                    .patientLastName(patientLastName)
                    .dateOfBirth(dateOfBirth)
                    .mobileNumber(mobileNumber)
                    .preferredSlots(preferredSlots)
                    .comments(comments)
                    .build();

            // Publish to queue
            MessagePublisher publisher = MessagePublisherFactory.getPublisher();
            logger.info("Publishing to queue: " + publisher.getType());
            publisher.publish(queueMessage, clientId != null ? clientId : "", logger);

            String redirectUrl = practiceId != null ? PracticeConfig.getRedirectUrl(String.valueOf(practiceId)) : null;
            logger.info("Practice=" + practiceId + ", resolved redirect_url=" + redirectUrl);

            return jsonResponse(request, HttpStatus.CREATED,
                    Map.of("success", true, "redirect_url", Objects.toString(redirectUrl, "")));

        } catch (Exception e) {
            logger.severe("Error publishing to queue: " + e.getClass().getName() + " - " + e.getMessage());
            return jsonResponse(request, HttpStatus.INTERNAL_SERVER_ERROR,
                    Map.of("error", "Internal server error"));
        }
    }

    private List<String> validate(JsonObject json) {
        List<String> errors = new ArrayList<>();

        String firstName = getStr(json, "first_name");
        if (firstName == null || firstName.isBlank()) {
            errors.add("first name is required");
        }

        String lastName = getStr(json, "last_name");
        if (lastName == null || lastName.isBlank()) {
            errors.add("last name is required");
        }

        String dob = getStr(json, "dob");
        if (dob == null || dob.isBlank()) {
            errors.add("dob is required");
        }

        String mobile = getStr(json, "mobile_number");
        if (mobile == null || mobile.isBlank()) {
            errors.add("Mobile number is required");
        }

        if (!getBool(json, "agree_privacy")) {
            errors.add("Must agree to privacy policy");
        }

        return errors;
    }

    private String getStr(JsonObject j, String field) {
        return j.has(field) && !j.get(field).isJsonNull() ? j.get(field).getAsString() : null;
    }

    private boolean getBool(JsonObject j, String field) {
        return j.has(field) && !j.get(field).isJsonNull() && j.get(field).getAsBoolean();
    }

    private List<String> getStringList(JsonObject j, String field) {
        if (!j.has(field) || j.get(field).isJsonNull()) {
            return new ArrayList<>();
        }
        try {
            JsonArray array = j.getAsJsonArray(field);
            List<String> result = new ArrayList<>();
            for (int i = 0; i < array.size(); i++) {
                if (!array.get(i).isJsonNull()) {
                    result.add(array.get(i).getAsString());
                }
            }
            return result;
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    private HttpResponseMessage jsonResponse(HttpRequestMessage<?> request, HttpStatus status, Map<String, ?> body) {
        HttpResponseMessage.Builder builder = request.createResponseBuilder(status)
                .body(gson.toJson(body))
                .header("Content-Type", "application/json");
        addCorsHeaders(builder, request);
        return builder.build();
    }

    private HttpResponseMessage corsResponse(HttpRequestMessage<?> request, HttpStatus status, Map<String, ?> body) {
        HttpResponseMessage.Builder builder = request.createResponseBuilder(status);
        if (body != null) {
            builder.body(gson.toJson(body))
                   .header("Content-Type", "application/json");
        }
        addCorsHeaders(builder, request);
        return builder.build();
    }

    private void addCorsHeaders(HttpResponseMessage.Builder builder, HttpRequestMessage<?> request) {
        String origin = request.getHeaders() != null ? request.getHeaders().get("Origin") : null;
        if (origin == null || origin.isBlank()) origin = "*";
        builder.header("Access-Control-Allow-Origin", origin)
               .header("Vary", "Origin")
               .header("Access-Control-Allow-Methods", "POST, OPTIONS")
               .header("Access-Control-Allow-Headers", "Content-Type, X-Registration-Token")
               .header("Access-Control-Max-Age", "3600");
    }

    private String getHeaderIgnoreCase(Map<String, String> headers, String name) {
        if (headers == null || name == null) return null;
        for (Map.Entry<String, String> e : headers.entrySet()) {
            if (e.getKey() != null && e.getKey().equalsIgnoreCase(name)) return e.getValue();
        }
        return null;
    }
}