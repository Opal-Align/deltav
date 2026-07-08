package com.opal.deltav.streaming;

import com.opal.deltav.model.QueueMessage;
import java.util.logging.Logger;

/**
 * No-operation publisher that does nothing.
 * Used when streaming is disabled (STREAMING_TYPE=NONE or not configured).
 */
public class NoOpPublisher implements MessagePublisher {

    @Override
    public void publish(QueueMessage message, String clientId, Logger logger) {
        logger.fine("Streaming disabled, skipping message publish for patientKey: " + message.getPatientKey());
    }

    @Override
    public StreamingType getType() {
        return StreamingType.NONE;
    }
}