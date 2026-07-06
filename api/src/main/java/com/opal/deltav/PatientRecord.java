package com.opal.deltav;

public final class PatientRecord {

    public final long patientKey;
    public final String phone;

    public PatientRecord(long patientKey, String phone) {
        this.patientKey = patientKey;
        this.phone = phone;
    }
}
