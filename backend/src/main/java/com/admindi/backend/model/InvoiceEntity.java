package com.admindi.backend.model;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "invoices")
public class InvoiceEntity {

    @Id
    private String id = UUID.randomUUID().toString();

    @Column(nullable = false)
    private String tenantProfileId;

    @Column(nullable = false)
    private String ownerId;

    @Column(nullable = false)
    private String monthYear; // Formato YYYY-MM (e.g. 2026-04)

    @Column(nullable = false)
    private LocalDate issueDate;

    @Column(nullable = false)
    private LocalDate dueDate;

    @Column(nullable = false)
    private BigDecimal baseAmount;

    @Column(nullable = false)
    private BigDecimal appliedLateFee = BigDecimal.ZERO;

    @Column(nullable = false)
    private BigDecimal totalAmount;

    // Settlement accounting
    @Column(name = "paid_amount", nullable = false)
    private BigDecimal paidAmount = BigDecimal.ZERO;

    @Column(name = "outstanding_amount")
    private BigDecimal outstandingAmount;

    @Column(name = "credit_balance", nullable = false)
    private BigDecimal creditBalance = BigDecimal.ZERO;

    @Column(name = "settlement_status", nullable = false)
    private String settlementStatus = "UNPAID"; // UNPAID, PARTIALLY_PAID, PAID, OVERPAID

    @Column(nullable = false)
    private String status; // PENDING, PAID, LATE, PARTIALLY_PAID

    private LocalDate paidDate;

    @Column(name = "lease_id")
    private String leaseId;

    @Column
    private String concept = "RENTA_MENSUAL";

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_method")
    private PaymentMethod paymentMethod;

    @Column(name = "payment_gateway_ref")
    private String paymentGatewayRef;

    @Column(columnDefinition = "TEXT")
    private String paymentNotes;

    @Column
    private String paymentReference;

    @Column
    private String proofOfPaymentUrl;

    @Column
    private java.time.LocalDateTime tenantUploadDate;

    public InvoiceEntity() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getTenantProfileId() { return tenantProfileId; }
    public void setTenantProfileId(String tenantProfileId) { this.tenantProfileId = tenantProfileId; }

    public String getOwnerId() { return ownerId; }
    public void setOwnerId(String ownerId) { this.ownerId = ownerId; }

    public String getMonthYear() { return monthYear; }
    public void setMonthYear(String monthYear) { this.monthYear = monthYear; }

    public LocalDate getIssueDate() { return issueDate; }
    public void setIssueDate(LocalDate issueDate) { this.issueDate = issueDate; }

    public LocalDate getDueDate() { return dueDate; }
    public void setDueDate(LocalDate dueDate) { this.dueDate = dueDate; }

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

    public LocalDate getPaidDate() { return paidDate; }
    public void setPaidDate(LocalDate paidDate) { this.paidDate = paidDate; }

    public String getPaymentNotes() { return paymentNotes; }
    public void setPaymentNotes(String paymentNotes) { this.paymentNotes = paymentNotes; }

    public String getPaymentReference() { return paymentReference; }
    public void setPaymentReference(String paymentReference) { this.paymentReference = paymentReference; }

    public String getProofOfPaymentUrl() { return proofOfPaymentUrl; }
    public void setProofOfPaymentUrl(String proofOfPaymentUrl) { this.proofOfPaymentUrl = proofOfPaymentUrl; }

    public java.time.LocalDateTime getTenantUploadDate() { return tenantUploadDate; }
    public void setTenantUploadDate(java.time.LocalDateTime tenantUploadDate) { this.tenantUploadDate = tenantUploadDate; }

    public String getLeaseId() { return leaseId; }
    public void setLeaseId(String leaseId) { this.leaseId = leaseId; }

    public String getConcept() { return concept; }
    public void setConcept(String concept) { this.concept = concept; }

    public PaymentMethod getPaymentMethod() { return paymentMethod; }
    public void setPaymentMethod(PaymentMethod paymentMethod) { this.paymentMethod = paymentMethod; }

    public String getPaymentGatewayRef() { return paymentGatewayRef; }
    public void setPaymentGatewayRef(String paymentGatewayRef) { this.paymentGatewayRef = paymentGatewayRef; }

    // --- Shortfall reason (partial payment justification) ---
    @Column(name = "shortfall_reason", length = 40)
    private String shortfallReason; // PARTIAL_SAME_MONTH, PARTIAL_NEXT_MONTH, REQUESTING_AGREEMENT, BANK_ISSUE, OTHER

    @Column(name = "shortfall_description", columnDefinition = "TEXT")
    private String shortfallDescription;

    @Column(name = "promised_completion_date")
    private LocalDate promisedCompletionDate;

    public String getShortfallReason() { return shortfallReason; }
    public void setShortfallReason(String shortfallReason) { this.shortfallReason = shortfallReason; }
    public String getShortfallDescription() { return shortfallDescription; }
    public void setShortfallDescription(String shortfallDescription) { this.shortfallDescription = shortfallDescription; }
    public LocalDate getPromisedCompletionDate() { return promisedCompletionDate; }
    public void setPromisedCompletionDate(LocalDate promisedCompletionDate) { this.promisedCompletionDate = promisedCompletionDate; }
}
