package com.admindi.backend.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Prospecto de arrendatario propuesto por el agente inmobiliario al dueño durante
 * el ciclo de captación de una vacancia.
 *
 * <p>Flujo:
 * <ol>
 *   <li>Agente captura (nombre, teléfono, email, notas) → {@code PENDING}.</li>
 *   <li>Dueño decide: {@code ACCEPTED} → estado del inmueble pasa a AWAITING_CONTRACT.
 *       {@code REJECTED} → vuelve a PENDING_RENT; el agente puede proponer otro.</li>
 *   <li>Si el dueño no responde en 24h, se dispara recordatorio (reusa
 *       {@link #lastReminderAt} para no spamear más seguido).</li>
 * </ol>
 *
 * <p>No guardamos datos sensibles del prospecto (INE, comprobantes) aquí; ese
 * paso es parte del expediente del arrendatario (tenant_profiles) que se crea al
 * firmar. Sólo nombre + contacto básico para que el dueño decida.
 */
@Entity
@Table(name = "prospect_submissions")
public class ProspectSubmissionEntity {

    public static final String DECISION_PENDING = "PENDING";
    public static final String DECISION_ACCEPTED = "ACCEPTED";
    public static final String DECISION_REJECTED = "REJECTED";

    @Id
    @Column(name = "id", length = 64, nullable = false)
    private String id = UUID.randomUUID().toString();

    @Column(name = "vacancy_id", length = 64, nullable = false)
    private String vacancyId;

    @Column(name = "property_id", length = 64, nullable = false)
    private String propertyId;

    @Column(name = "owner_id", length = 64, nullable = false)
    private String ownerId;

    @Column(name = "agent_user_id", length = 64, nullable = false)
    private String agentUserId;

    @Column(name = "prospect_name", length = 255, nullable = false)
    private String prospectName;

    @Column(name = "prospect_phone", length = 32)
    private String prospectPhone;

    @Column(name = "prospect_email", length = 255)
    private String prospectEmail;

    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;

    @Column(name = "submitted_at", nullable = false)
    private LocalDateTime submittedAt = LocalDateTime.now();

    @Column(name = "owner_decision", length = 16, nullable = false)
    private String ownerDecision = DECISION_PENDING;

    @Column(name = "decided_at")
    private LocalDateTime decidedAt;

    @Column(name = "decided_by", length = 64)
    private String decidedBy;

    @Column(name = "rejection_reason", columnDefinition = "TEXT")
    private String rejectionReason;

    @Column(name = "last_reminder_at")
    private LocalDateTime lastReminderAt;

    public ProspectSubmissionEntity() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getVacancyId() { return vacancyId; }
    public void setVacancyId(String vacancyId) { this.vacancyId = vacancyId; }
    public String getPropertyId() { return propertyId; }
    public void setPropertyId(String propertyId) { this.propertyId = propertyId; }
    public String getOwnerId() { return ownerId; }
    public void setOwnerId(String ownerId) { this.ownerId = ownerId; }
    public String getAgentUserId() { return agentUserId; }
    public void setAgentUserId(String agentUserId) { this.agentUserId = agentUserId; }
    public String getProspectName() { return prospectName; }
    public void setProspectName(String prospectName) { this.prospectName = prospectName; }
    public String getProspectPhone() { return prospectPhone; }
    public void setProspectPhone(String prospectPhone) { this.prospectPhone = prospectPhone; }
    public String getProspectEmail() { return prospectEmail; }
    public void setProspectEmail(String prospectEmail) { this.prospectEmail = prospectEmail; }
    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }
    public LocalDateTime getSubmittedAt() { return submittedAt; }
    public void setSubmittedAt(LocalDateTime submittedAt) { this.submittedAt = submittedAt; }
    public String getOwnerDecision() { return ownerDecision; }
    public void setOwnerDecision(String ownerDecision) { this.ownerDecision = ownerDecision; }
    public LocalDateTime getDecidedAt() { return decidedAt; }
    public void setDecidedAt(LocalDateTime decidedAt) { this.decidedAt = decidedAt; }
    public String getDecidedBy() { return decidedBy; }
    public void setDecidedBy(String decidedBy) { this.decidedBy = decidedBy; }
    public String getRejectionReason() { return rejectionReason; }
    public void setRejectionReason(String rejectionReason) { this.rejectionReason = rejectionReason; }
    public LocalDateTime getLastReminderAt() { return lastReminderAt; }
    public void setLastReminderAt(LocalDateTime lastReminderAt) { this.lastReminderAt = lastReminderAt; }
}
