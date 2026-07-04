package com.opal.deltav.model;

import java.time.OffsetDateTime;
import java.util.UUID;

public class RegistrationData {
    private final String id;
    private final String practiceId;
    private final String registrant;
    private final String patientType;
    private final String firstName;
    private final String lastName;
    private final String dob;
    private final boolean confirmAccurate;
    private final boolean agreePrivacy;
    private final String redirectUrl;
    private final String relationship;
    private final String relationshipOther;
    private final OffsetDateTime submittedAt;

    private RegistrationData(Builder builder) {
        this.id = builder.id != null ? builder.id : UUID.randomUUID().toString();
        this.practiceId = builder.practiceId;
        this.registrant = builder.registrant;
        this.patientType = builder.patientType;
        this.firstName = builder.firstName;
        this.lastName = builder.lastName;
        this.dob = builder.dob;
        this.confirmAccurate = builder.confirmAccurate;
        this.agreePrivacy = builder.agreePrivacy;
        this.redirectUrl = builder.redirectUrl;
        this.relationship = builder.relationship;
        this.relationshipOther = builder.relationshipOther;
        this.submittedAt = builder.submittedAt != null ? builder.submittedAt : OffsetDateTime.now();
    }

    public String getId() { return id; }
    public String getPracticeId() { return practiceId; }
    public String getRegistrant() { return registrant; }
    public String getPatientType() { return patientType; }
    public String getFirstName() { return firstName; }
    public String getLastName() { return lastName; }
    public String getDob() { return dob; }
    public boolean isConfirmAccurate() { return confirmAccurate; }
    public boolean isAgreePrivacy() { return agreePrivacy; }
    public String getRedirectUrl() { return redirectUrl; }
    public String getRelationship() { return relationship; }
    public String getRelationshipOther() { return relationshipOther; }
    public OffsetDateTime getSubmittedAt() { return submittedAt; }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String id;
        private String practiceId;
        private String registrant;
        private String patientType;
        private String firstName;
        private String lastName;
        private String dob;
        private boolean confirmAccurate;
        private boolean agreePrivacy;
        private String redirectUrl;
        private String relationship;
        private String relationshipOther;
        private OffsetDateTime submittedAt;

        public Builder id(String id) { this.id = id; return this; }
        public Builder practiceId(String practiceId) { this.practiceId = practiceId; return this; }
        public Builder registrant(String registrant) { this.registrant = registrant; return this; }
        public Builder patientType(String patientType) { this.patientType = patientType; return this; }
        public Builder firstName(String firstName) { this.firstName = firstName; return this; }
        public Builder lastName(String lastName) { this.lastName = lastName; return this; }
        public Builder dob(String dob) { this.dob = dob; return this; }
        public Builder confirmAccurate(boolean confirmAccurate) { this.confirmAccurate = confirmAccurate; return this; }
        public Builder agreePrivacy(boolean agreePrivacy) { this.agreePrivacy = agreePrivacy; return this; }
        public Builder redirectUrl(String redirectUrl) { this.redirectUrl = redirectUrl; return this; }
        public Builder relationship(String relationship) { this.relationship = relationship; return this; }
        public Builder relationshipOther(String relationshipOther) { this.relationshipOther = relationshipOther; return this; }
        public Builder submittedAt(OffsetDateTime submittedAt) { this.submittedAt = submittedAt; return this; }

        public RegistrationData build() {
            return new RegistrationData(this);
        }
    }
}