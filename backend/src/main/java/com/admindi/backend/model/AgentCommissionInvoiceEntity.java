package com.admindi.backend.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Comisión owed por el owner al agente inmobiliario al firmar un contrato con
 * éxito. Representa la "factura" interna de comisión; no emite CFDI (eso queda
 * en la contabilidad del agente fuera del sistema).
 *
 * <p>Fórmula adoptada (Fase 2):
 * {@code commission_amount = monthly_rent × contract_months × commission_pct}.
 *
 * <p>{@link #commissionPct} queda explícito en la fila para auditoría: si el
 * super admin cambia el default de plataforma (3%) en el futuro, las comisiones
 * previas no se re-calculan porque el % vigente al firmar ya está congelado aquí.
 *
 * <p>Flujo de estado:
 * <ul>
 *   <li>PENDING — recién creada; se notifica al dueño con CLABE del agente.</li>
 *   <li>AWAITING_SPEI — dueño subió comprobante; se está validando contra Banxico.</li>
 *   <li>PENDING_MANUAL_CONFIRM — 3 intentos de validación CEP fallaron; se pide
 *       al agente confirmar en su banco.</li>
 *   <li>PAID — validación OK o confirmación manual del agente; egreso contable
 *       actualizado a PAID.</li>
 *   <li>VOIDED — se anuló (p.ej. el contrato se canceló antes de pagar).</li>
 * </ul>
 */
@Entity
@Table(name = "agent_commission_invoices")
public class AgentCommissionInvoiceEntity {

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_AWAITING_SPEI = "AWAITING_SPEI";
    public static final String STATUS_PENDING_MANUAL = "PENDING_MANUAL_CONFIRM";
    public static final String STATUS_PAID = "PAID";
    public static final String STATUS_VOIDED = "VOIDED";

    public static final String SOURCE_PLATFORM = "PLATFORM";
    public static final String SOURCE_PRIVATE = "PRIVATE";

    public static final int MAX_SPEI_ATTEMPTS = 3;

    @Id
    @Column(name = "id", length = 64, nullable = false)
    private String id = UUID.randomUUID().toString();

    @Column(name = "owner_id", length = 64, nullable = false)
    private String ownerId;

    @Column(name = "agent_user_id", length = 64, nullable = false)
    private String agentUserId;

    @Column(name = "agent_source", length = 16, nullable = false)
    private String agentSource;

    @Column(name = "lease_id", length = 64)
    private String leaseId;

    @Column(name = "property_id", length = 64, nullable = false)
    private String propertyId;

    @Column(name = "vacancy_id", length = 64)
    private String vacancyId;

    @Column(name = "monthly_rent", precision = 14, scale = 2, nullable = false)
    private BigDecimal monthlyRent;

    @Column(name = "contract_months", nullable = false)
    private Integer contractMonths;

    @Column(name = "commission_pct", precision = 6, scale = 4, nullable = false)
    private BigDecimal commissionPct;

    @Column(name = "commission_amount", precision = 14, scale = 2, nullable = false)
    private BigDecimal commissionAmount;

    @Column(name = "status", length = 24, nullable = false)
    private String status = STATUS_PENDING;

    @Column(name = "spei_proof_file_id", length = 64)
    private String speiProofFileId;

    @Column(name = "spei_validation_attempts", nullable = false)
    private Integer speiValidationAttempts = 0;

    @Column(name = "spei_last_error", columnDefinition = "TEXT")
    private String speiLastError;

    @Column(name = "paid_at")
    private LocalDateTime paidAt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt = LocalDateTime.now();

    @Column(name = "expense_id", length = 64)
    private String expenseId;

    public AgentCommissionInvoiceEntity() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getOwnerId() { return ownerId; }
    public void setOwnerId(String ownerId) { this.ownerId = ownerId; }
    public String getAgentUserId() { return agentUserId; }
    public void setAgentUserId(String agentUserId) { this.agentUserId = agentUserId; }
    public String getAgentSource() { return agentSource; }
    public void setAgentSource(String agentSource) { this.agentSource = agentSource; }
    public String getLeaseId() { return leaseId; }
    public void setLeaseId(String leaseId) { this.leaseId = leaseId; }
    public String getPropertyId() { return propertyId; }
    public void setPropertyId(String propertyId) { this.propertyId = propertyId; }
    public String getVacancyId() { return vacancyId; }
    public void setVacancyId(String vacancyId) { this.vacancyId = vacancyId; }
    public BigDecimal getMonthlyRent() { return monthlyRent; }
    public void setMonthlyRent(BigDecimal monthlyRent) { this.monthlyRent = monthlyRent; }
    public Integer getContractMonths() { return contractMonths; }
    public void setContractMonths(Integer contractMonths) { this.contractMonths = contractMonths; }
    public BigDecimal getCommissionPct() { return commissionPct; }
    public void setCommissionPct(BigDecimal commissionPct) { this.commissionPct = commissionPct; }
    public BigDecimal getCommissionAmount() { return commissionAmount; }
    public void setCommissionAmount(BigDecimal commissionAmount) { this.commissionAmount = commissionAmount; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getSpeiProofFileId() { return speiProofFileId; }
    public void setSpeiProofFileId(String speiProofFileId) { this.speiProofFileId = speiProofFileId; }
    public Integer getSpeiValidationAttempts() { return speiValidationAttempts; }
    public void setSpeiValidationAttempts(Integer speiValidationAttempts) { this.speiValidationAttempts = speiValidationAttempts; }
    public String getSpeiLastError() { return speiLastError; }
    public void setSpeiLastError(String speiLastError) { this.speiLastError = speiLastError; }
    public LocalDateTime getPaidAt() { return paidAt; }
    public void setPaidAt(LocalDateTime paidAt) { this.paidAt = paidAt; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
    public String getExpenseId() { return expenseId; }
    public void setExpenseId(String expenseId) { this.expenseId = expenseId; }
}
