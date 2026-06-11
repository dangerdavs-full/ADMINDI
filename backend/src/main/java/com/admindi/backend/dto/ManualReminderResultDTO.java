package com.admindi.backend.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Resultado de un intento de envío manual de recordatorio de pago (Fase B2).
 *
 * <p>Si el recordatorio se disparó, {@code success=true} y {@code channels} lista los canales
 * efectivamente intentados (el dispatcher no retorna fallos por canal individual; los audita
 * y sigue). {@code remainingToday} refleja cuántos envíos manuales le quedan al dueño sobre
 * este inquilino en la ventana de 24h.
 */
public class ManualReminderResultDTO {

    private boolean success;
    private List<String> channels;
    private int remainingToday;
    private String invoiceId;
    private BigDecimal amount;
    private LocalDate dueDate;

    public ManualReminderResultDTO() {}

    public ManualReminderResultDTO(boolean success, List<String> channels, int remainingToday,
                                    String invoiceId, BigDecimal amount, LocalDate dueDate) {
        this.success = success;
        this.channels = channels;
        this.remainingToday = remainingToday;
        this.invoiceId = invoiceId;
        this.amount = amount;
        this.dueDate = dueDate;
    }

    public boolean isSuccess() { return success; }
    public void setSuccess(boolean success) { this.success = success; }
    public List<String> getChannels() { return channels; }
    public void setChannels(List<String> channels) { this.channels = channels; }
    public int getRemainingToday() { return remainingToday; }
    public void setRemainingToday(int remainingToday) { this.remainingToday = remainingToday; }
    public String getInvoiceId() { return invoiceId; }
    public void setInvoiceId(String invoiceId) { this.invoiceId = invoiceId; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }
    public LocalDate getDueDate() { return dueDate; }
    public void setDueDate(LocalDate dueDate) { this.dueDate = dueDate; }
}
