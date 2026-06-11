package com.admindi.backend.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Vacancia comercial de un inmueble — se abre al terminar un lease o al eliminar
 * el último tenant del expediente y queda activa hasta que se firma un nuevo
 * contrato o hasta que se cierra manualmente.
 *
 * <p>Fase 2: se agregan campos del ciclo comercial del agente inmobiliario.
 * {@link #chainState} refleja el paso actual del flujo (ver
 * {@link PropertyStatus} para el estado equivalente en el inmueble); los demás
 * campos guardan el avance específico (fotos subidas, contrato firmado, etc.).
 */
@Entity
@Table(name = "vacancies")
public class VacancyEntity {

    // Estados de chainState — alineados con los PropertyStatus del ciclo comercial.
    public static final String CHAIN_AWAITING_AGENT = "AWAITING_AGENT";
    public static final String CHAIN_AGENT_ACCEPTED = "AGENT_ACCEPTED";
    public static final String CHAIN_PHOTOS_UPLOADED = "PHOTOS_UPLOADED";
    public static final String CHAIN_PROSPECT_PROPOSED = "PROSPECT_PROPOSED";
    public static final String CHAIN_AWAITING_CONTRACT = "AWAITING_CONTRACT";
    public static final String CHAIN_CONTRACT_SIGNED = "CONTRACT_SIGNED";
    public static final String CHAIN_CLOSED = "CLOSED";
    public static final String CHAIN_EXHAUSTED = "CHAIN_EXHAUSTED";

    @Id
    private String id = UUID.randomUUID().toString();

    @Column(name = "owner_id", nullable = false)
    private String ownerId;

    @Column(name = "property_id", nullable = false)
    private String propertyId;

    /** Legado BD; dominio por propertyId. Oculto en API JSON. */
    @Column(name = "unit_id")
    private String unitId;

    @Column(name = "opened_at", nullable = false)
    private LocalDateTime openedAt = LocalDateTime.now();

    @Column(name = "closed_at")
    private LocalDateTime closedAt;

    @Column(nullable = false, length = 32)
    private String status = "OPEN";

    @Column(name = "assigned_agent_id")
    private String assignedAgentId;

    @Column(columnDefinition = "TEXT")
    private String notes;

    @Column(name = "chain_state", length = 32)
    private String chainState;

    @Column(name = "current_priority_order")
    private Integer currentPriorityOrder;

    @Column(name = "photos_uploaded_at")
    private LocalDateTime photosUploadedAt;

    @Column(name = "contract_signed_at")
    private LocalDateTime contractSignedAt;

    @Column(name = "contract_evidence_file_id", length = 64)
    private String contractEvidenceFileId;

    @Column(name = "contract_months")
    private Integer contractMonths;

    @Column(name = "contract_monthly_rent", precision = 14, scale = 2)
    private BigDecimal contractMonthlyRent;

    @Column(name = "contract_deposit", precision = 14, scale = 2)
    private BigDecimal contractDeposit;

    public VacancyEntity() {}

    public String getId() { return id; } public void setId(String id) { this.id = id; }
    public String getOwnerId() { return ownerId; } public void setOwnerId(String v) { this.ownerId = v; }
    public String getPropertyId() { return propertyId; } public void setPropertyId(String v) { this.propertyId = v; }
    @JsonIgnore
    public String getUnitId() { return unitId; }
    @JsonIgnore
    public void setUnitId(String v) { this.unitId = v; }
    public LocalDateTime getOpenedAt() { return openedAt; } public void setOpenedAt(LocalDateTime v) { this.openedAt = v; }
    public LocalDateTime getClosedAt() { return closedAt; } public void setClosedAt(LocalDateTime v) { this.closedAt = v; }
    public String getStatus() { return status; } public void setStatus(String v) { this.status = v; }
    public String getAssignedAgentId() { return assignedAgentId; } public void setAssignedAgentId(String v) { this.assignedAgentId = v; }
    public String getNotes() { return notes; } public void setNotes(String v) { this.notes = v; }
    public String getChainState() { return chainState; }
    public void setChainState(String chainState) { this.chainState = chainState; }
    public Integer getCurrentPriorityOrder() { return currentPriorityOrder; }
    public void setCurrentPriorityOrder(Integer currentPriorityOrder) { this.currentPriorityOrder = currentPriorityOrder; }
    public LocalDateTime getPhotosUploadedAt() { return photosUploadedAt; }
    public void setPhotosUploadedAt(LocalDateTime photosUploadedAt) { this.photosUploadedAt = photosUploadedAt; }
    public LocalDateTime getContractSignedAt() { return contractSignedAt; }
    public void setContractSignedAt(LocalDateTime contractSignedAt) { this.contractSignedAt = contractSignedAt; }
    public String getContractEvidenceFileId() { return contractEvidenceFileId; }
    public void setContractEvidenceFileId(String contractEvidenceFileId) { this.contractEvidenceFileId = contractEvidenceFileId; }
    public Integer getContractMonths() { return contractMonths; }
    public void setContractMonths(Integer contractMonths) { this.contractMonths = contractMonths; }
    public BigDecimal getContractMonthlyRent() { return contractMonthlyRent; }
    public void setContractMonthlyRent(BigDecimal contractMonthlyRent) { this.contractMonthlyRent = contractMonthlyRent; }
    public BigDecimal getContractDeposit() { return contractDeposit; }
    public void setContractDeposit(BigDecimal contractDeposit) { this.contractDeposit = contractDeposit; }
}
