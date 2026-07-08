package com.opal.deltav.function;

import com.microsoft.azure.functions.*;
import com.microsoft.azure.functions.annotation.*;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.opal.deltav.config.PracticeConfig;
import com.opal.deltav.model.RegistrationData;
import com.opal.deltav.schedulelinktoken.ScheduleLinkToken;
import com.opal.deltav.schedulelinktoken.ScheduleLinkTokenProvider;
import com.opal.deltav.schedulelinktoken.ScheduleLinkTokenProviderFactory;
import com.opal.deltav.session.SessionManager;
import com.opal.deltav.streaming.MessagePublisher;
import com.opal.deltav.streaming.MessagePublisherFactory;
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

        String secret = System.getenv("REGISTRATION_TOKEN_SECRET");
        if (secret == null || secret.isBlank()) {
            logger.severe("REGISTRATION_TOKEN_SECRET is not configured");
            return jsonResponse(request, HttpStatus.INTERNAL_SERVER_ERROR,
                    Map.of("error", "Server configuration error"));
        }
        String headerToken = getHeaderIgnoreCase(request.getHeaders(), "X-Registration-Token");
        String bodyToken = json.has("registration_token") && !json.get("registration_token").isJsonNull()
                ? json.get("registration_token").getAsString() : null;
        String token = (headerToken != null && !headerToken.isBlank()) ? headerToken : bodyToken;
        TokenUtil.ValidationResult tokenResult = TokenUtil.validate(token, secret);
        if (!tokenResult.valid) {
            logger.warning("Registration rejected due to token error: " + tokenResult.error);
            return jsonResponse(request, HttpStatus.UNAUTHORIZED,
                    Map.of("error", tokenResult.error));
        }

        // Validate session from cookie
        String sessionId = extractSessionIdFromCookie(request.getHeaders(), logger);
        if (sessionId == null || sessionId.isBlank()) {
            logger.warning("Registration rejected: missing session cookie");
            return jsonResponse(request, HttpStatus.UNAUTHORIZED,
                    Map.of("error", "Session required"));
        }

        if (!SessionManager.getInstance().isValidSession(sessionId, logger)) {
            logger.warning("Registration rejected: invalid or expired session");
            return jsonResponse(request, HttpStatus.UNAUTHORIZED,
                    Map.of("error", "Invalid or expired session"));
        }

        // Get schedule link token from session to retrieve client/practice data
        String scheduleLinkToken = SessionManager.getInstance().getTokenForSession(sessionId, logger);
        if (scheduleLinkToken == null || scheduleLinkToken.isBlank()) {
            logger.warning("Registration rejected: no schedule link token found for session");
            return jsonResponse(request, HttpStatus.BAD_REQUEST,
                    Map.of("error", "Invalid session - missing token data"));
        }

        // Validate and get token data from table storage
        ScheduleLinkTokenProvider tokenProvider = ScheduleLinkTokenProviderFactory.getProvider();
        ScheduleLinkTokenProvider.ValidationResult validationResult = tokenProvider.validateToken(scheduleLinkToken, logger);
        if (!validationResult.valid) {
            logger.warning("Registration rejected: schedule link token invalid - " + validationResult.error);
            return jsonResponse(request, HttpStatus.BAD_REQUEST,
                    Map.of("error", "Invalid or expired link"));
        }

        ScheduleLinkToken tokenData = validationResult.token;

        // Validate request
        List<String> errors = validate(json);
        if (!errors.isEmpty()) {
            logger.warning("Validation failed: " + String.join(", ", errors));
            return jsonResponse(request, HttpStatus.BAD_REQUEST,
                    Map.of("errors", errors));
        }

        try {
            // Get data from token (Azure Table)
            String clientId = tokenData.getClientId() != null ? String.valueOf(tokenData.getClientId()) : "";
            String practiceId = tokenData.getPracticeId() != null ? String.valueOf(tokenData.getPracticeId()) : "";
            String mobileNumber = tokenData.getMobileNumber() != null ? tokenData.getMobileNumber() : "";

            // Get preferred slots from request
            List<String> preferredSlots = getStringList(json, "preferred_slots");
            String comments = getStr(json, "comments");

            // Build registration data
            RegistrationData registrationData = RegistrationData.builder()
                    .clientId(clientId)
                    .practiceId(practiceId)
                    .mobileNumber(mobileNumber)
                    .registrant("")  // Not provided in new payload
                    .patientType("") // Not provided in new payload
                    .firstName("")   // Not provided in new payload
                    .lastName("")    // Not provided in new payload
                    .dob("")         // Not provided in new payload
                    .confirmAccurate(true) // Implied by submission
                    .agreePrivacy(getBool(json, "agree_privacy"))
                    .redirectUrl("") // Will be resolved from practice config
                    .relationship("")
                    .relationshipOther("")
                    .preferredSlots(preferredSlots)
                    .comments(comments)
                    .build();

            // Publish to queue
            MessagePublisher publisher = MessagePublisherFactory.getPublisher();
            logger.info("Publishing to queue: " + publisher.getType());
            publisher.publish(registrationData, logger);

            // Mark token as used after successful publish
            String clientIp = getClientIp(request);
            tokenProvider.markAsUsed(scheduleLinkToken, clientIp, logger);

            // Invalidate session after successful registration
            SessionManager.getInstance().invalidateSession(sessionId, logger);

            String redirectUrl = PracticeConfig.getRedirectUrl(practiceId);
            logger.info("Practice=" + practiceId + ", resolved redirect_url=" + redirectUrl);

            // Return response with cookie cleared
            return jsonResponseWithClearCookie(request, HttpStatus.CREATED,
                    Map.of("id", registrationData.getId(), "redirect_url", Objects.toString(redirectUrl, "")));

        } catch (Exception e) {
            logger.severe("Error publishing to queue: " + e.getClass().getName() + " - " + e.getMessage());
            return jsonResponse(request, HttpStatus.INTERNAL_SERVER_ERROR,
                    Map.of("error", "Internal server error"));
        }
    }

    private List<String> validate(JsonObject json) {
        List<String> errors = new ArrayList<>();

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

    private HttpResponseMessage jsonResponseWithClearCookie(HttpRequestMessage<?> request, HttpStatus status, Map<String, ?> body) {
        HttpResponseMessage.Builder builder = request.createResponseBuilder(status)
                .body(gson.toJson(body))
                .header("Content-Type", "application/json")
                .header("Set-Cookie", "DELTAV_SESSION=; Max-Age=0; Path=/; HttpOnly; SameSite=Strict");
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
               .header("Access-Control-Allow-Headers", "Content-Type, Authorization, X-Requested-With, X-Registration-Token")
               .header("Access-Control-Max-Age", "3600");
    }

    private String getHeaderIgnoreCase(Map<String, String> headers, String name) {
        if (headers == null || name == null) return null;
        for (Map.Entry<String, String> e : headers.entrySet()) {
            if (e.getKey() != null && e.getKey().equalsIgnoreCase(name)) return e.getValue();
        }
        return null;
    }

    private String extractSessionIdFromCookie(Map<String, String> headers, Logger logger) {
        String cookieHeader = getHeaderIgnoreCase(headers, "Cookie");
        if (cookieHeader == null || cookieHeader.isBlank()) {
            return null;
        }

        for (String cookie : cookieHeader.split(";")) {
            String trimmed = cookie.trim();
            if (trimmed.startsWith("DELTAV_SESSION=")) {
                String sessionId = trimmed.substring("DELTAV_SESSION=".length()).trim();
                logger.info("Found session ID in cookie");
                return sessionId;
            }
        }

        return null;
    }

    private String getClientIp(HttpRequestMessage<?> request) {
        Map<String, String> headers = request.getHeaders();

        String xff = getHeaderIgnoreCase(headers, "X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            String[] ips = xff.split(",");
            return ips[0].trim();
        }

        String realIp = getHeaderIgnoreCase(headers, "X-Real-IP");
        if (realIp != null && !realIp.isBlank()) {
            return realIp.trim();
        }

        String clientIp = getHeaderIgnoreCase(headers, "CLIENT-IP");
        if (clientIp != null && !clientIp.isBlank()) {
            return clientIp.trim();
        }

        return "unknown";
    }
}