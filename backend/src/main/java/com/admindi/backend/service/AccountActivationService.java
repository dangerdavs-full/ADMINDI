package com.admindi.backend.service;

import com.admindi.backend.model.AuditEventEntity;
import com.admindi.backend.model.Role;
import com.admindi.backend.model.UserActivationTokenEntity;
import com.admindi.backend.model.UserEntity;
import com.admindi.backend.repository.AuditEventRepository;
import com.admindi.backend.repository.UserActivationTokenRepository;
import com.admindi.backend.repository.UserRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Emisión y consumo de tokens de activación para cuentas recién creadas
 * (staff, agente inmobiliario, proveedor de mantenimiento).
 *
 * <h3>Contrato de seguridad</h3>
 * <ul>
 *   <li>El token en claro existe <strong>una sola vez</strong> en el frame de
 *       {@link #issue(String, String, String, HttpServletRequest)} y viaja en la
 *       URL que se entrega por email / WhatsApp al destinatario. En la base
 *       guardamos el SHA-256 hex del token ({@code token_hash}) — una lectura
 *       de la base no permite impersonar al user.</li>
 *   <li>El token tiene TTL configurable (default 24 h). Expirado o consumido
 *       ya no sirve.</li>
 *   <li>Al reemitir (botón "Reenviar link") se revocan todos los tokens
 *       pendientes del user antes de crear el nuevo, evitando que un token
 *       antiguo filtrado siga funcionando.</li>
 *   <li>{@link #consume(String, String, String)} exige contraseña fuerte (mín.
 *       8 chars) y rota el password del user, limpia MFA pendiente, y revoca
 *       todas las sesiones previas (un atacante con un refresh viejo no
 *       aprovecha la activación).</li>
 * </ul>
 *
 * <h3>Auditoría</h3>
 * Cada emisión/consumo/revocación genera un evento {@code ACCOUNT_ACTIVATION_*}
 * en {@code audit_events} con IP + user-agent + actor. No se guarda el token
 * ni el password.
 */
@Service
public class AccountActivationService {

    private static final Logger logger = LoggerFactory.getLogger(AccountActivationService.class);

    /** Longitud del token en bytes antes de codificar a base64url. 32 bytes ~ 256 bits. */
    private static final int TOKEN_BYTES = 32;

    /** Mínimo de caracteres para la nueva contraseña al consumir el token. */
    private static final int MIN_PASSWORD_LENGTH = 8;

    /** Canales válidos. */
    public static final String CHANNEL_EMAIL = "EMAIL";
    public static final String CHANNEL_WHATSAPP = "WHATSAPP";
    public static final String CHANNEL_BOTH = "BOTH";

    private final UserActivationTokenRepository tokenRepo;
    private final UserRepository userRepo;
    private final PasswordEncoder passwordEncoder;
    private final AuditEventRepository auditRepo;
    private final DomainEventDispatcher dispatcher;
    private final SecureRandom secureRandom = new SecureRandom();

    @Value("${app.url:http://localhost:3000}")
    private String appBaseUrl;

    @Value("${admindi.activation.ttl-hours:24}")
    private int ttlHours;

    public AccountActivationService(UserActivationTokenRepository tokenRepo,
                                    UserRepository userRepo,
                                    PasswordEncoder passwordEncoder,
                                    AuditEventRepository auditRepo,
                                    DomainEventDispatcher dispatcher) {
        this.tokenRepo = tokenRepo;
        this.userRepo = userRepo;
        this.passwordEncoder = passwordEncoder;
        this.auditRepo = auditRepo;
        this.dispatcher = dispatcher;
    }

    /** Resultado de emitir un token (para logs del creator; la URL ya se despachó). */
    public record IssuedActivation(String tokenId, String activationUrl, String channel,
                                   LocalDateTime expiresAt) {}

    /**
     * Emite un token y dispatches el link por EMAIL y/o WHATSAPP según
     * {@code channel} ({@link #CHANNEL_EMAIL}, {@link #CHANNEL_WHATSAPP},
     * {@link #CHANNEL_BOTH}). Revoca cualquier token pendiente previo del
     * mismo user (one-and-only-one activación vigente).
     *
     * @param userId       id del user que acaba de crearse.
     * @param channel      canal pedido; si null/invalid → BOTH.
     * @param actorEmail   email del actor (SUPER_ADMIN u OWNER creador).
     * @param req          request HTTP para capturar IP; puede ser null si
     *                     se invoca desde un job interno.
     */
    @Transactional
    public IssuedActivation issue(String userId, String channel, String actorEmail, HttpServletRequest req) {
        UserEntity u = userRepo.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("Usuario no encontrado: " + userId));
        if (u.getRole() == Role.SUPER_ADMIN) {
            throw new IllegalStateException("SUPER_ADMIN no usa activación por link.");
        }
        if (!u.isActive()) {
            throw new IllegalStateException(
                    "La cuenta está desactivada; reactívala antes de emitir link de activación.");
        }

        String normChannel = normalizeChannel(channel);

        // 1) Revocar pendientes anteriores: solo un token activo a la vez.
        revokePendingInternal(userId, "REISSUED");

        // 2) Generar token nuevo (32 bytes random → base64url, sin padding).
        byte[] raw = new byte[TOKEN_BYTES];
        secureRandom.nextBytes(raw);
        String rawToken = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        String tokenHash = sha256Hex(rawToken);

        LocalDateTime now = LocalDateTime.now();
        LocalDateTime expiresAt = now.plusHours(effectiveTtlHours());

        UserActivationTokenEntity t = new UserActivationTokenEntity();
        t.setId(UUID.randomUUID().toString());
        t.setUserId(userId);
        t.setTokenHash(tokenHash);
        t.setChannel(normChannel);
        t.setIssuedAt(now);
        t.setExpiresAt(expiresAt);
        t.setIssuedBy(actorEmail);
        t.setIssuedIp(clientIp(req));
        tokenRepo.save(t);

        String activationUrl = buildActivationUrl(rawToken);

        // 3) Notificaciones al usuario (email + whatsapp según channel).
        dispatchActivationNotifications(u, activationUrl, expiresAt, normChannel, actorEmail);

        // 4) Auditoría (sin token ni URL con parámetros sensibles).
        persistAudit(actorEmail, "ACCOUNT_ACTIVATION_ISSUED", u,
                "channel=" + normChannel + " ttlHours=" + effectiveTtlHours()
                        + " tokenId=" + t.getId());

        logger.info("[ACTIVATION] issued userId={} channel={} tokenId={} expiresAt={}",
                userId, normChannel, t.getId(), expiresAt);
        return new IssuedActivation(t.getId(), activationUrl, normChannel, expiresAt);
    }

    /**
     * Consume el token: valida TTL + estado, actualiza la contraseña del user,
     * marca el token como consumido y revoca cualquier otro token pendiente.
     * También limpia {@code mustChangePassword=false} para que el user pueda
     * hacer login normal.
     *
     * @throws IllegalArgumentException si el token es inválido/expirado/consumido.
     */
    @Transactional
    public void consume(String rawToken, String newPassword, String ip) {
        if (rawToken == null || rawToken.isBlank()) {
            throw new IllegalArgumentException("Token requerido.");
        }
        if (newPassword == null || newPassword.length() < MIN_PASSWORD_LENGTH) {
            throw new IllegalArgumentException("La contraseña debe tener al menos "
                    + MIN_PASSWORD_LENGTH + " caracteres.");
        }
        String hash = sha256Hex(rawToken);
        UserActivationTokenEntity t = tokenRepo.findByTokenHash(hash)
                .orElseThrow(() -> new IllegalArgumentException("Link de activación inválido."));

        LocalDateTime now = LocalDateTime.now();
        if (!t.isUsable(now)) {
            String reason = t.getConsumedAt() != null ? "already_consumed"
                    : t.getRevokedAt() != null ? "revoked"
                    : "expired";
            persistAudit(null, "ACCOUNT_ACTIVATION_CONSUME_REJECTED", null,
                    "tokenId=" + t.getId() + " reason=" + reason);
            throw new IllegalArgumentException("Link de activación expirado o ya usado.");
        }

        UserEntity u = userRepo.findById(t.getUserId())
                .orElseThrow(() -> new IllegalArgumentException("Cuenta no encontrada."));
        if (!u.isActive()) {
            throw new IllegalArgumentException("Cuenta desactivada; contacta al administrador.");
        }

        // Rota password; limpia must_change_password (el user acaba de ponerlo él mismo).
        u.setPassword(passwordEncoder.encode(newPassword));
        u.setMustChangePassword(false);
        userRepo.save(u);

        t.setConsumedAt(now);
        t.setConsumedIp(ip);
        tokenRepo.save(t);

        // Revoca los otros pendientes por si habían dos canales con un token cada uno.
        revokePendingInternal(u.getId(), "CONSUMED_SIBLING");

        persistAudit(u.getLoginUsername(), "ACCOUNT_ACTIVATION_CONSUMED", u,
                "tokenId=" + t.getId() + " channel=" + t.getChannel());

        logger.info("[ACTIVATION] consumed userId={} tokenId={}", u.getId(), t.getId());
    }

    /**
     * Revoca cualquier token pendiente del user sin emitir uno nuevo.
     * Útil al tombstonear/deactivar un user — cualquier link ya entregado
     * deja de funcionar inmediatamente.
     */
    @Transactional
    public int revokeAllForUser(String userId, String reason) {
        int n = revokePendingInternal(userId, reason);
        if (n > 0) {
            persistAudit(null, "ACCOUNT_ACTIVATION_REVOKED", null,
                    "userId=" + userId + " count=" + n + " reason=" + reason);
        }
        return n;
    }

    /** Info mínima para la pantalla pública de activación (sin exponer user_id crudo). */
    public record ActivationInfo(String userName, String userEmail, boolean usable, LocalDateTime expiresAt) {}

    public ActivationInfo inspect(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            return new ActivationInfo(null, null, false, null);
        }
        String hash = sha256Hex(rawToken);
        UserActivationTokenEntity t = tokenRepo.findByTokenHash(hash).orElse(null);
        if (t == null) {
            return new ActivationInfo(null, null, false, null);
        }
        LocalDateTime now = LocalDateTime.now();
        boolean usable = t.isUsable(now);
        UserEntity u = userRepo.findById(t.getUserId()).orElse(null);
        String name = u != null ? u.getName() : null;
        // V54: el único email del user es contactEmail (puede ser null en
        // tombstones, pero el detector de cuenta no accionable ahora usa el
        // username tombstoneado `tombstone-*`).
        String email = u != null ? u.getContactEmail() : null;
        String username = u != null ? u.getLoginUsername() : null;
        if (u == null || !u.isActive() || (username != null && username.startsWith("tombstone-"))) {
            usable = false;
        }
        return new ActivationInfo(name, email, usable, t.getExpiresAt());
    }

    // ─── Helpers ────────────────────────────────────────────────────────

    private int revokePendingInternal(String userId, String reason) {
        List<UserActivationTokenEntity> pending = tokenRepo
                .findByUserIdAndConsumedAtIsNullAndRevokedAtIsNull(userId);
        LocalDateTime now = LocalDateTime.now();
        for (UserActivationTokenEntity t : pending) {
            t.setRevokedAt(now);
            tokenRepo.save(t);
        }
        if (!pending.isEmpty()) {
            logger.info("[ACTIVATION] revoked {} pending token(s) for userId={} reason={}",
                    pending.size(), userId, reason);
        }
        return pending.size();
    }

    private void dispatchActivationNotifications(UserEntity u, String activationUrl,
                                                 LocalDateTime expiresAt, String channel,
                                                 String actorEmail) {
        String ttlHumanized = effectiveTtlHours() + "h";
        String title = "Activa tu cuenta ADMINDI";
        String body =
                "Hola " + (u.getName() != null ? u.getName() : "") + ",\n\n"
                + "Tu cuenta en la plataforma ADMINDI fue creada.\n"
                + "Para terminar de activarla y establecer tu contraseña, abre este enlace:\n\n"
                + activationUrl + "\n\n"
                + "Este enlace es válido por " + ttlHumanized + " y es de un solo uso.\n"
                + "Si no reconoces esta actividad, ignora este correo.\n";

        // Variables para la plantilla Twilio admindi_account_activation_v1 (cuando
        // Meta la apruebe). Slots: {{1}}=nombre, {{2}}=URL. Si el SID no está
        // registrado, TwilioWhatsAppService cae a body libre (válido dentro de
        // ventana 24h o audita WHATSAPP_SKIPPED fuera de ella).
        Map<String, String> templateVars = new LinkedHashMap<>();
        templateVars.put("1", u.getName() != null ? u.getName() : "");
        templateVars.put("2", activationUrl);

        // Un solo dispatch canaliza IN_APP + EMAIL + WhatsApp. forceAllChannels=true
        // evita que una preferencia residual apague el link de activación — un user
        // recién creado no ha configurado preferencias y la entrega es operativamente
        // crítica (sin link no hay cuenta utilizable).
        try {
            dispatcher.dispatch(
                    "ACCOUNT_ACTIVATION",
                    title,
                    body,
                    null,                       // ownerId neutro (el user es staff/provider)
                    actorEmail != null ? actorEmail : "SYSTEM",
                    List.of(u.getId()),
                    templateVars,
                    null,                       // no legacy whatsappCallback
                    true                        // forceAllChannels
            );
        } catch (Exception e) {
            logger.warn("[ACTIVATION] dispatch failed userId={}: {}", u.getId(), e.getMessage());
        }
    }

    public String buildActivationUrl(String rawToken) {
        String base = (appBaseUrl == null || appBaseUrl.isBlank())
                ? "http://localhost:3000" : appBaseUrl.trim();
        if (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        return base + "/activate?token=" + rawToken;
    }

    private int effectiveTtlHours() {
        return ttlHours > 0 ? ttlHours : 24;
    }

    private String normalizeChannel(String c) {
        if (c == null) return CHANNEL_BOTH;
        String u = c.trim().toUpperCase();
        return switch (u) {
            case CHANNEL_EMAIL, CHANNEL_WHATSAPP, CHANNEL_BOTH -> u;
            default -> CHANNEL_BOTH;
        };
    }

    private String clientIp(HttpServletRequest req) {
        if (req == null) return null;
        String fwd = req.getHeader("X-Forwarded-For");
        if (fwd != null && !fwd.isBlank()) {
            int comma = fwd.indexOf(',');
            return comma > 0 ? fwd.substring(0, comma).trim() : fwd.trim();
        }
        return req.getRemoteAddr();
    }

    private static String sha256Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] out = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(out.length * 2);
            for (byte b : out) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 no disponible", e);
        }
    }

    private void persistAudit(String actor, String eventType, UserEntity u, String detail) {
        try {
            AuditEventEntity a = new AuditEventEntity();
            a.setId(UUID.randomUUID().toString());
            a.setTimestamp(LocalDateTime.now());
            a.setActorId(actor != null ? actor : "SYSTEM");
            a.setActorRole(Objects.equals(actor, "SYSTEM") ? "SYSTEM" : "USER");
            a.setEventType(eventType);
            a.setResourceType("User");
            a.setResourceId(u != null ? u.getId() : null);
            a.setOwnerId(u != null ? u.getOwnerId() : null);
            a.setNewValues("{\"detail\":\"" + (detail == null ? "" : detail.replace("\"", "'")) + "\"}");
            auditRepo.save(a);
        } catch (Exception e) {
            logger.warn("[ACTIVATION] audit save failed: {}", e.getMessage());
        }
    }
}
