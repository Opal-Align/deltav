package com.opal.deltav.util;

import com.google.gson.Gson;
import com.google.gson.annotations.SerializedName;

/**
 * Decrypted schedule-link PII payload stored on Azure Table as pii_payload_enc/iv/key_id.
 */
public class ScheduleLinkPiiPayload {

    public static final int CURRENT_VERSION = 1;
    private static final Gson GSON = new Gson();

    private int v;

    @SerializedName("smsPhone")
    private String smsPhone;

    @SerializedName("patientId")
    private String patientId;

    @SerializedName("patientFirstName")
    private String patientFirstName;

    @SerializedName("patientLastName")
    private String patientLastName;

    @SerializedName("guarantorFirstName")
    private String guarantorFirstName;

    @SerializedName("guarantorLastName")
    private String guarantorLastName;

    @SerializedName("dob")
    private String dob;

    public ScheduleLinkPiiPayload() {
    }

    public static ScheduleLinkPiiPayload fromJson(String json) {
        if (json == null || json.isBlank()) {
            throw new IllegalArgumentException("JSON must not be blank");
        }
        ScheduleLinkPiiPayload payload = GSON.fromJson(json, ScheduleLinkPiiPayload.class);
        if (payload.v != CURRENT_VERSION) {
            throw new IllegalArgumentException("Unsupported PII payload version: " + payload.v);
        }
        if (payload.smsPhone == null || payload.smsPhone.isBlank()) {
            throw new IllegalArgumentException("smsPhone must not be blank");
        }
        return payload;
    }

    // Getters
    public int getV() {
        return v;
    }

    public String getSmsPhone() {
        return smsPhone;
    }

    public String getPatientId() {
        return patientId;
    }

    public String getPatientFirstName() {
        return patientFirstName;
    }

    public String getPatientLastName() {
        return patientLastName;
    }

    public String getGuarantorFirstName() {
        return guarantorFirstName;
    }

    public String getGuarantorLastName() {
        return guarantorLastName;
    }

    public String getDob() {
        return dob;
    }
}