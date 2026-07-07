package com.opal.deltav;

public final class RegistrationSession {

    public static final String STATE_CREATED = "created";
    public static final String STATE_MOBILE_BOUND = "mobile_bound";
    public static final String STATE_OTP_VERIFIED = "otp_verified";

    public final String sessionId;
    public final String practiceId;
    public final String state;
    public final String phoneE164;

    public RegistrationSession(String sessionId, String practiceId, String state, String phoneE164) {
        this.sessionId = sessionId;
        this.practiceId = practiceId;
        this.state = state;
        this.phoneE164 = phoneE164;
    }

    public boolean isMobileBound() {
        return STATE_MOBILE_BOUND.equals(state) || STATE_OTP_VERIFIED.equals(state);
    }

    public boolean isOtpVerified() {
        return STATE_OTP_VERIFIED.equals(state);
    }
}
