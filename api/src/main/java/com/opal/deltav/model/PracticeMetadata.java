package com.opal.deltav.model;

/**
 * POJO representing practice metadata loaded from Azure Table Storage.
 */
public class PracticeMetadata {
    private final String id;
    private final String clientId;
    private final String practiceId;
    private final String practiceName;
    private final String smsFromNumber;
    private final String logoName;
    private final boolean active;

    public PracticeMetadata(String id, String clientId, String practiceId, String practiceName,
                           String smsFromNumber, String logoName, boolean active) {
        this.id = id;
        this.clientId = clientId;
        this.practiceId = practiceId;
        this.practiceName = practiceName;
        this.smsFromNumber = smsFromNumber;
        this.logoName = logoName;
        this.active = active;
    }

    public String getId() {
        return id;
    }

    public String getClientId() {
        return clientId;
    }

    public String getPracticeId() {
        return practiceId;
    }

    public String getPracticeName() {
        return practiceName;
    }

    public String getSmsFromNumber() {
        return smsFromNumber;
    }

    public String getLogoName() {
        return logoName;
    }

    public boolean isActive() {
        return active;
    }

    @Override
    public String toString() {
        return "PracticeMetadata{" +
                "id='" + id + '\'' +
                ", clientId='" + clientId + '\'' +
                ", practiceId='" + practiceId + '\'' +
                ", practiceName='" + practiceName + '\'' +
                ", smsFromNumber='" + smsFromNumber + '\'' +
                ", logoName='" + logoName + '\'' +
                ", active=" + active +
                '}';
    }
}