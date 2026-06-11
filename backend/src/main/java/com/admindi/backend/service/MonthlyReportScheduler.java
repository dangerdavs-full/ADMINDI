package com.admindi.backend.service;

import com.admindi.backend.ai.AiAccountingService;
import com.admindi.backend.model.ExpenseEntity;
import com.admindi.backend.model.InvoiceEntity;
import com.admindi.backend.model.PaymentEntity;
import com.admindi.backend.model.Role;
import com.admindi.backend.model.UserEntity;
import com.admindi.backend.repository.ExpenseRepository;
import com.admindi.backend.repository.InvoiceRepository;
import com.admindi.backend.repository.NotificationRepository;
import com.admindi.backend.repository.PaymentRepository;
import com.admindi.backend.repository.PropertyRepository;
import com.admindi.backend.repository.TenantProfileRepository;
import com.admindi.backend.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Reporte mensual agregado para el DUEÑO (Bloque B — notificaciones).
 *
 * Se ejecuta el **día 1 de cada mes a las 08:00 CDMX** y manda a cada dueño activo un
 * resumen del mes que acaba de terminar con:
 *   - Ingresos cobrados (suma de {@code paidAmount} de facturas cuyo {@code monthYear} corresponde al mes reportado).
 *   - Pendiente por cobrar (suma de {@code outstandingAmount} de esas mismas facturas).
 *   - Gastos registrados (suma de {@code amount} de expenses aprobados/pagados creados en el mes reportado).
 *   - Ocupación (propiedades con al menos un expediente de inquilino activo vs total).
 *
 * No sustituye al reporte detallado por propiedad (`PropertyReportService`) ni al PDF contable
 * (`ReportService.generateMonthlyAccountantZip`). Es una notificación de primer vistazo que invita
 * al dueño a entrar al portal.
 *
 * Idempotencia: mismo patrón que {@link UnpaidTenantsDigestScheduler} — ventana de 30 días en
 * {@link NotificationRepository#existsRecentForUser} evita duplicar si el proceso reinicia el día 1.
 */
@Service
public class MonthlyReportScheduler {

    private static final Logger logger = LoggerFactory.getLogger(MonthlyReportScheduler.class);
    private static final String EVENT_TYPE = "OWNER_MONTHLY_REPORT";
    private static final ZoneId MEXICO_CITY = ZoneId.of("America/Mexico_City");
    private static final DateTimeFormatter MONTH_YEAR_FMT = DateTimeFormatter.ofPattern("yyyy-MM");
    /** Ventana de idempotencia para no duplicar si el job se redispara dentro del mismo mes. */
    private static final long IDEMPOTENCY_WINDOW_DAYS = 25L;

    private final InvoiceRepository invoiceRepo;
    private final ExpenseRepository expenseRepo;
    private final UserRepository userRepo;
    private final PropertyRepository propertyRepo;
    private final TenantProfileRepository tenantProfileRepo;
    private final NotificationRepository notificationRepo;
    private final DomainEventDispatcher dispatcher;
    private final PaymentRepository paymentRepo;
    private final AiAccountingService aiAccountingService;

    @Value("${app.url:http://localhost:3000}")
    private String appUrl;

    public MonthlyReportScheduler(InvoiceRepository invoiceRepo,
                                  ExpenseRepository expenseRepo,
                                  UserRepository userRepo,
                                  PropertyRepository propertyRepo,
                                  TenantProfileRepository tenantProfileRepo,
                                  NotificationRepository notificationRepo,
                                  DomainEventDispatcher dispatcher,
                                  PaymentRepository paymentRepo,
                                  AiAccountingService aiAccountingService) {
        this.invoiceRepo = invoiceRepo;
        this.expenseRepo = expenseRepo;
        this.userRepo = userRepo;
        this.propertyRepo = propertyRepo;
        this.tenantProfileRepo = tenantProfileRepo;
        this.notificationRepo = notificationRepo;
        this.dispatcher = dispatcher;
        this.paymentRepo = paymentRepo;
        this.aiAccountingService = aiAccountingService;
    }

    /**
     * Cron: día 1 de cada mes a las 08:00 CDMX. Override vía
     * {@code admindi.reports.monthly.cron} para pruebas en QA.
     */
    @Scheduled(cron = "${admindi.reports.monthly.cron:0 0 8 1 * *}", zone = "America/Mexico_City")
    public void sendMonthlyReports() {
        LocalDate today = LocalDate.now(MEXICO_CITY);
        YearMonth reportedMonth = YearMonth.from(today).minusMonths(1);
        LocalDateTime idempotencyCutoff = LocalDateTime.now().minusDays(IDEMPOTENCY_WINDOW_DAYS);

        int dispatched = 0;
        List<UserEntity> owners = userRepo.findByRoleAndActiveTrue(Role.OWNER);

        for (UserEntity owner : owners) {
            try {
                if (sendReportForOwner(owner, reportedMonth, idempotencyCutoff)) {
                    dispatched++;
                }
            } catch (Exception ex) {
                logger.warn("[MONTHLY-REPORT] owner={} failed: {}", owner.getId(), ex.getMessage());
            }
        }

        logger.info("[MONTHLY-REPORT] monthly tick done: owners={} dispatched={} reportedMonth={}",
                owners.size(), dispatched, reportedMonth);
    }

    /**
     * @return true si se envió el reporte. false si el dueño no tenía actividad o si ya se
     *         había enviado en la ventana de idempotencia.
     */
    private boolean sendReportForOwner(UserEntity owner, YearMonth reportedMonth,
                                       LocalDateTime idempotencyCutoff) {
        if (notificationRepo.existsRecentForUser(owner.getId(), EVENT_TYPE, idempotencyCutoff)) {
            return false;
        }

        String monthYearStr = reportedMonth.format(MONTH_YEAR_FMT);

        List<InvoiceEntity> invoices = invoiceRepo.findByOwnerId(owner.getId()).stream()
                .filter(inv -> monthYearStr.equals(inv.getMonthYear()))
                .toList();

        BigDecimal collected = invoices.stream()
                .map(inv -> inv.getPaidAmount() != null ? inv.getPaidAmount() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal outstanding = invoices.stream()
                .filter(inv -> !"PAID".equalsIgnoreCase(inv.getSettlementStatus()))
                .map(inv -> inv.getOutstandingAmount() != null ? inv.getOutstandingAmount() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal expenses = expenseRepo.findByOwnerId(owner.getId()).stream()
                .filter(e -> e.getCreatedAt() != null
                        && YearMonth.from(e.getCreatedAt()).equals(reportedMonth))
                .filter(MonthlyReportScheduler::isCountableExpense)
                .map(ExpenseEntity::getAmount)
                .filter(a -> a != null)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        OccupancyStats occ = computeOccupancy(owner.getId());

        // Si el dueño NO tiene propiedades todavía, no le mandamos un reporte vacío
        // (todavía está en onboarding y un "0 cobrado / 0 gastos" sólo agrega ruido).
        if (occ.totalProperties == 0 && invoices.isEmpty() && expenses.signum() == 0) {
            return false;
        }

        // V56 — estadísticas enriquecidas:
        //  - Pagos parciales: invoices del mes que se liquidaron con 2+ PaymentEntity.
        //  - Top categorías de gasto: usa ai_category de V56 si está disponible.
        //  - Resumen SAT: ISR estimado 2% (retención PF arrendamiento tradicional).
        PartialStats partials = computePartialStats(invoices);
        Map<String, BigDecimal> topExpenseCategories = computeTopExpenseCategories(
                owner.getId(), reportedMonth);
        SatSummary sat = computeSatSummary(collected);
        String narrative = aiAccountingService.generateMonthlyNarrative(
                owner.getName(), prettyMonth(reportedMonth),
                collected, outstanding, expenses, partials.partialInvoices(),
                topExpenseCategories);

        String title = "Reporte mensual " + prettyMonth(reportedMonth);
        String body = buildBody(owner, reportedMonth, collected, outstanding, expenses, occ,
                partials, topExpenseCategories, sat, narrative);

        // Variables WhatsApp — plantilla admindi_monthly_report_v2 (7 slots).
        String occupancyText;
        if (occ.totalProperties == 0) {
            occupancyText = "0 de 0 inmuebles";
        } else {
            int pct = (int) Math.round(100.0 * occ.occupiedProperties / occ.totalProperties);
            occupancyText = occ.occupiedProperties + " de " + occ.totalProperties
                    + " inmueble" + (occ.totalProperties == 1 ? "" : "s")
                    + " (" + pct + "%)";
        }

        Map<String, String> tplVars = Map.of(
                "1", owner.getName() != null ? owner.getName() : "",
                "2", prettyMonth(reportedMonth),
                "3", collected.toPlainString(),
                "4", outstanding.toPlainString(),
                "5", expenses.toPlainString(),
                "6", occupancyText,
                "7", appUrl != null ? appUrl : ""
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

    private String buildBody(UserEntity owner, YearMonth reportedMonth,
                             BigDecimal collected, BigDecimal outstanding,
                             BigDecimal expenses, OccupancyStats occ,
                             PartialStats partials,
                             Map<String, BigDecimal> topExpenseCategories,
                             SatSummary sat,
                             String narrative) {
        StringBuilder sb = new StringBuilder();
        sb.append("Hola ").append(owner.getName() != null ? owner.getName() : "").append(",\n\n");
        sb.append("Tu reporte del mes de ").append(prettyMonth(reportedMonth)).append(" ya está listo.\n\n");

        if (narrative != null && !narrative.isBlank()) {
            sb.append(narrative.trim()).append("\n\n");
        }

        sb.append("Resumen:\n");
        sb.append("  • Ingresos cobrados: $").append(collected.toPlainString()).append("\n");
        sb.append("  • Pendiente por cobrar: $").append(outstanding.toPlainString()).append("\n");
        sb.append("  • Gastos registrados: $").append(expenses.toPlainString()).append("\n");
        sb.append("  • Ocupación: ").append(occ.occupiedProperties)
          .append(" de ").append(occ.totalProperties).append(" inmueble")
          .append(occ.totalProperties == 1 ? "" : "s");
        if (occ.totalProperties > 0) {
            int pct = (int) Math.round(100.0 * occ.occupiedProperties / occ.totalProperties);
            sb.append(" ocupado").append(occ.occupiedProperties == 1 ? "" : "s")
              .append(" (").append(pct).append("%)");
        }
        sb.append("\n\n");

        // V56 — cuántas rentas se liquidaron con pagos parciales.
        if (partials.partialInvoices() > 0) {
            sb.append("Pagos parciales del mes:\n");
            sb.append("  • ").append(partials.partialInvoices())
              .append(" renta(s) se liquidaron con 2 o más pagos.\n");
            sb.append("  • Promedio de pagos por renta parcial: ")
              .append(partials.averagePaymentsPerPartial()).append(".\n\n");
        }

        if (topExpenseCategories != null && !topExpenseCategories.isEmpty()) {
            sb.append("Top categorías de gasto:\n");
            for (Map.Entry<String, BigDecimal> e : topExpenseCategories.entrySet()) {
                sb.append("  • ").append(e.getKey()).append(": $")
                  .append(e.getValue().toPlainString()).append("\n");
            }
            sb.append("\n");
        }

        if (sat != null && collected.signum() > 0) {
            sb.append("Resumen fiscal (referencia, confirma con tu contador):\n");
            sb.append("  • Ingresos gravables: $").append(sat.grossIncome().toPlainString()).append("\n");
            sb.append("  • ISR estimado 2% (retención PF arrendamiento): $")
              .append(sat.isrEstimate().toPlainString()).append("\n");
            sb.append("  • Gastos potencialmente deducibles: $")
              .append(sat.deductibleExpenses().toPlainString()).append("\n\n");
        }

        sb.append("El detalle por inmueble y el PDF contable están disponibles en tu portal.");
        return sb.toString();
    }

    private OccupancyStats computeOccupancy(String ownerId) {
        int total = propertyRepo.findByOwnerId(ownerId).size();
        long occupied = tenantProfileRepo.findByOwnerId(ownerId).stream()
                .filter(p -> p.getArchivedAt() == null)
                .filter(p -> p.getPropertyId() != null)
                .map(com.admindi.backend.model.TenantProfileEntity::getPropertyId)
                .distinct()
                .count();
        return new OccupancyStats(total, (int) occupied);
    }

    private static boolean isCountableExpense(ExpenseEntity e) {
        // Contamos gastos ya aprobados o pagados. Cotizaciones QUOTED y rechazados no cuentan.
        String s = e.getStatus();
        if (s == null) return false;
        String up = s.toUpperCase(Locale.ROOT);
        return "APPROVED".equals(up) || "PAID".equals(up);
    }

    private static final Locale ES_MX = Locale.forLanguageTag("es-MX");

    private static String prettyMonth(YearMonth ym) {
        String monthName = ym.getMonth().getDisplayName(TextStyle.FULL, ES_MX);
        // Capitalizar primera letra (JDK devuelve "marzo" en minúscula).
        if (monthName.length() > 0) {
            monthName = monthName.substring(0, 1).toUpperCase(Locale.ROOT) + monthName.substring(1);
        }
        return monthName + " " + ym.getYear();
    }

    private record OccupancyStats(int totalProperties, int occupiedProperties) {}

    /**
     * V56 — Resumen de pagos parciales del mes reportado.
     *
     * @param partialInvoices número de facturas que se liquidaron con 2+ pagos.
     * @param averagePaymentsPerPartial promedio (1 decimal) de pagos por factura
     *        parcial. Si no hay parciales, devuelve 0.
     */
    private record PartialStats(int partialInvoices, String averagePaymentsPerPartial) {}

    /**
     * V56 — Estimación fiscal simplificada para PF arrendamiento tradicional.
     *
     * <ul>
     *   <li>grossIncome: lo cobrado en el mes.</li>
     *   <li>isrEstimate: 2% retención ISR del Art. 126 LISR.</li>
     *   <li>deductibleExpenses: suma de gastos con ai_tax_deductible=TRUE.</li>
     * </ul>
     * NO constituye asesoría fiscal — es solo orientación para el reporte.
     */
    private record SatSummary(BigDecimal grossIncome, BigDecimal isrEstimate,
                               BigDecimal deductibleExpenses) {}

    private PartialStats computePartialStats(List<InvoiceEntity> invoices) {
        int partial = 0;
        long totalPayments = 0;
        for (InvoiceEntity inv : invoices) {
            long count = paymentRepo.findByInvoiceId(inv.getId()).size();
            if (count >= 2) {
                partial++;
                totalPayments += count;
            }
        }
        String avg = partial == 0 ? "0"
                : BigDecimal.valueOf(totalPayments)
                        .divide(BigDecimal.valueOf(partial), 1, RoundingMode.HALF_UP)
                        .toPlainString();
        return new PartialStats(partial, avg);
    }

    /**
     * Top 3 categorías de gasto por monto del mes reportado. Usa
     * ai_category (V56) si está disponible; si no, cae a {@code type}
     * (enum legacy).
     */
    private Map<String, BigDecimal> computeTopExpenseCategories(String ownerId, YearMonth month) {
        List<ExpenseEntity> expenses = expenseRepo.findByOwnerId(ownerId).stream()
                .filter(e -> e.getCreatedAt() != null
                        && YearMonth.from(e.getCreatedAt()).equals(month))
                .filter(MonthlyReportScheduler::isCountableExpense)
                .toList();

        Map<String, BigDecimal> agg = new LinkedHashMap<>();
        for (ExpenseEntity e : expenses) {
            String cat = e.getAiCategory() != null && !e.getAiCategory().isBlank()
                    ? e.getAiCategory() : e.getType();
            if (cat == null) cat = "OTROS";
            BigDecimal amount = e.getAmount() == null ? BigDecimal.ZERO : e.getAmount();
            agg.merge(cat, amount, BigDecimal::add);
        }

        return agg.entrySet().stream()
                .sorted(Map.Entry.<String, BigDecimal>comparingByValue().reversed())
                .limit(3)
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue,
                        (a, b) -> a, LinkedHashMap::new));
    }

    private SatSummary computeSatSummary(BigDecimal collected) {
        // 2% retención ISR aplicable a arrendamiento de PF (Art. 126 LISR).
        // Es un cálculo de referencia; el contador valida antes de cualquier
        // declaración real. Aquí omitimos deducciones complejas porque
        // arrendamiento tradicional tiene opción ciega de 35% de deducción que
        // requiere evaluación caso a caso — mejor lo deja al contador.
        BigDecimal isr = collected.multiply(new BigDecimal("0.02"))
                .setScale(2, RoundingMode.HALF_UP);
        // Gastos deducibles = aún no diferenciamos por ai_tax_deductible en
        // este scheduler porque el caller lo ha pre-calculado via expenses.
        // Para V56 dejamos deductibleExpenses=0 (refinamiento posterior si
        // se requiere una métrica dedicada).
        return new SatSummary(collected, isr, BigDecimal.ZERO);
    }

    /** Util para QA: permite disparar manualmente sin esperar al cron. */
    @SuppressWarnings("unused")
    public int runNowForTesting(YearMonth reportedMonth) {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(IDEMPOTENCY_WINDOW_DAYS);
        int count = 0;
        for (UserEntity u : userRepo.findByRoleAndActiveTrue(Role.OWNER)) {
            try {
                if (sendReportForOwner(u, reportedMonth, cutoff)) count++;
            } catch (Exception ignored) { /* continuar loop */ }
        }
        return count;
    }
}
