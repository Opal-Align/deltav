package com.opal.deltav.storage;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Factory for creating StorageWriter instances based on configuration.
 *
 * Configure via environment variable STORAGE_TYPE:
 * - TABLE_STORAGE (default)
 * - BLOB_STORAGE
 * - POSTGRES
 * - COSMOS_DB
 */
public class StorageWriterFactory {

    private static final Map<StorageType, StorageWriter> writers = new ConcurrentHashMap<>();

    private StorageWriterFactory() {
    }

    /**
     * Gets the StorageWriter based on the STORAGE_TYPE environment variable.
     * Defaults to TABLE_STORAGE if not configured.
     */
    public static StorageWriter getWriter() {
        String storageTypeEnv = System.getenv("STORAGE_TYPE");
        StorageType type = parseStorageType(storageTypeEnv);
        return getWriter(type);
    }

    /**
     * Gets the StorageWriter for the specified storage type.
     */
    public static StorageWriter getWriter(StorageType type) {
        return writers.computeIfAbsent(type, StorageWriterFactory::createWriter);
    }

    private static StorageType parseStorageType(String value) {
        if (value == null || value.isBlank()) {
            return StorageType.TABLE_STORAGE;
        }
        try {
            return StorageType.valueOf(value.toUpperCase().trim());
        } catch (IllegalArgumentException e) {
            return StorageType.TABLE_STORAGE;
        }
    }

    private static StorageWriter createWriter(StorageType type) {
        return switch (type) {
            case TABLE_STORAGE -> new TableStorageWriter();
            case POSTGRES, BLOB_STORAGE, COSMOS_DB -> throw new UnsupportedOperationException(
                    "Storage type " + type + " is not yet implemented");
        };
    }
}