package com.opal.deltav.function;

import com.microsoft.azure.functions.*;
import com.microsoft.azure.functions.annotation.*;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.opal.deltav.config.PracticeConfig;
import com.opal.deltav.model.RegistrationData;
import com.opal.deltav.schedulelinktoken.ScheduleLinkTokenProvider;
import com.opal.deltav.schedulelinktoken.ScheduleLinkTokenProviderFactory;
import com.opal.deltav.session.SessionManager;
import com.opal.deltav.streaming.MessagePublisher;
import com.opal.deltav.streaming.MessagePublisherFactory;
import com.opal.deltav.util.TokenUtil;

import java.time.LocalDate;
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

        List<String> errors = validate(json);
        if (!errors.isEmpty()) {
            logger.warning("Validation failed: " + String.join(", ", errors));
            return jsonResponse(request, HttpStatus.BAD_REQUEST,
                    Map.of("errors", errors));
        }

        try {
            // Build registration data
            RegistrationData registrationData = RegistrationData.builder()
                    .clientId("101")
                    .practiceId(getStr(json, "1012"))
                    .registrant(getStr(json, "registrant"))
                    .patientType(getStr(json, "patient_type"))
                    .firstName(getStr(json, "first_name"))
                    .lastName(getStr(json, "last_name"))
                    .dob(getStr(json, "dob"))
                    .confirmAccurate(getBool(json, "confirm_accurate"))
                    .agreePrivacy(getBool(json, "agree_privacy"))
                    .redirectUrl(getStr(json, "redirect_url"))
                    .relationship(getStr(json, "relationship"))
                    .relationshipOther(getStr(json, "relationship_other"))
                    .build();

            // Publish to queue
            MessagePublisher publisher = MessagePublisherFactory.getPublisher();
            logger.info("Publishing to queue: " + publisher.getType());
            publisher.publish(registrationData, logger);

            // Mark token as used after successful publish
            String scheduleLinkToken = SessionManager.getInstance().getTokenForSession(sessionId, logger);
            if (scheduleLinkToken != null) {
                String clientIp = getClientIp(request);
                ScheduleLinkTokenProvider tokenProvider = ScheduleLinkTokenProviderFactory.getProvider();
                tokenProvider.markAsUsed(scheduleLinkToken, clientIp, logger);
            }

            // Invalidate session after successful registration
            SessionManager.getInstance().invalidateSession(sessionId, logger);

            String practiceId = registrationData.getPracticeId();
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

        requireNonBlank(json, "client_id", "Client ID is required", errors);
        requireNonBlank(json, "first_name", "First name is required", errors);
        requireNonBlank(json, "last_name", "Last name is required", errors);
        requireNonBlank(json, "dob", "Date of birth is required", errors);
        requireNonBlank(json, "patient_type", "Patient type is required", errors);

        String dob = getStr(json, "dob");
        if (dob != null && !dob.isBlank()) {
            try {
                if (LocalDate.parse(dob).isAfter(LocalDate.now())) {
                    errors.add("Date of birth cannot be in the future");
                }
            } catch (Exception e) {
                errors.add("Invalid date format for dob");
            }
        }

        if (!getBool(json, "confirm_accurate")) errors.add("Must confirm accuracy");
        if (!getBool(json, "agree_privacy")) errors.add("Must agree to privacy policy");

        String registrant = getStr(json, "registrant");
        if ("another".equals(registrant)) {
            requireNonBlank(json, "relationship", "Relationship is required", errors);
            if ("other".equals(getStr(json, "relationship"))) {
                requireNonBlank(json, "relationship_other", "Please specify the relationship", errors);
            }
        }

        return errors;
    }

    private void requireNonBlank(JsonObject j, String field, String msg, List<String> errors) {
        String val = getStr(j, field);
        if (val == null || val.isBlank()) errors.add(msg);
    }

    private String getStr(JsonObject j, String field) {
        return j.has(field) && !j.get(field).isJsonNull() ? j.get(field).getAsString() : null;
    }

    private boolean getBool(JsonObject j, String field) {
        return j.has(field) && !j.get(field).isJsonNull() && j.get(field).getAsBoolean();
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

    /**
     * Extract session ID from Cookie header.
     * Cookie format: "DELTAV_SESSION=uuid; other=value"
     */
    private String extractSessionIdFromCookie(Map<String, String> headers, Logger logger) {
        String cookieHeader = getHeaderIgnoreCase(headers, "Cookie");
        if (cookieHeader == null || cookieHeader.isBlank()) {
            return null;
        }

        // Parse cookies: "name1=value1; name2=value2"
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

    /**
     * Get client IP address from request headers.
     * Checks X-Forwarded-For, X-Real-IP, and falls back to direct connection.
     */
    private String getClientIp(HttpRequestMessage<?> request) {
        Map<String, String> headers = request.getHeaders();

        // Check X-Forwarded-For (may contain multiple IPs, take the first)
        String xff = getHeaderIgnoreCase(headers, "X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            String[] ips = xff.split(",");
            return ips[0].trim();
        }

        // Check X-Real-IP
        String realIp = getHeaderIgnoreCase(headers, "X-Real-IP");
        if (realIp != null && !realIp.isBlank()) {
            return realIp.trim();
        }

        // Check CLIENT-IP
        String clientIp = getHeaderIgnoreCase(headers, "CLIENT-IP");
        if (clientIp != null && !clientIp.isBlank()) {
            return clientIp.trim();
        }

        return "unknown";
    }
}