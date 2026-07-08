package com.opal.deltav.function;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.microsoft.azure.functions.*;
import com.microsoft.azure.functions.annotation.*;
import com.opal.deltav.otp.RedisOtpService;
import com.opal.deltav.otp.RedisSessionService;
import com.opal.deltav.otp.RegistrationSession;
import com.opal.deltav.otp.SmsOtpSender;
import com.opal.deltav.util.PhoneUtil;

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

        // Validate session from cookie
        String sessionId = extractSessionIdFromCookie(request.getHeaders(), logger);
        if (sessionId == null || sessionId.isBlank()) {
            logger.warning("OTP send rejected: missing session cookie");
            return jsonResponse(request, HttpStatus.UNAUTHORIZED, Map.of("error", "Session required"));
        }

        RegistrationSession session = RedisSessionService.getSession(sessionId);
        if (session == null) {
            logger.warning("OTP send rejected: invalid session " + sessionId);
            return jsonResponse(request, HttpStatus.UNAUTHORIZED, Map.of("error", "Invalid or expired session"));
        }

        JsonObject json = parseJson(request, logger);
        if (json == null) {
            return jsonResponse(request, HttpStatus.BAD_REQUEST, Map.of("error", "Invalid JSON"));
        }

        String mobile = getString(json, "mobile");

        try {
            boolean enforceCooldown;

            // If session already has mobile bound, this is a resend
            if (session.isMobileBound()) {
                if (session.isOtpVerified()) {
                    return jsonResponse(request, HttpStatus.BAD_REQUEST, Map.of("error", "already_verified"));
                }
                enforceCooldown = true;
            } else {
                // First time - bind mobile to session
                String phoneE164 = PhoneUtil.toE164(mobile);
                if (phoneE164 == null) {
                    return jsonResponse(request, HttpStatus.BAD_REQUEST, Map.of("error", "invalid_mobile"));
                }
                session = RedisSessionService.bindMobile(sessionId, session.practiceId, phoneE164);
                enforceCooldown = false;
            }

            return deliverOtp(request, logger, session, enforceCooldown);
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

        // Validate session from cookie
        String sessionId = extractSessionIdFromCookie(request.getHeaders(), logger);
        if (sessionId == null || sessionId.isBlank()) {
            logger.warning("OTP verify rejected: missing session cookie");
            return jsonResponse(request, HttpStatus.UNAUTHORIZED, Map.of("error", "Session required"));
        }

        RegistrationSession session = RedisSessionService.getSession(sessionId);
        if (session == null) {
            logger.warning("OTP verify rejected: invalid session " + sessionId);
            return jsonResponse(request, HttpStatus.UNAUTHORIZED, Map.of("error", "Invalid or expired session"));
        }

        if (!session.isMobileBound()) {
            logger.warning("OTP verify rejected: session not bound to mobile " + sessionId);
            return jsonResponse(request, HttpStatus.BAD_REQUEST, Map.of("error", "Mobile not verified for session"));
        }

        if (session.isOtpVerified()) {
            return jsonResponse(request, HttpStatus.OK, Map.of(
                    "verified", true,
                    "session_state", RegistrationSession.STATE_OTP_VERIFIED));
        }

        JsonObject json = parseJson(request, logger);
        if (json == null) {
            return jsonResponse(request, HttpStatus.BAD_REQUEST, Map.of("error", "Invalid JSON"));
        }

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

    private static HttpResponseMessage deliverOtp(HttpRequestMessage<Optional<String>> request, Logger logger,
                                                  RegistrationSession session, boolean enforceCooldown) {
        RedisOtpService.SendResult result = RedisOtpService.sendOtp(session, enforceCooldown);
        if (!result.success) {
            if ("resend_throttled".equals(result.error)) {
                return jsonResponse(request, HttpStatus.TOO_MANY_REQUESTS, Map.of(
                        "error", result.error,
                        "retry_after_seconds", result.retryAfterSeconds));
            }
            if ("send_limit_reached".equals(result.error)) {
                return jsonResponse(request, HttpStatus.TOO_MANY_REQUESTS, Map.of("error", result.error));
            }
            return jsonResponse(request, HttpStatus.BAD_REQUEST, Map.of("error", result.error));
        }

        if (!SmsOtpSender.isConfigured() && !isPocMode()) {
            RedisOtpService.clearOtp(session.sessionId);
            logger.severe("SMS is not configured and OTP_POC_MODE is disabled");
            return jsonResponse(request, HttpStatus.INTERNAL_SERVER_ERROR, Map.of("error", "sms_not_configured"));
        }

        SmsOtpSender.SmsDeliveryResult smsResult =
                SmsOtpSender.sendOtpToE164(session.practiceId, session.phoneE164, result.otp);
        if (!smsResult.sent && !smsResult.skipped) {
            RedisOtpService.clearOtp(session.sessionId);
            return jsonResponse(request, HttpStatus.BAD_GATEWAY,
                    Map.of("error", "sms_send_failed", "message", "Could not send OTP. Please try again."));
        }

        Map<String, Object> body = new HashMap<>();
        body.put("success", true);
        body.put("session_id", session.sessionId);
        body.put("phone_masked", RedisSessionService.maskPhone(session.phoneE164));
        body.put("expires_in_seconds", result.expiresInSeconds);
        body.put("sends_remaining", result.sendsRemaining);
        if (isPocMode()) {
            body.put("poc_otp", result.otp);
            logger.info("POC OTP shown in response for session " + session.sessionId);
        }
        return jsonResponse(request, HttpStatus.OK, body);
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

    private static boolean isPocMode() {
        String flag = System.getenv("OTP_POC_MODE");
        return flag != null && ("true".equalsIgnoreCase(flag) || "1".equals(flag));
    }

    private static HttpResponseMessage jsonResponse(HttpRequestMessage<?> request, HttpStatus status, Map<String, ?> body) {
        HttpResponseMessage.Builder builder = request.createResponseBuilder(status)
                .body(gson.toJson(body))
                .header("Content-Type", "application/json");
        addCorsHeaders(builder, request);
        return builder.build();
    }

    private static HttpResponseMessage corsResponse(HttpRequestMessage<?> request, HttpStatus status, Map<String, ?> body) {
        HttpResponseMessage.Builder builder = request.createResponseBuilder(status);
        if (body != null) {
            builder.body(gson.toJson(body)).header("Content-Type", "application/json");
        }
        addCorsHeaders(builder, request);
        return builder.build();
    }

    private static void addCorsHeaders(HttpResponseMessage.Builder builder, HttpRequestMessage<?> request) {
        String origin = request.getHeaders() != null ? request.getHeaders().get("Origin") : null;
        if (origin == null || origin.isBlank()) origin = "*";
        builder.header("Access-Control-Allow-Origin", origin)
                .header("Vary", "Origin")
                .header("Access-Control-Allow-Methods", "POST, OPTIONS")
                .header("Access-Control-Allow-Headers", "Content-Type, Authorization, X-Requested-With, X-Registration-Token")
                .header("Access-Control-Max-Age", "3600");
    }

    private static String extractSessionIdFromCookie(Map<String, String> headers, Logger logger) {
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

    private static String getHeaderIgnoreCase(Map<String, String> headers, String name) {
        if (headers == null || name == null) return null;
        for (Map.Entry<String, String> e : headers.entrySet()) {
            if (e.getKey() != null && e.getKey().equalsIgnoreCase(name)) return e.getValue();
        }
        return null;
    }
}