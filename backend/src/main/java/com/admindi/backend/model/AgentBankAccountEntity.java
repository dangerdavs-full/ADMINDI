package com.admindi.backend.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * CLABE interbancaria que un agente configura en su panel para recibir pagos del
 * dueño (comisión inmobiliaria o costo de mantenimiento).
 *
 * <p>Un agente (REAL_ESTATE_AGENT o MAINTENANCE_PROVIDER) tiene como máximo UNA
 * cuenta activa a la vez; cambiarla reemplaza la anterior (la fila se actualiza
 * in-place y se revalida).
 *
 * <p>Estados de {@link #validationStatus}:
 * <ul>
 *   <li>PENDING — recién capturada, aún no validada.</li>
 *   <li>VALIDATED — Banxico (adapter) confirmó la CLABE como válida.</li>
 *   <li>FAILED — 3 intentos de validación fallaron; el sistema ya no reintentará
 *       automáticamente pero la CLABE se muestra al dueño con una marca de
 *       "No validada"; el agente debe corregirla o confirmar manualmente.</li>
 * </ul>
 *
 * <p>Seguridad: la CLABE es dato financiero sensible. No loggeamos la CLABE
 * completa en audit logs; solo los últimos 4 dígitos.
 */
@Entity
@Table(name = "agent_bank_accounts")
public class AgentBankAccountEntity {

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_VALIDATED = "VALIDATED";
    public static final String STATUS_FAILED = "FAILED";

    /** Máximo de intentos de validación automática antes de marcar FAILED. */
    public static final int MAX_VALIDATION_ATTEMPTS = 3;

    @Id
    @Column(name = "id", length = 64, nullable = false)
    private String id = UUID.randomUUID().toString();

    @Column(name = "agent_user_id", length = 64, nullable = false, unique = true)
    private String agentUserId;

    @Column(name = "clabe", length = 18, nullable = false)
    private String clabe;

    @Column(name = "bank_name", length = 128)
    private String bankName;

    @Column(name = "account_holder", length = 255)
    private String accountHolder;

    @Column(name = "validation_status", length = 16, nullable = false)
    private String validationStatus = STATUS_PENDING;

    @Column(name = "validation_attempts", nullable = false)
    private Integer validationAttempts = 0;

    @Column(name = "last_validation_error", columnDefinition = "TEXT")
    private String lastValidationError;

    @Column(name = "validated_at")
    private LocalDateTime validatedAt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt = LocalDateTime.now();

    public AgentBankAccountEntity() {}

    /** Útil para mostrar a terceros sin exponer la CLABE completa. */
    public String maskedClabe() {
        if (clabe == null || clabe.length() < 4) {
            return "****";
        }
        return "**** **** **** " + clabe.substring(clabe.length() - 4);
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getAgentUserId() { return agentUserId; }
    public void setAgentUserId(String agentUserId) { this.agentUserId = agentUserId; }
    public String getClabe() { return clabe; }
    public void setClabe(String clabe) { this.clabe = clabe; }
    public String getBankName() { return bankName; }
    public void setBankName(String bankName) { this.bankName = bankName; }
    public String getAccountHolder() { return accountHolder; }
    public void setAccountHolder(String accountHolder) { this.accountHolder = accountHolder; }
    public String getValidationStatus() { return validationStatus; }
    public void setValidationStatus(String validationStatus) { this.validationStatus = validationStatus; }
    public Integer getValidationAttempts() { return validationAttempts; }
    public void setValidationAttempts(Integer validationAttempts) { this.validationAttempts = validationAttempts; }
    public String getLastValidationError() { return lastValidationError; }
    public void setLastValidationError(String lastValidationError) { this.lastValidationError = lastValidationError; }
    public LocalDateTime getValidatedAt() { return validatedAt; }
    public void setValidatedAt(LocalDateTime validatedAt) { this.validatedAt = validatedAt; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
