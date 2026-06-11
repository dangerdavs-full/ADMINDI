package com.admindi.backend.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Indica si el botón "Enviar recordatorio manual" debe mostrarse habilitado para un inquilino
 * específico.
 *
 * <p>Si {@code eligible=false}, {@code reason} explica el motivo para que el frontend lo
 * muestre en un tooltip:
 * <ul>
 *   <li>{@code NO_INVOICE_DUE}      – inquilino al corriente, no hay nada que recordar.</li>
 *   <li>{@code RATE_LIMIT_REACHED}  – ya se enviaron 2 recordatorios manuales en las últimas 24h.</li>
 *   <li>{@code IDOR}                – este inquilino no pertenece al dueño activo (UI no debería llegar aquí).</li>
 * </ul>
 */
public class ManualReminderEligibilityDTO {

    private boolean eligible;
    private String reason;
    private int remainingToday;
    private String invoiceId;
    private BigDecimal amount;
    private LocalDate dueDate;

    public ManualReminderEligibilityDTO() {}

    public static ManualReminderEligibilityDTO ineligible(String reason, int remainingToday) {
        ManualReminderEligibilityDTO d = new ManualReminderEligibilityDTO();
        d.eligible = false;
        d.reason = reason;
        d.remainingToday = remainingToday;
        return d;
    }

    public static ManualReminderEligibilityDTO eligible(int remainingToday, String invoiceId,
                                                        BigDecimal amount, LocalDate dueDate) {
        ManualReminderEligibilityDTO d = new ManualReminderEligibilityDTO();
        d.eligible = true;
        d.remainingToday = remainingToday;
        d.invoiceId = invoiceId;
        d.amount = amount;
        d.dueDate = dueDate;
        return d;
    }

    public boolean isEligible() { return eligible; }
    public void setEligible(boolean eligible) { this.eligible = eligible; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public int getRemainingToday() { return remainingToday; }
    public void setRemainingToday(int remainingToday) { this.remainingToday = remainingToday; }
    public String getInvoiceId() { return invoiceId; }
    public void setInvoiceId(String invoiceId) { this.invoiceId = invoiceId; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }
    public LocalDate getDueDate() { return dueDate; }
    public void setDueDate(LocalDate dueDate) { this.dueDate = dueDate; }
}
