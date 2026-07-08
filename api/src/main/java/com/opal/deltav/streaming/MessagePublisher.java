package com.opal.deltav.streaming;

import com.opal.deltav.model.RegistrationData;
import java.util.logging.Logger;

public interface MessagePublisher {

    /**
     * Publishes registration data to a streaming service.
     *
     * @param data the registration data to publish
     * @param logger the logger for logging operations
     * @throws StreamingException if publishing fails
     */
    void publish(RegistrationData data, Logger logger) throws StreamingException;

    /**
     * Returns the streaming type identifier.
     */
    StreamingType getType();
}