package com.opal.deltav.streaming;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Factory for creating MessagePublisher instances based on configuration.
 *
 * Configure via environment variable STREAMING_TYPE:
 * - NONE (default) - No streaming
 * - EVENT_HUB - Publish to Azure Event Hub
 * - QUEUE_STORAGE - Publish to Azure Queue Storage
 */
public class MessagePublisherFactory {

    private static final Map<StreamingType, MessagePublisher> publishers = new ConcurrentHashMap<>();

    private MessagePublisherFactory() {
    }

    /**
     * Gets the MessagePublisher based on the STREAMING_TYPE environment variable.
     * Defaults to NONE (no streaming) if not configured.
     */
    public static MessagePublisher getPublisher() {
        String streamingTypeEnv = System.getenv("STREAMING_TYPE");
        StreamingType type = parseStreamingType(streamingTypeEnv);
        return getPublisher(type);
    }

    /**
     * Gets the MessagePublisher for the specified streaming type.
     */
    public static MessagePublisher getPublisher(StreamingType type) {
        return publishers.computeIfAbsent(type, MessagePublisherFactory::createPublisher);
    }

    /**
     * Checks if streaming is enabled based on configuration.
     */
    public static boolean isStreamingEnabled() {
        String streamingTypeEnv = System.getenv("STREAMING_TYPE");
        StreamingType type = parseStreamingType(streamingTypeEnv);
        return type != StreamingType.NONE;
    }

    private static StreamingType parseStreamingType(String value) {
        if (value == null || value.isBlank()) {
            return StreamingType.NONE;
        }
        try {
            return StreamingType.valueOf(value.toUpperCase().trim());
        } catch (IllegalArgumentException e) {
            return StreamingType.NONE;
        }
    }

    private static MessagePublisher createPublisher(StreamingType type) {
        return switch (type) {
            case NONE -> new NoOpPublisher();
            case QUEUE_STORAGE -> new QueueStoragePublisher();
            case EVENT_HUB -> throw new UnsupportedOperationException(
                    "Streaming type " + type + " is not yet implemented");
        };
    }
}