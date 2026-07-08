package com.opal.deltav.model;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public class RegistrationData {
    private final String id;
    private final String clientId;
    private final String practiceId;
    private final String mobileNumber;
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
    private final List<String> preferredSlots;
    private final String comments;
    private final OffsetDateTime submittedAt;

    private RegistrationData(Builder builder) {
        this.id = builder.id != null ? builder.id : UUID.randomUUID().toString();
        this.clientId = builder.clientId;
        this.practiceId = builder.practiceId;
        this.mobileNumber = builder.mobileNumber;
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
        this.preferredSlots = builder.preferredSlots;
        this.comments = builder.comments;
        this.submittedAt = builder.submittedAt != null ? builder.submittedAt : OffsetDateTime.now();
    }

    public String getId() { return id; }
    public String getClientId() { return clientId; }
    public String getPracticeId() { return practiceId; }
    public String getMobileNumber() { return mobileNumber; }
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
    public List<String> getPreferredSlots() { return preferredSlots; }
    public String getComments() { return comments; }
    public OffsetDateTime getSubmittedAt() { return submittedAt; }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String id;
        private String clientId;
        private String practiceId;
        private String mobileNumber;
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
        private List<String> preferredSlots;
        private String comments;
        private OffsetDateTime submittedAt;

        public Builder id(String id) { this.id = id; return this; }
        public Builder clientId(String clientId) { this.clientId = clientId; return this; }
        public Builder practiceId(String practiceId) { this.practiceId = practiceId; return this; }
        public Builder mobileNumber(String mobileNumber) { this.mobileNumber = mobileNumber; return this; }
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
        public Builder preferredSlots(List<String> preferredSlots) { this.preferredSlots = preferredSlots; return this; }
        public Builder comments(String comments) { this.comments = comments; return this; }
        public Builder submittedAt(OffsetDateTime submittedAt) { this.submittedAt = submittedAt; return this; }

        public RegistrationData build() {
            return new RegistrationData(this);
        }
    }
}