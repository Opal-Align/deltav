package com.opal.deltav.model;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Message sent to the Azure Storage Queue for processing by the Python worker.
 * Contains only the fields required by the worker.
 */
public class QueueMessage {
    private final Long patientKey;
    private final String patientId;
    private final Long practiceId;
    private final List<String> preferredSlots;
    private final String comments;
    private final OffsetDateTime submittedAt;

    private QueueMessage(Builder builder) {
        this.patientKey = builder.patientKey;
        this.patientId = builder.patientId;
        this.practiceId = builder.practiceId;
        this.preferredSlots = builder.preferredSlots;
        this.comments = builder.comments;
        this.submittedAt = builder.submittedAt != null ? builder.submittedAt : OffsetDateTime.now();
    }

    public Long getPatientKey() {
        return patientKey;
    }

    public String getPatientId() {
        return patientId;
    }

    public Long getPracticeId() {
        return practiceId;
    }

    public List<String> getPreferredSlots() {
        return preferredSlots;
    }

    public String getComments() {
        return comments;
    }

    public OffsetDateTime getSubmittedAt() {
        return submittedAt;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private Long patientKey;
        private String patientId;
        private Long practiceId;
        private List<String> preferredSlots;
        private String comments;
        private OffsetDateTime submittedAt;

        public Builder patientKey(Long patientKey) {
            this.patientKey = patientKey;
            return this;
        }

        public Builder patientId(String patientId) {
            this.patientId = patientId;
            return this;
        }

        public Builder practiceId(Long practiceId) {
            this.practiceId = practiceId;
            return this;
        }

        public Builder preferredSlots(List<String> preferredSlots) {
            this.preferredSlots = preferredSlots;
            return this;
        }

        public Builder comments(String comments) {
            this.comments = comments;
            return this;
        }

        public Builder submittedAt(OffsetDateTime submittedAt) {
            this.submittedAt = submittedAt;
            return this;
        }

        public QueueMessage build() {
            return new QueueMessage(this);
        }
    }
}