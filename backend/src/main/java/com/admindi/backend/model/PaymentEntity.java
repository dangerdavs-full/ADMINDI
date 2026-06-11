package com.admindi.backend.model;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "payments")
public class PaymentEntity {

    @Id
    private String id = UUID.randomUUID().toString();

    @Column(name = "invoice_id", nullable = false)
    private String invoiceId;

    @Column(name = "owner_id", nullable = false)
    private String ownerId;

    @Column(name = "tenant_profile_id")
    private String tenantProfileId;

    @Column(nullable = false)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_method", nullable = false)
    private PaymentMethod paymentMethod;

    @Column(name = "gateway_reference")
    private String gatewayReference;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PaymentStatus status = PaymentStatus.PENDING;

    @Column(name = "paid_at")
    private LocalDateTime paidAt;

    @Column(name = "confirmed_by")
    private String confirmedBy;

    @Column(name = "confirmed_at")
    private LocalDateTime confirmedAt;

    @Column(columnDefinition = "TEXT")
    private String notes;

    @Column(name = "created_at")
    private LocalDateTime createdAt = LocalDateTime.now();

    // How much of this payment was applied to the invoice vs unapplied (credit)
    @Column(name = "applied_amount")
    private BigDecimal appliedAmount;

    @Column(name = "unapplied_amount")
    private BigDecimal unappliedAmount = BigDecimal.ZERO;

    // ─── V56: Clasificación IA (sugerencias editables por el dueño) ──────
    @Column(name = "ai_category")
    private String aiCategory;

    @Column(name = "ai_cfdi_use")
    private String aiCfdiUse;

    @Column(name = "ai_tax_deductible")
    private Boolean aiTaxDeductible;

    @Column(name = "ai_confidence", precision = 3, scale = 2)
    private BigDecimal aiConfidence;

    @Column(name = "ai_reviewed_by_user", nullable = false)
    private boolean aiReviewedByUser = false;

    @Column(name = "ai_last_run_at")
    private LocalDateTime aiLastRunAt;

    public PaymentEntity() {}

    // Getters & Setters
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getInvoiceId() { return invoiceId; }
    public void setInvoiceId(String invoiceId) { this.invoiceId = invoiceId; }

    public String getOwnerId() { return ownerId; }
    public void setOwnerId(String ownerId) { this.ownerId = ownerId; }

    public String getTenantProfileId() { return tenantProfileId; }
    public void setTenantProfileId(String tenantProfileId) { this.tenantProfileId = tenantProfileId; }

    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }

    public PaymentMethod getPaymentMethod() { return paymentMethod; }
    public void setPaymentMethod(PaymentMethod paymentMethod) { this.paymentMethod = paymentMethod; }

    public String getGatewayReference() { return gatewayReference; }
    public void setGatewayReference(String gatewayReference) { this.gatewayReference = gatewayReference; }

    public PaymentStatus getStatus() { return status; }
    public void setStatus(PaymentStatus status) { this.status = status; }

    public LocalDateTime getPaidAt() { return paidAt; }
    public void setPaidAt(LocalDateTime paidAt) { this.paidAt = paidAt; }

    public String getConfirmedBy() { return confirmedBy; }
    public void setConfirmedBy(String confirmedBy) { this.confirmedBy = confirmedBy; }

    public LocalDateTime getConfirmedAt() { return confirmedAt; }
    public void setConfirmedAt(LocalDateTime confirmedAt) { this.confirmedAt = confirmedAt; }

    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public BigDecimal getAppliedAmount() { return appliedAmount; }
    public void setAppliedAmount(BigDecimal appliedAmount) { this.appliedAmount = appliedAmount; }

    public BigDecimal getUnappliedAmount() { return unappliedAmount; }
    public void setUnappliedAmount(BigDecimal unappliedAmount) { this.unappliedAmount = unappliedAmount; }

    public String getAiCategory() { return aiCategory; }
    public void setAiCategory(String aiCategory) { this.aiCategory = aiCategory; }
    public String getAiCfdiUse() { return aiCfdiUse; }
    public void setAiCfdiUse(String aiCfdiUse) { this.aiCfdiUse = aiCfdiUse; }
    public Boolean getAiTaxDeductible() { return aiTaxDeductible; }
    public void setAiTaxDeductible(Boolean aiTaxDeductible) { this.aiTaxDeductible = aiTaxDeductible; }
    public BigDecimal getAiConfidence() { return aiConfidence; }
    public void setAiConfidence(BigDecimal aiConfidence) { this.aiConfidence = aiConfidence; }
    public boolean isAiReviewedByUser() { return aiReviewedByUser; }
    public void setAiReviewedByUser(boolean aiReviewedByUser) { this.aiReviewedByUser = aiReviewedByUser; }
    public LocalDateTime getAiLastRunAt() { return aiLastRunAt; }
    public void setAiLastRunAt(LocalDateTime aiLastRunAt) { this.aiLastRunAt = aiLastRunAt; }
}
