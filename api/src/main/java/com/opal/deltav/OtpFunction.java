package com.opal.deltav;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.microsoft.azure.functions.*;
import com.microsoft.azure.functions.annotation.*;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Logger;

public class OtpFunction {

    private static final Gson gson = new Gson();

    @FunctionName("otpSend")
    public HttpResponseMessage sendOtp(
            @HttpTrigger(
                    name = "req",
                    methods = {HttpMethod.POST, HttpMethod.OPTIONS},
                    authLevel = AuthorizationLevel.ANONYMOUS,
                    route = "api/otp/send"
            ) HttpRequestMessage<Optional<String>> request,
            final ExecutionContext context) {

        Logger logger = context.getLogger();
        if (request.getHttpMethod() == HttpMethod.OPTIONS) {
            return corsResponse(request, HttpStatus.NO_CONTENT, null);
        }

        JsonObject json = parseJson(request, logger);
        if (json == null) {
            return jsonResponse(request, HttpStatus.BAD_REQUEST, Map.of("error", "Invalid JSON"));
        }

        String sessionId = getString(json, "session_id");
        RegistrationSession session = RedisSessionService.getSession(sessionId);
        if (session == null || !session.isIdentityVerified()) {
            return jsonResponse(request, HttpStatus.BAD_REQUEST, Map.of("error", "invalid_session"));
        }
        if (session.isOtpVerified()) {
            return jsonResponse(request, HttpStatus.BAD_REQUEST, Map.of("error", "already_verified"));
        }

        try {
            return IdentityFunction.deliverOtp(request, logger, session, true);
        } catch (IllegalStateException e) {
            logger.severe("OTP send configuration error: " + e.getMessage());
            return jsonResponse(request, HttpStatus.INTERNAL_SERVER_ERROR, Map.of("error", "Server configuration error"));
        } catch (Exception e) {
            logger.severe("OTP send failed: " + e.getClass().getName() + " - " + e.getMessage());
            return jsonResponse(request, HttpStatus.INTERNAL_SERVER_ERROR, Map.of("error", "Failed to send OTP"));
        }
    }

    @FunctionName("otpVerify")
    public HttpResponseMessage verifyOtp(
            @HttpTrigger(
                    name = "req",
                    methods = {HttpMethod.POST, HttpMethod.OPTIONS},
                    authLevel = AuthorizationLevel.ANONYMOUS,
                    route = "api/otp/verify"
            ) HttpRequestMessage<Optional<String>> request,
            final ExecutionContext context) {

        Logger logger = context.getLogger();
        if (request.getHttpMethod() == HttpMethod.OPTIONS) {
            return corsResponse(request, HttpStatus.NO_CONTENT, null);
        }

        JsonObject json = parseJson(request, logger);
        if (json == null) {
            return jsonResponse(request, HttpStatus.BAD_REQUEST, Map.of("error", "Invalid JSON"));
        }

        String sessionId = getString(json, "session_id");
        String otp = getString(json, "otp");

        try {
            RedisOtpService.VerifyResult result = RedisOtpService.verifyOtp(sessionId, otp);
            if (result.verified) {
                return jsonResponse(request, HttpStatus.OK, Map.of(
                        "verified", true,
                        "session_state", RegistrationSession.STATE_OTP_VERIFIED));
            }

            Map<String, Object> body = new HashMap<>();
            body.put("verified", false);
            body.put("error", result.error);
            if (result.refreshRequired) {
                body.put("refresh_required", true);
                body.put("message", "Too many incorrect attempts. Please request a new code.");
                return jsonResponse(request, HttpStatus.TOO_MANY_REQUESTS, body);
            }
            body.put("attempts_remaining", result.attemptsRemaining);
            return jsonResponse(request, HttpStatus.BAD_REQUEST, body);
        } catch (IllegalStateException e) {
            logger.severe("OTP verify configuration error: " + e.getMessage());
            return jsonResponse(request, HttpStatus.INTERNAL_SERVER_ERROR, Map.of("error", "Server configuration error"));
        } catch (Exception e) {
            logger.severe("OTP verify failed: " + e.getClass().getName() + " - " + e.getMessage());
            return jsonResponse(request, HttpStatus.INTERNAL_SERVER_ERROR, Map.of("error", "Failed to verify OTP"));
        }
    }

    private static JsonObject parseJson(HttpRequestMessage<Optional<String>> request, Logger logger) {
        String body = request.getBody().orElse(null);
        if (body == null || body.isBlank()) return null;
        try {
            return gson.fromJson(body, JsonObject.class);
        } catch (Exception e) {
            logger.warning("Invalid JSON: " + e.getMessage());
            return null;
        }
    }

    private static String getString(JsonObject json, String field) {
        return json.has(field) && !json.get(field).isJsonNull() ? json.get(field).getAsString() : null;
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
            builder.body(gson.toJson(body)).header("Content-Type", "application/json");
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
}
