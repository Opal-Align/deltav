package com.opal.deltav.otp;

import redis.clients.jedis.Jedis;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

public final class RedisSessionService {

    private static final String SESSION_KEY_PREFIX = "deltav:session:";
    private static final int SESSION_TTL_SECONDS = 1800;

    private RedisSessionService() {
    }

    public static String createSession(String practiceId, Logger logger) {
        return createSession(practiceId, null, logger);
    }

    public static String createSession(String practiceId, String token, Logger logger) {
        String normalizedPractice = RedisOtpService.normalizePracticeId(practiceId);
        logger.info("Creating session: " + normalizedPractice);
        if (normalizedPractice == null) {
            throw new IllegalArgumentException("invalid_practice");
        }
        String sessionId = UUID.randomUUID().toString();
        try (Jedis jedis = RedisClients.getPool(logger).getResource()) {
            Map<String, String> fields = new HashMap<>();
            fields.put("state", RegistrationSession.STATE_CREATED);
            fields.put("practice_id", normalizedPractice);
            if (token != null && !token.isBlank()) {
                fields.put("token", token);
            }
            jedis.hset(sessionKey(sessionId), fields);
            jedis.expire(sessionKey(sessionId), SESSION_TTL_SECONDS);
        }
        return sessionId;
    }

    public static RegistrationSession bindMobile(String sessionId, String practiceId, String phoneE164, Logger logger) {
        RegistrationSession existing = getSession(sessionId, logger);
        if (existing == null) {
            throw new IllegalArgumentException("invalid_session");
        }
        if (!practiceId.equals(existing.practiceId)) {
            throw new IllegalArgumentException("invalid_session");
        }
        if (existing.isOtpVerified()) {
            throw new IllegalStateException("session_already_verified");
        }

        try (Jedis jedis = RedisClients.getPool(logger).getResource()) {
            Map<String, String> fields = new HashMap<>();
            fields.put("state", RegistrationSession.STATE_MOBILE_BOUND);
            fields.put("phone_e164", phoneE164);
            jedis.hset(sessionKey(sessionId), fields);
            jedis.expire(sessionKey(sessionId), SESSION_TTL_SECONDS);
        }
        return getSession(sessionId, logger);
    }

    public static void markOtpVerified(String sessionId, Logger logger) {
        RegistrationSession session = getSession(sessionId, logger);
        if (session == null || !session.isMobileBound()) {
            throw new IllegalArgumentException("invalid_session");
        }
        try (Jedis jedis = RedisClients.getPool(logger).getResource()) {
            jedis.hset(sessionKey(sessionId), "state", RegistrationSession.STATE_OTP_VERIFIED);
            jedis.expire(sessionKey(sessionId), SESSION_TTL_SECONDS);
        }
    }

    public static RegistrationSession getSession(String sessionId, Logger logger) {
        if (sessionId == null || sessionId.isBlank()) return null;
        try (Jedis jedis = RedisClients.getPool(logger).getResource()) {
            Map<String, String> fields = jedis.hgetAll(sessionKey(sessionId));
            if (fields == null || fields.isEmpty()) return null;

            String practiceId = fields.get("practice_id");
            String state = fields.getOrDefault("state", RegistrationSession.STATE_CREATED);
            String phoneE164 = fields.get("phone_e164");
            String token = fields.get("token");
            return new RegistrationSession(sessionId, practiceId, state, phoneE164, token);
        }
    }

    public static boolean exists(String sessionId, Logger logger) {
        if (sessionId == null || sessionId.isBlank()) return false;
        try (Jedis jedis = RedisClients.getPool(logger).getResource()) {
            return jedis.exists(sessionKey(sessionId));
        }
    }

    public static void invalidateSession(String sessionId, Logger logger) {
        if (sessionId == null || sessionId.isBlank()) return;
        try (Jedis jedis = RedisClients.getPool(logger).getResource()) {
            jedis.del(sessionKey(sessionId));
        }
    }

    public static void extendSession(String sessionId, int ttlSeconds, Logger logger) {
        if (sessionId == null || sessionId.isBlank()) return;
        try (Jedis jedis = RedisClients.getPool(logger).getResource()) {
            jedis.expire(sessionKey(sessionId), ttlSeconds);
        }
    }

    public static String maskPhone(String phoneE164) {
        String digits = phoneE164 == null ? "" : phoneE164.replaceAll("\\D", "");
        if (digits.length() < 4) return "(***) ***-****";
        String last4 = digits.substring(digits.length() - 4);
        return "(***) ***-" + last4;
    }

    private static String sessionKey(String sessionId) {
        return SESSION_KEY_PREFIX + sessionId;
    }
}