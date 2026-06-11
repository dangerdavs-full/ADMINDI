package com.admindi.backend.model;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "expenses")
public class ExpenseEntity {

    @Id
    private String id = UUID.randomUUID().toString();

    @Column(name = "owner_id", nullable = false)
    private String ownerId;

    @Column(name = "property_id", nullable = false)
    private String propertyId;

    @Column(nullable = false, length = 30)
    private String type; // MAINTENANCE, COMMERCIAL, MANUAL

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(nullable = false)
    private BigDecimal amount;

    @Column(nullable = false, length = 20)
    private String status = "QUOTED"; // QUOTED, APPROVED, REJECTED, PAID

    @Column(name = "approved_by")
    private String approvedBy;

    @Column(name = "approved_at")
    private LocalDateTime approvedAt;

    @Column(name = "paid_at")
    private LocalDateTime paidAt;

    @Column(name = "linked_resource_type", length = 50)
    private String linkedResourceType; // MAINTENANCE_TICKET, COMMERCIAL_ACTIVITY, etc.

    @Column(name = "linked_resource_id")
    private String linkedResourceId;

    @Column(name = "evidence_file_id")
    private String evidenceFileId;

    @Column(columnDefinition = "TEXT")
    private String notes;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "approved_amount")
    private BigDecimal approvedAmount;

    @Column(name = "paid_amount", nullable = false)
    private BigDecimal paidAmount = BigDecimal.ZERO;

    @Column(name = "outstanding_amount")
    private BigDecimal outstandingAmount;

    @Column(name = "payment_settlement_status", nullable = false, length = 32)
    private String paymentSettlementStatus = "UNPAID";

    @Column(name = "payment_method", length = 24)
    private String paymentMethod;

    @Column(name = "owner_confirmation_status", nullable = false, length = 24)
    private String ownerConfirmationStatus = "PENDING";

    @Column(name = "provider_confirmation_status", nullable = false, length = 24)
    private String providerConfirmationStatus = "PENDING";

    @Column(name = "provider_user_id")
    private String providerUserId;

    // V49 / Bloque 4: referencias al archivo del presupuesto del proveedor y al
    // comprobante de pago del dueño. Permiten que el expediente del inmueble
    // muestre el egreso con sus dos documentos "por dentro" sin tener que saltar
    // a maintenance_quotes / payments. Ambos son opcionales; las filas previas
    // a V49 los tienen null y el frontend lo muestra como "sin adjunto".
    @Column(name = "budget_file_id")
    private String budgetFileId;

    @Column(name = "payment_proof_file_id")
    private String paymentProofFileId;

    /**
     * V64 — Descuento absorbido por la plataforma (crédito a favor del dueño).
     * Típicamente 15% del bruto cuando el proveedor es PLATFORM; 0 en otro caso.
     * No afecta lo que el proveedor cobra — el dueño transfiere el bruto, pero
     * contablemente solo "le sale" el neto.
     */
    @Column(name = "platform_credit_amount", nullable = false, precision = 14, scale = 2)
    private BigDecimal platformCreditAmount = BigDecimal.ZERO;

    /**
     * V64 — Lo que realmente le salió al dueño de su bolsillo
     * (= amount - platformCreditAmount). Los reportes contables deben sumar
     * este campo, no {@code amount}, para no inflar los egresos con el crédito
     * de plataforma.
     */
    @Column(name = "net_expense_amount", precision = 14, scale = 2)
    private BigDecimal netExpenseAmount;

    // ─── V56: Clasificación IA (sugerencias editables por el dueño/contador) ──
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

    public ExpenseEntity() {}

    // Getters & Setters
    public String getId() { return id; } public void setId(String id) { this.id = id; }
    public String getOwnerId() { return ownerId; } public void setOwnerId(String v) { this.ownerId = v; }
    public String getPropertyId() { return propertyId; } public void setPropertyId(String v) { this.propertyId = v; }
    public String getType() { return type; } public void setType(String v) { this.type = v; }
    public String getDescription() { return description; } public void setDescription(String v) { this.description = v; }
    public BigDecimal getAmount() { return amount; } public void setAmount(BigDecimal v) { this.amount = v; }
    public String getStatus() { return status; } public void setStatus(String v) { this.status = v; }
    public String getApprovedBy() { return approvedBy; } public void setApprovedBy(String v) { this.approvedBy = v; }
    public LocalDateTime getApprovedAt() { return approvedAt; } public void setApprovedAt(LocalDateTime v) { this.approvedAt = v; }
    public LocalDateTime getPaidAt() { return paidAt; } public void setPaidAt(LocalDateTime v) { this.paidAt = v; }
    public String getLinkedResourceType() { return linkedResourceType; } public void setLinkedResourceType(String v) { this.linkedResourceType = v; }
    public String getLinkedResourceId() { return linkedResourceId; } public void setLinkedResourceId(String v) { this.linkedResourceId = v; }
    public String getEvidenceFileId() { return evidenceFileId; } public void setEvidenceFileId(String v) { this.evidenceFileId = v; }
    public String getNotes() { return notes; } public void setNotes(String v) { this.notes = v; }
    public LocalDateTime getCreatedAt() { return createdAt; } public void setCreatedAt(LocalDateTime v) { this.createdAt = v; }

    public BigDecimal getApprovedAmount() { return approvedAmount; }
    public void setApprovedAmount(BigDecimal approvedAmount) { this.approvedAmount = approvedAmount; }
    public BigDecimal getPaidAmount() { return paidAmount; }
    public void setPaidAmount(BigDecimal paidAmount) { this.paidAmount = paidAmount; }
    public BigDecimal getOutstandingAmount() { return outstandingAmount; }
    public void setOutstandingAmount(BigDecimal outstandingAmount) { this.outstandingAmount = outstandingAmount; }
    public String getPaymentSettlementStatus() { return paymentSettlementStatus; }
    public void setPaymentSettlementStatus(String paymentSettlementStatus) { this.paymentSettlementStatus = paymentSettlementStatus; }
    public String getPaymentMethod() { return paymentMethod; }
    public void setPaymentMethod(String paymentMethod) { this.paymentMethod = paymentMethod; }
    public String getOwnerConfirmationStatus() { return ownerConfirmationStatus; }
    public void setOwnerConfirmationStatus(String ownerConfirmationStatus) { this.ownerConfirmationStatus = ownerConfirmationStatus; }
    public String getProviderConfirmationStatus() { return providerConfirmationStatus; }
    public void setProviderConfirmationStatus(String providerConfirmationStatus) { this.providerConfirmationStatus = providerConfirmationStatus; }
    public String getProviderUserId() { return providerUserId; }
    public void setProviderUserId(String providerUserId) { this.providerUserId = providerUserId; }
    public String getBudgetFileId() { return budgetFileId; }
    public void setBudgetFileId(String budgetFileId) { this.budgetFileId = budgetFileId; }
    public String getPaymentProofFileId() { return paymentProofFileId; }
    public void setPaymentProofFileId(String paymentProofFileId) { this.paymentProofFileId = paymentProofFileId; }
    public BigDecimal getPlatformCreditAmount() { return platformCreditAmount; }
    public void setPlatformCreditAmount(BigDecimal platformCreditAmount) {
        this.platformCreditAmount = platformCreditAmount != null ? platformCreditAmount : BigDecimal.ZERO;
    }
    public BigDecimal getNetExpenseAmount() { return netExpenseAmount; }
    public void setNetExpenseAmount(BigDecimal netExpenseAmount) { this.netExpenseAmount = netExpenseAmount; }

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
