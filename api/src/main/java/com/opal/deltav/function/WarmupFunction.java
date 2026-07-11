package com.opal.deltav.function;

import com.microsoft.azure.functions.*;
import com.microsoft.azure.functions.annotation.*;
import com.opal.deltav.config.PracticeMetadataLoader;

import java.util.logging.Logger;

/**
 * Warmup function that runs during function app startup.
 * Eagerly loads practice metadata to avoid cold start latency.
 */
public class WarmupFunction {

    @FunctionName("warmup")
    public void run(
            @WarmupTrigger(name = "warmupTrigger") String warmupTrigger,
            final ExecutionContext context) {

        Logger logger = context.getLogger();
        logger.info("Warmup function triggered - initializing caches...");

        // Initialize practice metadata loader
        PracticeMetadataLoader.initialize();

        logger.info("Warmup complete - metadata cache initialized with " +
                PracticeMetadataLoader.getMetadataMap().size() + " entries");
    }
}