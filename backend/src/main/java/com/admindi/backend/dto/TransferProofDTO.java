package com.admindi.backend.dto;

import java.math.BigDecimal;

public class TransferProofDTO {
    private String id;
    private String invoiceId;
    private String tenantName;
    private String tenantEmail;
    private String monthYear;
    private String fileUrl;
    private Boolean cepXmlAvailable;
    private Boolean cepPdfAvailable;
    private String claveRastreo;
    private String bankEmitter;
    private String accountReceiver;
    private BigDecimal amount;
    private String transferDate;
    private String status;
    private String rejectionReason;
    private String missingFields;
    private String submittedAt;
    private String reviewedAt;
    private String reviewedBy;

    // V57 — sistema de tipos e intentos
    private String paymentType;          // SPEI | CASH
    private Integer attemptNumber;        // 1..3
    private Integer attemptsRemaining;    // cuántos le quedan al inquilino
    private String expiresAt;             // ISO; solo CASH
    private Long hoursRemaining;          // horas que le quedan al dueño para validar (CASH)
    private String ownerValidationNotes;  // motivo/nota del dueño

    // Getters & Setters
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
    public String getFileUrl() { return fileUrl; }
    public void setFileUrl(String fileUrl) { this.fileUrl = fileUrl; }
    public Boolean getCepXmlAvailable() { return cepXmlAvailable; }
    public void setCepXmlAvailable(Boolean cepXmlAvailable) { this.cepXmlAvailable = cepXmlAvailable; }
    public Boolean getCepPdfAvailable() { return cepPdfAvailable; }
    public void setCepPdfAvailable(Boolean cepPdfAvailable) { this.cepPdfAvailable = cepPdfAvailable; }
    public String getClaveRastreo() { return claveRastreo; }
    public void setClaveRastreo(String claveRastreo) { this.claveRastreo = claveRastreo; }
    public String getBankEmitter() { return bankEmitter; }
    public void setBankEmitter(String bankEmitter) { this.bankEmitter = bankEmitter; }
    public String getAccountReceiver() { return accountReceiver; }
    public void setAccountReceiver(String accountReceiver) { this.accountReceiver = accountReceiver; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }
    public String getTransferDate() { return transferDate; }
    public void setTransferDate(String transferDate) { this.transferDate = transferDate; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getRejectionReason() { return rejectionReason; }
    public void setRejectionReason(String rejectionReason) { this.rejectionReason = rejectionReason; }
    public String getMissingFields() { return missingFields; }
    public void setMissingFields(String missingFields) { this.missingFields = missingFields; }
    public String getSubmittedAt() { return submittedAt; }
    public void setSubmittedAt(String submittedAt) { this.submittedAt = submittedAt; }
    public String getReviewedAt() { return reviewedAt; }
    public void setReviewedAt(String reviewedAt) { this.reviewedAt = reviewedAt; }
    public String getReviewedBy() { return reviewedBy; }
    public void setReviewedBy(String reviewedBy) { this.reviewedBy = reviewedBy; }

    public String getPaymentType() { return paymentType; }
    public void setPaymentType(String paymentType) { this.paymentType = paymentType; }
    public Integer getAttemptNumber() { return attemptNumber; }
    public void setAttemptNumber(Integer attemptNumber) { this.attemptNumber = attemptNumber; }
    public Integer getAttemptsRemaining() { return attemptsRemaining; }
    public void setAttemptsRemaining(Integer attemptsRemaining) { this.attemptsRemaining = attemptsRemaining; }
    public String getExpiresAt() { return expiresAt; }
    public void setExpiresAt(String expiresAt) { this.expiresAt = expiresAt; }
    public Long getHoursRemaining() { return hoursRemaining; }
    public void setHoursRemaining(Long hoursRemaining) { this.hoursRemaining = hoursRemaining; }
    public String getOwnerValidationNotes() { return ownerValidationNotes; }
    public void setOwnerValidationNotes(String ownerValidationNotes) { this.ownerValidationNotes = ownerValidationNotes; }
}
