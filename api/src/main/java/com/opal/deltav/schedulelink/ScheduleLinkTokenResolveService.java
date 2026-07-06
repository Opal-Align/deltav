package com.opal.deltav.schedulelink;

import java.time.OffsetDateTime;
import java.util.logging.Logger;

/** Resolves opaque schedule link tokens via {@link ScheduleLinkTokenTableReader}. */
public class ScheduleLinkTokenResolveService {

    private static final String UNKNOWN_PRACTICE = "Unknown Practice";
    private static final String STATUS_ACTIVE = "active";

    private final ScheduleLinkTokenTableReader tableReader;
    private final LogoBlobService logoBlobService;

    public ScheduleLinkTokenResolveService() {
        this(new ScheduleLinkTokenTableReader(), new LogoBlobService());
    }

    ScheduleLinkTokenResolveService(ScheduleLinkTokenTableReader tableReader, LogoBlobService logoBlobService) {
        this.tableReader = tableReader;
        this.logoBlobService = logoBlobService;
    }

    public ScheduleLinkPageResponse resolve(String plainToken, Logger logger) throws ResolveException {
        String tokenHash = OpaqueTokenUtil.hash(plainToken);

        ScheduleLinkTokenRecord row = tableReader.findByTokenHash(tokenHash, logger)
                .orElseThrow(() -> new ResolveException(ResolveFailure.FORGED));

        if (!STATUS_ACTIVE.equalsIgnoreCase(row.status())) {
            tableReader.incrementAttemptCount(tokenHash, row.attemptCount(), logger);
            throw new ResolveException(ResolveFailure.INVALID_STATUS);
        }

        if (row.expiresAt() != null && row.expiresAt().isBefore(OffsetDateTime.now())) {
            tableReader.incrementAttemptCount(tokenHash, row.attemptCount(), logger);
            throw new ResolveException(ResolveFailure.EXPIRED);
        }

        return new ScheduleLinkPageResponse(
                resolvePracticeName(row),
                logoBlobService.generateReadSasUrl(row.logoBlobPath(), logger));
    }

    static String resolvePracticeName(ScheduleLinkTokenRecord row) {
        String name = row.practiceName();
        if (name == null || name.isBlank()) {
            return UNKNOWN_PRACTICE;
        }
        return name.trim();
    }

    enum ResolveFailure {
        FORGED,
        INVALID_STATUS,
        EXPIRED
    }

    static class ResolveException extends Exception {
        private final ResolveFailure failure;

        ResolveException(ResolveFailure failure) {
            super(failure.name());
            this.failure = failure;
        }

        ResolveFailure getFailure() {
            return failure;
        }
    }

    public record ScheduleLinkPageResponse(String practiceName, String logoUrl) {
    }
}
