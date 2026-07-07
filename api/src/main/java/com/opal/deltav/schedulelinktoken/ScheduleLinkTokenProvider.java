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
     * Returns the provider type identifier.
     */
    ScheduleLinkTokenProviderType getType();
}