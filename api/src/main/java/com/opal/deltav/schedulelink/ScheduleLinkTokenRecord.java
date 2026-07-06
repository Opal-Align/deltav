package com.opal.deltav.schedulelink;

import java.time.OffsetDateTime;

/** Row read from Azure Table {@code ScheduleLinkTokens}. */
public record ScheduleLinkTokenRecord(
        String tokenHash,
        String status,
        OffsetDateTime expiresAt,
        int attemptCount,
        String practiceName,
        String logoBlobPath) {
}
