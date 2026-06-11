package com.admindi.backend.whatsapp;

import com.admindi.backend.model.InvoiceEntity;
import com.admindi.backend.model.PaymentEntity;
import com.admindi.backend.model.PaymentStatus;
import com.admindi.backend.model.PropertyEntity;
import com.admindi.backend.model.TenantProfileEntity;
import com.admindi.backend.model.UserEntity;
import com.admindi.backend.model.WhatsappConversationStateEntity;
import com.admindi.backend.repository.InvoiceRepository;
import com.admindi.backend.repository.PaymentRepository;
import com.admindi.backend.repository.PropertyRepository;
import com.admindi.backend.repository.TenantProfileRepository;
import com.admindi.backend.repository.UserRepository;
import com.admindi.backend.service.TwilioWhatsAppService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Flujo WhatsApp del DUEÑO: informe de pagos del mes.
 *
 * Solo ofrece datos que existen:
 *  - La lista de meses sale de las facturas reales del dueño (no de meses calendario).
 *  - La lista de arrendatarios sale de las facturas del mes elegido.
 *  - Con un solo mes o un solo arrendatario se autoselecciona sin preguntar.
 *
 * Consulta repositorios directamente acotando todo por {@code ownerId}, igual
 * que {@link com.admindi.backend.service.MonthlyReportScheduler}; no requiere
 * SecurityContext.
 */
@Service
public class WhatsAppOwnerReportFlow {

    private static final Logger logger = LoggerFactory.getLogger(WhatsAppOwnerReportFlow.class);
    private static final Locale ES_MX = Locale.forLanguageTag("es-MX");
    private static final DateTimeFormatter YM_FMT = DateTimeFormatter.ofPattern("yyyy-MM");
    private static final DateTimeFormatter DAY_FMT = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    /** Meses mostrados en la lista (los más recientes); los demás se piden escritos. */
    private static final int MAX_MONTHS_LISTED = 6;
    /** Arrendatarios mostrados en la lista del mes. */
    private static final int MAX_TENANTS_LISTED = 15;
    /** Margen bajo el MAX_BODY_CHARS=1500 de TwilioWhatsAppService para no truncar. */
    private static final int CHUNK_CHARS = 1400;

    private static final Pattern YEAR_FIRST = Pattern.compile("^(\\d{4})[-/](\\d{1,2})$");
    private static final Pattern MONTH_FIRST = Pattern.compile("^(\\d{1,2})[-/](\\d{4})$");

    private final WhatsAppSessionService sessions;
    private final TwilioWhatsAppService twilio;
    private final InvoiceRepository invoiceRepo;
    private final PaymentRepository paymentRepo;
    private final TenantProfileRepository tenantProfileRepo;
    private final PropertyRepository propertyRepo;
    private final UserRepository userRepo;

    public WhatsAppOwnerReportFlow(WhatsAppSessionService sessions,
                                    TwilioWhatsAppService twilio,
                                    InvoiceRepository invoiceRepo,
                                    PaymentRepository paymentRepo,
                                    TenantProfileRepository tenantProfileRepo,
                                    PropertyRepository propertyRepo,
                                    UserRepository userRepo) {
        this.sessions = sessions;
        this.twilio = twilio;
        this.invoiceRepo = invoiceRepo;
        this.paymentRepo = paymentRepo;
        this.tenantProfileRepo = tenantProfileRepo;
        this.propertyRepo = propertyRepo;
        this.userRepo = userRepo;
    }

    // ─── Paso 1: elegir mes (solo meses con facturas reales) ─────────────

    public void startReportFlow(String fromE164, UserEntity owner,
                                 WhatsappConversationStateEntity session) {
        List<YearMonth> months = availableMonths(owner.getId());

        if (months.isEmpty()) {
            twilio.sendFreeformWhatsApp(fromE164,
                    "Aún no tienes facturas de renta registradas, así que no hay informes "
                            + "que mostrar. Se generan automáticamente con cada periodo de renta.\n\n"
                            + "Escribe MENU para volver.");
            sessions.transition(session, WhatsAppBotState.OWNER_MENU, Map.of());
            return;
        }

        if (months.size() == 1) {
            YearMonth only = months.get(0);
            twilio.sendFreeformWhatsApp(fromE164,
                    "Solo tienes facturas de *" + prettyMonth(only) + "*.");
            promptScope(fromE164, session, only);
            return;
        }

        int limit = Math.min(months.size(), MAX_MONTHS_LISTED);
        List<String> options = new ArrayList<>(limit);
        StringBuilder sb = new StringBuilder();
        sb.append("📊 Informe de pagos. Meses con facturas:\n\n");
        for (int i = 0; i < limit; i++) {
            YearMonth ym = months.get(i);
            options.add(ym.format(YM_FMT));
            sb.append(i + 1).append(") ").append(prettyMonth(ym)).append("\n");
        }
        if (months.size() > limit) {
            sb.append("\n(Tienes ").append(months.size())
              .append(" meses con facturas; para uno anterior escríbelo como MM-AAAA, ej. 02-2026.)");
        }
        sb.append("\nResponde con el número. MENU para cancelar.");

        sessions.transition(session, WhatsAppBotState.OWNER_REPORT_MONTH, Map.of(
                "reportMonthOptions", options));
        twilio.sendFreeformWhatsApp(fromE164, sb.toString());
    }

    public void handleMonthChoice(String fromE164, UserEntity owner,
                                   WhatsappConversationStateEntity session, String body) {
        String trimmed = body == null ? "" : body.trim();
        List<YearMonth> available = availableMonths(owner.getId());
        YearMonth chosen = parseTypedMonth(trimmed);

        if (chosen == null) {
            Map<String, Object> ctx = sessions.getContext(session);
            Object raw = ctx.get("reportMonthOptions");
            List<?> options = raw instanceof List ? (List<?>) raw : List.of();
            int idx = parseOption(trimmed) - 1;
            if (idx >= 0 && idx < options.size()) {
                chosen = YearMonth.parse(String.valueOf(options.get(idx)), YM_FMT);
            }
        }

        if (chosen == null) {
            twilio.sendFreeformWhatsApp(fromE164,
                    "No reconozco ese mes. Responde con el número de la lista "
                            + "o escríbelo como MM-AAAA (ej. 02-2026).");
            return;
        }
        if (!available.contains(chosen)) {
            twilio.sendFreeformWhatsApp(fromE164,
                    "No tienes facturas en " + prettyMonth(chosen) + ".\n\n"
                            + "Meses disponibles: " + joinMonths(available) + ".\n"
                            + "Elige uno de esos o escribe MENU.");
            return;
        }

        promptScope(fromE164, session, chosen);
    }

    // ─── Paso 2: alcance ──────────────────────────────────────────────────

    private void promptScope(String fromE164, WhatsappConversationStateEntity session,
                              YearMonth month) {
        sessions.transition(session, WhatsAppBotState.OWNER_REPORT_SCOPE, Map.of(
                "reportMonth", month.format(YM_FMT)));
        twilio.sendFreeformWhatsApp(fromE164,
                "Informe de *" + prettyMonth(month) + "*. ¿Qué quieres ver?\n\n"
                        + "1) Resumen de todos mis inmuebles\n"
                        + "2) Detalle de un arrendatario\n\n"
                        + "Responde con el número. MENU para cancelar.");
    }

    public void handleScopeChoice(String fromE164, UserEntity owner,
                                   WhatsappConversationStateEntity session, String body) {
        YearMonth month = monthFromSession(session);
        if (month == null) {
            twilio.sendFreeformWhatsApp(fromE164, "Sesión inválida. Escribe MENU para reiniciar.");
            sessions.transition(session, WhatsAppBotState.OWNER_MENU, Map.of());
            return;
        }

        String opt = digitsOnly(body);
        if ("1".equals(opt)) {
            sendChunked(fromE164, buildMonthlySummary(owner, month));
            sessions.transition(session, WhatsAppBotState.OWNER_MENU, Map.of());
            return;
        }
        if ("2".equals(opt)) {
            promptTenantPick(fromE164, owner, session, month);
            return;
        }
        twilio.sendFreeformWhatsApp(fromE164,
                "Responde 1 para el resumen general o 2 para elegir arrendatario.");
    }

    /**
     * Lista solo arrendatarios que tienen factura en el mes elegido (incluye
     * expedientes archivados — el dato histórico existe). Con uno solo, manda
     * el detalle directamente.
     */
    private void promptTenantPick(String fromE164, UserEntity owner,
                                   WhatsappConversationStateEntity session, YearMonth month) {
        List<String> profileIds = new ArrayList<>(new LinkedHashSet<>(
                monthInvoices(owner.getId(), month).stream()
                        .map(InvoiceEntity::getTenantProfileId)
                        .filter(id -> id != null && !id.isBlank())
                        .toList()));

        if (profileIds.isEmpty()) {
            twilio.sendFreeformWhatsApp(fromE164,
                    "No hay facturas de arrendatarios en " + prettyMonth(month)
                            + ". Escribe MENU para volver.");
            sessions.transition(session, WhatsAppBotState.OWNER_MENU, Map.of());
            return;
        }

        if (profileIds.size() == 1) {
            TenantProfileEntity only = ownedProfile(owner, profileIds.get(0));
            if (only != null) {
                sendChunked(fromE164, buildTenantDetail(only, month));
            } else {
                twilio.sendFreeformWhatsApp(fromE164,
                        "No pude resolver el expediente del arrendatario. Escribe MENU.");
            }
            sessions.transition(session, WhatsAppBotState.OWNER_MENU, Map.of());
            return;
        }

        List<TenantProfileEntity> profiles = profileIds.stream()
                .map(id -> ownedProfile(owner, id))
                .filter(p -> p != null)
                .sorted(Comparator.comparing(this::pickerLabel, String.CASE_INSENSITIVE_ORDER))
                .toList();

        int limit = Math.min(profiles.size(), MAX_TENANTS_LISTED);
        List<String> ids = new ArrayList<>(limit);
        StringBuilder sb = new StringBuilder();
        sb.append("¿Qué arrendatario? (").append(prettyMonth(month)).append(")\n\n");
        for (int i = 0; i < limit; i++) {
            TenantProfileEntity p = profiles.get(i);
            ids.add(p.getId());
            sb.append(i + 1).append(") ").append(pickerLabel(p)).append("\n");
        }
        if (profiles.size() > limit) {
            sb.append("\n(Mostrando ").append(limit).append(" de ").append(profiles.size())
              .append("; usa el resumen general (opción 1) para verlos todos.)\n");
        }
        sb.append("\nResponde con el número. MENU para cancelar.");

        sessions.transition(session, WhatsAppBotState.OWNER_REPORT_TENANT_PICK, Map.of(
                "reportMonth", month.format(YM_FMT),
                "reportTenantIds", ids));
        twilio.sendFreeformWhatsApp(fromE164, sb.toString());
    }

    // ─── Paso 3: detalle por arrendatario ─────────────────────────────────

    public void handleTenantPick(String fromE164, UserEntity owner,
                                  WhatsappConversationStateEntity session, String body) {
        YearMonth month = monthFromSession(session);
        Map<String, Object> ctx = sessions.getContext(session);
        Object raw = ctx.get("reportTenantIds");
        List<?> ids = raw instanceof List ? (List<?>) raw : List.of();
        int idx = parseOption(body) - 1;
        if (month == null || idx < 0 || idx >= ids.size()) {
            twilio.sendFreeformWhatsApp(fromE164,
                    "Opción no válida. Responde con el número del arrendatario o MENU.");
            return;
        }

        TenantProfileEntity profile = ownedProfile(owner, String.valueOf(ids.get(idx)));
        if (profile == null) {
            twilio.sendFreeformWhatsApp(fromE164, "Arrendatario no válido. Escribe MENU.");
            sessions.transition(session, WhatsAppBotState.OWNER_MENU, Map.of());
            return;
        }

        sendChunked(fromE164, buildTenantDetail(profile, month));
        sessions.transition(session, WhatsAppBotState.OWNER_MENU, Map.of());
    }

    // ─── Construcción de informes ─────────────────────────────────────────

    /**
     * Resumen del mes para todos los inmuebles del dueño, agrupado por estado:
     * pagado, parcial y pendiente, con totales al pie.
     */
    String buildMonthlySummary(UserEntity owner, YearMonth month) {
        List<InvoiceEntity> invoices = monthInvoices(owner.getId(), month);

        if (invoices.isEmpty()) {
            return "📊 *" + prettyMonth(month) + "*\n\n"
                    + "No hay facturas de renta en ese mes.\n\nEscribe MENU para volver.";
        }

        Map<String, TenantProfileEntity> profileCache = new HashMap<>();
        List<InvoiceEntity> paid = new ArrayList<>();
        List<InvoiceEntity> partial = new ArrayList<>();
        List<InvoiceEntity> unpaid = new ArrayList<>();
        for (InvoiceEntity inv : invoices) {
            String s = inv.getSettlementStatus() != null ? inv.getSettlementStatus() : "";
            if ("PAID".equalsIgnoreCase(s) || "OVERPAID".equalsIgnoreCase(s)) paid.add(inv);
            else if ("PARTIALLY_PAID".equalsIgnoreCase(s)) partial.add(inv);
            else unpaid.add(inv);
        }
        Comparator<InvoiceEntity> byTenant =
                Comparator.comparing(inv -> invoiceLabel(inv, profileCache),
                        String.CASE_INSENSITIVE_ORDER);
        paid.sort(byTenant);
        partial.sort(byTenant);
        unpaid.sort(byTenant);

        BigDecimal collected = invoices.stream()
                .map(inv -> nz(inv.getPaidAmount()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal pendingTotal = invoices.stream()
                .map(this::outstanding)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        StringBuilder sb = new StringBuilder();
        sb.append("📊 *Informe ").append(prettyMonth(month)).append("*\n");

        if (!paid.isEmpty()) {
            sb.append("\n✅ *Pagado* (").append(paid.size()).append(")\n");
            for (InvoiceEntity inv : paid) {
                sb.append("• ").append(invoiceLabel(inv, profileCache))
                  .append(": $").append(nz(inv.getPaidAmount()).toPlainString());
                if (inv.getPaidDate() != null) {
                    sb.append(" (").append(inv.getPaidDate().format(DAY_FMT)).append(")");
                }
                sb.append("\n");
            }
        }
        if (!partial.isEmpty()) {
            sb.append("\n🟡 *Pago parcial* (").append(partial.size()).append(")\n");
            for (InvoiceEntity inv : partial) {
                sb.append("• ").append(invoiceLabel(inv, profileCache))
                  .append(": $").append(nz(inv.getPaidAmount()).toPlainString())
                  .append(" de $").append(nz(inv.getTotalAmount()).toPlainString())
                  .append(" (faltan $").append(outstanding(inv).toPlainString()).append(")\n");
            }
        }
        if (!unpaid.isEmpty()) {
            sb.append("\n🔴 *Pendiente* (").append(unpaid.size()).append(")\n");
            for (InvoiceEntity inv : unpaid) {
                sb.append("• ").append(invoiceLabel(inv, profileCache))
                  .append(": $").append(nz(inv.getTotalAmount()).toPlainString());
                if (inv.getDueDate() != null) {
                    sb.append(" (vence ").append(inv.getDueDate().format(DAY_FMT)).append(")");
                }
                sb.append("\n");
            }
        }

        sb.append("\n*Totales del mes:*\n");
        sb.append("• Cobrado: $").append(collected.toPlainString()).append(" MXN\n");
        sb.append("• Pendiente: $").append(pendingTotal.toPlainString()).append(" MXN\n");
        sb.append("• Rentas liquidadas: ").append(paid.size()).append(" de ").append(invoices.size());
        sb.append("\n\nEscribe MENU para volver.");
        return sb.toString();
    }

    /**
     * Detalle de un arrendatario en el mes: factura + pagos confirmados
     * (fecha, monto y método).
     */
    String buildTenantDetail(TenantProfileEntity profile, YearMonth month) {
        String name = tenantName(profile);
        String property = propertyName(profile.getPropertyId());
        String header = "📋 *" + name + "* — " + prettyMonth(month) + "\nInmueble: " + property + "\n";

        InvoiceEntity inv = invoiceRepo
                .findByTenantProfileIdAndMonthYear(profile.getId(), month.format(YM_FMT))
                .orElse(null);
        if (inv == null) {
            return header + "\nNo hay factura de renta para ese mes."
                    + "\n\nEscribe MENU para volver.";
        }

        StringBuilder sb = new StringBuilder(header);
        sb.append("\nRenta: $").append(nz(inv.getTotalAmount()).toPlainString()).append(" MXN");
        if (nz(inv.getAppliedLateFee()).signum() > 0) {
            sb.append(" (incluye recargo $").append(inv.getAppliedLateFee().toPlainString()).append(")");
        }
        sb.append("\nPagado: $").append(nz(inv.getPaidAmount()).toPlainString()).append(" MXN");
        sb.append("\nEstado: ").append(statusLabel(inv));
        if (outstanding(inv).signum() > 0) {
            sb.append("\nPor cobrar: $").append(outstanding(inv).toPlainString()).append(" MXN");
            if (inv.getDueDate() != null) {
                sb.append(" (vencimiento ").append(inv.getDueDate().format(DAY_FMT)).append(")");
            }
        }

        List<PaymentEntity> payments = paymentRepo.findByInvoiceId(inv.getId()).stream()
                .filter(p -> p.getStatus() == PaymentStatus.CONFIRMED)
                .sorted(Comparator.comparing(
                        p -> p.getPaidAt() != null ? p.getPaidAt() : p.getCreatedAt(),
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
        if (payments.isEmpty()) {
            sb.append("\n\nSin pagos confirmados todavía.");
        } else {
            sb.append("\n\n*Pagos recibidos:*\n");
            for (PaymentEntity p : payments) {
                sb.append("• ");
                if (p.getPaidAt() != null) {
                    sb.append(p.getPaidAt().format(DAY_FMT)).append(" — ");
                }
                sb.append("$").append(nz(p.getAmount()).toPlainString())
                  .append(" (").append(methodLabel(p)).append(")\n");
            }
        }

        sb.append("\nEscribe MENU para volver.");
        return sb.toString();
    }

    // ─── Datos ────────────────────────────────────────────────────────────

    /** Meses con facturas reales del dueño, el más reciente primero. */
    private List<YearMonth> availableMonths(String ownerId) {
        return validInvoices(ownerId).stream()
                .map(InvoiceEntity::getMonthYear)
                .filter(m -> m != null && !m.isBlank())
                .distinct()
                .map(WhatsAppOwnerReportFlow::parseYm)
                .filter(ym -> ym != null)
                .sorted(Comparator.reverseOrder())
                .toList();
    }

    /** Facturas del dueño en un mes, sin VOID ni CANCELLED. */
    private List<InvoiceEntity> monthInvoices(String ownerId, YearMonth month) {
        String monthStr = month.format(YM_FMT);
        return validInvoices(ownerId).stream()
                .filter(inv -> monthStr.equals(inv.getMonthYear()))
                .toList();
    }

    private List<InvoiceEntity> validInvoices(String ownerId) {
        return invoiceRepo.findByOwnerId(ownerId).stream()
                .filter(inv -> !"VOID".equalsIgnoreCase(inv.getStatus()))
                .filter(inv -> !"CANCELLED".equalsIgnoreCase(inv.getSettlementStatus()))
                .toList();
    }

    /** Expediente solo si pertenece al dueño autenticado (defensa adicional). */
    private TenantProfileEntity ownedProfile(UserEntity owner, String profileId) {
        return tenantProfileRepo.findById(profileId)
                .filter(p -> owner.getId().equals(p.getOwnerId()))
                .orElse(null);
    }

    // ─── Helpers ──────────────────────────────────────────────────────────

    /** Envía texto largo en varias partes (límite Twilio 1500 chars) cortando por línea. */
    private void sendChunked(String fromE164, String text) {
        if (text.length() <= CHUNK_CHARS) {
            twilio.sendFreeformWhatsApp(fromE164, text);
            return;
        }
        StringBuilder chunk = new StringBuilder();
        for (String line : text.split("\n", -1)) {
            if (chunk.length() + line.length() + 1 > CHUNK_CHARS) {
                twilio.sendFreeformWhatsApp(fromE164, chunk.toString());
                chunk.setLength(0);
            }
            if (chunk.length() > 0) chunk.append("\n");
            chunk.append(line);
        }
        if (chunk.length() > 0) {
            twilio.sendFreeformWhatsApp(fromE164, chunk.toString());
        }
    }

    private YearMonth monthFromSession(WhatsappConversationStateEntity session) {
        Map<String, Object> ctx = sessions.getContext(session);
        String raw = (String) ctx.get("reportMonth");
        if (raw == null || raw.isBlank()) return null;
        YearMonth ym = parseYm(raw);
        if (ym == null) {
            logger.warn("[OWNER-REPORT] invalid reportMonth in session: {}", raw);
        }
        return ym;
    }

    private static YearMonth parseYm(String raw) {
        try {
            return YearMonth.parse(raw, YM_FMT);
        } catch (Exception ex) {
            return null;
        }
    }

    /** Acepta "2026-02", "02-2026", "2/2026"… null si no es un mes escrito. */
    private YearMonth parseTypedMonth(String input) {
        if (input == null) return null;
        String s = input.trim();
        Matcher yearFirst = YEAR_FIRST.matcher(s);
        if (yearFirst.matches()) {
            return safeYearMonth(yearFirst.group(1), yearFirst.group(2));
        }
        Matcher monthFirst = MONTH_FIRST.matcher(s);
        if (monthFirst.matches()) {
            return safeYearMonth(monthFirst.group(2), monthFirst.group(1));
        }
        return null;
    }

    private YearMonth safeYearMonth(String year, String month) {
        try {
            int m = Integer.parseInt(month);
            if (m < 1 || m > 12) return null;
            return YearMonth.of(Integer.parseInt(year), m);
        } catch (Exception ex) {
            return null;
        }
    }

    private static String joinMonths(List<YearMonth> months) {
        int limit = Math.min(months.size(), MAX_MONTHS_LISTED);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < limit; i++) {
            if (i > 0) sb.append(", ");
            sb.append(prettyMonth(months.get(i)));
        }
        if (months.size() > limit) sb.append("…");
        return sb.toString();
    }

    private String pickerLabel(TenantProfileEntity p) {
        return tenantName(p) + " — " + propertyName(p.getPropertyId());
    }

    private String invoiceLabel(InvoiceEntity inv, Map<String, TenantProfileEntity> cache) {
        TenantProfileEntity profile = cache.computeIfAbsent(inv.getTenantProfileId(),
                id -> tenantProfileRepo.findById(id).orElse(null));
        if (profile == null) {
            return "Expediente " + shortId(inv.getTenantProfileId());
        }
        return propertyName(profile.getPropertyId()) + " — " + tenantName(profile);
    }

    private String tenantName(TenantProfileEntity profile) {
        if (profile == null || profile.getUserId() == null) return "Inquilino";
        return userRepo.findById(profile.getUserId())
                .map(UserEntity::getName)
                .filter(n -> n != null && !n.isBlank())
                .orElse("Inquilino");
    }

    private String propertyName(String propertyId) {
        if (propertyId == null) return "Sin inmueble";
        return propertyRepo.findById(propertyId)
                .map(PropertyEntity::getName)
                .filter(n -> n != null && !n.isBlank())
                .orElse("Inmueble " + shortId(propertyId));
    }

    private String statusLabel(InvoiceEntity inv) {
        String s = inv.getSettlementStatus() != null ? inv.getSettlementStatus() : inv.getStatus();
        if (s == null) return "Desconocido";
        return switch (s.toUpperCase(Locale.ROOT)) {
            case "PAID" -> "PAGADO ✅";
            case "OVERPAID" -> "PAGADO (con saldo a favor) ✅";
            case "PARTIALLY_PAID" -> "PAGO PARCIAL 🟡";
            default -> "LATE".equalsIgnoreCase(inv.getStatus())
                    ? "VENCIDO 🔴" : "PENDIENTE 🔴";
        };
    }

    private String methodLabel(PaymentEntity p) {
        if (p.getPaymentMethod() == null) return "otro";
        return switch (p.getPaymentMethod()) {
            case CASH -> "efectivo";
            case TRANSFER_SPEI -> "SPEI";
            case MERCADO_PAGO -> "Mercado Pago";
            default -> "otro";
        };
    }

    private BigDecimal outstanding(InvoiceEntity inv) {
        if (inv.getOutstandingAmount() != null) return inv.getOutstandingAmount().max(BigDecimal.ZERO);
        return nz(inv.getTotalAmount()).subtract(nz(inv.getPaidAmount())).max(BigDecimal.ZERO);
    }

    private static BigDecimal nz(BigDecimal v) {
        return v != null ? v : BigDecimal.ZERO;
    }

    private static String shortId(String id) {
        if (id == null) return "?";
        return id.length() > 8 ? id.substring(0, 8) : id;
    }

    private static String prettyMonth(YearMonth ym) {
        String monthName = ym.getMonth().getDisplayName(TextStyle.FULL, ES_MX);
        if (!monthName.isEmpty()) {
            monthName = monthName.substring(0, 1).toUpperCase(Locale.ROOT) + monthName.substring(1);
        }
        return monthName + " " + ym.getYear();
    }

    private static String digitsOnly(String s) {
        if (s == null) return "";
        return s.replaceAll("\\D", "");
    }

    private static int parseOption(String body) {
        try {
            return Integer.parseInt(digitsOnly(body));
        } catch (Exception ex) {
            return 0;
        }
    }
}
