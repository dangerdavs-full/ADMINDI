package com.admindi.backend.service;

import com.admindi.backend.model.AuditEventEntity;
import com.admindi.backend.model.UserEntity;
import com.admindi.backend.repository.AuditEventRepository;
import com.admindi.backend.repository.UserRepository;
import dev.samstevens.totp.code.CodeVerifier;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Centralized reauth service.
 * Validates password + MFA (if enabled) for sensitive operations.
 * Audits every attempt (success/failure) with IP and User-Agent.
 *
 * Used by: PropertyService (delete), OwnerService (purge), PermissionController (grants).
 */
@Service
public class ReauthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final CodeVerifier codeVerifier;
    private final AuditEventRepository auditRepo;

    public ReauthService(UserRepository userRepository,
                         PasswordEncoder passwordEncoder,
                         CodeVerifier codeVerifier,
                         AuditEventRepository auditRepo) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.codeVerifier = codeVerifier;
        this.auditRepo = auditRepo;
    }

    /**
     * Verify reauth for the currently authenticated user.
     *
     * @param password  User's current password (required)
     * @param mfaCode   TOTP code (required if user has MFA enabled)
     * @param operation Human-readable operation name for audit (e.g. "PURGE_OWNER", "PROPERTY_DELETE")
     * @throws RuntimeException if verification fails
     */
    public void verifyReauth(String password, String mfaCode, String operation) {
        // V48: auth.getName() es el username del actor. Ver docs/ARCHITECTURE_IDENTITY_AND_EXPEDIENTES.md §3.2.
        String actorUsername = SecurityContextHolder.getContext().getAuthentication().getName();
        UserEntity user = userRepository.findByUsername(actorUsername)
                .orElseThrow(() -> new RuntimeException("Usuario no encontrado."));

        verifyReauthForUser(user, password, mfaCode, operation);
    }

    /**
     * Verify reauth for a specific user (used when the actor is known).
     */
    public void verifyReauthForUser(UserEntity user, String password, String mfaCode, String operation) {
        HttpServletRequest httpReq = getHttpRequest();
        String ip = httpReq != null ? httpReq.getRemoteAddr() : "unknown";
        String ua = httpReq != null ? httpReq.getHeader("User-Agent") : "unknown";

        // V54: el actorId del audit es el username del user (único, V48).
        String actorUsername = user.getLoginUsername();
        if (password == null || password.isBlank()) {
            auditReauthAttempt(actorUsername, operation, false, "No password provided", ip, ua);
            throw new RuntimeException("Se requiere contraseña para esta operación.");
        }
        if (!passwordEncoder.matches(password, user.getPassword())) {
            auditReauthAttempt(actorUsername, operation, false, "Password mismatch", ip, ua);
            throw new RuntimeException("Contraseña incorrecta.");
        }
        if (user.isMfaEnabled()) {
            if (mfaCode == null || mfaCode.isBlank()) {
                auditReauthAttempt(actorUsername, operation, false, "MFA required but not provided", ip, ua);
                throw new RuntimeException("Se requiere código MFA para esta operación.");
            }
            if (!codeVerifier.isValidCode(user.getMfaSecret(), mfaCode)) {
                auditReauthAttempt(actorUsername, operation, false, "Invalid MFA code", ip, ua);
                throw new RuntimeException("Código MFA inválido.");
            }
        }

        auditReauthAttempt(actorUsername, operation, true, "OK", ip, ua);
    }

    private void auditReauthAttempt(String actorUsername, String operation, boolean success,
                                     String detail, String ip, String ua) {
        try {
            AuditEventEntity audit = new AuditEventEntity();
            audit.setId(UUID.randomUUID().toString());
            audit.setTimestamp(LocalDateTime.now());
            audit.setActorId(actorUsername);
            audit.setEventType(success ? "REAUTH_SUCCESS" : "REAUTH_FAILURE");
            audit.setResourceType("REAUTH");
            audit.setResourceId(operation);
            audit.setIpAddress(ip);
            audit.setUserAgent(ua);
            audit.setNewValues("{\"operation\":\"" + operation + "\",\"result\":\"" + detail + "\"}");
            auditRepo.save(audit);
        } catch (Exception ignored) {
            // Audit failure must not block the operation
        }
    }

    private HttpServletRequest getHttpRequest() {
        try {
            return ((ServletRequestAttributes) RequestContextHolder.currentRequestAttributes()).getRequest();
        } catch (Exception e) {
            return null;
        }
    }
}
