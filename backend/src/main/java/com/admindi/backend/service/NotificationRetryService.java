package com.admindi.backend.service;

import com.admindi.backend.dto.ManualReminderResultDTO;
import com.admindi.backend.model.AuditEventEntity;
import com.admindi.backend.model.TenantProfileEntity;
import com.admindi.backend.repository.TenantProfileRepository;
import com.admindi.backend.repository.UserRepository;
import com.admindi.backend.security.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;

/**
 * Reintento manual de notificaciones fallidas (Bloque C - observabilidad).
 *
 * <h2>Alcance intencionalmente reducido</h2>
 * Solo soporta reintento de {@code MANUAL_PAYMENT_REMINDER}. Razones:
 * <ul>
 *   <li>Es el único evento donde un envío extra tiene valor operativo claro
 *       (persuadir al inquilino a pagar).</li>
 *   <li>Los recordatorios automáticos (1D/2D/3D/5D) ya los reintenta el cron al día
 *       siguiente — re-disparar manualmente no agrega valor y duplica spam.</li>
 *   <li>Los eventos de bienvenida/perfil-actualizado son one-shot y re-enviarlos fuera
 *       de su contexto original crea confusión en el destinatario.</li>
 * </ul>
 *
 * <p>El frontend consulta el eventType del audit-row y muestra el botón "Reintentar"
 * solo cuando {@code eventType == "MANUAL_PAYMENT_REMINDER"} y outcome=FAILED.
 *
 * <p>Seguridad: delega 100% a {@link ManualPaymentReminderService#sendManualReminder},
 * heredando su contrato completo (reauth password+MFA, rate limit 2/24h, scope IDOR,
 * audit, forceAllChannels). Esto garantiza que un retry NO bypassea ninguna defensa.
 */
@Service
public class NotificationRetryService {

    private static final Logger logger = LoggerFactory.getLogger(NotificationRetryService.class);
    private static final String SUPPORTED_EVENT = "MANUAL_PAYMENT_REMINDER";

    private final NotificationHistoryService historyService;
    private final ManualPaymentReminderService manualReminderService;
    private final TenantProfileRepository tenantProfileRepository;
    private final UserRepository userRepository;

    public NotificationRetryService(NotificationHistoryService historyService,
                                    ManualPaymentReminderService manualReminderService,
                                    TenantProfileRepository tenantProfileRepository,
                                    UserRepository userRepository) {
        this.historyService = historyService;
        this.manualReminderService = manualReminderService;
        this.tenantProfileRepository = tenantProfileRepository;
        this.userRepository = userRepository;
    }

    public ManualReminderResultDTO retry(String auditEventId, String password, String mfaCode) {
        AuditEventEntity row = historyService.findById(auditEventId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Evento de notificación no encontrado."));

        // Defensa IDOR: el auditEvent debe pertenecer al portafolio activo del caller.
        String ownerScope = TenantContext.resolveOwnerId(userRepository);
        if (ownerScope == null || !ownerScope.equals(row.getOwnerId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Este evento no pertenece al portafolio activo.");
        }

        // Solo reintentamos eventos fallidos: un SENT exitoso no tiene sentido reintentar,
        // un SKIPPED tiene una razón semántica válida (p.ej. tenant sin teléfono) que
        // reintentar tampoco resuelve.
        String rawType = row.getEventType();
        if (rawType == null || !rawType.contains("FAILED_")) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Solo se pueden reintentar envíos fallidos.");
        }

        // Limitación documentada: solo MANUAL_PAYMENT_REMINDER.
        if (!rawType.endsWith("_" + SUPPORTED_EVENT)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, String.format(
                    "Reintento no disponible para este tipo de notificación. " +
                    "Los recordatorios automáticos se reenvían solos al día siguiente; para " +
                    "otros avisos, genera uno nuevo manualmente si aplica."));
        }

        // Resolver el tenantProfileId activo del recipient.
        // El recipient puede tener múltiples profiles históricos (archivados); buscamos
        // el activo en el scope del owner. Si no existe → 409.
        String recipientUserId = row.getResourceId();
        if (recipientUserId == null || recipientUserId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "El evento no tiene destinatario válido.");
        }

        List<TenantProfileEntity> profiles = tenantProfileRepository
                .findByUserIdAndOwnerIdAndArchivedAtIsNull(recipientUserId, ownerScope);
        Optional<TenantProfileEntity> active = profiles.stream().findFirst();
        if (active.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "El inquilino ya no tiene expediente activo en este portafolio.");
        }

        logger.info("[NOTIF-RETRY] auditEvent={} actor={} tenantProfile={} delegating to ManualPaymentReminderService",
                auditEventId, TenantContext.resolveOwnerId(userRepository),
                active.get().getId());

        // Delegamos al servicio manual que ya valida reauth, rate limit y dispara por
        // los 3 canales. Si falla algún check (ej: rate limit alcanzado desde el último
        // reintento), ese error se propaga como ResponseStatusException y el frontend
        // lo muestra en el modal de reauth.
        return manualReminderService.sendManualReminder(active.get().getId(), password, mfaCode);
    }
}
