package com.opal.deltav;

/**
 * Phone number normalization and E.164 formatting for OTP SMS (US numbers).
 */
public final class PhoneUtil {

    private static final String DEFAULT_COUNTRY_CODE = "1";

    private PhoneUtil() {}

    public static String countryCode() {
        String code = System.getenv("SMS_COUNTRY_CODE");
        if (code == null || code.isBlank()) {
            code = System.getenv("OTP_COUNTRY_CODE");
        }
        if (code == null || code.isBlank()) {
            return DEFAULT_COUNTRY_CODE;
        }
        return code.replaceAll("\\D", "");
    }

    /**
     * Normalizes to national number without country code (10 digits for US).
     */
    public static String normalizeMobile(String mobile) {
        if (mobile == null) return null;
        String digits = mobile.replaceAll("\\D", "");
        if (digits.isEmpty()) return null;

        String cc = countryCode();
        if (digits.startsWith(cc) && digits.length() == cc.length() + 10) {
            digits = digits.substring(cc.length());
        } else if ("1".equals(cc) && digits.length() == 11 && digits.startsWith("1")) {
            digits = digits.substring(1);
        }

        return digits.length() == 10 ? digits : null;
    }

    public static String toE164(String mobile) {
        String normalized = normalizeMobile(mobile);
        if (normalized == null) return null;
        return "+" + countryCode() + normalized;
    }
}
