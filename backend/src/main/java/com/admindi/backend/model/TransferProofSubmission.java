package com.admindi.backend.model;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "transfer_proof_submissions")
public class TransferProofSubmission {

    @Id
    private String id = UUID.randomUUID().toString();

    @Column(name = "invoice_id", nullable = false)
    private String invoiceId;

    @Column(name = "owner_id", nullable = false)
    private String ownerId;

    @Column(name = "tenant_profile_id")
    private String tenantProfileId;

    @Column(name = "file_url")
    private String fileUrl;

    @Column(name = "cep_xml_url")
    private String cepXmlUrl;

    @Column(name = "cep_pdf_url")
    private String cepPdfUrl;

    @Column(name = "clave_rastreo")
    private String claveRastreo;

    @Column(name = "bank_emitter")
    private String bankEmitter;

    @Column(name = "account_receiver")
    private String accountReceiver;

    private BigDecimal amount;

    @Column(name = "transfer_date")
    private LocalDate transferDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TransferProofStatus status = TransferProofStatus.RECEIVED;

    @Column(name = "rejection_reason", columnDefinition = "TEXT")
    private String rejectionReason;

    @Column(name = "missing_fields", columnDefinition = "jsonb")
    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.JSON)
    private String missingFields;

    @Column(name = "submitted_at")
    private LocalDateTime submittedAt = LocalDateTime.now();

    @Column(name = "reviewed_at")
    private LocalDateTime reviewedAt;

    @Column(name = "reviewed_by")
    private String reviewedBy;

    // ── V57 — Tipificación y sistema de intentos ──────────────────────────

    /**
     * SPEI o CASH. Default SPEI por back-compat con filas históricas.
     * Se decide en el momento del submit y no cambia después.
     */
    @Column(name = "payment_type", nullable = false)
    private String paymentType = "SPEI";

    /** 1, 2 o 3. Auto-calculado al submit usando el contador por invoice+type. */
    @Column(name = "attempt_number", nullable = false)
    private int attemptNumber = 1;

    /**
     * Solo aplica a CASH: fecha/hora tras la que {@link TransferProofStatus#EXPIRED_AWAITING_OWNER}
     * se aplica si el dueño no decidió. Default: submitted_at + 120h.
     */
    @Column(name = "expires_at")
    private LocalDateTime expiresAt;

    /**
     * Notas del dueño al aprobar o rechazar un comprobante CASH. Visible al
     * inquilino si el rechazo lleva motivo (para que sepa qué hizo mal).
     */
    @Column(name = "owner_validation_notes", columnDefinition = "TEXT")
    private String ownerValidationNotes;

    /**
     * V58 — cómo capturó los datos el inquilino:
     *  - AI_OCR: subió foto, Claude Vision extrajo datos (límite 6/mes por inquilino).
     *  - MANUAL: tecleó los datos (ilimitado).
     *  - CASH: pago en efectivo (reglas V57 propias).
     */
    @Column(name = "capture_method", nullable = false)
    private String captureMethod = "MANUAL";

    public TransferProofSubmission() {}

    // Getters & Setters
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getInvoiceId() { return invoiceId; }
    public void setInvoiceId(String invoiceId) { this.invoiceId = invoiceId; }

    public String getOwnerId() { return ownerId; }
    public void setOwnerId(String ownerId) { this.ownerId = ownerId; }

    public String getTenantProfileId() { return tenantProfileId; }
    public void setTenantProfileId(String tenantProfileId) { this.tenantProfileId = tenantProfileId; }

    public String getFileUrl() { return fileUrl; }
    public void setFileUrl(String fileUrl) { this.fileUrl = fileUrl; }

    public String getCepXmlUrl() { return cepXmlUrl; }
    public void setCepXmlUrl(String cepXmlUrl) { this.cepXmlUrl = cepXmlUrl; }

    public String getCepPdfUrl() { return cepPdfUrl; }
    public void setCepPdfUrl(String cepPdfUrl) { this.cepPdfUrl = cepPdfUrl; }

    public String getClaveRastreo() { return claveRastreo; }
    public void setClaveRastreo(String claveRastreo) { this.claveRastreo = claveRastreo; }

    public String getBankEmitter() { return bankEmitter; }
    public void setBankEmitter(String bankEmitter) { this.bankEmitter = bankEmitter; }

    public String getAccountReceiver() { return accountReceiver; }
    public void setAccountReceiver(String accountReceiver) { this.accountReceiver = accountReceiver; }

    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }

    public LocalDate getTransferDate() { return transferDate; }
    public void setTransferDate(LocalDate transferDate) { this.transferDate = transferDate; }

    public TransferProofStatus getStatus() { return status; }
    public void setStatus(TransferProofStatus status) { this.status = status; }

    public String getRejectionReason() { return rejectionReason; }
    public void setRejectionReason(String rejectionReason) { this.rejectionReason = rejectionReason; }

    public String getMissingFields() { return missingFields; }
    public void setMissingFields(String missingFields) { this.missingFields = missingFields; }

    public LocalDateTime getSubmittedAt() { return submittedAt; }
    public void setSubmittedAt(LocalDateTime submittedAt) { this.submittedAt = submittedAt; }

    public LocalDateTime getReviewedAt() { return reviewedAt; }
    public void setReviewedAt(LocalDateTime reviewedAt) { this.reviewedAt = reviewedAt; }

    public String getReviewedBy() { return reviewedBy; }
    public void setReviewedBy(String reviewedBy) { this.reviewedBy = reviewedBy; }

    public String getPaymentType() { return paymentType; }
    public void setPaymentType(String paymentType) { this.paymentType = paymentType; }

    public int getAttemptNumber() { return attemptNumber; }
    public void setAttemptNumber(int attemptNumber) { this.attemptNumber = attemptNumber; }

    public LocalDateTime getExpiresAt() { return expiresAt; }
    public void setExpiresAt(LocalDateTime expiresAt) { this.expiresAt = expiresAt; }

    public String getOwnerValidationNotes() { return ownerValidationNotes; }
    public void setOwnerValidationNotes(String ownerValidationNotes) { this.ownerValidationNotes = ownerValidationNotes; }

    public boolean isCash() { return "CASH".equalsIgnoreCase(paymentType); }
    public boolean isSpei() { return "SPEI".equalsIgnoreCase(paymentType); }

    public String getCaptureMethod() { return captureMethod; }
    public void setCaptureMethod(String captureMethod) { this.captureMethod = captureMethod; }
    public boolean isAiOcr() { return "AI_OCR".equalsIgnoreCase(captureMethod); }
    public boolean isManualCapture() { return "MANUAL".equalsIgnoreCase(captureMethod); }
}
