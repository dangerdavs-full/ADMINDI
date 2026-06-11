package com.admindi.backend.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

public class InvoiceHistoryDTO {
    private String invoiceId;
    private String monthYear; // "2026-04" -> sirve para el eje X de la gráfica
    private LocalDate paidDate;
    private BigDecimal amountCollected;
    private String tenantName;
    private String status; // "PAID", "LATE"
    private String paymentReference;
    private String paymentNotes;

    public InvoiceHistoryDTO() {}

    public String getInvoiceId() { return invoiceId; }
    public void setInvoiceId(String invoiceId) { this.invoiceId = invoiceId; }

    public String getMonthYear() { return monthYear; }
    public void setMonthYear(String monthYear) { this.monthYear = monthYear; }

    public LocalDate getPaidDate() { return paidDate; }
    public void setPaidDate(LocalDate paidDate) { this.paidDate = paidDate; }

    public BigDecimal getAmountCollected() { return amountCollected; }
    public void setAmountCollected(BigDecimal amountCollected) { this.amountCollected = amountCollected; }

    public String getTenantName() { return tenantName; }
    public void setTenantName(String tenantName) { this.tenantName = tenantName; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getPaymentReference() { return paymentReference; }
    public void setPaymentReference(String paymentReference) { this.paymentReference = paymentReference; }

    public String getPaymentNotes() { return paymentNotes; }
    public void setPaymentNotes(String paymentNotes) { this.paymentNotes = paymentNotes; }
}
