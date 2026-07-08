package com.opal.deltav.otp;

import com.opal.deltav.util.OtpHashUtil;
import com.opal.deltav.util.PhoneUtil;
import redis.clients.jedis.Jedis;

import java.security.SecureRandom;

/**
 * Session-scoped OTP storage in Redis.
 * Keys: otp:{sessionId}, otp:attempts:{sessionId}, otp:send_count:{sessionId}, otp:last_sent:{sessionId}
 */
public final class RedisOtpService {

    private static final int OTP_TTL_SECONDS = 60;
    private static final int MAX_VERIFY_ATTEMPTS = 3;
    private static final int MAX_OTP_SENDS = 3;
    private static final int RESEND_COOLDOWN_SECONDS = 60;

    private static final String OTP_KEY_PREFIX = "otp:";
    private static final String ATTEMPTS_KEY_PREFIX = "otp:attempts:";
    private static final String SEND_COUNT_KEY_PREFIX = "otp:send_count:";
    private static final String LAST_SENT_KEY_PREFIX = "otp:last_sent:";

    private static final SecureRandom RANDOM = new SecureRandom();

    private RedisOtpService() {}

    public static String normalizePracticeId(String practiceId) {
        if (practiceId == null) return null;
        String trimmed = practiceId.trim();
        if (trimmed.isBlank()) return null;
        if (!trimmed.matches("[a-zA-Z0-9_-]+")) return null;
        return trimmed;
    }

    public static String normalizeMobile(String mobile) {
        return PhoneUtil.normalizeMobile(mobile);
    }

    public static SendResult sendOtp(RegistrationSession session, boolean enforceCooldown) {
        if (session == null || !session.isMobileBound()) {
            return SendResult.invalidSession();
        }
        if (session.phoneE164 == null || session.phoneE164.isBlank()) {
            return SendResult.invalidSession();
        }

        try (Jedis jedis = RedisClients.getPool().getResource()) {
            String sendCountRaw = jedis.get(sendCountKey(session.sessionId));
            int sendCount = sendCountRaw == null || sendCountRaw.isBlank() ? 0 : Integer.parseInt(sendCountRaw);
            if (sendCount >= MAX_OTP_SENDS) {
                return SendResult.sendLimitReached();
            }

            if (enforceCooldown && sendCount > 0) {
                String lastSentRaw = jedis.get(lastSentKey(session.sessionId));
                if (lastSentRaw != null && !lastSentRaw.isBlank()) {
                    long lastSent = Long.parseLong(lastSentRaw);
                    long elapsed = (System.currentTimeMillis() - lastSent) / 1000L;
                    if (elapsed < RESEND_COOLDOWN_SECONDS) {
                        return SendResult.resendThrottled((int) (RESEND_COOLDOWN_SECONDS - elapsed));
                    }
                }
            }

            String otp = generateOtp();
            String secret = getOtpHashSecret();
            String hashedOtp = OtpHashUtil.hashOtp(session.sessionId, session.phoneE164, otp, secret);

            jedis.setex(otpKey(session.sessionId), OTP_TTL_SECONDS, hashedOtp);
            jedis.del(attemptsKey(session.sessionId));

            long newCount = jedis.incr(sendCountKey(session.sessionId));
            if (newCount == 1) {
                jedis.expire(sendCountKey(session.sessionId), OTP_TTL_SECONDS * MAX_OTP_SENDS);
            }
            jedis.setex(lastSentKey(session.sessionId), OTP_TTL_SECONDS * MAX_OTP_SENDS,
                    Long.toString(System.currentTimeMillis()));

            return SendResult.ok(otp, OTP_TTL_SECONDS, MAX_OTP_SENDS - (int) newCount);
        }
    }

    public static void clearOtp(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) return;
        try (Jedis jedis = RedisClients.getPool().getResource()) {
            jedis.del(otpKey(sessionId));
            jedis.del(attemptsKey(sessionId));
        }
    }

    public static VerifyResult verifyOtp(String sessionId, String otp) {
        RegistrationSession session = RedisSessionService.getSession(sessionId);
        if (session == null || !session.isMobileBound()) {
            return VerifyResult.invalidSession();
        }
        if (otp == null || !otp.matches("\\d{6}")) {
            return VerifyResult.invalidOtp(remainingAttempts(sessionId));
        }

        try (Jedis jedis = RedisClients.getPool().getResource()) {
            String storedHash = jedis.get(otpKey(sessionId));
            if (storedHash == null || storedHash.isBlank()) {
                return VerifyResult.expired();
            }

            String secret = getOtpHashSecret();
            if (OtpHashUtil.matches(sessionId, session.phoneE164, otp, storedHash, secret)) {
                jedis.del(otpKey(sessionId));
                jedis.del(attemptsKey(sessionId));
                RedisSessionService.markOtpVerified(sessionId);
                return VerifyResult.success();
            }

            long attempts = jedis.incr(attemptsKey(sessionId));
            if (attempts == 1) {
                jedis.expire(attemptsKey(sessionId), OTP_TTL_SECONDS * 5L);
            }
            if (attempts >= MAX_VERIFY_ATTEMPTS) {
                jedis.del(otpKey(sessionId));
                jedis.del(attemptsKey(sessionId));
                return VerifyResult.tooManyAttempts();
            }
            return VerifyResult.invalidOtp((int) (MAX_VERIFY_ATTEMPTS - attempts));
        }
    }

    private static int remainingAttempts(String sessionId) {
        try (Jedis jedis = RedisClients.getPool().getResource()) {
            String val = jedis.get(attemptsKey(sessionId));
            if (val == null || val.isBlank()) return MAX_VERIFY_ATTEMPTS;
            int used = Integer.parseInt(val);
            return Math.max(0, MAX_VERIFY_ATTEMPTS - used);
        } catch (Exception e) {
            return MAX_VERIFY_ATTEMPTS;
        }
    }

    private static String generateOtp() {
        int value = RANDOM.nextInt(1_000_000);
        return String.format("%06d", value);
    }

    private static String otpKey(String sessionId) {
        return OTP_KEY_PREFIX + sessionId;
    }

    private static String attemptsKey(String sessionId) {
        return ATTEMPTS_KEY_PREFIX + sessionId;
    }

    private static String sendCountKey(String sessionId) {
        return SEND_COUNT_KEY_PREFIX + sessionId;
    }

    private static String lastSentKey(String sessionId) {
        return LAST_SENT_KEY_PREFIX + sessionId;
    }

    private static String getOtpHashSecret() {
        String secret = System.getenv("OTP_HASH_SECRET");
        if (secret == null || secret.isBlank()) {
            secret = System.getenv("REGISTRATION_TOKEN_SECRET");
        }
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("OTP_HASH_SECRET or REGISTRATION_TOKEN_SECRET must be configured");
        }
        return secret;
    }

    public static final class SendResult {
        public final boolean success;
        public final String error;
        public final String otp;
        public final int expiresInSeconds;
        public final int sendsRemaining;
        public final int retryAfterSeconds;

        private SendResult(boolean success, String error, String otp, int expiresInSeconds,
                           int sendsRemaining, int retryAfterSeconds) {
            this.success = success;
            this.error = error;
            this.otp = otp;
            this.expiresInSeconds = expiresInSeconds;
            this.sendsRemaining = sendsRemaining;
            this.retryAfterSeconds = retryAfterSeconds;
        }

        public static SendResult ok(String otp, int expiresInSeconds, int sendsRemaining) {
            return new SendResult(true, null, otp, expiresInSeconds, sendsRemaining, 0);
        }

        public static SendResult invalidSession() {
            return new SendResult(false, "invalid_session", null, 0, 0, 0);
        }

        public static SendResult invalidMobile() {
            return new SendResult(false, "invalid_mobile", null, 0, 0, 0);
        }

        public static SendResult invalidPractice() {
            return new SendResult(false, "invalid_practice", null, 0, 0, 0);
        }

        public static SendResult sendLimitReached() {
            return new SendResult(false, "send_limit_reached", null, 0, 0, 0);
        }

        public static SendResult resendThrottled(int retryAfterSeconds) {
            return new SendResult(false, "resend_throttled", null, 0, 0, retryAfterSeconds);
        }
    }

    public static final class VerifyResult {
        public final boolean verified;
        public final String error;
        public final int attemptsRemaining;
        public final boolean refreshRequired;

        private VerifyResult(boolean verified, String error, int attemptsRemaining, boolean refreshRequired) {
            this.verified = verified;
            this.error = error;
            this.attemptsRemaining = attemptsRemaining;
            this.refreshRequired = refreshRequired;
        }

        public static VerifyResult success() {
            return new VerifyResult(true, null, 0, false);
        }

        public static VerifyResult invalidSession() {
            return new VerifyResult(false, "invalid_session", 0, false);
        }

        public static VerifyResult invalidOtp(int attemptsRemaining) {
            return new VerifyResult(false, "invalid_otp", attemptsRemaining, false);
        }

        public static VerifyResult expired() {
            return new VerifyResult(false, "otp_expired", 0, false);
        }

        public static VerifyResult tooManyAttempts() {
            return new VerifyResult(false, "too_many_attempts", 0, true);
        }
    }
}