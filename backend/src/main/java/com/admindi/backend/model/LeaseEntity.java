package com.admindi.backend.model;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "leases")
public class LeaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(name = "owner_id", nullable = false)
    private String ownerId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "property_id", nullable = false)
    private PropertyEntity property;

    /** Legado: opcional cuando el contrato cuelga solo del inmueble (Paso 1). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "unit_id")
    private UnitEntity unit;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tenant_id", nullable = false)
    private UserEntity tenant;

    @Column(nullable = false)
    private LocalDate startDate;

    @Column(nullable = false)
    private LocalDate endDate;

    @Column(nullable = false)
    private BigDecimal monthlyRent;

    private BigDecimal depositAmount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private LeaseStatus status;

    @Column(name = "payment_day", nullable = false)
    private int paymentDay; // Ej. día 5 de cada mes

    @Column(name = "document_url")
    private String documentUrl;

    @Column(name = "document_file_name")
    private String documentFileName;

    @Column(name = "document_content_type")
    private String documentContentType;

    public String getId() { return id; }
    public String getOwnerId() { return ownerId; }
    public void setOwnerId(String ownerId) { this.ownerId = ownerId; }

    public PropertyEntity getProperty() { return property; }
    public void setProperty(PropertyEntity property) { this.property = property; }

    /**
     * Pivote de dominio: {@code property} directa; si falta, deriva de {@code unit} (filas legadas).
     */
    public PropertyEntity resolvePropertyEntity() {
        if (property != null) {
            return property;
        }
        if (unit != null) {
            return unit.getProperty();
        }
        return null;
    }

    public UnitEntity getUnit() { return unit; }
    public void setUnit(UnitEntity unit) { this.unit = unit; }
    public UserEntity getTenant() { return tenant; }
    public void setTenant(UserEntity tenant) { this.tenant = tenant; }
    public LocalDate getStartDate() { return startDate; }
    public void setStartDate(LocalDate startDate) { this.startDate = startDate; }
    public LocalDate getEndDate() { return endDate; }
    public void setEndDate(LocalDate endDate) { this.endDate = endDate; }
    public BigDecimal getMonthlyRent() { return monthlyRent; }
    public void setMonthlyRent(BigDecimal monthlyRent) { this.monthlyRent = monthlyRent; }
    public BigDecimal getDepositAmount() { return depositAmount; }
    public void setDepositAmount(BigDecimal depositAmount) { this.depositAmount = depositAmount; }
    public LeaseStatus getStatus() { return status; }
    public void setStatus(LeaseStatus status) { this.status = status; }
    public int getPaymentDay() { return paymentDay; }
    public void setPaymentDay(int paymentDay) { this.paymentDay = paymentDay; }
    public String getDocumentUrl() { return documentUrl; }
    public void setDocumentUrl(String documentUrl) { this.documentUrl = documentUrl; }
    public String getDocumentFileName() { return documentFileName; }
    public void setDocumentFileName(String documentFileName) { this.documentFileName = documentFileName; }
    public String getDocumentContentType() { return documentContentType; }
    public void setDocumentContentType(String documentContentType) { this.documentContentType = documentContentType; }
}
