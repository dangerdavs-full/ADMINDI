package com.admindi.backend.service;

import com.admindi.backend.dto.ManualReminderEligibilityDTO;
import com.admindi.backend.dto.ManualReminderResultDTO;
import com.admindi.backend.model.AuditEventEntity;
import com.admindi.backend.model.InvoiceEntity;
import com.admindi.backend.model.ManualPaymentReminderSentEntity;
import com.admindi.backend.model.PropertyEntity;
import com.admindi.backend.model.TenantProfileEntity;
import com.admindi.backend.model.UserEntity;
import com.admindi.backend.repository.AuditEventRepository;
import com.admindi.backend.repository.InvoiceRepository;
import com.admindi.backend.repository.ManualPaymentReminderSentRepository;
import com.admindi.backend.repository.PropertyRepository;
import com.admindi.backend.repository.TenantProfileRepository;
import com.admindi.backend.repository.UserRepository;
import com.admindi.backend.security.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Envío MANUAL de recordatorios de pago, disparado por el dueño o un administrador de
 * propiedades desde el perfil del inquilino (Fase B2 de notificaciones - cobranza real).
 *
 * <h3>Garantías de seguridad</h3>
 * <ol>
 *   <li><b>IDOR</b>: se valida que {@code tenantProfile.ownerId} coincida con el ownerId
 *       activo resuelto por {@link TenantContext}. Bloquea que un dueño empuje mensajes
 *       a inquilinos de otro portafolio.</li>
 *   <li><b>Reauth</b>: cada envío requiere password + TOTP válidos en la misma llamada
 *       ({@link ReauthService#verifyReauth}). NO hay token cacheado — cada click del botón
 *       exige credenciales frescas.</li>
 *   <li><b>Rate limit</b>: máximo 2 envíos manuales por inquilino en 24 horas, calculado
 *       contra {@code manual_payment_reminders_sent}. Protege contra abuso y contra cuentas
 *       comprometidas.</li>
 *   <li><b>Audit trail</b>: cada éxito emite fila en {@code audit_events} con
 *       {@code eventType=MANUAL_PAYMENT_REMINDER_SENT}, actor, target y meta JSON.</li>
 * </ol>
 *
 * <h3>Canales</h3>
 * Se invoca al dispatcher con {@code forceAllChannels=true}: el recordatorio manual ignora
 * las preferencias del inquilino porque es una acción deliberada del dueño con fricción alta
 * (password + MFA). Decisión de producto registrada en el ADR de Bloque B.
 *
 * <h3>Plantilla WhatsApp</h3>
 * Event type {@code MANUAL_PAYMENT_REMINDER}; el SID de la plantilla
 * {@code admindi_payment_reminder_manual_v1} se configura en
 * {@code application-secrets.yml}. Mientras Meta no apruebe la plantilla, WhatsApp cae a
 * body libre (solo entrega dentro de ventana 24h). Email + in-app funcionan sin plantilla.
 */
@Service
public class ManualPaymentReminderService {

    private static final Logger logger = LoggerFactory.getLogger(ManualPaymentReminderService.class);

    /** Tope de envíos manuales por inquilino por ventana móvil de 24 horas. */
    private static final int DAILY_LIMIT = 2;

    /** Ventana para contar envíos recientes al aplicar rate limit. */
    private static final long RATE_LIMIT_WINDOW_HOURS = 24;

    /** Motivos de no-elegibilidad (expuestos al frontend vía {@link ManualReminderEligibilityDTO}). */
    private static final String REASON_IDOR = "IDOR";
    private static final String REASON_NO_INVOICE = "NO_INVOICE_DUE";
    private static final String REASON_RATE_LIMIT = "RATE_LIMIT_REACHED";

    /** EventType único para distinguir en registry, dispatcher, audit y notifications. */
    private static final String EVENT_TYPE = "MANUAL_PAYMENT_REMINDER";

    private final UserRepository userRepository;
    private final TenantProfileRepository tenantProfileRepository;
    private final InvoiceRepository invoiceRepository;
    private final PropertyRepository propertyRepository;
    private final ManualPaymentReminderSentRepository trackingRepository;
    private final DomainEventDispatcher dispatcher;
    private final ReauthService reauthService;
    private final AuditEventRepository auditRepo;

    public ManualPaymentReminderService(UserRepository userRepository,
                                        TenantProfileRepository tenantProfileRepository,
                                        InvoiceRepository invoiceRepository,
                                        PropertyRepository propertyRepository,
                                        ManualPaymentReminderSentRepository trackingRepository,
                                        DomainEventDispatcher dispatcher,
                                        ReauthService reauthService,
                                        AuditEventRepository auditRepo) {
        this.userRepository = userRepository;
        this.tenantProfileRepository = tenantProfileRepository;
        this.invoiceRepository = invoiceRepository;
        this.propertyRepository = propertyRepository;
        this.trackingRepository = trackingRepository;
        this.dispatcher = dispatcher;
        this.reauthService = reauthService;
        this.auditRepo = auditRepo;
    }

    /**
     * Determina si el botón "Enviar recordatorio manual" debe mostrarse habilitado para el
     * inquilino dado. NO envía nada. NO lanza excepciones por IDOR — retorna eligibility con
     * reason=IDOR para que el frontend decida (normalmente no mostrar el botón).
     */
    @Transactional(readOnly = true)
    public ManualReminderEligibilityDTO checkEligibility(String tenantProfileId) {
        TenantProfileEntity profile = tenantProfileRepository.findById(tenantProfileId).orElse(null);
        if (profile == null || profile.getArchivedAt() != null) {
            return ManualReminderEligibilityDTO.ineligible(REASON_NO_INVOICE, DAILY_LIMIT);
        }

        String ownerIdActive = TenantContext.resolveOwnerId(userRepository);
        if (ownerIdActive == null || !ownerIdActive.equals(profile.getOwnerId())) {
            return ManualReminderEligibilityDTO.ineligible(REASON_IDOR, DAILY_LIMIT);
        }

        int remaining = remainingSendsToday(profile.getUserId());
        if (remaining <= 0) {
            return ManualReminderEligibilityDTO.ineligible(REASON_RATE_LIMIT, 0);
        }

        Optional<InvoiceEntity> targetOpt = findOldestUnpaidInvoice(tenantProfileId);
        if (targetOpt.isEmpty()) {
            return ManualReminderEligibilityDTO.ineligible(REASON_NO_INVOICE, remaining);
        }

        InvoiceEntity target = targetOpt.get();
        return ManualReminderEligibilityDTO.eligible(
                remaining, target.getId(), target.getTotalAmount(), target.getDueDate());
    }

    /**
     * Ejecuta el envío manual. Orden estricto de validaciones:
     * <ol>
     *   <li>Existe y vigente.</li>
     *   <li>IDOR.</li>
     *   <li>Hay invoice elegible.</li>
     *   <li>Rate limit no excedido.</li>
     *   <li>Reauth password + MFA (último porque registra intento en audit).</li>
     *   <li>Dispatch forzado por todos los canales.</li>
     *   <li>Tracking + audit de éxito.</li>
     * </ol>
     */
    @Transactional
    public ManualReminderResultDTO sendManualReminder(String tenantProfileId, String password,
                                                      String mfaCode) {
        TenantProfileEntity profile = tenantProfileRepository.findById(tenantProfileId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Inquilino no encontrado."));
        if (profile.getArchivedAt() != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "El expediente del inquilino está archivado.");
        }

        String ownerIdActive = TenantContext.resolveOwnerId(userRepository);
        if (ownerIdActive == null || !ownerIdActive.equals(profile.getOwnerId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Este inquilino no pertenece al portafolio activo.");
        }

        InvoiceEntity invoice = findOldestUnpaidInvoice(tenantProfileId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT,
                        "El inquilino no tiene facturas con saldo pendiente."));

        UserEntity tenant = userRepository.findById(profile.getUserId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Usuario del inquilino no encontrado."));
        if (!tenant.isActive()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "La cuenta del inquilino está inactiva.");
        }

        int remainingBefore = remainingSendsToday(tenant.getId());
        if (remainingBefore <= 0) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    "Ya enviaste el máximo de recordatorios a este inquilino en las últimas 24 horas.");
        }

        // Reauth DESPUÉS de las validaciones baratas: evita auditar intentos que iban
        // a fallar por IDOR o falta de factura con un falso "reauth attempt" ruidoso.
        try {
            reauthService.verifyReauth(password, mfaCode, EVENT_TYPE);
        } catch (RuntimeException e) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, e.getMessage());
        }

        String actorEmail = currentActorEmail();
        UserEntity actor = userRepository.findByLoginIdentifier(actorEmail).orElse(null);
        String actorUserId = actor != null ? actor.getId() : null;

        String title = "Recordatorio de pago";
        String body = buildBody(invoice, profile, tenant);
        Map<String, String> tplVars = buildTemplateVariables(invoice, profile, tenant);

        dispatcher.dispatch(
                EVENT_TYPE,
                title,
                body,
                profile.getOwnerId(),
                actorEmail,
                List.of(tenant.getId()),
                tplVars,
                null,
                true // forceAllChannels — decisión de producto documentada
        );

        ManualPaymentReminderSentEntity track = new ManualPaymentReminderSentEntity();
        track.setId(UUID.randomUUID().toString());
        track.setInvoiceId(invoice.getId());
        track.setTenantUserId(tenant.getId());
        track.setActorUserId(actorUserId != null ? actorUserId : "unknown");
        track.setOwnerId(profile.getOwnerId());
        track.setSentAt(LocalDateTime.now());
        trackingRepository.save(track);

        auditManualSendSuccess(actorEmail, actor, profile, tenant, invoice);

        int remainingAfter = Math.max(0, remainingBefore - 1);
        logger.info("[MANUAL-REMINDER] sent actor={} tenantProfile={} invoice={} remainingToday={}",
                actorEmail, tenantProfileId, invoice.getId(), remainingAfter);

        return new ManualReminderResultDTO(
                true,
                List.of("IN_APP", "EMAIL", "WHATSAPP"),
                remainingAfter,
                invoice.getId(),
                invoice.getTotalAmount(),
                invoice.getDueDate()
        );
    }

    private Optional<InvoiceEntity> findOldestUnpaidInvoice(String tenantProfileId) {
        return invoiceRepository.findByTenantProfileId(tenantProfileId).stream()
                .filter(ManualPaymentReminderService::isUnpaid)
                .min(Comparator.comparing(
                        InvoiceEntity::getDueDate,
                        Comparator.nullsLast(Comparator.naturalOrder())
                ));
    }

    private static boolean isUnpaid(InvoiceEntity inv) {
        String status = inv.getStatus();
        String settlement = inv.getSettlementStatus();
        if ("PAID".equalsIgnoreCase(status)) return false;
        if ("PAID".equalsIgnoreCase(settlement)) return false;
        if ("VOID".equalsIgnoreCase(status)) return false;
        if ("CANCELLED".equalsIgnoreCase(settlement)) return false;
        BigDecimal outstanding = inv.getOutstandingAmount();
        return outstanding == null || outstanding.signum() > 0;
    }

    private int remainingSendsToday(String tenantUserId) {
        LocalDateTime since = LocalDateTime.now().minusHours(RATE_LIMIT_WINDOW_HOURS);
        long used = trackingRepository.countByTenantUserIdAndSentAtAfter(tenantUserId, since);
        return Math.max(0, DAILY_LIMIT - (int) Math.min(used, Integer.MAX_VALUE));
    }

    /**
     * 6 slots, idéntico contrato a los templates automáticos de recordatorio.
     *   1 → nombre del arrendatario
     *   2 → nombre del inmueble
     *   3 → monto total sin "$"
     *   4 → fecha de vencimiento dd/MM/yyyy
     *   5 → nombre del banco del dueño
     *   6 → CLABE del dueño
     */
    private Map<String, String> buildTemplateVariables(InvoiceEntity invoice,
                                                      TenantProfileEntity profile,
                                                      UserEntity tenant) {
        String propertyName = "tu inmueble";
        if (profile.getPropertyId() != null && !profile.getPropertyId().isBlank()) {
            PropertyEntity prop = propertyRepository.findById(profile.getPropertyId()).orElse(null);
            if (prop != null && prop.getName() != null) propertyName = prop.getName();
        }

        UserEntity owner = userRepository.findById(profile.getOwnerId()).orElse(null);
        String bank = (owner != null && owner.getBankName() != null && !owner.getBankName().isBlank())
                ? owner.getBankName()
                : "tu banco de origen";
        String clabe = (owner != null && owner.getClabe() != null && !owner.getClabe().isBlank())
                ? owner.getClabe()
                : "(sin CLABE registrada)";

        // V62 — outstanding = saldo pendiente real (tras pagos parciales).
        // Solo caemos a totalAmount si outstanding viene null (facturas legacy).
        BigDecimal amount = resolveReminderAmount(invoice);
        String due = invoice.getDueDate() != null
                ? invoice.getDueDate().format(DateTimeFormatter.ofPattern("dd/MM/yyyy"))
                : "(sin fecha)";

        return Map.of(
                "1", tenant.getName() != null ? tenant.getName() : "",
                "2", propertyName,
                "3", amount.toPlainString(),
                "4", due,
                "5", bank,
                "6", clabe
        );
    }

    /**
     * V62 — Monto efectivo del recordatorio. Prefiere outstanding si es > 0;
     * cae a totalAmount si outstanding no existe o es 0/negativo.
     */
    private BigDecimal resolveReminderAmount(InvoiceEntity invoice) {
        BigDecimal outstanding = invoice.getOutstandingAmount();
        if (outstanding != null && outstanding.signum() > 0) return outstanding;
        return invoice.getTotalAmount() != null ? invoice.getTotalAmount() : BigDecimal.ZERO;
    }

    private String buildBody(InvoiceEntity invoice, TenantProfileEntity profile, UserEntity tenant) {
        String propertyName = "tu inmueble";
        if (profile.getPropertyId() != null && !profile.getPropertyId().isBlank()) {
            PropertyEntity prop = propertyRepository.findById(profile.getPropertyId()).orElse(null);
            if (prop != null && prop.getName() != null) propertyName = prop.getName();
        }

        UserEntity owner = userRepository.findById(profile.getOwnerId()).orElse(null);
        String bank = owner != null ? owner.getBankName() : null;
        String clabe = owner != null ? owner.getClabe() : null;

        BigDecimal outstanding = invoice.getOutstandingAmount();
        BigDecimal total = invoice.getTotalAmount();

        StringBuilder sb = new StringBuilder();
        sb.append("Hola ").append(tenant.getName() != null ? tenant.getName() : "").append(",\n\n");
        sb.append("Te enviamos un recordatorio de pago pendiente de tu renta en ").append(propertyName).append(".\n");
        if (outstanding != null && total != null
                && outstanding.signum() > 0 && outstanding.compareTo(total) < 0) {
            // V62 — pago parcial: comunica saldo pendiente vs total para que
            // quede claro que no se le cobra dos veces.
            sb.append("Saldo pendiente: $").append(outstanding.toPlainString())
              .append(" (de un total de $").append(total.toPlainString()).append(")\n");
        } else {
            BigDecimal amount = resolveReminderAmount(invoice);
            if (amount.signum() > 0) {
                sb.append("Monto por pagar: $").append(amount.toPlainString()).append("\n");
            }
        }
        if (invoice.getDueDate() != null) {
            sb.append("Fecha de vencimiento: ").append(invoice.getDueDate()).append("\n");
        }
        if (clabe != null && !clabe.isBlank()) {
            sb.append("\nDatos para transferencia SPEI:\n");
            if (bank != null && !bank.isBlank()) sb.append("  • Banco: ").append(bank).append("\n");
            sb.append("  • CLABE: ").append(clabe).append("\n");
        }
        sb.append("\nSi ya realizaste el pago, por favor ignora este mensaje o envíanos tu comprobante.");
        return sb.toString();
    }

    private void auditManualSendSuccess(String actorEmail, UserEntity actor,
                                        TenantProfileEntity profile, UserEntity tenant,
                                        InvoiceEntity invoice) {
        try {
            AuditEventEntity audit = new AuditEventEntity();
            audit.setId(UUID.randomUUID().toString());
            audit.setTimestamp(LocalDateTime.now());
            audit.setActorId(actorEmail);
            audit.setActorRole(actor != null && actor.getRole() != null ? actor.getRole().name() : "UNKNOWN");
            audit.setEventType("MANUAL_PAYMENT_REMINDER_SENT");
            audit.setResourceType("TENANT_PROFILE");
            audit.setResourceId(profile.getId());
            audit.setOwnerId(profile.getOwnerId());
            audit.setNewValues(String.format(
                    "{\"tenantUserId\":\"%s\",\"invoiceId\":\"%s\",\"amount\":\"%s\",\"dueDate\":\"%s\"}",
                    tenant.getId(),
                    invoice.getId(),
                    invoice.getTotalAmount() != null ? invoice.getTotalAmount().toPlainString() : "0",
                    invoice.getDueDate() != null ? invoice.getDueDate().toString() : ""
            ));
            auditRepo.save(audit);
        } catch (Exception e) {
            // El audit no debe nunca bloquear el flujo — ya enviamos la notificación.
            logger.warn("[MANUAL-REMINDER] audit fallido tenantProfile={} err={}",
                    profile.getId(), e.getMessage());
        }
    }

    private String currentActorEmail() {
        try {
            return SecurityContextHolder.getContext().getAuthentication().getName();
        } catch (Exception e) {
            return "unknown";
        }
    }
}
