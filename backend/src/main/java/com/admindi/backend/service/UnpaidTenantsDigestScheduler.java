package com.admindi.backend.service;

import com.admindi.backend.model.InvoiceEntity;
import com.admindi.backend.model.PropertyEntity;
import com.admindi.backend.model.Role;
import com.admindi.backend.model.TenantProfileEntity;
import com.admindi.backend.model.UserEntity;
import com.admindi.backend.repository.InvoiceRepository;
import com.admindi.backend.repository.NotificationRepository;
import com.admindi.backend.repository.PropertyRepository;
import com.admindi.backend.repository.TenantProfileRepository;
import com.admindi.backend.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Digest diario para el DUEÑO con los inquilinos que tienen renta vencida al día de hoy.
 *
 * Diseño (Bloque B — notificaciones):
 *   - Se ejecuta cada mañana a las 09:00 en zona {@code America/Mexico_City} (una hora después
 *     del cron de {@link PaymentReminderScheduler} para que si un arrendatario paga esa mañana
 *     el digest al dueño ya refleje el estado actualizado).
 *   - Para cada usuario con rol OWNER activo, carga sus facturas vía
 *     {@link InvoiceRepository#findByOwnerId(String)} y filtra las que están VENCIDAS al día de
 *     hoy (dueDate &lt;= today) y aún no liquidadas.
 *   - Si hay al menos una factura vencida, se dispatcha el evento
 *     {@code OWNER_UNPAID_TENANTS_DIGEST} con un resumen agregado (cantidad, monto total,
 *     lista corta de arrendatarios).
 *   - Si no hay facturas vencidas, NO se envía nada (evitamos correo "todo bien" diario).
 *
 * Idempotencia:
 *   - Antes de dispatchar se consulta {@link NotificationRepository#existsRecentForUser} con
 *     ventana de 20 horas. Esto evita duplicar el digest si el proceso se reinicia entre
 *     las 09:00 y las 10:00 (el Spring scheduler redispara el job al arrancar si misfire).
 *
 * Seguridad:
 *   - No se exponen montos ni datos personales en logs (solo IDs y contadores).
 *   - Cualquier fallo por dueño queda en try/catch y NO detiene el loop del cron.
 */
@Service
public class UnpaidTenantsDigestScheduler {

    private static final Logger logger = LoggerFactory.getLogger(UnpaidTenantsDigestScheduler.class);

    /** Evento del catálogo de notificaciones (ver {@code NotificationEventCatalog}). */
    private static final String EVENT_TYPE = "OWNER_UNPAID_TENANTS_DIGEST";

    /** Ventana de idempotencia: 20h. Suficiente para cubrir reinicios del mismo ciclo diario. */
    private static final long IDEMPOTENCY_WINDOW_HOURS = 20L;

    /** Máximo de arrendatarios a enumerar en el cuerpo; si hay más, se trunca con "+N más". */
    private static final int MAX_TENANTS_IN_BODY = 5;

    private static final ZoneId MEXICO_CITY = ZoneId.of("America/Mexico_City");
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final InvoiceRepository invoiceRepo;
    private final UserRepository userRepo;
    private final TenantProfileRepository tenantProfileRepo;
    private final PropertyRepository propertyRepo;
    private final NotificationRepository notificationRepo;
    private final DomainEventDispatcher dispatcher;

    @Value("${app.url:http://localhost:3000}")
    private String appUrl;

    public UnpaidTenantsDigestScheduler(InvoiceRepository invoiceRepo,
                                        UserRepository userRepo,
                                        TenantProfileRepository tenantProfileRepo,
                                        PropertyRepository propertyRepo,
                                        NotificationRepository notificationRepo,
                                        DomainEventDispatcher dispatcher) {
        this.invoiceRepo = invoiceRepo;
        this.userRepo = userRepo;
        this.tenantProfileRepo = tenantProfileRepo;
        this.propertyRepo = propertyRepo;
        this.notificationRepo = notificationRepo;
        this.dispatcher = dispatcher;
    }

    /**
     * Tick diario. Por defecto 09:00 CDMX; puede sobreescribirse vía
     * {@code admindi.digest.unpaid.cron} en {@code application.yml} para tests.
     */
    @Scheduled(cron = "${admindi.digest.unpaid.cron:0 0 9 * * *}", zone = "America/Mexico_City")
    public void sendDailyDigest() {
        LocalDate today = LocalDate.now(MEXICO_CITY);
        LocalDateTime idempotencyCutoff = LocalDateTime.now().minusHours(IDEMPOTENCY_WINDOW_HOURS);

        int dispatched = 0;
        int skippedIdempotent = 0;
        int skippedNoUnpaid = 0;

        List<UserEntity> owners = userRepo.findByRoleAndActiveTrue(Role.OWNER);

        for (UserEntity owner : owners) {
            try {
                if (processOwner(owner, today, idempotencyCutoff)) {
                    dispatched++;
                } else {
                    // Determinar motivo para logs agregados
                    if (notificationRepo.existsRecentForUser(owner.getId(), EVENT_TYPE, idempotencyCutoff)) {
                        skippedIdempotent++;
                    } else {
                        skippedNoUnpaid++;
                    }
                }
            } catch (Exception ex) {
                logger.warn("[DIGEST-UNPAID] owner={} failed: {}", owner.getId(), ex.getMessage());
            }
        }

        if (dispatched > 0 || skippedIdempotent > 0) {
            logger.info("[DIGEST-UNPAID] daily tick: owners={} dispatched={} skippedIdempotent={} skippedNoUnpaid={}",
                    owners.size(), dispatched, skippedIdempotent, skippedNoUnpaid);
        }
    }

    /**
     * @return true si se dispatchó el digest para este dueño, false si se saltó (idempotencia
     *         o no hay morosos).
     */
    private boolean processOwner(UserEntity owner, LocalDate today, LocalDateTime idempotencyCutoff) {
        // Idempotencia: evitar duplicados si el cron se redispara.
        if (notificationRepo.existsRecentForUser(owner.getId(), EVENT_TYPE, idempotencyCutoff)) {
            return false;
        }

        List<InvoiceEntity> allInvoices = invoiceRepo.findByOwnerId(owner.getId());
        List<InvoiceEntity> overdue = allInvoices.stream()
                .filter(inv -> inv.getDueDate() != null && !inv.getDueDate().isAfter(today))
                .filter(UnpaidTenantsDigestScheduler::isUnpaid)
                .collect(Collectors.toList());

        if (overdue.isEmpty()) {
            return false;
        }

        BigDecimal total = overdue.stream()
                .map(inv -> inv.getOutstandingAmount() != null ? inv.getOutstandingAmount() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        String title = "Resumen de inquilinos con pago vencido (" + today.format(DATE_FMT) + ")";
        String body = buildDigestBody(owner, overdue, total, today);

        // Variables WhatsApp — plantilla admindi_unpaid_digest_v2 (5 slots).
        String shortList = buildShortTenantList(overdue);
        Map<String, String> tplVars = Map.of(
                "1", owner.getName() != null ? owner.getName() : "",
                "2", String.valueOf(overdue.size()),
                "3", total.toPlainString(),
                "4", shortList,
                "5", appUrl != null ? appUrl : ""
        );

        dispatcher.dispatch(
                EVENT_TYPE,
                title,
                body,
                owner.getId(),
                "SYSTEM",
                List.of(owner.getId()),
                tplVars,
                null
        );
        return true;
    }

    /**
     * Lista compacta de arrendatarios morosos para el slot {{4}} de la plantilla.
     * Formato: "Juan Pérez, María García y 3 más" — sin saltos de línea porque los
     * slots de Twilio no soportan ni line breaks ni URLs embebidas.
     */
    private String buildShortTenantList(List<InvoiceEntity> overdue) {
        int shown = Math.min(overdue.size(), MAX_TENANTS_IN_BODY);
        List<String> names = new ArrayList<>(shown);
        for (int i = 0; i < shown; i++) {
            names.add(resolveTenantName(overdue.get(i)));
        }
        String joined = String.join(", ", names);
        int remaining = overdue.size() - shown;
        if (remaining > 0) {
            joined += " y " + remaining + " más";
        }
        return joined;
    }

    private String buildDigestBody(UserEntity owner, List<InvoiceEntity> overdue,
                                    BigDecimal total, LocalDate today) {
        StringBuilder sb = new StringBuilder();
        sb.append("Hola ").append(owner.getName() != null ? owner.getName() : "").append(",\n\n");
        sb.append("Al día de hoy ").append(today.format(DATE_FMT))
          .append(" tienes ").append(overdue.size())
          .append(" factura").append(overdue.size() == 1 ? "" : "s")
          .append(" vencida").append(overdue.size() == 1 ? "" : "s")
          .append(" por un total de $").append(total.toPlainString()).append(".\n\n");

        sb.append("Detalle:\n");
        int shown = 0;
        for (InvoiceEntity inv : overdue) {
            if (shown >= MAX_TENANTS_IN_BODY) break;
            String tenantName = resolveTenantName(inv);
            String propertyName = resolvePropertyName(inv);
            long daysOverdue = java.time.temporal.ChronoUnit.DAYS.between(inv.getDueDate(), today);
            BigDecimal outstanding = inv.getOutstandingAmount() != null
                    ? inv.getOutstandingAmount() : BigDecimal.ZERO;

            sb.append("  • ").append(tenantName)
              .append(" (").append(propertyName).append(") — $")
              .append(outstanding.toPlainString())
              .append(" · ").append(daysOverdue)
              .append(" día").append(daysOverdue == 1 ? "" : "s")
              .append(" de atraso · periodo ")
              .append(inv.getMonthYear() != null ? inv.getMonthYear() : "(n/d)")
              .append("\n");
            shown++;
        }
        int remaining = overdue.size() - shown;
        if (remaining > 0) {
            sb.append("  • …y ").append(remaining).append(" más.\n");
        }

        sb.append("\nEl sistema ya envió recordatorios automáticos a los arrendatarios 5, 3, 2 y 1 día antes del vencimiento. ");
        sb.append("Si quieres escalar el seguimiento, ingresa a tu portal.");
        return sb.toString();
    }

    private String resolveTenantName(InvoiceEntity inv) {
        try {
            if (inv.getTenantProfileId() == null) return "(arrendatario)";
            TenantProfileEntity profile = tenantProfileRepo.findById(inv.getTenantProfileId()).orElse(null);
            if (profile == null || profile.getUserId() == null) return "(arrendatario)";
            UserEntity tenant = userRepo.findById(profile.getUserId()).orElse(null);
            return tenant != null && tenant.getName() != null ? tenant.getName() : "(arrendatario)";
        } catch (Exception ignored) {
            return "(arrendatario)";
        }
    }

    private String resolvePropertyName(InvoiceEntity inv) {
        try {
            if (inv.getTenantProfileId() == null) return "(inmueble)";
            TenantProfileEntity profile = tenantProfileRepo.findById(inv.getTenantProfileId()).orElse(null);
            if (profile == null || profile.getPropertyId() == null) return "(inmueble)";
            PropertyEntity prop = propertyRepo.findById(profile.getPropertyId()).orElse(null);
            return prop != null && prop.getName() != null ? prop.getName() : "(inmueble)";
        } catch (Exception ignored) {
            return "(inmueble)";
        }
    }

    private static boolean isUnpaid(InvoiceEntity inv) {
        if (inv == null) return false;
        String status = inv.getStatus();
        String settlement = inv.getSettlementStatus();
        if ("PAID".equalsIgnoreCase(status)) return false;
        if ("PAID".equalsIgnoreCase(settlement)) return false;
        BigDecimal outstanding = inv.getOutstandingAmount();
        if (outstanding != null && outstanding.signum() <= 0) return false;
        return true;
    }

    /** Para tests unitarios externos (si quieren invocar el tick sin esperar al cron). */
    @SuppressWarnings("unused")
    public int runNowForTesting() {
        LocalDate today = LocalDate.now(MEXICO_CITY);
        LocalDateTime cutoff = LocalDateTime.now().minusHours(IDEMPOTENCY_WINDOW_HOURS);
        int count = 0;
        List<UserEntity> owners = new ArrayList<>(userRepo.findByRoleAndActiveTrue(Role.OWNER));
        for (UserEntity u : owners) {
            try {
                if (processOwner(u, today, cutoff)) count++;
            } catch (Exception ignored) { /* continuar loop */ }
        }
        return count;
    }
}
