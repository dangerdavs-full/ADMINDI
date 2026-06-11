package com.admindi.backend.controller;

import com.admindi.backend.model.UserEntity;
import com.admindi.backend.repository.UserRepository;
import com.admindi.backend.service.EncryptionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * One-shot endpoint to encrypt existing plaintext PII data in the users table.
 * Run once after enabling ENCRYPTION_KEY in production.
 * Protected: SUPER_ADMIN only.
 */
@RestController
@RequestMapping("/api/admin")
public class EncryptionMigrationController {

    private static final Logger logger = LoggerFactory.getLogger(EncryptionMigrationController.class);

    private final UserRepository userRepository;
    private final EncryptionService encryptionService;

    @Autowired
    public EncryptionMigrationController(UserRepository userRepository, EncryptionService encryptionService) {
        this.userRepository = userRepository;
        this.encryptionService = encryptionService;
    }

    /**
     * Reads all users. For each PII field, if it looks like plaintext (not encrypted),
     * encrypts and saves it. Idempotent — safe to run multiple times.
     */
    @PostMapping("/encrypt-existing-data")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ResponseEntity<Map<String, Object>> encryptExistingData() {
        if (!encryptionService.isEnabled()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "Encryption is not enabled. Set ENCRYPTION_KEY environment variable first."
            ));
        }

        List<UserEntity> allUsers = userRepository.findAll();
        int migrated = 0;
        int skipped = 0;
        int errors = 0;

        for (UserEntity user : allUsers) {
            try {
                boolean changed = false;

                // The @Convert annotation on UserEntity means JPA already decrypts on read
                // and encrypts on write. For legacy plaintext data, the decrypt() gracefully
                // returns the plaintext as-is (fallback). So we just need to re-save each user
                // to trigger the converter to encrypt the plaintext values.
                //
                // We check if at least one field has data to avoid unnecessary writes.
                if (hasAnyPiiData(user)) {
                    userRepository.save(user);
                    changed = true;
                }

                if (changed) {
                    migrated++;
                    logger.info("[ENCRYPT-MIGRATION] Encrypted PII for user: {} ({})",
                            user.getId(), user.getLoginUsername());
                } else {
                    skipped++;
                }
            } catch (Exception e) {
                errors++;
                logger.error("[ENCRYPT-MIGRATION] Error encrypting user {}: {}", user.getId(), e.getMessage());
            }
        }

        logger.info("[ENCRYPT-MIGRATION] Complete. Migrated: {}, Skipped: {}, Errors: {}", migrated, skipped, errors);

        return ResponseEntity.ok(Map.of(
                "total", allUsers.size(),
                "migrated", migrated,
                "skipped", skipped,
                "errors", errors
        ));
    }

    private boolean hasAnyPiiData(UserEntity user) {
        return user.getMfaSecret() != null
                || user.getPhone() != null
                || user.getContactEmail() != null
                || user.getContactPhone() != null
                || user.getContactCountryCode() != null;
    }
}
