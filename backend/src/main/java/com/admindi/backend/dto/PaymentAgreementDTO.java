package com.admindi.backend.dto;

import java.math.BigDecimal;
import java.util.List;

public class PaymentAgreementDTO {
    private String id;
    private String ownerId;
    private String tenantProfileId;
    private String tenantName;
    private String tenantEmail;
    private String leaseId;
    private String invoiceId;
    private String monthYear;
    private BigDecimal requestedAmount;
    private BigDecimal approvedAmount;
    private BigDecimal deferredAmount;
    private String reason;
    private String description;
    private String evidenceFileUrl;
    private String status;
    private String createdAt;
    private String approvedAt;
    private String approvedBy;
    private String rejectedAt;
    private String rejectedBy;
    private String rejectionReason;
    private List<AgreementInstallmentDTO> installments;

    // Getters & Setters
    public String getId() { return id; } public void setId(String id) { this.id = id; }
    public String getOwnerId() { return ownerId; } public void setOwnerId(String ownerId) { this.ownerId = ownerId; }
    public String getTenantProfileId() { return tenantProfileId; } public void setTenantProfileId(String v) { this.tenantProfileId = v; }
    public String getTenantName() { return tenantName; } public void setTenantName(String v) { this.tenantName = v; }
    public String getTenantEmail() { return tenantEmail; } public void setTenantEmail(String v) { this.tenantEmail = v; }
    public String getLeaseId() { return leaseId; } public void setLeaseId(String v) { this.leaseId = v; }
    public String getInvoiceId() { return invoiceId; } public void setInvoiceId(String v) { this.invoiceId = v; }
    public String getMonthYear() { return monthYear; } public void setMonthYear(String v) { this.monthYear = v; }
    public BigDecimal getRequestedAmount() { return requestedAmount; } public void setRequestedAmount(BigDecimal v) { this.requestedAmount = v; }
    public BigDecimal getApprovedAmount() { return approvedAmount; } public void setApprovedAmount(BigDecimal v) { this.approvedAmount = v; }
    public BigDecimal getDeferredAmount() { return deferredAmount; } public void setDeferredAmount(BigDecimal v) { this.deferredAmount = v; }
    public String getReason() { return reason; } public void setReason(String v) { this.reason = v; }
    public String getDescription() { return description; } public void setDescription(String v) { this.description = v; }
    public String getEvidenceFileUrl() { return evidenceFileUrl; } public void setEvidenceFileUrl(String v) { this.evidenceFileUrl = v; }
    public String getStatus() { return status; } public void setStatus(String v) { this.status = v; }
    public String getCreatedAt() { return createdAt; } public void setCreatedAt(String v) { this.createdAt = v; }
    public String getApprovedAt() { return approvedAt; } public void setApprovedAt(String v) { this.approvedAt = v; }
    public String getApprovedBy() { return approvedBy; } public void setApprovedBy(String v) { this.approvedBy = v; }
    public String getRejectedAt() { return rejectedAt; } public void setRejectedAt(String v) { this.rejectedAt = v; }
    public String getRejectedBy() { return rejectedBy; } public void setRejectedBy(String v) { this.rejectedBy = v; }
    public String getRejectionReason() { return rejectionReason; } public void setRejectionReason(String v) { this.rejectionReason = v; }
    public List<AgreementInstallmentDTO> getInstallments() { return installments; } public void setInstallments(List<AgreementInstallmentDTO> v) { this.installments = v; }
}
