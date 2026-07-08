package com.opal.deltav.streaming;

import com.opal.deltav.model.RegistrationData;
import java.util.logging.Logger;

/**
 * No-operation publisher that does nothing.
 * Used when streaming is disabled (STREAMING_TYPE=NONE or not configured).
 */
public class NoOpPublisher implements MessagePublisher {

    @Override
    public void publish(RegistrationData data, Logger logger) {
        logger.fine("Streaming disabled, skipping message publish for: " + data.getId());
    }

    @Override
    public StreamingType getType() {
        return StreamingType.NONE;
    }
}