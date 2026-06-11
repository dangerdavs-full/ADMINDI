package com.admindi.backend.model;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Snapshot inmutable del estado financiero del expediente en el momento del archivo.
 * Se conserva para poder responder desde el inmueble: "cuánto pagó, cuántos meses,
 * qué evidencias dejó, cuánto quedó a deber".
 */
@Entity
@Table(name = "tenant_archive_snapshots")
public class TenantArchiveSnapshotEntity {

    @Id
    @Column(length = 64)
    private String id;

    @Column(name = "owner_id", nullable = false, length = 64)
    private String ownerId;

    @Column(name = "property_id", length = 64)
    private String propertyId;

    @Column(name = "tenant_user_id", nullable = false, length = 64)
    private String tenantUserId;

    @Column(name = "tenant_profile_id", nullable = false, length = 64)
    private String tenantProfileId;

    @Column(name = "tenant_name")
    private String tenantName;

    @Column(name = "tenant_email")
    private String tenantEmail;

    @Column(name = "months_paid_count", nullable = false)
    private int monthsPaidCount;

    @Column(name = "months_with_debt_count", nullable = false)
    private int monthsWithDebtCount;

    @Column(name = "total_paid_amount", nullable = false, precision = 14, scale = 2)
    private BigDecimal totalPaidAmount = BigDecimal.ZERO;

    @Column(name = "total_owed_amount", nullable = false, precision = 14, scale = 2)
    private BigDecimal totalOwedAmount = BigDecimal.ZERO;

    @Column(name = "applied_late_fee_total", nullable = false, precision = 14, scale = 2)
    private BigDecimal appliedLateFeeTotal = BigDecimal.ZERO;

    @Column(name = "active_agreements_count", nullable = false)
    private int activeAgreementsCount;

    @Column(name = "evidences_count", nullable = false)
    private int evidencesCount;

    /** JSON con desglose por mes y lista de file_url de evidencias SPEI/CEP. */
    @Column(name = "payload_json", columnDefinition = "TEXT")
    private String payloadJson;

    @Column(name = "archived_by_user_id", length = 64)
    private String archivedByUserId;

    @Column(name = "archived_by_role", length = 32)
    private String archivedByRole;

    @Column(name = "archived_at", nullable = false)
    private LocalDateTime archivedAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getOwnerId() { return ownerId; }
    public void setOwnerId(String ownerId) { this.ownerId = ownerId; }
    public String getPropertyId() { return propertyId; }
    public void setPropertyId(String propertyId) { this.propertyId = propertyId; }
    public String getTenantUserId() { return tenantUserId; }
    public void setTenantUserId(String tenantUserId) { this.tenantUserId = tenantUserId; }
    public String getTenantProfileId() { return tenantProfileId; }
    public void setTenantProfileId(String tenantProfileId) { this.tenantProfileId = tenantProfileId; }
    public String getTenantName() { return tenantName; }
    public void setTenantName(String tenantName) { this.tenantName = tenantName; }
    public String getTenantEmail() { return tenantEmail; }
    public void setTenantEmail(String tenantEmail) { this.tenantEmail = tenantEmail; }
    public int getMonthsPaidCount() { return monthsPaidCount; }
    public void setMonthsPaidCount(int monthsPaidCount) { this.monthsPaidCount = monthsPaidCount; }
    public int getMonthsWithDebtCount() { return monthsWithDebtCount; }
    public void setMonthsWithDebtCount(int monthsWithDebtCount) { this.monthsWithDebtCount = monthsWithDebtCount; }
    public BigDecimal getTotalPaidAmount() { return totalPaidAmount; }
    public void setTotalPaidAmount(BigDecimal totalPaidAmount) { this.totalPaidAmount = totalPaidAmount; }
    public BigDecimal getTotalOwedAmount() { return totalOwedAmount; }
    public void setTotalOwedAmount(BigDecimal totalOwedAmount) { this.totalOwedAmount = totalOwedAmount; }
    public BigDecimal getAppliedLateFeeTotal() { return appliedLateFeeTotal; }
    public void setAppliedLateFeeTotal(BigDecimal appliedLateFeeTotal) { this.appliedLateFeeTotal = appliedLateFeeTotal; }
    public int getActiveAgreementsCount() { return activeAgreementsCount; }
    public void setActiveAgreementsCount(int activeAgreementsCount) { this.activeAgreementsCount = activeAgreementsCount; }
    public int getEvidencesCount() { return evidencesCount; }
    public void setEvidencesCount(int evidencesCount) { this.evidencesCount = evidencesCount; }
    public String getPayloadJson() { return payloadJson; }
    public void setPayloadJson(String payloadJson) { this.payloadJson = payloadJson; }
    public String getArchivedByUserId() { return archivedByUserId; }
    public void setArchivedByUserId(String archivedByUserId) { this.archivedByUserId = archivedByUserId; }
    public String getArchivedByRole() { return archivedByRole; }
    public void setArchivedByRole(String archivedByRole) { this.archivedByRole = archivedByRole; }
    public LocalDateTime getArchivedAt() { return archivedAt; }
    public void setArchivedAt(LocalDateTime archivedAt) { this.archivedAt = archivedAt; }
}
