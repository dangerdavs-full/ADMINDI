package com.admindi.backend.model;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Expediente: relación (tenant user + owner + property). Un mismo {@code user_id} puede tener varios expedientes
 * (mismo u otro dueño). Paso 3: archivo por {@code id} de este perfil — terminar lease, liberar inmueble, vacancia,
 * revocar membresía del dueño si era el último expediente con ese owner, cancelar factura abierta del periodo.
 */
@Entity
@Table(name = "tenant_profiles")
public class TenantProfileEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    // Relación lógica al usuario (Un usuario puede rentar múltiples lugares)
    @Column(name = "user_id", nullable = false)
    private String userId;

    @Column(name = "owner_id", nullable = false)
    private String ownerId;

    @Column(name = "property_id", nullable = true) // Puede estar registrado pero sin propiedad asignada aún
    private String propertyId;

    @Column(nullable = false)
    private BigDecimal rentAmount;

    @Column(nullable = false)
    private int paymentDay; // Ej. día 5 de cada mes

    // --- Módulo de Cobranza ---
    @Column(nullable = false, columnDefinition = "boolean default false")
    private boolean hasLateFee = false;

    @Column(nullable = true)
    private String lateFeeType; // "PERCENTAGE" o "FIXED_AMOUNT"

    @Column(nullable = true)
    private BigDecimal lateFeeValue;

    @Column(nullable = false, columnDefinition = "integer default 0")
    private int gracePeriodDays = 0; // Por defecto 0 días extra

    /** No nulo = expediente dado de baja (historial conservado). */
    @Column(name = "archived_at")
    private LocalDateTime archivedAt;

    public TenantProfileEntity() {}

    public TenantProfileEntity(String userId, String ownerId, String propertyId, BigDecimal rentAmount, int paymentDay) {
        this.userId = userId;
        this.ownerId = ownerId;
        this.propertyId = propertyId;
        this.rentAmount = rentAmount;
        this.paymentDay = paymentDay;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }

    public String getOwnerId() { return ownerId; }
    public void setOwnerId(String ownerId) { this.ownerId = ownerId; }

    public String getPropertyId() { return propertyId; }
    public void setPropertyId(String propertyId) { this.propertyId = propertyId; }

    public BigDecimal getRentAmount() { return rentAmount; }
    public void setRentAmount(BigDecimal rentAmount) { this.rentAmount = rentAmount; }

    public int getPaymentDay() { return paymentDay; }
    public void setPaymentDay(int paymentDay) { this.paymentDay = paymentDay; }

    public boolean isHasLateFee() { return hasLateFee; }
    public void setHasLateFee(boolean hasLateFee) { this.hasLateFee = hasLateFee; }

    public String getLateFeeType() { return lateFeeType; }
    public void setLateFeeType(String lateFeeType) { this.lateFeeType = lateFeeType; }

    public BigDecimal getLateFeeValue() { return lateFeeValue; }
    public void setLateFeeValue(BigDecimal lateFeeValue) { this.lateFeeValue = lateFeeValue; }

    public int getGracePeriodDays() { return gracePeriodDays; }
    public void setGracePeriodDays(int gracePeriodDays) { this.gracePeriodDays = gracePeriodDays; }

    public LocalDateTime getArchivedAt() { return archivedAt; }
    public void setArchivedAt(LocalDateTime archivedAt) { this.archivedAt = archivedAt; }
}
