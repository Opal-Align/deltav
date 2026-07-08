package com.opal.deltav.util;

import com.azure.identity.DefaultAzureCredentialBuilder;
import com.azure.security.keyvault.secrets.SecretClient;
import com.azure.security.keyvault.secrets.SecretClientBuilder;

import java.util.Base64;
import java.util.logging.Logger;

/**
 * Service for fetching secrets from Azure Key Vault.
 */
public final class KeyVaultService {

    private static volatile SecretClient secretClient;
    private static volatile byte[] cachedDek;
    private static final Object lock = new Object();

    private KeyVaultService() {
    }

    /**
     * Get Data Encryption Key (DEK) for PII decryption.
     * Fetches from Key Vault and caches in memory.
     *
     * @param logger Logger for error messages
     * @return DEK as byte array, or null if not configured/available
     */
    public static byte[] getDek(Logger logger) {
        if (cachedDek != null) {
            return cachedDek;
        }

        synchronized (lock) {
            if (cachedDek != null) {
                return cachedDek;
            }

            // Check for direct Base64 DEK in environment (for local development)
            String dekBase64 = System.getenv("PII_DEK_BASE64");
            if (dekBase64 != null && !dekBase64.isBlank()) {
                logger.info("Using PII DEK from environment variable");
                cachedDek = ScheduleLinkPiiCrypto.decodeDekBase64(dekBase64);
                return cachedDek;
            }

            // Fetch from Key Vault
            String vaultUrl = System.getenv("KEY_VAULT_URL");
            String secretName = System.getenv("PII_DEK_SECRET_NAME");

            if (vaultUrl == null || vaultUrl.isBlank()) {
                logger.warning("KEY_VAULT_URL is not configured - PII decryption disabled");
                return null;
            }

            if (secretName == null || secretName.isBlank()) {
                secretName = "schedule-link-pii-dek"; // default secret name
            }

            try {
                SecretClient client = getSecretClient(vaultUrl, logger);
                String secretValue = client.getSecret(secretName).getValue();
                cachedDek = ScheduleLinkPiiCrypto.decodeDekBase64(secretValue);
                logger.info("Successfully fetched PII DEK from Key Vault");
                return cachedDek;
            } catch (Exception e) {
                logger.severe("Failed to fetch PII DEK from Key Vault: " + e.getMessage());
                return null;
            }
        }
    }

    /**
     * Check if DEK is available (either from env or Key Vault).
     */
    public static boolean isDekAvailable() {
        String dekBase64 = System.getenv("PII_DEK_BASE64");
        if (dekBase64 != null && !dekBase64.isBlank()) {
            return true;
        }

        String vaultUrl = System.getenv("KEY_VAULT_URL");
        return vaultUrl != null && !vaultUrl.isBlank();
    }

    /**
     * Clear cached DEK (for testing or key rotation).
     */
    public static void clearCache() {
        synchronized (lock) {
            cachedDek = null;
        }
    }

    private static SecretClient getSecretClient(String vaultUrl, Logger logger) {
        if (secretClient == null) {
            synchronized (lock) {
                if (secretClient == null) {
                    logger.info("Initializing Key Vault client for: " + vaultUrl);
                    secretClient = new SecretClientBuilder()
                            .vaultUrl(vaultUrl)
                            .credential(new DefaultAzureCredentialBuilder().build())
                            .buildClient();
                }
            }
        }
        return secretClient;
    }
}