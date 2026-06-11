package com.admindi.backend.service;

import com.admindi.backend.model.AuditEventEntity;
import com.admindi.backend.model.RefreshTokenSessionEntity;
import com.admindi.backend.model.Role;
import com.admindi.backend.model.UserEntity;
import com.admindi.backend.repository.AuditEventRepository;
import com.admindi.backend.repository.RefreshTokenSessionRepository;
import com.admindi.backend.repository.UserRepository;
import com.admindi.backend.security.TokenBlacklistService;
import com.admindi.backend.whatsapp.WhatsAppPinService;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class AccountRecoveryService {

    private static final Logger logger = LoggerFactory.getLogger(AccountRecoveryService.class);

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuditEventRepository auditEventRepository;
    private final RefreshTokenSessionRepository refreshRepo;
    private final TokenBlacklistService blacklistService;
    private final ReauthService reauthService;
    private final DomainEventDispatcher dispatcher;
    private final WhatsAppPinService pinService;

    @Value("${app.url:http://localhost:3000}")
    private String appUrl;

    public AccountRecoveryService(
            UserRepository userRepository,
            PasswordEncoder passwordEncoder,
            AuditEventRepository auditEventRepository,
            RefreshTokenSessionRepository refreshRepo,
            TokenBlacklistService blacklistService,
            ReauthService reauthService,
            DomainEventDispatcher dispatcher,
            WhatsAppPinService pinService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.auditEventRepository = auditEventRepository;
        this.refreshRepo = refreshRepo;
        this.blacklistService = blacklistService;
        this.reauthService = reauthService;
        this.dispatcher = dispatcher;
        this.pinService = pinService;
    }

    public enum RecoveryType {
        PASSWORD_RESET,
        MFA_RESET,
        /**
         * Reset del NIP que el inquilino usa en el chatbot de WhatsApp (V55).
         * NO toca password ni MFA del portal web — es un tercer factor
         * independiente, específico de WhatsApp. Solo aplica a role=TENANT.
         */
        PIN_RESET,
        FULL_RECOVERY
    }

    public static class RecoveryRequest {
        /**
         * V48 / Bloque 2: target se identifica por username global. Se conserva
         * {@code targetEmail} para retro-compat del panel antiguo pero el backend
         * resuelve primero por username y sólo cae a email como legacy.
         */
        private String targetUsername;
        private String targetEmail;
        private RecoveryType type;
        private String reason;
        private String password;
        private String mfaCode;

        public String getTargetUsername() { return targetUsername; }
        public void setTargetUsername(String targetUsername) { this.targetUsername = targetUsername; }
        public String getTargetEmail() { return targetEmail; }
        public void setTargetEmail(String targetEmail) { this.targetEmail = targetEmail; }
        public RecoveryType getType() { return type; }
        public void setType(RecoveryType type) { this.type = type; }
        public String getReason() { return reason; }
        public void setReason(String reason) { this.reason = reason; }
        public String getPassword() { return password; }
        public void setPassword(String password) { this.password = password; }
        public String getMfaCode() { return mfaCode; }
        public void setMfaCode(String mfaCode) { this.mfaCode = mfaCode; }
    }

    public static class RecoveryResult {
        private String tempPassword;
        private boolean mfaReset;
        private boolean passwordReset;
        private boolean pinReset;
        private int sessionsRevoked;

        public String getTempPassword() { return tempPassword; }
        public void setTempPassword(String tempPassword) { this.tempPassword = tempPassword; }
        public boolean isMfaReset() { return mfaReset; }
        public void setMfaReset(boolean mfaReset) { this.mfaReset = mfaReset; }
        public boolean isPasswordReset() { return passwordReset; }
        public void setPasswordReset(boolean passwordReset) { this.passwordReset = passwordReset; }
        public boolean isPinReset() { return pinReset; }
        public void setPinReset(boolean pinReset) { this.pinReset = pinReset; }
        public int getSessionsRevoked() { return sessionsRevoked; }
        public void setSessionsRevoked(int sessionsRevoked) { this.sessionsRevoked = sessionsRevoked; }
    }

    @Transactional
    public RecoveryResult executeRecovery(RecoveryRequest request) {
        // 1. Validate request
        if (request.getReason() == null || request.getReason().trim().length() < 10) {
            throw new RuntimeException("Se requiere un motivo de recuperación (mínimo 10 caracteres).");
        }

        // 2. Reauth del actor (password + MFA). Aplica para SUPER_ADMIN y OWNER
        //    por igual: prevenir abuso de sesión robada en operaciones que emiten
        //    credenciales temporales y revocan MFA del objetivo.
        String operation = "ACCOUNT_RECOVERY_" + (request.getType() != null ? request.getType().name() : "UNKNOWN");
        reauthService.verifyReauth(request.getPassword(), request.getMfaCode(), operation);

        // 3. Get actor (the admin performing the recovery)
        //    V52 (2026-04-17): auth.getName() SIEMPRE es username (post-V48). El
        //    fallback por email se eliminó porque el email ya no es único y podría
        //    resolver a un actor distinto del que emitió el token — crítico en un
        //    flujo que emite credenciales temporales y audita acciones de recovery.
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        String actorSubject = auth.getName();
        UserEntity actor = userRepository.findByUsername(actorSubject)
                .orElseThrow(() -> new RuntimeException("Actor not found"));

        // 4. Get target user
        //    V54: el email fue eliminado de la tabla users y el contactEmail no
        //    es único. El recovery EXIGE siempre `targetUsername` (identificador
        //    único global, V48). Cualquier intento de recovery por email se
        //    rechaza con mensaje claro pidiendo username.
        UserEntity target;
        if (request.getTargetUsername() == null || request.getTargetUsername().isBlank()) {
            throw new RuntimeException(
                    "Debes indicar 'targetUsername' (identificador único). "
                            + "El recovery por email ya no es soportado en V54.");
        }
        String normalizedUsername = request.getTargetUsername().trim();
        target = userRepository.findByUsername(normalizedUsername)
                .orElseThrow(() -> new RuntimeException("Usuario objetivo no encontrado: " + normalizedUsername));

        // 5. Enforce access control
        enforceAccessControl(actor, target, request.getType());

        // 5. Execute recovery actions
        RecoveryResult result = new RecoveryResult();

        if (request.getType() == RecoveryType.PASSWORD_RESET || request.getType() == RecoveryType.FULL_RECOVERY) {
            String tempPass = generateTempPassword();
            target.setPassword(passwordEncoder.encode(tempPass));
            target.setMustChangePassword(true);
            result.setTempPassword(tempPass);
            result.setPasswordReset(true);
        }

        if (request.getType() == RecoveryType.MFA_RESET || request.getType() == RecoveryType.FULL_RECOVERY) {
            target.setMfaEnabled(false);
            target.setMfaSecret(null);
            result.setMfaReset(true);
        }

        // V55: NIP de WhatsApp (específico para TENANT). FULL_RECOVERY también
        // lo limpia para que una recuperación completa deje al usuario con
        // credenciales frescas en todos los canales.
        if (request.getType() == RecoveryType.PIN_RESET || request.getType() == RecoveryType.FULL_RECOVERY) {
            pinService.reset(target.getId());
            result.setPinReset(true);
        }

        userRepository.save(target);

        // 6. Revoke ALL active sessions / refresh tokens
        int revoked = revokeAllSessions(target.getId());
        result.setSessionsRevoked(revoked);

        // 7. Create audit record
        createAuditRecord(actor, target, request, result);

        // 8. Create fee event for admin recovery ($500 MXN anchor)
        createFeeEvent(actor, target, request.getType());

        // 9. Notificar al target por email + in-app + whatsapp (si aplica).
        //    forceAllChannels=true porque es operativamente crítico: el user
        //    DEBE enterarse del reset aunque tenga notificaciones apagadas
        //    (sin esto no sabe su nueva contraseña ni que perdió MFA). Se
        //    envuelve en try/catch para que un fallo de correo/WhatsApp no
        //    bloquee la operación administrativa — el audit y el cambio de
        //    credenciales ya se persistieron arriba.
        dispatchRecoveryNotification(target, actor, request, result);

        return result;
    }

    /**
     * Despacha el aviso de recuperación al usuario objetivo por todos los
     * canales disponibles. Si el reset incluyó contraseña, el correo contiene
     * las credenciales de re-acceso; si fue MFA, avisa que debe volver a
     * configurarlo al próximo login.
     */
    private void dispatchRecoveryNotification(UserEntity target, UserEntity actor,
                                              RecoveryRequest request, RecoveryResult result) {
        try {
            String body = buildRecoveryBody(target, actor, request, result);
            Map<String, String> tplVars = new LinkedHashMap<>();
            tplVars.put("1", target.getName() != null ? target.getName() : "");
            tplVars.put("2", target.getContactEmail() != null ? target.getContactEmail() : "");
            tplVars.put("3", appUrl != null ? appUrl : "");
            dispatcher.dispatch(
                    "ACCOUNT_RECOVERED",
                    "Recuperación de cuenta en ADMINDI",
                    body,
                    target.getOwnerId(),
                    actor.getLoginUsername(),
                    List.of(target.getId()),
                    tplVars,
                    null,
                    true /* forceAllChannels — el user debe enterarse sí o sí */
            );
        } catch (Exception e) {
            logger.warn("[RECOVERY] Fallo al notificar al usuario {} (motivo no bloqueante): {}",
                    target.getId(), e.getMessage());
        }
    }

    private String buildRecoveryBody(UserEntity target, UserEntity actor,
                                     RecoveryRequest request, RecoveryResult result) {
        StringBuilder sb = new StringBuilder();
        sb.append("Hola ").append(target.getName() != null ? target.getName() : "").append(",\n\n");
        sb.append("Un administrador (").append(actor.getLoginUsername() != null ? actor.getLoginUsername() : "SISTEMA")
                .append(") ejecutó una recuperación sobre tu cuenta en ADMINDI.\n\n");
        sb.append("Motivo registrado: ").append(request.getReason() != null ? request.getReason() : "(sin motivo)").append("\n\n");
        sb.append("Cambios aplicados:\n");
        sb.append("  • Contraseña: ").append(result.isPasswordReset() ? "reseteada" : "sin cambios").append("\n");
        sb.append("  • MFA: ").append(result.isMfaReset() ? "reseteado (debes volver a configurarlo al próximo login)" : "sin cambios").append("\n");
        sb.append("  • NIP WhatsApp: ").append(result.isPinReset()
                ? "reseteado (el chatbot te pedirá un NIP nuevo en tu próxima interacción)"
                : "sin cambios").append("\n");
        sb.append("  • Sesiones revocadas: ").append(result.getSessionsRevoked()).append("\n");

        if (result.isPasswordReset() && result.getTempPassword() != null) {
            sb.append("\nDatos para tu próximo ingreso:\n");
            sb.append("  • Portal: ").append(appUrl).append("\n");
            sb.append("  • Usuario: ").append(target.getLoginUsername() != null ? target.getLoginUsername() : "").append("\n");
            sb.append("  • Contraseña temporal: ").append(result.getTempPassword()).append("\n");
            sb.append("\nPor seguridad, el sistema te pedirá cambiar la contraseña en tu primer acceso.\n");
            sb.append("Recuerda: tu identificador de login es el USUARIO, no el correo.\n");
        }

        sb.append("\nSi tú no solicitaste esta recuperación, contacta de inmediato al administrador — todas tus sesiones activas fueron cerradas como medida preventiva.");
        return sb.toString();
    }

    private void enforceAccessControl(UserEntity actor, UserEntity target, RecoveryType type) {
        Role actorRole = actor.getRole();
        Role targetRole = target.getRole();

        // INVARIANTE DE SISTEMA: Ningún flujo administrativo puede tocar las
        // credenciales de un SUPER_ADMIN. Permitir reset de password/MFA de un SA
        // equivale a una toma de control total: cualquier SA (o sesión comprometida)
        // podría "quemar" a otro SA y quedar como único root. Las recuperaciones
        // entre SAs se hacen por procedimiento fuera de banda (DB + auditoría).
        if (targetRole == Role.SUPER_ADMIN) {
            throw new RuntimeException(
                    "No se puede ejecutar recuperación sobre una cuenta SUPER_ADMIN desde esta interfaz. " +
                    "Ese cambio requiere procedimiento elevado fuera de banda.");
        }

        // V55: PIN_RESET solo tiene sentido para TENANT (único rol que usa el
        // chatbot de WhatsApp). Bloqueamos el uso del tipo sobre otros roles
        // para evitar auditorías confusas y cargos improcedentes.
        if (type == RecoveryType.PIN_RESET && targetRole != Role.TENANT) {
            throw new RuntimeException(
                    "El NIP de WhatsApp solo aplica a cuentas TENANT. Usa PASSWORD_RESET o MFA_RESET para otros roles.");
        }

        // Un actor no puede lanzar recovery contra su propia cuenta desde el panel
        // admin; para eso existe el flujo normal de "olvidé mi contraseña".
        // V54: comparamos por id (robusto a cualquier cambio de username/email).
        if (actor.getId() != null && actor.getId().equals(target.getId())) {
            throw new RuntimeException("No puedes ejecutar recuperación sobre tu propia cuenta desde aquí.");
        }

        // Only SUPER_ADMIN can recover an OWNER
        if (targetRole == Role.OWNER && actorRole != Role.SUPER_ADMIN) {
            throw new RuntimeException("Solo un SUPER_ADMIN puede recuperar una cuenta OWNER.");
        }

        if (actorRole == Role.SUPER_ADMIN) {
            // SUPER_ADMIN puede recuperar cualquier cuenta EXCEPTO otro SUPER_ADMIN
            // (ya bloqueado arriba).
            return;
        }

        if (actorRole == Role.OWNER) {
            // OWNER can only recover users within their own context
            String actorOwnerId = actor.getOwnerId() != null ? actor.getOwnerId() : actor.getId();
            String targetOwnerId = target.getOwnerId();

            if (targetOwnerId == null || !targetOwnerId.equals(actorOwnerId)) {
                throw new RuntimeException("Solo puedes recuperar cuentas dentro de tu propio contexto.");
            }
            // OWNER can recover PROPERTY_ADMIN, ACCOUNTANT, TENANT within their context
            if (targetRole != Role.PROPERTY_ADMIN && targetRole != Role.ACCOUNTANT && targetRole != Role.TENANT) {
                throw new RuntimeException("No tienes permiso para recuperar este tipo de cuenta.");
            }
            return;
        }

        throw new RuntimeException("No tienes permiso para ejecutar recuperaciones de cuenta.");
    }

    private String generateTempPassword() {
        byte[] randomBytes = new byte[8];
        new SecureRandom().nextBytes(randomBytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);
    }

    private int revokeAllSessions(String userId) {
        List<RefreshTokenSessionEntity> sessions = refreshRepo.findByUserId(userId);
        int count = 0;
        for (RefreshTokenSessionEntity session : sessions) {
            if (!session.isRevoked()) {
                session.setRevoked(true);
                refreshRepo.save(session);
                // Also blacklist the JTI in Redis
                blacklistService.revokeToken(session.getId());
                count++;
            }
        }
        return count;
    }

    private void createAuditRecord(UserEntity actor, UserEntity target, RecoveryRequest request, RecoveryResult result) {
        HttpServletRequest httpReq = getHttpRequest();

        AuditEventEntity audit = new AuditEventEntity();
        audit.setId(UUID.randomUUID().toString());
        audit.setTimestamp(LocalDateTime.now());
        // V54: actor identificado por username (único, V48) en audit trail.
        audit.setActorId(actor.getLoginUsername());
        audit.setActorRole(actor.getRole().name());
        audit.setEventType("ADMIN_ACCOUNT_RECOVERY_" + request.getType().name());
        audit.setResourceType("User");
        audit.setResourceId(target.getId());
        audit.setOwnerId(target.getOwnerId());
        audit.setIpAddress(httpReq != null ? httpReq.getRemoteAddr() : "unknown");
        audit.setUserAgent(httpReq != null ? httpReq.getHeader("User-Agent") : "unknown");
        audit.setRequestId(UUID.randomUUID().toString());

        // Store detailed context in newValues
        String details = String.format(
            "{\"targetUsername\":\"%s\",\"targetContactEmail\":\"%s\",\"targetRole\":\"%s\",\"reason\":\"%s\",\"passwordReset\":%s,\"mfaReset\":%s,\"pinReset\":%s,\"sessionsRevoked\":%d}",
            safe(target.getLoginUsername()),
            safe(target.getContactEmail()),
            target.getRole().name(),
            request.getReason().replace("\"", "'"),
            result.isPasswordReset(),
            result.isMfaReset(),
            result.isPinReset(),
            result.getSessionsRevoked()
        );
        audit.setNewValues(details);

        auditEventRepository.save(audit);
    }

    private static String safe(String s) { return s == null ? "" : s.replace("\"", "'"); }

    private void createFeeEvent(UserEntity actor, UserEntity target, RecoveryType type) {
        HttpServletRequest httpReq = getHttpRequest();

        AuditEventEntity feeEvent = new AuditEventEntity();
        feeEvent.setId(UUID.randomUUID().toString());
        feeEvent.setTimestamp(LocalDateTime.now());
        feeEvent.setActorId(actor.getLoginUsername());
        feeEvent.setActorRole(actor.getRole().name());
        feeEvent.setEventType("FEE_ADMIN_ACCOUNT_RECOVERY");
        feeEvent.setResourceType("Invoices");
        feeEvent.setResourceId(target.getId());
        feeEvent.setOwnerId(target.getOwnerId());
        feeEvent.setIpAddress(httpReq != null ? httpReq.getRemoteAddr() : "unknown");
        feeEvent.setUserAgent(httpReq != null ? httpReq.getHeader("User-Agent") : "unknown");
        feeEvent.setNewValues(String.format(
            "{\"amount\":500.00,\"currency\":\"MXN\",\"recoveryType\":\"%s\",\"reason\":\"Cargo administrativo por recuperación de cuenta\"}",
            type.name()
        ));

        auditEventRepository.save(feeEvent);
    }

    private HttpServletRequest getHttpRequest() {
        try {
            return ((ServletRequestAttributes) RequestContextHolder.currentRequestAttributes()).getRequest();
        } catch (Exception e) {
            return null;
        }
    }
}
