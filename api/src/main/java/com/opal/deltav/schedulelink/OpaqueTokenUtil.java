package com.opal.deltav.schedulelink;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * SHA-256 hash for opaque schedule link tokens.
 * Must match org.opalalign.dashboard.common.util.OpaqueTokenUtil.
 */
public final class OpaqueTokenUtil {

    private OpaqueTokenUtil() {
    }

    public static String hash(String plainToken) {
        if (plainToken == null || plainToken.isBlank()) {
            throw new IllegalArgumentException("plainToken must not be blank");
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(plainToken.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hashed);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
