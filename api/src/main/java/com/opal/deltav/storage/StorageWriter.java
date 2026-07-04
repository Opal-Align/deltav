package com.opal.deltav.storage;

import com.opal.deltav.model.RegistrationData;
import java.util.logging.Logger;

public interface StorageWriter {

    /**
     * Writes registration data to the storage backend.
     *
     * @param data the registration data to write
     * @param logger the logger for logging operations
     * @return the ID of the written record
     * @throws StorageException if writing fails
     */
    String write(RegistrationData data, Logger logger) throws StorageException;

    /**
     * Returns the storage type identifier.
     */
    StorageType getType();
}