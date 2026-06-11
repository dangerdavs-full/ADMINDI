package com.admindi.backend.model;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "commercial_activities")
public class CommercialActivityEntity {

    @Id
    private String id = UUID.randomUUID().toString();

    @Column(name = "vacancy_id", nullable = false)
    private String vacancyId;

    @Column(name = "agent_user_id")
    private String agentUserId;

    @Column(name = "activity_type", nullable = false, length = 30)
    private String activityType; // VISIT, PHOTOS, OBSERVATION

    @Column(columnDefinition = "TEXT")
    private String notes;

    @Column(name = "commission_amount")
    private BigDecimal commissionAmount;

    @Column(name = "commission_status", length = 20)
    private String commissionStatus; // PENDING, APPROVED, REJECTED, PAID

    @Column(name = "evidence_file_id")
    private String evidenceFileId;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    public CommercialActivityEntity() {}

    public String getId() { return id; } public void setId(String id) { this.id = id; }
    public String getVacancyId() { return vacancyId; } public void setVacancyId(String v) { this.vacancyId = v; }
    public String getAgentUserId() { return agentUserId; } public void setAgentUserId(String v) { this.agentUserId = v; }
    public String getActivityType() { return activityType; } public void setActivityType(String v) { this.activityType = v; }
    public String getNotes() { return notes; } public void setNotes(String v) { this.notes = v; }
    public BigDecimal getCommissionAmount() { return commissionAmount; } public void setCommissionAmount(BigDecimal v) { this.commissionAmount = v; }
    public String getCommissionStatus() { return commissionStatus; } public void setCommissionStatus(String v) { this.commissionStatus = v; }
    public String getEvidenceFileId() { return evidenceFileId; } public void setEvidenceFileId(String v) { this.evidenceFileId = v; }
    public LocalDateTime getCreatedAt() { return createdAt; } public void setCreatedAt(LocalDateTime v) { this.createdAt = v; }
}
