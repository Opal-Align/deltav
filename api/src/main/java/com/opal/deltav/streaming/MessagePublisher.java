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
     * Publishes a raw JSON payload directly to a named queue, bypassing the
     * QueueMessage/client-id-suffix convention used by {@link #publish}.
     *
     * @param queueName the fully qualified queue name to publish to
     * @param jsonPayload the raw JSON string to publish
     * @param logger the logger for logging operations
     * @throws StreamingException if publishing fails
     */
    void publishRaw(String queueName, String jsonPayload, Logger logger) throws StreamingException;

    /**
     * Returns the streaming type identifier.
     */
    StreamingType getType();
}