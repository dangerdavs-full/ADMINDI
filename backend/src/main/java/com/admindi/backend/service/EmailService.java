package com.admindi.backend.service;

import com.admindi.backend.model.AuditEventEntity;
import com.admindi.backend.model.UserEntity;
import com.admindi.backend.repository.AuditEventRepository;
import com.admindi.backend.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Envío de correos automáticos desde el backend (no depende de n8n).
 *
 * Configuración externalizable (ver application.yml):
 *   spring.mail.*  (host, port, username, password, protocol, properties)
 *   app.mail.from, app.mail.enabled, app.mail.provider
 *
 * Reglas:
 *  - Ningún secreto se loguea ni se audita (redacción por defecto).
 *  - Si el envío falla o está deshabilitado, el flujo principal NO se rompe;
 *    se deja rastro en audit_events para diagnóstico.
 *  - "provider" es informativo; la implementación es SMTP estándar y quedará
 *    apta para migrar a SES/SendGrid/etc. cambiando config, no código.
 */
@Service
public class EmailService {

    private static final Logger logger = LoggerFactory.getLogger(EmailService.class);

    private final JavaMailSender mailSender;
    private final UserRepository userRepo;
    private final AuditEventRepository auditRepo;

    @Value("${app.mail.enabled:false}")
    private boolean enabled;

    @Value("${app.mail.provider:smtp-generic}")
    private String provider;

    @Value("${app.mail.from:}")
    private String fromAddress;

    public EmailService(JavaMailSender mailSender,
                        UserRepository userRepo,
                        AuditEventRepository auditRepo) {
        this.mailSender = mailSender;
        this.userRepo = userRepo;
        this.auditRepo = auditRepo;
    }

    public boolean isEnabled() {
        return enabled && fromAddress != null && !fromAddress.isBlank();
    }

    /**
     * Envía un correo simple (texto plano). No rompe el flujo si falla.
     *
     * @param recipientUserId id interno del destinatario; el buzón se toma de
     *                        {@link UserEntity#getContactEmail()} (V50: email ya no
     *                        es canal — si contactEmail no existe, se salta el envío).
     * @param eventType       evento de dominio (auditado).
     * @param ownerId         owner asociado al evento (contexto).
     * @param subject         asunto final.
     * @param body            cuerpo en texto plano (sin secretos).
     */
    public void sendEventEmail(String recipientUserId,
                               String eventType,
                               String ownerId,
                               String subject,
                               String body) {
        if (!isEnabled()) {
            logger.debug("[EMAIL] disabled, skipping event={} recipient={}", eventType, recipientUserId);
            auditMailOutcome(recipientUserId, ownerId, eventType, "EMAIL_SKIPPED",
                    "app.mail.enabled=false or app.mail.from missing");
            return;
        }
        if (recipientUserId == null || recipientUserId.isBlank()) {
            auditMailOutcome(recipientUserId, ownerId, eventType, "EMAIL_SKIPPED", "recipient null");
            return;
        }

        UserEntity user = userRepo.findById(recipientUserId).orElse(null);
        if (user == null) {
            auditMailOutcome(recipientUserId, ownerId, eventType, "EMAIL_SKIPPED", "recipient not found");
            return;
        }

        String to = pickRecipientAddress(user);
        if (to == null) {
            auditMailOutcome(recipientUserId, ownerId, eventType, "EMAIL_SKIPPED",
                    "recipient has no contactEmail (V50: email ya no es canal de envío)");
            return;
        }

        try {
            SimpleMailMessage msg = new SimpleMailMessage();
            msg.setFrom(fromAddress);
            msg.setTo(to);
            msg.setSubject(truncate(subject, 180));
            msg.setText(buildSafeBody(eventType, subject, body));
            mailSender.send(msg);
            auditMailOutcome(recipientUserId, ownerId, eventType, "EMAIL_SENT",
                    "provider=" + provider + " to=" + redactEmail(to));
        } catch (Exception ex) {
            // No propagar: el flujo principal debe continuar.
            logger.error("[EMAIL] send failed event={} user={} err={}", eventType, recipientUserId, ex.getMessage());
            auditMailOutcome(recipientUserId, ownerId, eventType, "EMAIL_FAILED",
                    "provider=" + provider + " to=" + redactEmail(to) + " err=" + safeMsg(ex));
        }
    }

    /**
     * V50 — {@code users.email} es sólo metadata histórica (nullable, no único,
     * puede coincidir entre cuentas) y <b>NO</b> es un canal válido de notificación.
     * El único buzón de envío es {@link UserEntity#getContactEmail()}. Si el usuario
     * no tiene {@code contactEmail} registrado, no mandamos correo (se audita como
     * {@code EMAIL_SKIPPED}) y el evento sigue vivo por IN_APP / WhatsApp.
     *
     * <p>Invariante: SUPER_ADMIN tiene {@code contactEmail == null} por construcción,
     * por lo que nunca recibirá correo desde este servicio aún si algún caller se
     * saltara el filtro del dispatcher.</p>
     */
    private String pickRecipientAddress(UserEntity user) {
        if (user.getContactEmail() != null && !user.getContactEmail().isBlank()) {
            return user.getContactEmail().trim();
        }
        return null;
    }

    private String buildSafeBody(String eventType, String subject, String body) {
        StringBuilder sb = new StringBuilder();
        sb.append(subject == null ? eventType : subject).append("\n\n");
        if (body != null && !body.isBlank()) {
            sb.append(body).append("\n\n");
        }
        sb.append("— ADMINDI — Notificación automática. No respondas a este correo.");
        return sb.toString();
    }

    private void auditMailOutcome(String userId, String ownerId, String eventType, String outcome, String detail) {
        try {
            AuditEventEntity a = new AuditEventEntity();
            a.setId(UUID.randomUUID().toString());
            a.setTimestamp(LocalDateTime.now());
            a.setActorId("SYSTEM");
            a.setActorRole("SYSTEM");
            a.setEventType("MAIL_" + outcome + "_" + (eventType == null ? "UNKNOWN" : eventType));
            a.setResourceType("EMAIL_NOTIFICATION");
            a.setResourceId(userId);
            a.setOwnerId(ownerId);
            a.setOldValues(null);
            a.setNewValues("{\"detail\":\"" + (detail == null ? "" : detail.replace("\"", "'")) + "\"}");
            auditRepo.save(a);
        } catch (Exception e) {
            logger.warn("[EMAIL] audit save failed for {}: {}", outcome, e.getMessage());
        }
    }

    private static String redactEmail(String addr) {
        if (addr == null) return "(null)";
        int at = addr.indexOf('@');
        if (at <= 1) return "***" + addr.substring(Math.max(0, at));
        return addr.charAt(0) + "***" + addr.substring(at);
    }

    private static String safeMsg(Exception ex) {
        String m = ex.getMessage();
        if (m == null) return ex.getClass().getSimpleName();
        // Evitar filtrar posibles credenciales/tokens en el mensaje de error del driver SMTP.
        String lower = m.toLowerCase();
        if (lower.contains("password") || lower.contains("secret") || lower.contains("token")) {
            return ex.getClass().getSimpleName() + " (redacted)";
        }
        return truncate(m, 240);
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }
}
