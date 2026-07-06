package com.opal.deltav;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

public final class OtpHashUtil {

    private OtpHashUtil() {}

    public static String hashOtp(String sessionId, long patientKey, String otp, String secret) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("OTP hash secret is not configured");
        }
        String payload = sessionId + ":" + patientKey + ":" + otp.trim();
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] hash = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to hash OTP", e);
        }
    }

    public static boolean matches(String sessionId, long patientKey, String otp, String storedHash, String secret) {
        if (storedHash == null || storedHash.isBlank()) return false;
        String expected = hashOtp(sessionId, patientKey, otp, secret);
        return constantTimeEquals(expected, storedHash);
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null || a.length() != b.length()) return false;
        int result = 0;
        for (int i = 0; i < a.length(); i++) {
            result |= a.charAt(i) ^ b.charAt(i);
        }
        return result == 0;
    }
}
