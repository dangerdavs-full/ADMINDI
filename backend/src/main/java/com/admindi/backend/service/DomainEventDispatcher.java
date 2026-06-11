package com.admindi.backend.service;

import com.admindi.backend.model.AuditEventEntity;
import com.admindi.backend.model.NotificationEntity;
import com.admindi.backend.model.NotificationPreferenceEntity;
import com.admindi.backend.model.Role;
import com.admindi.backend.model.UserEntity;
import com.admindi.backend.notifications.NotificationChannels;
import com.admindi.backend.repository.AuditEventRepository;
import com.admindi.backend.repository.NotificationPreferenceRepository;
import com.admindi.backend.repository.NotificationRepository;
import com.admindi.backend.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Dispatcher central de eventos de dominio.
 *
 * Cada evento:
 *  1. Se auditA (siempre).
 *  2. Se crea IN_APP para cada recipiente (IN_APP es OBLIGATORIO -> no se consulta
 *     preferencia para apagarlo).
 *  3. Se manda EMAIL por recipiente si la preferencia EMAIL está activa
 *     (canal nativo del backend vía EmailService).
 *  4. Se dispara WhatsApp por recipiente si la preferencia WHATSAPP está activa,
 *     usando {@link TwilioWhatsAppService} y plantillas Meta cuando aplica.
 *
 * Notas de seguridad:
 *  - Fallos en EMAIL o WhatsApp NO rompen el flujo principal; quedan en audit.
 *  - Las preferencias se consultan por (userId, eventType, canal). Si no existe
 *    fila explícita, se asume habilitado (comportamiento estable previo).
 */
@Service
public class DomainEventDispatcher {

    private static final Logger logger = LoggerFactory.getLogger(DomainEventDispatcher.class);

    private final NotificationRepository notificationRepo;
    private final NotificationPreferenceRepository prefRepo;
    private final UserRepository userRepo;
    private final AuditEventRepository auditRepo;
    private final EmailService emailService;
    private final TwilioWhatsAppService twilioService;

    public DomainEventDispatcher(
            NotificationRepository notificationRepo,
            NotificationPreferenceRepository prefRepo,
            UserRepository userRepo,
            AuditEventRepository auditRepo,
            EmailService emailService,
            TwilioWhatsAppService twilioService) {
        this.notificationRepo = notificationRepo;
        this.prefRepo = prefRepo;
        this.userRepo = userRepo;
        this.auditRepo = auditRepo;
        this.emailService = emailService;
        this.twilioService = twilioService;
    }

    /**
     * Firma canónica del dispatch.
     *
     * @param eventType           e.g. OWNER_CONTACT_UPDATED
     * @param title               encabezado legible.
     * @param body                detalle opcional.
     * @param ownerId             contexto (puede ser null para eventos plataforma).
     * @param actorEmail          quién disparó el evento.
     * @param recipientUserIds    destinatarios reales de la notificación; si es
     *                            null/vacío se cae al actor (retrocompatibilidad).
     */
    public void dispatch(
            String eventType,
            String title,
            String body,
            String ownerId,
            String actorEmail) {
        dispatch(eventType, title, body, ownerId, actorEmail, null);
    }

    public void dispatch(
            String eventType,
            String title,
            String body,
            String ownerId,
            String actorEmail,
            List<String> recipientUserIds) {
        dispatch(eventType, title, body, ownerId, actorEmail, recipientUserIds,
                null, null);
    }

    /**
     * Destinatarios explícitos + variables de plantilla WhatsApp (sin callback legacy).
     */
    public void dispatch(
            String eventType,
            String title,
            String body,
            String ownerId,
            String actorEmail,
            List<String> recipientUserIds,
            Map<String, String> templateVariables) {
        dispatch(eventType, title, body, ownerId, actorEmail, recipientUserIds,
                templateVariables, null);
    }

    /**
     * Overload principal. Añade {@code templateVariables} para rutear WhatsApp por plantilla
     * Twilio (contentSid + contentVariables) cuando el eventType tiene plantilla aprobada.
     *
     * <p>Reglas:
     * <ul>
     *   <li>Si {@code templateVariables} es null o vacío: se manda body libre (legacy).</li>
     *   <li>Si {@code templateVariables} está poblado y el eventType tiene plantilla
     *       registrada en {@code TwilioTemplateRegistry}: se manda por Content API.</li>
     *   <li>Si el eventType NO tiene plantilla registrada: las {@code templateVariables}
     *       se ignoran (no hay a dónde enviarlas) y cae a body libre.</li>
     * </ul>
     *
     * El EMAIL y el IN_APP ignoran completamente {@code templateVariables} — siguen usando
     * {@code title} + {@code body} como hasta ahora.
     */
    public void dispatch(
            String eventType,
            String title,
            String body,
            String ownerId,
            String actorEmail,
            List<String> recipientUserIds,
            Map<String, String> templateVariables,
            Runnable whatsappCallback) {
        dispatch(eventType, title, body, ownerId, actorEmail, recipientUserIds,
                templateVariables, whatsappCallback, false);
    }

    /**
     * Overload con {@code forceAllChannels}: cuando es {@code true}, se envía por EMAIL y
     * WhatsApp ignorando las preferencias del destinatario. Está reservado para acciones
     * manuales sensibles (ej. recordatorio de pago disparado por el dueño con reauth + MFA)
     * donde el flujo de negocio exige entrega garantizada por todos los canales.
     *
     * <p>IN_APP siempre se crea — la preferencia IN_APP nunca se consulta ni siquiera en
     * modo normal. El flag solo afecta EMAIL y WhatsApp.
     *
     * <p>Usar {@code forceAllChannels=true} con ligereza es abuso: los callers deben tener
     * una justificación de negocio clara y registrar audit propio antes de invocarlo.
     * Este método NO audita "override"; confía en que el caller lo haga con su semántica.
     */
    public void dispatch(
            String eventType,
            String title,
            String body,
            String ownerId,
            String actorEmail,
            List<String> recipientUserIds,
            Map<String, String> templateVariables,
            Runnable whatsappCallback,
            boolean forceAllChannels) {

        createAuditRecord(eventType, actorEmail, ownerId);

        List<String> resolved = (recipientUserIds != null && !recipientUserIds.isEmpty())
                ? recipientUserIds
                : fallbackRecipient(actorEmail);

        // Invariante de dominio: SUPER_ADMIN NUNCA recibe notificaciones de negocio.
        // Sólo administra la plataforma (crear dueños, reset password/MFA, eliminar).
        // No tiene contactEmail/contactPhone/whatsapp y el dispatcher los descarta
        // silenciosamente aunque un caller los incluya por error.
        resolved = filterOutSuperAdmins(resolved, eventType);

        int whatsappSent = 0;
        boolean anyWhatsappOptIn = false;
        for (String recipientId : resolved) {
            if (recipientId == null || recipientId.isBlank()) continue;

            // IN_APP: canal obligatorio. Siempre se crea la notificación interna.
            createInAppNotification(recipientId, ownerId, eventType, title, body);

            // EMAIL: backend nativo. Respeta preferencia salvo que forceAllChannels esté activo.
            if (forceAllChannels || isChannelEnabled(recipientId, eventType, NotificationChannels.EMAIL)) {
                try {
                    emailService.sendEventEmail(recipientId, eventType, ownerId, title, body);
                } catch (Exception e) {
                    logger.error("[Dispatcher] EMAIL send failed event={} user={} err={}",
                            eventType, recipientId, e.getMessage());
                }
            }

            // WHATSAPP: Twilio directo por destinatario. Respeta preferencia salvo forceAllChannels.
            // Si el evento tiene plantilla + templateVariables, viaja como contentSid;
            // si no, cae al body libre (solo válido dentro de ventana 24h).
            if (forceAllChannels || isChannelEnabled(recipientId, eventType, NotificationChannels.WHATSAPP)) {
                anyWhatsappOptIn = true;
                try {
                    twilioService.sendEventWhatsApp(
                            recipientId, eventType, ownerId, title, body, templateVariables);
                    whatsappSent++;
                } catch (Exception e) {
                    // Doble red de seguridad: el service ya no debería propagar, pero si algo
                    // revienta aquí no se rompe el flujo del dominio.
                    logger.error("[Dispatcher] WHATSAPP send failed event={} user={} err={}",
                            eventType, recipientId, e.getMessage());
                }
            }
        }

        logger.debug("[Dispatcher] Event dispatched: {} owner={} recipients={} whatsappSent={} forced={}",
                eventType, ownerId, resolved.size(), whatsappSent, forceAllChannels);
    }

    private List<String> fallbackRecipient(String actorEmail) {
        List<String> out = new ArrayList<>();
        String actorUserId = resolveActorUserId(actorEmail);
        if (actorUserId != null) out.add(actorUserId);
        return out;
    }

    /**
     * Filtra SUPER_ADMIN de la lista de destinatarios. El rol SUPER_ADMIN no participa
     * en notificaciones de negocio: no tiene contactEmail/contactPhone/whatsapp y su
     * única responsabilidad es administrativa (crear dueños, reset password/MFA,
     * eliminar cuentas). Si un caller incluye un SUPER_ADMIN por error, lo descartamos
     * aquí de forma silenciosa y lo dejamos registrado a nivel debug.
     */
    private List<String> filterOutSuperAdmins(List<String> userIds, String eventType) {
        if (userIds == null || userIds.isEmpty()) return userIds;
        List<String> kept = new ArrayList<>(userIds.size());
        for (String id : userIds) {
            if (id == null || id.isBlank()) continue;
            Role role = userRepo.findById(id).map(UserEntity::getRole).orElse(null);
            if (role == Role.SUPER_ADMIN) {
                logger.debug("[Dispatcher] Descarto SUPER_ADMIN {} de recipientes del evento {}", id, eventType);
                continue;
            }
            kept.add(id);
        }
        return kept;
    }

    private boolean isChannelEnabled(String userId, String eventType, String channel) {
        return prefRepo.findByUserIdAndEventTypeAndChannel(userId, eventType, channel)
                .map(NotificationPreferenceEntity::isEnabled)
                .orElse(true);
    }

    private void createInAppNotification(String userId, String ownerId, String eventType, String title, String body) {
        NotificationEntity notif = new NotificationEntity();
        notif.setId(UUID.randomUUID().toString());
        notif.setUserId(userId);
        notif.setOwnerId(ownerId);
        notif.setEventType(eventType);
        notif.setTitle(title);
        notif.setBody(body);
        notif.setChannel(NotificationChannels.IN_APP);
        notif.setRead(false);
        notif.setCreatedAt(LocalDateTime.now());
        notificationRepo.save(notif);
    }

    private void createAuditRecord(String eventType, String actorEmail, String ownerId) {
        try {
            String actorRole = resolveActorRole(actorEmail);
            AuditEventEntity audit = new AuditEventEntity();
            audit.setId(UUID.randomUUID().toString());
            audit.setTimestamp(LocalDateTime.now());
            audit.setActorId(actorEmail);
            audit.setActorRole(actorRole);
            audit.setEventType(eventType);
            audit.setOwnerId(ownerId);
            audit.setResourceType("DOMAIN_EVENT");
            auditRepo.save(audit);
        } catch (Exception e) {
            logger.error("[Dispatcher] Failed to create audit record for {}: {}", eventType, e.getMessage());
        }
    }

    /**
     * Resuelve el userId del actor a partir del identificador que el caller haya
     * pasado en {@code actorEmail}.
     *
     * <p><b>V50 — semántica del parámetro:</b> aunque el parámetro se llama
     * {@code actorEmail} por compatibilidad con callsites históricos, el valor
     * esperado es el <em>username</em> del actor (el que expone
     * {@link org.springframework.security.core.Authentication#getName()} tras V48,
     * o el que regresa {@code user.getUsername()}).
     *
     * <p><b>V52 (2026-04-17) — eliminado el fallback findByEmail:</b> el email ya
     * no es único por diseño, varios usuarios pueden compartirlo. Si caíamos al
     * fallback de email y había colisión, el evento se atribuía a un actor
     * arbitrario (contaminando el audit log). Post-V52 sólo resolvemos por
     * username; si no hay match, el actor queda sin userId (el evento se
     * registra con {@code SYSTEM} o sin actor).
     */
    private String resolveActorUserId(String actorEmail) {
        if (actorEmail == null || "SYSTEM".equals(actorEmail)) return null;
        // V51 — username case-sensitive: solo trim, no lowercase.
        String trimmed = actorEmail.trim();
        return userRepo.findByUsername(trimmed)
                .map(UserEntity::getId)
                .orElse(null);
    }

    private String resolveActorRole(String actorEmail) {
        if (actorEmail == null || "SYSTEM".equals(actorEmail)) return "SYSTEM";
        String trimmed = actorEmail.trim();
        return userRepo.findByUsername(trimmed)
                .map(u -> u.getRole().name())
                .orElse("UNKNOWN");
    }
}
