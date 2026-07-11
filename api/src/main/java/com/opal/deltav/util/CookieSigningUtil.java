package com.opal.deltav.util;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Utility for signing and verifying cookies to prevent tampering.
 * Format: value.signature
 */
public final class CookieSigningUtil {

    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final String SEPARATOR = ".";

    private CookieSigningUtil() {}

    /**
     * Sign a cookie value with HMAC-SHA256.
     * @param value the cookie value to sign
     * @return the signed value in format: value.signature
     */
    public static String sign(String value) {
        String secret = getSecret();
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("COOKIE_SIGNING_SECRET is not configured");
        }
        String signature = computeHmac(value, secret);
        return value + SEPARATOR + signature;
    }

    /**
     * Verify and extract the original value from a signed cookie.
     * @param signedValue the signed cookie value (format: value.signature)
     * @return the original value if signature is valid, null otherwise
     */
    public static String verifyAndExtract(String signedValue) {
        if (signedValue == null || !signedValue.contains(SEPARATOR)) {
            return null;
        }

        String secret = getSecret();
        if (secret == null || secret.isBlank()) {
            return null;
        }

        int lastDot = signedValue.lastIndexOf(SEPARATOR);
        if (lastDot <= 0 || lastDot >= signedValue.length() - 1) {
            return null;
        }

        String value = signedValue.substring(0, lastDot);
        String providedSignature = signedValue.substring(lastDot + 1);
        String expectedSignature = computeHmac(value, secret);

        if (constantTimeEquals(expectedSignature, providedSignature)) {
            return value;
        }
        return null;
    }

    /**
     * Check if a signed cookie value is valid.
     */
    public static boolean isValid(String signedValue) {
        return verifyAndExtract(signedValue) != null;
    }

    private static String computeHmac(String value, String secret) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            SecretKeySpec keySpec = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM);
            mac.init(keySpec);
            byte[] hmacBytes = mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hmacBytes);
        } catch (Exception e) {
            throw new RuntimeException("Failed to compute HMAC", e);
        }
    }

    /**
     * Constant-time string comparison to prevent timing attacks.
     */
    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null || a.length() != b.length()) {
            return false;
        }
        int result = 0;
        for (int i = 0; i < a.length(); i++) {
            result |= a.charAt(i) ^ b.charAt(i);
        }
        return result == 0;
    }

    private static String getSecret() {
        String secret = System.getenv("COOKIE_SIGNING_SECRET");
        if (secret == null || secret.isBlank()) {
            // Fall back to REGISTRATION_TOKEN_SECRET if COOKIE_SIGNING_SECRET not set
            secret = System.getenv("REGISTRATION_TOKEN_SECRET");
        }
        return secret;
    }
}