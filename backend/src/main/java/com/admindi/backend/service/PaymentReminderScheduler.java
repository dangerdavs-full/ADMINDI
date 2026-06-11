package com.admindi.backend.service;

import com.admindi.backend.model.InvoiceEntity;
import com.admindi.backend.model.PaymentReminderSentEntity;
import com.admindi.backend.model.PropertyEntity;
import com.admindi.backend.model.TenantProfileEntity;
import com.admindi.backend.model.UserEntity;
import com.admindi.backend.repository.InvoiceRepository;
import com.admindi.backend.repository.PaymentReminderSentRepository;
import com.admindi.backend.repository.PropertyRepository;
import com.admindi.backend.repository.TenantProfileRepository;
import com.admindi.backend.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Programa recordatorios de pago al arrendatario 5, 3, 2 y 1 días antes de la
 * fecha de vencimiento de cada factura no pagada (Fase 1 notificaciones).
 *
 * Tick diario 08:00 CST. Idempotente vía {@code payment_reminders_sent}
 * (unicidad por invoice + daysBefore + canal + destinatario): ejecutar el scheduler
 * 2 veces en el mismo día no duplica mensajes.
 *
 * Reglas:
 *  - Solo facturas con status != PAID y outstandingAmount > 0 (o null, por compat).
 *  - Solo para el arrendatario del `tenantProfileId` asociado a la factura.
 *  - El canal real (EMAIL/WHATSAPP) lo decide el {@link DomainEventDispatcher} según
 *    preferencias. Aquí marcamos en tracking el canal genérico "DISPATCH" para
 *    considerar el recordatorio enviado (el detalle por canal queda en audit_events
 *    vía EmailService / TwilioWhatsAppService).
 *  - Si algo revienta, se loguea y se continúa con el siguiente invoice: un fallo
 *    aislado no debe detener los demás recordatorios del día.
 */
@Service
public class PaymentReminderScheduler {

    private static final Logger logger = LoggerFactory.getLogger(PaymentReminderScheduler.class);

    /** Offsets en días antes del vencimiento en los que se dispara recordatorio. */
    private static final int[] REMINDER_OFFSETS = {5, 3, 2, 1};

    /** Canal "meta" usado en la tabla de tracking: el dispatcher decide IN_APP/EMAIL/WA. */
    private static final String DISPATCH_CHANNEL = "DISPATCH";

    private final InvoiceRepository invoiceRepo;
    private final TenantProfileRepository tenantProfileRepo;
    private final UserRepository userRepo;
    private final PropertyRepository propertyRepo;
    private final PaymentReminderSentRepository trackingRepo;
    private final DomainEventDispatcher dispatcher;

    public PaymentReminderScheduler(InvoiceRepository invoiceRepo,
                                    TenantProfileRepository tenantProfileRepo,
                                    UserRepository userRepo,
                                    PropertyRepository propertyRepo,
                                    PaymentReminderSentRepository trackingRepo,
                                    DomainEventDispatcher dispatcher) {
        this.invoiceRepo = invoiceRepo;
        this.tenantProfileRepo = tenantProfileRepo;
        this.userRepo = userRepo;
        this.propertyRepo = propertyRepo;
        this.trackingRepo = trackingRepo;
        this.dispatcher = dispatcher;
    }

    /**
     * Tick diario. Cron en zona CDMX para que el mensaje llegue en horario razonable
     * (08:00 local) independientemente de la zona del servidor.
     */
    @Scheduled(cron = "${admindi.reminders.cron:0 0 8 * * *}", zone = "America/Mexico_City")
    public void sendDailyReminders() {
        LocalDate today = LocalDate.now(java.time.ZoneId.of("America/Mexico_City"));
        int dispatched = 0;

        for (int daysBefore : REMINDER_OFFSETS) {
            LocalDate targetDueDate = today.plusDays(daysBefore);
            List<InvoiceEntity> dueInvoices = invoiceRepo.findByDueDate(targetDueDate).stream()
                    .filter(PaymentReminderScheduler::isUnpaid)
                    .toList();

            for (InvoiceEntity inv : dueInvoices) {
                try {
                    if (processInvoiceReminder(inv, daysBefore)) {
                        dispatched++;
                    }
                } catch (Exception ex) {
                    logger.warn("[REMINDER] failed invoice={} daysBefore={} err={}",
                            inv.getId(), daysBefore, ex.getMessage());
                }
            }
        }

        if (dispatched > 0) {
            logger.info("[REMINDER] daily tick done: dispatched={} reminders", dispatched);
        }
    }

    private boolean processInvoiceReminder(InvoiceEntity inv, int daysBefore) {
        TenantProfileEntity profile = tenantProfileRepo.findById(inv.getTenantProfileId()).orElse(null);
        if (profile == null || profile.getArchivedAt() != null) return false;

        UserEntity tenant = userRepo.findById(profile.getUserId()).orElse(null);
        if (tenant == null || !tenant.isActive()) return false;

        // Idempotencia: si ya enviamos este recordatorio (invoice + daysBefore + canal + user), saltar.
        if (trackingRepo.existsByInvoiceIdAndDaysBeforeAndChannelAndRecipientUserId(
                inv.getId(), daysBefore, DISPATCH_CHANNEL, tenant.getId())) {
            return false;
        }

        String eventType = "TENANT_PAYMENT_REMINDER_" + daysBefore + "D";
        String title = "Recordatorio de pago (" + daysBefore + " "
                + (daysBefore == 1 ? "día" : "días") + ")";
        String body = buildBody(inv, profile, tenant, daysBefore);

        // Variables WhatsApp — plantillas admindi_payment_reminder_{5,3,2,1}d_v1 (6 slots).
        // Estas 4 plantillas comparten estructura: solo cambia el texto de "faltan N días"
        // vs "mañana", pero el SLOT MAP es idéntico para las 4.
        Map<String, String> tplVars = buildReminderTemplateVars(inv, profile, tenant);

        dispatcher.dispatch(
                eventType,
                title,
                body,
                profile.getOwnerId(),
                "SYSTEM",
                List.of(tenant.getId()),
                tplVars,
                null
        );

        PaymentReminderSentEntity track = new PaymentReminderSentEntity();
        track.setId(UUID.randomUUID().toString());
        track.setInvoiceId(inv.getId());
        track.setDaysBefore(daysBefore);
        track.setChannel(DISPATCH_CHANNEL);
        track.setRecipientUserId(tenant.getId());
        track.setSentAt(LocalDateTime.now());
        trackingRepo.save(track);
        return true;
    }

    /**
     * Construye el mapa de contentVariables para las 4 plantillas de recordatorio
     * ({@code admindi_payment_reminder_5d_v1}, {@code _3d_v1}, {@code _2d_v1}, {@code _1d_v1}).
     *
     * Slots (idénticos en las 4 plantillas):
     *   1 → nombre del arrendatario
     *   2 → nombre del inmueble
     *   3 → monto total formateado (sin "$")
     *   4 → fecha de vencimiento dd/MM/yyyy
     *   5 → nombre del banco del dueño (o "tu banco de origen" si no hay)
     *   6 → CLABE del dueño (o "(sin CLABE registrada)" si el dueño no la capturó)
     */
    private Map<String, String> buildReminderTemplateVars(InvoiceEntity inv,
                                                           TenantProfileEntity profile,
                                                           UserEntity tenant) {
        String propertyName = "tu inmueble";
        if (profile.getPropertyId() != null && !profile.getPropertyId().isBlank()) {
            PropertyEntity prop = propertyRepo.findById(profile.getPropertyId()).orElse(null);
            if (prop != null && prop.getName() != null) propertyName = prop.getName();
        }

        UserEntity owner = userRepo.findById(profile.getOwnerId()).orElse(null);
        String clabe = (owner != null && owner.getClabe() != null && !owner.getClabe().isBlank())
                ? owner.getClabe()
                : "(sin CLABE registrada)";
        String bank = (owner != null && owner.getBankName() != null && !owner.getBankName().isBlank())
                ? owner.getBankName()
                : "tu banco de origen";

        // V62 — el recordatorio debe reflejar lo que REALMENTE debe el inquilino,
        // no el total original de la factura. Si ya pagó parcial, outstanding<total.
        // Fallback a totalAmount solo si outstanding es null (facturas legacy).
        BigDecimal amount = resolveReminderAmount(inv);
        String due = inv.getDueDate() != null
                ? inv.getDueDate().format(DateTimeFormatter.ofPattern("dd/MM/yyyy"))
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
     * V62 — Monto a mostrar en el recordatorio: outstanding (lo que falta) si
     * está disponible y positivo, total como fallback. Nunca retorna null.
     */
    private BigDecimal resolveReminderAmount(InvoiceEntity inv) {
        BigDecimal outstanding = inv.getOutstandingAmount();
        if (outstanding != null && outstanding.signum() > 0) return outstanding;
        return inv.getTotalAmount() != null ? inv.getTotalAmount() : BigDecimal.ZERO;
    }

    private String buildBody(InvoiceEntity inv, TenantProfileEntity profile,
                             UserEntity tenant, int daysBefore) {
        String propertyName = "tu inmueble";
        if (profile.getPropertyId() != null && !profile.getPropertyId().isBlank()) {
            PropertyEntity prop = propertyRepo.findById(profile.getPropertyId()).orElse(null);
            if (prop != null && prop.getName() != null) propertyName = prop.getName();
        }

        UserEntity owner = userRepo.findById(profile.getOwnerId()).orElse(null);
        String clabe = owner != null && owner.getClabe() != null ? owner.getClabe() : null;
        String bank = owner != null && owner.getBankName() != null ? owner.getBankName() : null;

        BigDecimal outstanding = inv.getOutstandingAmount();
        BigDecimal total = inv.getTotalAmount();

        StringBuilder sb = new StringBuilder();
        sb.append("Hola ").append(tenant.getName() != null ? tenant.getName() : "").append(",\n\n");
        sb.append("Te recordamos que tu renta de ").append(propertyName);
        if (outstanding != null && total != null
                && outstanding.signum() > 0 && outstanding.compareTo(total) < 0) {
            // V62 — pago parcial: aclara cuánto falta y cuánto era el total.
            sb.append(" tiene un saldo pendiente de $")
              .append(outstanding.toPlainString())
              .append(" (de un total de $")
              .append(total.toPlainString())
              .append(")");
        } else {
            BigDecimal amount = resolveReminderAmount(inv);
            if (amount.signum() > 0) {
                sb.append(" por $").append(amount.toPlainString());
            }
        }
        sb.append(" vence ");
        if (daysBefore == 1) {
            sb.append("mañana");
        } else {
            sb.append("en ").append(daysBefore).append(" días");
        }
        sb.append(" (").append(inv.getDueDate()).append(").\n");

        if (clabe != null && !clabe.isBlank()) {
            sb.append("\nDatos para transferencia SPEI:\n");
            if (bank != null) sb.append("  • Banco: ").append(bank).append("\n");
            sb.append("  • CLABE: ").append(clabe).append("\n");
        }
        sb.append("\nSi ya pagaste, ignora este mensaje.");
        return sb.toString();
    }

    private static boolean isUnpaid(InvoiceEntity inv) {
        String status = inv.getStatus();
        String settlement = inv.getSettlementStatus();
        if ("PAID".equalsIgnoreCase(status)) return false;
        if ("PAID".equalsIgnoreCase(settlement)) return false;
        java.math.BigDecimal outstanding = inv.getOutstandingAmount();
        if (outstanding != null && outstanding.signum() <= 0) return false;
        return true;
    }
}
