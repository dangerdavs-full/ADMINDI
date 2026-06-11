package com.admindi.backend.model;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Presupuesto de mantenimiento subido por proveedor/admin para aprobación del dueño.
 * Estados terminales: APPROVED | REJECTED.
 * La aprobación requiere reauth MFA+password (ver controller).
 */
@Entity
@Table(name = "maintenance_budgets")
public class MaintenanceBudgetEntity {

    public static final String STATUS_SUBMITTED = "SUBMITTED";
    public static final String STATUS_APPROVED = "APPROVED";
    public static final String STATUS_REJECTED = "REJECTED";

    @Id
    @Column(length = 64)
    private String id;

    @Column(name = "owner_id", nullable = false, length = 64)
    private String ownerId;

    @Column(name = "property_id", length = 64)
    private String propertyId;

    @Column(name = "provider_user_id", length = 64)
    private String providerUserId;

    @Column(nullable = false)
    private String title;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(precision = 14, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, length = 8)
    private String currency = "MXN";

    @Column(nullable = false, length = 32)
    private String status = STATUS_SUBMITTED;

    @Column(name = "file_url", nullable = false, length = 512)
    private String fileUrl;

    @Column(name = "file_name")
    private String fileName;

    @Column(name = "file_content_type", length = 128)
    private String fileContentType;

    @Column(name = "file_size_bytes")
    private Long fileSizeBytes;

    @Column(name = "submitted_by_user_id", nullable = false, length = 64)
    private String submittedByUserId;

    @Column(name = "submitted_at", nullable = false)
    private LocalDateTime submittedAt;

    @Column(name = "decided_at")
    private LocalDateTime decidedAt;

    @Column(name = "decided_by_user_id", length = 64)
    private String decidedByUserId;

    @Column(name = "decision_note", columnDefinition = "TEXT")
    private String decisionNote;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getOwnerId() { return ownerId; }
    public void setOwnerId(String ownerId) { this.ownerId = ownerId; }
    public String getPropertyId() { return propertyId; }
    public void setPropertyId(String propertyId) { this.propertyId = propertyId; }
    public String getProviderUserId() { return providerUserId; }
    public void setProviderUserId(String providerUserId) { this.providerUserId = providerUserId; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }
    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getFileUrl() { return fileUrl; }
    public void setFileUrl(String fileUrl) { this.fileUrl = fileUrl; }
    public String getFileName() { return fileName; }
    public void setFileName(String fileName) { this.fileName = fileName; }
    public String getFileContentType() { return fileContentType; }
    public void setFileContentType(String fileContentType) { this.fileContentType = fileContentType; }
    public Long getFileSizeBytes() { return fileSizeBytes; }
    public void setFileSizeBytes(Long fileSizeBytes) { this.fileSizeBytes = fileSizeBytes; }
    public String getSubmittedByUserId() { return submittedByUserId; }
    public void setSubmittedByUserId(String submittedByUserId) { this.submittedByUserId = submittedByUserId; }
    public LocalDateTime getSubmittedAt() { return submittedAt; }
    public void setSubmittedAt(LocalDateTime submittedAt) { this.submittedAt = submittedAt; }
    public LocalDateTime getDecidedAt() { return decidedAt; }
    public void setDecidedAt(LocalDateTime decidedAt) { this.decidedAt = decidedAt; }
    public String getDecidedByUserId() { return decidedByUserId; }
    public void setDecidedByUserId(String decidedByUserId) { this.decidedByUserId = decidedByUserId; }
    public String getDecisionNote() { return decisionNote; }
    public void setDecisionNote(String decisionNote) { this.decisionNote = decisionNote; }
}
