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
    private final String patientFirstName;
    private final String patientMiddleName;
    private final String patientLastName;
    private final String dateOfBirth;
    private final String mobileNumber;
    private final List<String> preferredSlots;
    private final String comments;
    private final OffsetDateTime submittedAt;

    private QueueMessage(Builder builder) {
        this.patientKey = builder.patientKey;
        this.patientId = builder.patientId;
        this.practiceId = builder.practiceId;
        this.patientFirstName = builder.patientFirstName;
        this.patientMiddleName = builder.patientMiddleName;
        this.patientLastName = builder.patientLastName;
        this.dateOfBirth = builder.dateOfBirth;
        this.mobileNumber = builder.mobileNumber;
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

    public String getPatientFirstName() {
        return patientFirstName;
    }

    public String getPatientMiddleName() {
        return patientMiddleName;
    }

    public String getPatientLastName() {
        return patientLastName;
    }

    public String getDateOfBirth() {
        return dateOfBirth;
    }

    public String getMobileNumber() {
        return mobileNumber;
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
        private String patientFirstName;
        private String patientMiddleName;
        private String patientLastName;
        private String dateOfBirth;
        private String mobileNumber;
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

        public Builder patientFirstName(String patientFirstName) {
            this.patientFirstName = patientFirstName;
            return this;
        }

        public Builder patientMiddleName(String patientMiddleName) {
            this.patientMiddleName = patientMiddleName;
            return this;
        }

        public Builder patientLastName(String patientLastName) {
            this.patientLastName = patientLastName;
            return this;
        }

        public Builder dateOfBirth(String dateOfBirth) {
            this.dateOfBirth = dateOfBirth;
            return this;
        }

        public Builder mobileNumber(String mobileNumber) {
            this.mobileNumber = mobileNumber;
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