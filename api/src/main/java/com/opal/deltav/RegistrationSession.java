package com.opal.deltav;

public final class RegistrationSession {

    public static final String STATE_CREATED = "created";
    public static final String STATE_IDENTITY_VERIFIED = "identity_verified";
    public static final String STATE_OTP_VERIFIED = "otp_verified";

    public final String sessionId;
    public final String practiceId;
    public final String state;
    public final long patientKey;
    public final String phoneE164;

    public RegistrationSession(String sessionId, String practiceId, String state, long patientKey, String phoneE164) {
        this.sessionId = sessionId;
        this.practiceId = practiceId;
        this.state = state;
        this.patientKey = patientKey;
        this.phoneE164 = phoneE164;
    }

    public boolean isIdentityVerified() {
        return STATE_IDENTITY_VERIFIED.equals(state) || STATE_OTP_VERIFIED.equals(state);
    }

    public boolean isOtpVerified() {
        return STATE_OTP_VERIFIED.equals(state);
    }
}
