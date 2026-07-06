package com.opal.deltav;

import com.azure.communication.sms.SmsClient;
import com.azure.communication.sms.SmsClientBuilder;
import com.azure.communication.sms.models.SmsSendOptions;
import com.azure.communication.sms.models.SmsSendResult;

import java.util.logging.Logger;

/**
 * Sends OTP SMS via Azure Communication Services — same provider used by backend-service SmsService.
 */
public final class SmsOtpSender {

    private static final Logger LOGGER = Logger.getLogger(SmsOtpSender.class.getName());
    private static volatile SmsClient client;
    private static final Object clientLock = new Object();

    private SmsOtpSender() {}

    public static SmsDeliveryResult sendOtp(String practiceId, String mobile, String otp) {
        String toNumber = PhoneUtil.toE164(mobile);
        if (toNumber == null) {
            return SmsDeliveryResult.failed("invalid_mobile");
        }
        return sendOtpToE164(practiceId, toNumber, otp);
    }

    public static SmsDeliveryResult sendOtpToE164(String practiceId, String toNumber, String otp) {
        if (isSmsDisabled()) {
            LOGGER.info("SMS sending disabled; OTP not sent via SMS for practice=" + practiceId);
            return SmsDeliveryResult.skipped();
        }

        String connectionString = getConnectionString();
        if (connectionString == null || connectionString.isBlank()) {
            throw new IllegalStateException("AZURE_COMMUNICATION_CONNECTION_STRING is not configured");
        }

        String fromNumber = resolveFromNumber();
        if (fromNumber == null || fromNumber.isBlank()) {
            throw new IllegalStateException("SMS_DEFAULT_FROM_NUMBER is not configured");
        }

        String message = "Your verification code is " + otp + ". It expires in 1 minute. Do not share this code.";
        try {
            SmsSendResult result = getClient(connectionString).send(
                    fromNumber,
                    toNumber,
                    message,
                    new SmsSendOptions().setDeliveryReportEnabled(true));

            if (result.isSuccessful()) {
                LOGGER.info("OTP SMS accepted by ACS for " + maskDestination(toNumber) +
                        " messageId=" + result.getMessageId());
                return SmsDeliveryResult.sent();
            }

            String error = result.getErrorMessage() != null ? result.getErrorMessage() : "sms_send_failed";
            LOGGER.warning("OTP SMS failed for " + maskDestination(toNumber) + ": " + error);
            return SmsDeliveryResult.failed(error);
        } catch (Exception e) {
            LOGGER.severe("OTP SMS error: " + e.getClass().getName() + " - " + e.getMessage());
            return SmsDeliveryResult.failed("sms_send_failed");
        }
    }

    public static boolean isSmsDisabled() {
        String flag = System.getenv("SMS_DISABLE");
        return flag != null && ("true".equalsIgnoreCase(flag) || "1".equals(flag));
    }

    public static boolean isConfigured() {
        if (isSmsDisabled()) return false;
        String conn = getConnectionString();
        String from = resolveFromNumber();
        return conn != null && !conn.isBlank() && from != null && !from.isBlank();
    }

    private static String resolveFromNumber() {
        String from = System.getenv("SMS_DEFAULT_FROM_NUMBER");
        if (from == null || from.isBlank()) {
            from = System.getenv("AZURE_SMS_FROM_PHONE");
        }
        if (from == null || from.isBlank()) return null;
        from = from.trim();
        if (from.startsWith("+")) return from;
        return "+" + from.replaceAll("\\D", "");
    }

    private static String getConnectionString() {
        String conn = System.getenv("AZURE_COMMUNICATION_CONNECTION_STRING");
        if (conn == null || conn.isBlank()) {
            conn = System.getenv("AZURE_SMS_CONNECTION_STRING");
        }
        return conn;
    }

    private static SmsClient getClient(String connectionString) {
        if (client == null) {
            synchronized (clientLock) {
                if (client == null) {
                    client = new SmsClientBuilder()
                            .connectionString(connectionString)
                            .buildClient();
                }
            }
        }
        return client;
    }

    private static String maskDestination(String e164) {
        if (e164 == null || e164.length() < 6) return "****";
        return e164.substring(0, Math.min(4, e164.length())) + "******" + e164.substring(e164.length() - 4);
    }

    public static final class SmsDeliveryResult {
        public final boolean sent;
        public final boolean skipped;
        public final String error;

        private SmsDeliveryResult(boolean sent, boolean skipped, String error) {
            this.sent = sent;
            this.skipped = skipped;
            this.error = error;
        }

        public static SmsDeliveryResult sent() {
            return new SmsDeliveryResult(true, false, null);
        }

        public static SmsDeliveryResult skipped() {
            return new SmsDeliveryResult(false, true, null);
        }

        public static SmsDeliveryResult failed(String error) {
            return new SmsDeliveryResult(false, false, error);
        }
    }
}
