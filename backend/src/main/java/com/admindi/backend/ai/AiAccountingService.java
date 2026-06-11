package com.admindi.backend.ai;

import com.admindi.backend.model.ExpenseEntity;
import com.admindi.backend.model.InvoiceEntity;
import com.admindi.backend.model.PaymentEntity;
import com.admindi.backend.repository.ExpenseRepository;
import com.admindi.backend.repository.InvoiceRepository;
import com.admindi.backend.repository.PaymentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;

/**
 * Contabilidad asistida por IA.
 *
 * Responsabilidades:
 *  - Categorizar pagos e egresos (RENTA_BASE, RECARGO_MORATORIO, SERVICIOS,
 *    COMISION_AGENTE, MANTENIMIENTO_*, IMPUESTOS, etc.).
 *  - Sugerir uso del CFDI (catálogo SAT simplificado: G03, I08, P01, etc.).
 *  - Estimar deducibilidad fiscal (true/false sugerido; el contador valida).
 *  - Ejecutarse ASÍNCRONAMENTE tras que el pago se confirma para no bloquear
 *    el flujo crítico del inquilino.
 *
 * Fallbacks:
 *  - Si Claude está deshabilitado, no hace nada (los campos quedan null, el
 *    contador puede clasificar manual).
 *  - Si Claude responde pero con JSON inválido, registra como fallido en
 *    {@code ai_usage_log} y no sobrescribe valores existentes.
 */
@Service
public class AiAccountingService {

    private static final Logger logger = LoggerFactory.getLogger(AiAccountingService.class);

    private static final String PAYMENT_SYSTEM_PROMPT = """
            Eres contador fiscal mexicano experto en arrendamiento de inmuebles.
            Recibes metadatos de un pago recibido por el arrendador.
            
            Categoriza el pago con una de estas etiquetas (exacta, en MAYÚSCULAS):
              RENTA_BASE               — pago ordinario de renta mensual
              RECARGO_MORATORIO        — monto específicamente por mora
              DEPOSITO_GARANTIA        — depósito en garantía (primer mes, final)
              ANTICIPO                 — anticipo de rentas futuras
              SERVICIOS                — luz, agua, internet facturados aparte
              DIFERENCIA_AJUSTE        — ajuste o reintegro
              OTROS                    — no encaja en los anteriores
            
            Sugerencia de uso CFDI (catálogo SAT simplificado):
              G03  - Gastos en general
              I08  - Otras maquinarias y equipos
              P01  - Por definir
              ARR  - Arrendamiento (uso común)
            
            Deducibilidad fiscal: true si el arrendador podría deducirlo en su
            declaración mensual (PF arrendadora); false si es un ingreso
            gravable puro (como la renta base).
            
            Devuelve SOLO JSON:
            {
              "category": "...",
              "cfdiUse": "...",
              "taxDeductible": true|false,
              "confidence": 0.0-1.0,
              "reasoning": "breve explicación en español"
            }
            """;

    private static final String EXPENSE_SYSTEM_PROMPT = """
            Eres contador fiscal mexicano experto en gastos de arrendador.
            Recibes un egreso del dueño.
            
            Categoriza con una de estas etiquetas exactas:
              MANTENIMIENTO_PLOMERIA / MANTENIMIENTO_ELECTRICO / MANTENIMIENTO_PINTURA
              MANTENIMIENTO_ESTRUCTURAL / MANTENIMIENTO_GENERAL
              COMISION_AGENTE          — comisión pagada a agente inmobiliario
              SERVICIOS                — predial, agua, luz, cuotas condominio
              IMPUESTOS                — ISR, IVA, predial si aplica
              COMERCIALIZACION         — publicidad, fotos, staging
              OTROS
            
            CFDI sugerido: G03 (gastos en general) suele ser el más común para
            arrendadores; usa otro si la descripción lo amerita.
            
            Deducibilidad: true casi siempre para mantenimiento y comisiones,
            true para servicios del inmueble, dudoso para OTROS.
            
            Devuelve SOLO JSON:
            {
              "category": "...",
              "cfdiUse": "...",
              "taxDeductible": true|false,
              "confidence": 0.0-1.0,
              "reasoning": "breve explicación"
            }
            """;

    private final ClaudeService claude;
    private final PaymentRepository paymentRepo;
    private final ExpenseRepository expenseRepo;
    private final InvoiceRepository invoiceRepo;

    public AiAccountingService(ClaudeService claude,
                                PaymentRepository paymentRepo,
                                ExpenseRepository expenseRepo,
                                InvoiceRepository invoiceRepo) {
        this.claude = claude;
        this.paymentRepo = paymentRepo;
        this.expenseRepo = expenseRepo;
        this.invoiceRepo = invoiceRepo;
    }

    /**
     * Categoriza un pago. Típicamente se llama desde {@code LedgerService}
     * después de {@code autoConfirmPayment}. Asíncrono: no bloquea la respuesta
     * al inquilino si Claude tarda o falla.
     */
    @Async
    public void categorizePaymentAsync(String paymentId) {
        paymentRepo.findById(paymentId).ifPresent(this::categorizePayment);
    }

    public void categorizePayment(PaymentEntity payment) {
        if (payment == null) return;
        try {
            // Construir un user message con solo los datos que Claude necesita.
            // Sensible NO enviamos: tokens, passwords, NIPs, notas internas con
            // datos confidenciales. Sí enviamos: método, monto, mes, notas
            // (sanitizadas por PromptGuardrails).
            InvoiceEntity invoice = invoiceRepo.findById(payment.getInvoiceId()).orElse(null);
            String userMessage = String.format(
                    "Pago recibido:\n" +
                    "- Método: %s\n" +
                    "- Monto: %s MXN\n" +
                    "- Monto aplicado a factura: %s MXN\n" +
                    "- Monto excedente (crédito): %s MXN\n" +
                    "- Mes de la factura: %s\n" +
                    "- Notas: %s",
                    payment.getPaymentMethod(),
                    toStr(payment.getAmount()),
                    toStr(payment.getAppliedAmount()),
                    toStr(payment.getUnappliedAmount()),
                    invoice != null ? invoice.getMonthYear() : "(desconocido)",
                    PromptGuardrails.sanitizeOrEmpty(payment.getNotes() == null ? "" : payment.getNotes()));

            ClaudeService.ClaudeResponse resp = claude.chat(PAYMENT_SYSTEM_PROMPT, userMessage,
                    true, null, payment.getOwnerId(), "ACCOUNTING_CATEGORIZE_PAYMENT");
            applyClassification(resp, payment);
        } catch (Exception ex) {
            logger.warn("[AI-ACCOUNTING] payment categorize failed id={}: {}",
                    payment.getId(), ex.getClass().getSimpleName());
        }
    }

    @Async
    public void categorizeExpenseAsync(String expenseId) {
        expenseRepo.findById(expenseId).ifPresent(this::categorizeExpense);
    }

    public void categorizeExpense(ExpenseEntity expense) {
        if (expense == null) return;
        try {
            String userMessage = String.format(
                    "Egreso:\n" +
                    "- Tipo: %s\n" +
                    "- Descripción: %s\n" +
                    "- Monto: %s MXN\n" +
                    "- Estado: %s\n" +
                    "- Método de pago: %s\n" +
                    "- Notas: %s",
                    expense.getType(),
                    PromptGuardrails.sanitizeOrEmpty(expense.getDescription() == null ? "" : expense.getDescription()),
                    toStr(expense.getAmount()),
                    expense.getStatus(),
                    expense.getPaymentMethod() == null ? "(no especificado)" : expense.getPaymentMethod(),
                    PromptGuardrails.sanitizeOrEmpty(expense.getNotes() == null ? "" : expense.getNotes()));

            ClaudeService.ClaudeResponse resp = claude.chat(EXPENSE_SYSTEM_PROMPT, userMessage,
                    true, null, expense.getOwnerId(), "ACCOUNTING_CATEGORIZE_EXPENSE");
            applyClassification(resp, expense);
        } catch (Exception ex) {
            logger.warn("[AI-ACCOUNTING] expense categorize failed id={}: {}",
                    expense.getId(), ex.getClass().getSimpleName());
        }
    }

    /**
     * Genera una narrativa ejecutiva (texto libre) para el reporte mensual.
     * Se llama desde {@code MonthlyReportScheduler} con estadísticas agregadas.
     * Devuelve cadena vacía si Claude está desactivado — el reporte sigue
     * usando su body estándar (fallback documentado).
     */
    public String generateMonthlyNarrative(String ownerName, String monthLabel,
                                            BigDecimal collected, BigDecimal outstanding,
                                            BigDecimal expenses, int partialInvoices,
                                            Map<String, BigDecimal> topExpenseCategories) {
        if (!isEnabled()) return "";

        String system = """
                Eres analista financiero junior del arrendador. Recibes estadísticas \
                del mes cerrado y debes escribir un resumen ejecutivo en español, \
                máximo 3 párrafos, empezando con un dato llamativo y terminando con \
                una recomendación concreta. Usa voz cercana pero profesional. No \
                inventes datos — solo usa los que te paso.
                Devuelve SOLO JSON: {"narrative": "...texto 3 párrafos..."}.
                """;

        StringBuilder sb = new StringBuilder();
        sb.append("Datos del mes ").append(monthLabel).append(" para ")
                .append(ownerName == null ? "el dueño" : ownerName).append(":\n");
        sb.append("- Cobrado: $").append(toStr(collected)).append(" MXN\n");
        sb.append("- Por cobrar: $").append(toStr(outstanding)).append(" MXN\n");
        sb.append("- Gastos: $").append(toStr(expenses)).append(" MXN\n");
        sb.append("- Rentas liquidadas con 2+ pagos parciales: ").append(partialInvoices).append("\n");
        if (topExpenseCategories != null && !topExpenseCategories.isEmpty()) {
            sb.append("- Top categorías de gasto:\n");
            for (Map.Entry<String, BigDecimal> e : topExpenseCategories.entrySet()) {
                sb.append("    * ").append(e.getKey()).append(": $").append(toStr(e.getValue())).append("\n");
            }
        }

        ClaudeService.ClaudeResponse resp = claude.chat(system, sb.toString(),
                true, null, null, "REPORT_NARRATIVE");
        if (!resp.ok()) return "";
        Object narrative = resp.structured().get("narrative");
        return narrative == null ? "" : narrative.toString();
    }

    public boolean isEnabled() {
        // Implícito: si Claude está disabled responde disabled y ok=false.
        // Una forma barata es disparar un chat trivial — pero preferimos
        // no gastar tokens. Este getter lo consumen callers solo para elegir
        // entre narrativa IA o fallback plano.
        return true;
    }

    // ─── Internals ──────────────────────────────────────────────────────────

    private void applyClassification(ClaudeService.ClaudeResponse resp, PaymentEntity payment) {
        if (!resp.ok()) return;
        Map<String, Object> json = resp.structured();
        if (json.isEmpty()) return;

        payment.setAiCategory(asString(json.get("category")));
        payment.setAiCfdiUse(asString(json.get("cfdiUse")));
        Object dedu = json.get("taxDeductible");
        if (dedu instanceof Boolean b) payment.setAiTaxDeductible(b);
        Object conf = json.get("confidence");
        if (conf instanceof Number n) payment.setAiConfidence(BigDecimal.valueOf(n.doubleValue()));
        payment.setAiLastRunAt(LocalDateTime.now());
        paymentRepo.save(payment);
    }

    private void applyClassification(ClaudeService.ClaudeResponse resp, ExpenseEntity expense) {
        if (!resp.ok()) return;
        Map<String, Object> json = resp.structured();
        if (json.isEmpty()) return;

        expense.setAiCategory(asString(json.get("category")));
        expense.setAiCfdiUse(asString(json.get("cfdiUse")));
        Object dedu = json.get("taxDeductible");
        if (dedu instanceof Boolean b) expense.setAiTaxDeductible(b);
        Object conf = json.get("confidence");
        if (conf instanceof Number n) expense.setAiConfidence(BigDecimal.valueOf(n.doubleValue()));
        expense.setAiLastRunAt(LocalDateTime.now());
        expenseRepo.save(expense);
    }

    private String asString(Object o) {
        if (o == null) return null;
        String s = o.toString().trim();
        return s.isBlank() || "null".equalsIgnoreCase(s) ? null : s;
    }

    private String toStr(BigDecimal b) {
        return b == null ? "0" : b.toPlainString();
    }
}
