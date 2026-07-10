package com.opal.deltav.util;

import java.util.Map;

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
     * Parse the DELTAV_CONTEXT cookie and extract the practice ID.
     * The cookie format is "clientId:practiceId".
     *
     * @param headers the HTTP headers map
     * @return the practice ID as Long, or null if not found or invalid
     */
    public static Long getPracticeIdFromContext(Map<String, String> headers) {
        String context = getCookieValue(headers, "DELTAV_CONTEXT");
        if (context == null || !context.contains(":")) return null;

        String[] parts = context.split(":", 2);
        if (parts.length == 2) {
            try {
                return Long.parseLong(parts[1].trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    /**
     * Parse the DELTAV_CONTEXT cookie and extract the client ID.
     * The cookie format is "clientId:practiceId".
     *
     * @param headers the HTTP headers map
     * @return the client ID as String, or null if not found
     */
    public static String getClientIdFromContext(Map<String, String> headers) {
        String context = getCookieValue(headers, "DELTAV_CONTEXT");
        if (context == null || !context.contains(":")) return null;

        String[] parts = context.split(":", 2);
        if (parts.length >= 1 && !parts[0].trim().isEmpty()) {
            return parts[0].trim();
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