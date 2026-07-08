package com.opal.deltav.streaming;

import com.opal.deltav.model.QueueMessage;
import java.util.logging.Logger;

public interface MessagePublisher {

    /**
     * Publishes queue message to a streaming service.
     *
     * @param message the queue message to publish
     * @param clientId the client ID for queue routing
     * @param logger the logger for logging operations
     * @throws StreamingException if publishing fails
     */
    void publish(QueueMessage message, String clientId, Logger logger) throws StreamingException;

    /**
     * Returns the streaming type identifier.
     */
    StreamingType getType();
}