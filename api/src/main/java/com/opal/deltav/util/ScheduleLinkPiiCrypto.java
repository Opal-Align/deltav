package com.opal.deltav.util;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * AES-256-GCM decryption for schedule link PII JSON payloads from Azure Table.
 */
public final class ScheduleLinkPiiCrypto {

    public static final int DEK_LENGTH_BYTES = 32;
    public static final int GCM_IV_LENGTH_BYTES = 12;
    public static final int GCM_TAG_LENGTH_BITS = 128;
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";

    private ScheduleLinkPiiCrypto() {
    }

    /**
     * Decrypt PII payload from Azure Table.
     *
     * @param encBase64 Base64 encoded ciphertext
     * @param ivBase64  Base64 encoded IV
     * @param dek       Data Encryption Key (32 bytes)
     * @return Decrypted ScheduleLinkPiiPayload
     */
    public static ScheduleLinkPiiPayload decryptPayload(String encBase64, String ivBase64, byte[] dek) {
        return ScheduleLinkPiiPayload.fromJson(decrypt(encBase64, ivBase64, dek));
    }

    /**
     * Decrypt Base64 encoded ciphertext to plain JSON string.
     */
    public static String decrypt(String encBase64, String ivBase64, byte[] dek) {
        validateDek(dek);
        if (encBase64 == null || encBase64.isBlank() || ivBase64 == null || ivBase64.isBlank()) {
            throw new IllegalArgumentException("enc and iv must not be blank");
        }
        try {
            byte[] ciphertext = Base64.getDecoder().decode(encBase64);
            byte[] iv = Base64.getDecoder().decode(ivBase64);
            if (iv.length != GCM_IV_LENGTH_BYTES) {
                throw new IllegalArgumentException("IV must decode to " + GCM_IV_LENGTH_BYTES + " bytes");
            }
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(dek, "AES"), new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv));
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to decrypt schedule link PII payload", ex);
        }
    }

    /**
     * Decode Base64 encoded DEK.
     */
    public static byte[] decodeDekBase64(String dekBase64) {
        if (dekBase64 == null || dekBase64.isBlank()) {
            throw new IllegalArgumentException("DEK Base64 must not be blank");
        }
        byte[] dek = Base64.getDecoder().decode(dekBase64.trim());
        validateDek(dek);
        return dek;
    }

    private static void validateDek(byte[] dek) {
        if (dek == null || dek.length != DEK_LENGTH_BYTES) {
            throw new IllegalArgumentException("DEK must be " + DEK_LENGTH_BYTES + " bytes");
        }
    }
}