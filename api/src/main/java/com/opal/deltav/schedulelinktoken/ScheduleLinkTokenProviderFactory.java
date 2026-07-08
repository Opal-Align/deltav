package com.opal.deltav.schedulelinktoken;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Factory for creating ScheduleLinkTokenProvider instances based on configuration.
 *
 * Configure via environment variable SCHEDULE_LINK_TOKEN_PROVIDER_TYPE:
 * - TABLE_STORAGE (default) - Azure Table Storage
 * - COSMOS_DB - Azure Cosmos DB
 */
public class ScheduleLinkTokenProviderFactory {

    private static final Map<ScheduleLinkTokenProviderType, ScheduleLinkTokenProvider> providers = new ConcurrentHashMap<>();

    private ScheduleLinkTokenProviderFactory() {
    }

    /**
     * Gets the ScheduleLinkTokenProvider based on the SCHEDULE_LINK_TOKEN_PROVIDER_TYPE environment variable.
     * Defaults to TABLE_STORAGE if not configured.
     */
    public static ScheduleLinkTokenProvider getProvider() {
        String providerTypeEnv = Objects.toString( System.getenv("SCHEDULE_LINK_TOKEN_PROVIDER_TYPE"),
                ScheduleLinkTokenProviderType.TABLE_STORAGE.toString());
        ScheduleLinkTokenProviderType type = parseProviderType(providerTypeEnv);
        return getProvider(type);
    }

    /**
     * Gets the ScheduleLinkTokenProvider for the specified type.
     */
    public static ScheduleLinkTokenProvider getProvider(ScheduleLinkTokenProviderType type) {
        return providers.computeIfAbsent(type, ScheduleLinkTokenProviderFactory::createProvider);
    }

    private static ScheduleLinkTokenProviderType parseProviderType(String value) {
        if (value == null || value.isBlank()) {
            return ScheduleLinkTokenProviderType.TABLE_STORAGE;
        }
        try {
            return ScheduleLinkTokenProviderType.valueOf(value.toUpperCase().trim());
        } catch (IllegalArgumentException e) {
            return ScheduleLinkTokenProviderType.TABLE_STORAGE;
        }
    }

    private static ScheduleLinkTokenProvider createProvider(ScheduleLinkTokenProviderType type) {
        return switch (type) {
            case TABLE_STORAGE -> new TableScheduleLinkTokenProvider();
            case COSMOS_DB -> throw new UnsupportedOperationException(
                    "Provider type " + type + " is not yet implemented");
        };
    }
}