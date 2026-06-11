package com.admindi.backend.dto;

import java.math.BigDecimal;

public class AgreementInstallmentDTO {
    private String id;
    private String agreementId;
    private String dueDate;
    private BigDecimal amount;
    private String status;
    private String paidAt;
    private String paymentId;

    public String getId() { return id; } public void setId(String id) { this.id = id; }
    public String getAgreementId() { return agreementId; } public void setAgreementId(String v) { this.agreementId = v; }
    public String getDueDate() { return dueDate; } public void setDueDate(String v) { this.dueDate = v; }
    public BigDecimal getAmount() { return amount; } public void setAmount(BigDecimal v) { this.amount = v; }
    public String getStatus() { return status; } public void setStatus(String v) { this.status = v; }
    public String getPaidAt() { return paidAt; } public void setPaidAt(String v) { this.paidAt = v; }
    public String getPaymentId() { return paymentId; } public void setPaymentId(String v) { this.paymentId = v; }
}
