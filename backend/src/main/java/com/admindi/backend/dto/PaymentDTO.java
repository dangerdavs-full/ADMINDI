package com.admindi.backend.dto;

import java.math.BigDecimal;

public class PaymentDTO {
    private String id;
    private String invoiceId;
    private String tenantName;
    private String tenantEmail;
    private String monthYear;
    private BigDecimal amount;
    private BigDecimal appliedAmount;
    private BigDecimal unappliedAmount;
    private String paymentMethod;
    private String gatewayReference;
    private String status;
    private String paidAt;
    private String confirmedBy;
    private String confirmedAt;
    private String notes;
    private String createdAt;

    // V56 — clasificación IA
    private String aiCategory;
    private String aiCfdiUse;
    private Boolean aiTaxDeductible;
    private BigDecimal aiConfidence;
    private Boolean aiReviewedByUser;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getInvoiceId() { return invoiceId; }
    public void setInvoiceId(String invoiceId) { this.invoiceId = invoiceId; }
    public String getTenantName() { return tenantName; }
    public void setTenantName(String tenantName) { this.tenantName = tenantName; }
    public String getTenantEmail() { return tenantEmail; }
    public void setTenantEmail(String tenantEmail) { this.tenantEmail = tenantEmail; }
    public String getMonthYear() { return monthYear; }
    public void setMonthYear(String monthYear) { this.monthYear = monthYear; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }
    public BigDecimal getAppliedAmount() { return appliedAmount; }
    public void setAppliedAmount(BigDecimal appliedAmount) { this.appliedAmount = appliedAmount; }
    public BigDecimal getUnappliedAmount() { return unappliedAmount; }
    public void setUnappliedAmount(BigDecimal unappliedAmount) { this.unappliedAmount = unappliedAmount; }
    public String getPaymentMethod() { return paymentMethod; }
    public void setPaymentMethod(String paymentMethod) { this.paymentMethod = paymentMethod; }
    public String getGatewayReference() { return gatewayReference; }
    public void setGatewayReference(String gatewayReference) { this.gatewayReference = gatewayReference; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getPaidAt() { return paidAt; }
    public void setPaidAt(String paidAt) { this.paidAt = paidAt; }
    public String getConfirmedBy() { return confirmedBy; }
    public void setConfirmedBy(String confirmedBy) { this.confirmedBy = confirmedBy; }
    public String getConfirmedAt() { return confirmedAt; }
    public void setConfirmedAt(String confirmedAt) { this.confirmedAt = confirmedAt; }
    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }
    public String getCreatedAt() { return createdAt; }
    public void setCreatedAt(String createdAt) { this.createdAt = createdAt; }

    public String getAiCategory() { return aiCategory; }
    public void setAiCategory(String aiCategory) { this.aiCategory = aiCategory; }
    public String getAiCfdiUse() { return aiCfdiUse; }
    public void setAiCfdiUse(String aiCfdiUse) { this.aiCfdiUse = aiCfdiUse; }
    public Boolean getAiTaxDeductible() { return aiTaxDeductible; }
    public void setAiTaxDeductible(Boolean aiTaxDeductible) { this.aiTaxDeductible = aiTaxDeductible; }
    public BigDecimal getAiConfidence() { return aiConfidence; }
    public void setAiConfidence(BigDecimal aiConfidence) { this.aiConfidence = aiConfidence; }
    public Boolean getAiReviewedByUser() { return aiReviewedByUser; }
    public void setAiReviewedByUser(Boolean aiReviewedByUser) { this.aiReviewedByUser = aiReviewedByUser; }
}
