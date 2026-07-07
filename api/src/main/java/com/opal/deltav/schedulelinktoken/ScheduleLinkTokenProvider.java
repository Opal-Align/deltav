package com.opal.deltav.schedulelinktoken;

import java.util.Map;
import java.util.function.Function;
import java.util.logging.Logger;

/**
 * Interface for fetching schedule link token data from storage.
 */
public interface ScheduleLinkTokenProvider {

    /**
     * Get all data for the given key as a Map.
     *
     * @param key the key/identifier
     * @param logger the logger
     * @return data map, or empty map if not found
     */
    Map<String, Object> getData(String key, Logger logger);

    /**
     * Get data for the given key and convert to the specified type.
     *
     * @param key the key/identifier
     * @param converter function to convert Map to desired type
     * @param logger the logger
     * @param <T> the target type
     * @return converted object, or null if not found
     */
    <T> T getData(String key, Function<Map<String, Object>, T> converter, Logger logger);

    /**
     * Get a specific value for the given key.
     *
     * @param key the key/identifier
     * @param fieldKey the field key to retrieve
     * @param logger the logger
     * @return value, or null if not found
     */
    Object getValue(String key, String fieldKey, Logger logger);

    /**
     * Check if data exists for the given key.
     *
     * @param key the key/identifier
     * @param logger the logger
     * @return true if exists
     */
    boolean exists(String key, Logger logger);

    /**
     * Get token as ScheduleLinkToken object.
     *
     * @param key the key/identifier
     * @param logger the logger
     * @return ScheduleLinkToken or null if not found
     */
    default ScheduleLinkToken getToken(String key, Logger logger) {
        return getData(key, ScheduleLinkToken::fromMap, logger);
    }

    /**
     * Validate token - checks existence, expiry, status, and revocation.
     *
     * @param key the key/identifier
     * @param logger the logger
     * @return ValidationResult with valid flag and error message
     */
    default ValidationResult validateToken(String key, Logger logger) {
        ScheduleLinkToken token = getToken(key, logger);
        if (token == null) {
            return new ValidationResult(false, "Token not found");
        }
        if (!token.isValid()) {
            return new ValidationResult(false, token.getValidationError());
        }
        return new ValidationResult(true, null, token);
    }

    /**
     * Result of token validation.
     */
    class ValidationResult {
        public final boolean valid;
        public final String error;
        public final ScheduleLinkToken token;

        public ValidationResult(boolean valid, String error) {
            this(valid, error, null);
        }

        public ValidationResult(boolean valid, String error, ScheduleLinkToken token) {
            this.valid = valid;
            this.error = error;
            this.token = token;
        }
    }

    /**
     * Returns the provider type identifier.
     */
    ScheduleLinkTokenProviderType getType();

    /**
     * Mark token as used after successful registration.
     *
     * @param key the token key
     * @param clientIp the client IP address
     * @param logger the logger
     */
    void markAsUsed(String key, String clientIp, Logger logger);
}