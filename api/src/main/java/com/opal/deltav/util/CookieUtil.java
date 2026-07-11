package com.opal.deltav.util;

import com.opal.deltav.config.PracticeMetadataLoader;
import com.opal.deltav.model.PracticeMetadata;

import java.util.Map;
import java.util.logging.Logger;

/**
 * Utility class for parsing cookies from HTTP headers.
 */
public final class CookieUtil {

    private CookieUtil() {
        // Utility class
    }

    /**
     * Get a cookie value from the request headers.
     *
     * @param headers    the HTTP headers map
     * @param cookieName the name of the cookie to retrieve
     * @return the cookie value, or null if not found
     */
    public static String getCookieValue(Map<String, String> headers, String cookieName) {
        String cookieHeader = getHeaderIgnoreCase(headers, "Cookie");
        if (cookieHeader == null || cookieHeader.isBlank()) return null;

        for (String cookie : cookieHeader.split(";")) {
            String trimmed = cookie.trim();
            if (trimmed.startsWith(cookieName + "=")) {
                return trimmed.substring(cookieName.length() + 1);
            }
        }
        return null;
    }

    /**
     * Get the DELTAV_CONTEXT cookie value (base64 encoded key).
     * Verifies that:
     * 1. The signed cookie (DELTAV_CONTEXT_SIG) has a valid signature
     * 2. The normal cookie (DELTAV_CONTEXT) matches the signed value
     *
     * @param headers the HTTP headers map
     * @return the base64 key if both cookies are valid and match, or null otherwise
     */
    public static String getContextKey(Map<String, String> headers, Logger logger) {
        // Read normal cookie
        String normalValue = getCookieValue(headers, "DELTAV_CONTEXT");
        if (normalValue == null || normalValue.isBlank()) return null;

        // Read signed cookie and verify signature
        String signedValue = getCookieValue(headers, "DELTAV_CONTEXT_SIG");
        if (signedValue == null || signedValue.isBlank()) return null;

        // Verify signature and extract the original value
        String verifiedValue = CookieSigningUtil.verifyAndExtract(signedValue);
        if (verifiedValue == null) return null;

        // Verify that normal cookie matches the signed value
        if (!normalValue.equals(verifiedValue)) {
            logger.info("Cookie is tampered");
            return null; // Cookie tampering detected
        }

        return verifiedValue;
    }

    /**
     * Get practice metadata from the DELTAV_CONTEXT cookie.
     * The cookie contains the base64 encoded key used to lookup metadata.
     *
     * @param headers the HTTP headers map
     * @return the PracticeMetadata, or null if not found
     */
    public static PracticeMetadata getMetadataFromContext(Map<String, String> headers, Logger logger) {
        String contextKey = getContextKey(headers, logger);
        if (contextKey == null || contextKey.isBlank()) return null;
        return PracticeMetadataLoader.getMetadata(contextKey, logger);
    }

    /**
     * Get practice ID from the DELTAV_CONTEXT cookie.
     * Looks up the base64 key in PracticeMetadataLoader to get the practice ID.
     *
     * @param headers the HTTP headers map
     * @return the practice ID as Long, or null if not found
     */
    public static Long getPracticeIdFromContext(Map<String, String> headers, Logger logger) {
        PracticeMetadata metadata = getMetadataFromContext(headers, logger);
        if (metadata == null || metadata.getPracticeId() == null) return null;
        try {
            return Long.parseLong(metadata.getPracticeId());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Get client ID from the DELTAV_CONTEXT cookie.
     * Looks up the base64 key in PracticeMetadataLoader to get the client ID.
     *
     * @param headers the HTTP headers map
     * @return the client ID as String, or null if not found
     */
    public static String getClientIdFromContext(Map<String, String> headers, Logger logger) {
        PracticeMetadata metadata = getMetadataFromContext(headers, logger);
        return metadata != null ? metadata.getClientId() : null;
    }

    private static String getHeaderIgnoreCase(Map<String, String> headers, String name) {
        if (headers == null || name == null) return null;
        for (Map.Entry<String, String> e : headers.entrySet()) {
            if (e.getKey() != null && e.getKey().equalsIgnoreCase(name)) return e.getValue();
        }
        return null;
    }
}