package com.admindi.backend.dto;

import java.math.BigDecimal;

public class InvoiceDTO {
    private String id;
    private String tenantName;
    private String tenantEmail;
    private String monthYear;
    private String issueDate;
    private String dueDate;
    private BigDecimal baseAmount;
    private BigDecimal appliedLateFee;
    private BigDecimal totalAmount;

    // Settlement accounting
    private BigDecimal paidAmount;
    private BigDecimal outstandingAmount;
    private BigDecimal creditBalance;
    private String settlementStatus; // UNPAID, PARTIALLY_PAID, PAID, OVERPAID

    private String status;
    private String paidDate;
    private String paymentReference;
    private String proofOfPaymentUrl;
    private String tenantUploadDate;

    private String leaseId;
    private String propertyId;
    private String shortfallReason;
    private String shortfallDescription;
    private String promisedCompletionDate;
    /** ACTIVE, REQUESTED, APPROVED, BREACHED, or null */
    private String agreementSummaryStatus;

    // Getters y Setters
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getTenantName() { return tenantName; }
    public void setTenantName(String tenantName) { this.tenantName = tenantName; }
    public String getTenantEmail() { return tenantEmail; }
    public void setTenantEmail(String tenantEmail) { this.tenantEmail = tenantEmail; }
    public String getMonthYear() { return monthYear; }
    public void setMonthYear(String monthYear) { this.monthYear = monthYear; }
    public String getIssueDate() { return issueDate; }
    public void setIssueDate(String issueDate) { this.issueDate = issueDate; }
    public String getDueDate() { return dueDate; }
    public void setDueDate(String dueDate) { this.dueDate = dueDate; }
    public BigDecimal getBaseAmount() { return baseAmount; }
    public void setBaseAmount(BigDecimal baseAmount) { this.baseAmount = baseAmount; }
    public BigDecimal getAppliedLateFee() { return appliedLateFee; }
    public void setAppliedLateFee(BigDecimal appliedLateFee) { this.appliedLateFee = appliedLateFee; }
    public BigDecimal getTotalAmount() { return totalAmount; }
    public void setTotalAmount(BigDecimal totalAmount) { this.totalAmount = totalAmount; }
    public BigDecimal getPaidAmount() { return paidAmount; }
    public void setPaidAmount(BigDecimal paidAmount) { this.paidAmount = paidAmount; }
    public BigDecimal getOutstandingAmount() { return outstandingAmount; }
    public void setOutstandingAmount(BigDecimal outstandingAmount) { this.outstandingAmount = outstandingAmount; }
    public BigDecimal getCreditBalance() { return creditBalance; }
    public void setCreditBalance(BigDecimal creditBalance) { this.creditBalance = creditBalance; }
    public String getSettlementStatus() { return settlementStatus; }
    public void setSettlementStatus(String settlementStatus) { this.settlementStatus = settlementStatus; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getPaidDate() { return paidDate; }
    public void setPaidDate(String paidDate) { this.paidDate = paidDate; }
    public String getPaymentReference() { return paymentReference; }
    public void setPaymentReference(String paymentReference) { this.paymentReference = paymentReference; }
    public String getProofOfPaymentUrl() { return proofOfPaymentUrl; }
    public void setProofOfPaymentUrl(String proofOfPaymentUrl) { this.proofOfPaymentUrl = proofOfPaymentUrl; }
    public String getTenantUploadDate() { return tenantUploadDate; }
    public void setTenantUploadDate(String tenantUploadDate) { this.tenantUploadDate = tenantUploadDate; }
    public String getLeaseId() { return leaseId; }
    public void setLeaseId(String leaseId) { this.leaseId = leaseId; }
    public String getPropertyId() { return propertyId; }
    public void setPropertyId(String propertyId) { this.propertyId = propertyId; }
    public String getShortfallReason() { return shortfallReason; }
    public void setShortfallReason(String shortfallReason) { this.shortfallReason = shortfallReason; }
    public String getShortfallDescription() { return shortfallDescription; }
    public void setShortfallDescription(String shortfallDescription) { this.shortfallDescription = shortfallDescription; }
    public String getPromisedCompletionDate() { return promisedCompletionDate; }
    public void setPromisedCompletionDate(String promisedCompletionDate) { this.promisedCompletionDate = promisedCompletionDate; }
    public String getAgreementSummaryStatus() { return agreementSummaryStatus; }
    public void setAgreementSummaryStatus(String agreementSummaryStatus) { this.agreementSummaryStatus = agreementSummaryStatus; }
}
