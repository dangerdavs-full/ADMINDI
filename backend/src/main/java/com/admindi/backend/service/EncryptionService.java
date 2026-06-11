package com.admindi.backend.service;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Locale;

/**
 * AES-256-GCM encryption service for PII at rest.
 *
 * Versioned format: "enc:v1:" + Base64( IV[12] || ciphertext || tag[16] )
 * Key: 32-byte Base64-encoded value from ENCRYPTION_KEY env var.
 *
 * Behavior:
 * - No prefix  → legacy plaintext, returned as-is (migration pending)
 * - "enc:v1:"  → decrypt; if fails → THROW (corrupted or wrong key)
 * - Disabled   → passthrough (solo perfiles exclusivamente dev/test/local/qa/secrets, sin token {@code prod})
 * - Perfil activo contiene {@code prod} o no es solo dev/test/local/qa/secrets → clave obligatoria; ausente / Base64 inválida / ≠32 bytes → fail-fast al arranque
 */
@Service
public class EncryptionService {

    private static final Logger logger = LoggerFactory.getLogger(EncryptionService.class);

    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int IV_LENGTH = 12;
    private static final int TAG_LENGTH_BITS = 128;
    private static final String ENCRYPTED_PREFIX = "enc:v1:";

    private final SecureRandom secureRandom = new SecureRandom();

    @Value("${app.encryption.key:}")
    private String encryptionKeyBase64;

    @Value("${spring.profiles.active:dev}")
    private String activeProfile;

    private SecretKey secretKey;

    @PostConstruct
    public void init() {
        boolean prodProfileActive = profileTokensContainProd(activeProfile);
        boolean onlyRelaxedProfiles = profileTokensAreOnlyRelaxed(activeProfile);

        if (encryptionKeyBase64 == null || encryptionKeyBase64.isBlank()) {
            // Si `prod` está activo, la clave es obligatoria aunque otro token sea `qa` (p. ej. prod,qa por error).
            if (prodProfileActive || !onlyRelaxedProfiles) {
                throw new RuntimeException(
                        "[ENCRYPTION] FATAL: ENCRYPTION_KEY is required when profile 'prod' is active, "
                                + "or when the active profile is not limited to dev/test/local/qa/secrets. "
                                + "Set the ENCRYPTION_KEY environment variable with a 32-byte Base64 key.");
            }
            logger.warn("[ENCRYPTION] No encryption key configured. "
                    + "Encryption is DISABLED (dev/test/local/qa/secrets). Set ENCRYPTION_KEY for production.");
            this.secretKey = null;
            return;
        }

        try {
            byte[] keyBytes = Base64.getDecoder().decode(encryptionKeyBase64);
            if (keyBytes.length != 32) {
                throw new IllegalArgumentException(
                        "ENCRYPTION_KEY must be exactly 32 bytes (256 bits) when decoded. Got: " + keyBytes.length);
            }
            this.secretKey = new SecretKeySpec(keyBytes, "AES");
            logger.info("[ENCRYPTION] AES-256-GCM encryption initialized successfully (prefix: {})", ENCRYPTED_PREFIX);
        } catch (IllegalArgumentException e) {
            throw new RuntimeException("[ENCRYPTION] Invalid ENCRYPTION_KEY: " + e.getMessage(), e);
        }
    }

    /** True if any comma-separated profile token equals {@code prod} (case-insensitive). */
    private static boolean profileTokensContainProd(String profiles) {
        if (profiles == null || profiles.isBlank()) {
            return false;
        }
        for (String p : profiles.split(",")) {
            if ("prod".equalsIgnoreCase(p.trim())) {
                return true;
            }
        }
        return false;
    }

    /**
     * True only if every non-empty token es uno de dev/test/local/qa/secrets (lowercase).
     * {@code secrets} es un perfil contenedor de credenciales (SMTP, Twilio) que se activa
     * junto a {@code local} en desarrollo; no eleva el tier de despliegue. {@code prod}
     * se valida por separado y siempre requiere clave.
     * Empty / unknown profile string → false (fail-safe toward requiring a key when ambiguous).
     */
    private static boolean profileTokensAreOnlyRelaxed(String profiles) {
        if (profiles == null || profiles.isBlank()) {
            return false;
        }
        String[] parts = profiles.split(",");
        boolean any = false;
        for (String raw : parts) {
            String t = raw.trim().toLowerCase(Locale.ROOT);
            if (t.isEmpty()) {
                continue;
            }
            any = true;
            if (!t.equals("dev") && !t.equals("test") && !t.equals("local")
                    && !t.equals("qa") && !t.equals("secrets")) {
                return false;
            }
        }
        return any;
    }

    /** Returns true if encryption is active (key is configured). */
    public boolean isEnabled() {
        return secretKey != null;
    }

    /**
     * Encrypt a plaintext string.
     * Returns "enc:v1:" + Base64(IV || ciphertext || tag).
     * Returns null for null input. Returns plaintext if encryption is disabled.
     */
    public String encrypt(String plaintext) {
        if (plaintext == null) return null;
        if (secretKey == null) return plaintext;

        // Already encrypted — don't double-encrypt
        if (plaintext.startsWith(ENCRYPTED_PREFIX)) return plaintext;

        try {
            byte[] iv = new byte[IV_LENGTH];
            secureRandom.nextBytes(iv);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, new GCMParameterSpec(TAG_LENGTH_BITS, iv));

            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            ByteBuffer buffer = ByteBuffer.allocate(IV_LENGTH + ciphertext.length);
            buffer.put(iv);
            buffer.put(ciphertext);

            return ENCRYPTED_PREFIX + Base64.getEncoder().encodeToString(buffer.array());
        } catch (Exception e) {
            throw new RuntimeException("[ENCRYPTION] Encrypt failed", e);
        }
    }

    /**
     * Decrypt a value. Behavior by case:
     * 1. null → null
     * 2. No prefix "enc:v1:" → legacy plaintext, return as-is
     * 3. Has "enc:v1:" prefix → strip prefix, decrypt. If fails → THROW (wrong key or corrupt)
     * 4. Encryption disabled → return as-is
     */
    public String decrypt(String storedValue) {
        if (storedValue == null) return null;
        if (secretKey == null) return stripPrefixIfPresent(storedValue);

        // Case 2: No prefix → legacy plaintext, return as-is
        if (!storedValue.startsWith(ENCRYPTED_PREFIX)) {
            return storedValue;
        }

        // Case 3: Has prefix → must decrypt successfully or throw
        String base64Part = storedValue.substring(ENCRYPTED_PREFIX.length());
        try {
            byte[] decoded = Base64.getDecoder().decode(base64Part);

            if (decoded.length < IV_LENGTH + 16 + 1) {
                throw new RuntimeException("[ENCRYPTION] Encrypted value too short — corrupted data");
            }

            ByteBuffer buffer = ByteBuffer.wrap(decoded);
            byte[] iv = new byte[IV_LENGTH];
            buffer.get(iv);
            byte[] ciphertext = new byte[buffer.remaining()];
            buffer.get(ciphertext);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, secretKey, new GCMParameterSpec(TAG_LENGTH_BITS, iv));

            byte[] plainBytes = cipher.doFinal(ciphertext);
            return new String(plainBytes, StandardCharsets.UTF_8);
        } catch (RuntimeException e) {
            throw e; // re-throw our own exceptions
        } catch (Exception e) {
            throw new RuntimeException(
                    "[ENCRYPTION] Failed to decrypt value with prefix 'enc:v1:'. "
                            + "Wrong ENCRYPTION_KEY or corrupted data.", e);
        }
    }

    private String stripPrefixIfPresent(String value) {
        return value.startsWith(ENCRYPTED_PREFIX) ? value.substring(ENCRYPTED_PREFIX.length()) : value;
    }
}
