package com.admindi.backend.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "cep_validation_attempts")
public class CepValidationAttempt {

    @Id
    private String id = UUID.randomUUID().toString();

    @Column(name = "transfer_proof_id", nullable = false)
    private String transferProofId;

    @Column(name = "request_payload", columnDefinition = "jsonb")
    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.JSON)
    private String requestPayload;

    @Column(name = "response_payload", columnDefinition = "jsonb")
    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.JSON)
    private String responsePayload;

    @Column(nullable = false)
    private String status; // SUCCESS, FAILED, INCOMPLETE_DATA

    @Column(name = "missing_fields", columnDefinition = "jsonb")
    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.JSON)
    private String missingFields;

    @Column(name = "attempted_at")
    private LocalDateTime attemptedAt = LocalDateTime.now();

    public CepValidationAttempt() {}

    // Getters & Setters
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getTransferProofId() { return transferProofId; }
    public void setTransferProofId(String transferProofId) { this.transferProofId = transferProofId; }

    public String getRequestPayload() { return requestPayload; }
    public void setRequestPayload(String requestPayload) { this.requestPayload = requestPayload; }

    public String getResponsePayload() { return responsePayload; }
    public void setResponsePayload(String responsePayload) { this.responsePayload = responsePayload; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getMissingFields() { return missingFields; }
    public void setMissingFields(String missingFields) { this.missingFields = missingFields; }

    public LocalDateTime getAttemptedAt() { return attemptedAt; }
    public void setAttemptedAt(LocalDateTime attemptedAt) { this.attemptedAt = attemptedAt; }
}
