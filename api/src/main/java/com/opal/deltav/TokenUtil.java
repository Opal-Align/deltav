package com.opal.deltav;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Objects;

/**
 * Simple HMAC-SHA256 token utility.
 * Token format: expEpochSeconds.nonceHex.signatureB64Url
 *   - signature = HMACSHA256(secret, exp+"."+nonce)
 */
public final class TokenUtil {
    private static final String HMAC_ALGO = "HmacSHA256";
    private static final SecureRandom RAND = new SecureRandom();

    private TokenUtil() {}

    public static String generateNonceHex(int bytes) {
        byte[] buf = new byte[bytes];
        RAND.nextBytes(buf);
        return HexFormat.of().formatHex(buf);
    }

    public static String sign(String data, String secret) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGO);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_ALGO));
            byte[] sig = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(sig);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to compute token signature", e);
        }
    }

    public static String createToken(long expEpochSeconds, String secret) {
        Objects.requireNonNull(secret, "secret");
        String nonce = generateNonceHex(16);
        String payload = expEpochSeconds + "." + nonce;
        String sig = sign(payload, secret);
        return payload + "." + sig;
    }

    public static ValidationResult validate(String token, String secret) {
        if (token == null || token.isBlank()) {
            return ValidationResult.error("missing_token");
        }
        String[] parts = token.split("\\.");
        if (parts.length != 3) {
            return ValidationResult.error("malformed_token");
        }
        long exp;
        try {
            exp = Long.parseLong(parts[0]);
        } catch (NumberFormatException e) {
            return ValidationResult.error("malformed_exp");
        }
        String nonce = parts[1];
        String sig = parts[2];
        String payload = parts[0] + "." + nonce;
        String expected = sign(payload, secret);
        if (!constantTimeEquals(sig, expected)) {
            return ValidationResult.error("invalid_signature");
        }
        long now = Instant.now().getEpochSecond();
        if (now > exp) {
            return ValidationResult.error("token_expired");
        }
        return ValidationResult.ok(exp);
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) return false;
        if (a.length() != b.length()) return false;
        int res = 0;
        for (int i = 0; i < a.length(); i++) {
            res |= a.charAt(i) ^ b.charAt(i);
        }
        return res == 0;
    }

    public static final class ValidationResult {
        public final boolean valid;
        public final String error;
        public final long exp;

        private ValidationResult(boolean valid, String error, long exp) {
            this.valid = valid;
            this.error = error;
            this.exp = exp;
        }

        public static ValidationResult ok(long exp) {
            return new ValidationResult(true, null, exp);
        }

        public static ValidationResult error(String error) {
            return new ValidationResult(false, error, 0L);
        }
    }
}
