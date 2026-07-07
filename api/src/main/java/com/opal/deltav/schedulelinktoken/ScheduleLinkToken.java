package com.opal.deltav.schedulelinktoken;

import java.time.OffsetDateTime;
import java.util.Map;

/**
 * Model for schedule link token data.
 */
public class ScheduleLinkToken {

    private String tokenHash;
    private Long patientKey;
    private Long practiceId;
    private Long probFactKey;
    private Long communicationLogId;
    private String status;
    private OffsetDateTime expiresAt;
    private Integer attemptCount;
    private String lastSeenIp;
    private OffsetDateTime usedAt;
    private OffsetDateTime revokedAt;
    private String revokedReason;
    private OffsetDateTime createdAt;
    private String createdByUserId;
    private String practiceName;
    private String logoBlobPath;

    public ScheduleLinkToken() {}

    /**
     * Check if token is valid (active and not expired).
     */
    public boolean isValid() {
        // Check status
        if (!"active".equalsIgnoreCase(status)) {
            return false;
        }

        // Check if revoked
        if (revokedAt != null) {
            return false;
        }

        // Check expiry
        if (expiresAt != null && expiresAt.isBefore(OffsetDateTime.now())) {
            return false;
        }

        return true;
    }

    /**
     * Get validation error message if invalid.
     */
    public String getValidationError() {
        if (revokedAt != null) {
            return "Token has been revoked";
        }
        if (!"active".equalsIgnoreCase(status)) {
            return "Token is not active (status: " + status + ")";
        }
        if (expiresAt != null && expiresAt.isBefore(OffsetDateTime.now())) {
            return "Token has expired";
        }
        return null;
    }

    /**
     * Create from Map (from table storage).
     */
    public static ScheduleLinkToken fromMap(Map<String, Object> map) {
        ScheduleLinkToken token = new ScheduleLinkToken();

        token.tokenHash = getStringValue(map, "token_hash");
        token.patientKey = getLongValue(map, "patient_key");
        token.practiceId = getLongValue(map, "practice_id");
        token.probFactKey = getLongValue(map, "prob_fact_key");
        token.communicationLogId = getLongValue(map, "communication_log_id");
        token.status = getStringValue(map, "status");
        token.expiresAt = getDateTimeValue(map, "expires_at");
        token.attemptCount = getIntValue(map, "attempt_count");
        token.lastSeenIp = getStringValue(map, "last_seen_ip");
        token.usedAt = getDateTimeValue(map, "used_at");
        token.revokedAt = getDateTimeValue(map, "revoked_at");
        token.revokedReason = getStringValue(map, "revoked_reason");
        token.createdAt = getDateTimeValue(map, "created_at");
        token.createdByUserId = getStringValue(map, "created_by_user_id");
        token.practiceName = getStringValue(map, "practice_name");
        token.logoBlobPath = getStringValue(map, "logo_blob_path");

        return token;
    }

    private static String getStringValue(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value != null ? value.toString() : null;
    }

    private static Long getLongValue(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value == null) return null;
        if (value instanceof Long) return (Long) value;
        if (value instanceof Number) return ((Number) value).longValue();
        try {
            return Long.parseLong(value.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Integer getIntValue(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value == null) return null;
        if (value instanceof Integer) return (Integer) value;
        if (value instanceof Number) return ((Number) value).intValue();
        try {
            return Integer.parseInt(value.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static OffsetDateTime getDateTimeValue(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value == null) return null;
        if (value instanceof OffsetDateTime) return (OffsetDateTime) value;
        try {
            return OffsetDateTime.parse(value.toString());
        } catch (Exception e) {
            return null;
        }
    }

    // Getters
    public String getTokenHash() { return tokenHash; }
    public Long getPatientKey() { return patientKey; }
    public Long getPracticeId() { return practiceId; }
    public Long getProbFactKey() { return probFactKey; }
    public Long getCommunicationLogId() { return communicationLogId; }
    public String getStatus() { return status; }
    public OffsetDateTime getExpiresAt() { return expiresAt; }
    public Integer getAttemptCount() { return attemptCount; }
    public String getLastSeenIp() { return lastSeenIp; }
    public OffsetDateTime getUsedAt() { return usedAt; }
    public OffsetDateTime getRevokedAt() { return revokedAt; }
    public String getRevokedReason() { return revokedReason; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public String getCreatedByUserId() { return createdByUserId; }
    public String getPracticeName() { return practiceName; }
    public String getLogoBlobPath() { return logoBlobPath; }

    // Setters
    public void setTokenHash(String tokenHash) { this.tokenHash = tokenHash; }
    public void setPatientKey(Long patientKey) { this.patientKey = patientKey; }
    public void setPracticeId(Long practiceId) { this.practiceId = practiceId; }
    public void setProbFactKey(Long probFactKey) { this.probFactKey = probFactKey; }
    public void setCommunicationLogId(Long communicationLogId) { this.communicationLogId = communicationLogId; }
    public void setStatus(String status) { this.status = status; }
    public void setExpiresAt(OffsetDateTime expiresAt) { this.expiresAt = expiresAt; }
    public void setAttemptCount(Integer attemptCount) { this.attemptCount = attemptCount; }
    public void setLastSeenIp(String lastSeenIp) { this.lastSeenIp = lastSeenIp; }
    public void setUsedAt(OffsetDateTime usedAt) { this.usedAt = usedAt; }
    public void setRevokedAt(OffsetDateTime revokedAt) { this.revokedAt = revokedAt; }
    public void setRevokedReason(String revokedReason) { this.revokedReason = revokedReason; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
    public void setCreatedByUserId(String createdByUserId) { this.createdByUserId = createdByUserId; }
    public void setPracticeName(String practiceName) { this.practiceName = practiceName; }
    public void setLogoBlobPath(String logoBlobPath) { this.logoBlobPath = logoBlobPath; }
}