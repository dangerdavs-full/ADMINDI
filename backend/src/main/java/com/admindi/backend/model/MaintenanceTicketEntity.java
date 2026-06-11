package com.admindi.backend.model;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Ticket de mantenimiento abierto por un tenant.
 *
 * <p>Fase 2 agrega los siguientes campos:
 * <ul>
 *   <li>{@link #awaitingOwnerAuth} — gate explícito del dueño antes de despachar al
 *       provider (si {@code true}, el ticket queda en {@code AWAITING_OWNER_AUTH}
 *       hasta que el dueño apruebe y elija proveedor).</li>
 *   <li>{@link #ownerChosenProviderId} — cuando el dueño tiene provider privado y
 *       de plataforma vinculados, elige explícitamente cuál usar; se respeta en
 *       lugar del resolutor automático.</li>
 *   <li>{@link #platformDiscountPct} / {@link #platformDiscountAmount} — descuento
 *       del 15% que la plataforma aplica cuando el provider elegido es de tipo
 *       PLATFORM. Se congela al aprobar la cotización.</li>
 *   <li>{@link #photoFileIdsJson} — array JSON con IDs de fotos que el tenant subió
 *       al crear el ticket (desde chatbot o web).</li>
 * </ul>
 *
 * <p>El estado {@code AWAITING_OWNER_AUTH} es nuevo en Fase 2 y <strong>precede</strong>
 * a los estados históricos (OPEN → AWAITING_PROVIDER_ACCEPT → ...). Tickets creados
 * antes del roll-out tienen {@code awaitingOwnerAuth = false} y siguen la ruta antigua.
 */
@Entity
@Table(name = "maintenance_tickets")
public class MaintenanceTicketEntity {

    public static final String STATUS_AWAITING_OWNER_AUTH = "AWAITING_OWNER_AUTH";
    public static final String STATUS_AWAITING_PROVIDER_ACCEPT = "AWAITING_PROVIDER_ACCEPT";
    public static final String STATUS_ACCEPTED = "ACCEPTED";
    public static final String STATUS_QUOTED = "QUOTED";
    public static final String STATUS_APPROVED = "APPROVED";
    /**
     * V63 — estado intermedio tras que el dueño sube su comprobante SPEI pero
     * antes de que el proveedor confirme haber recibido el dinero. Si el
     * proveedor confirma → {@link #STATUS_COMPLETED}; si rechaza (disputa)
     * → vuelve a {@link #STATUS_APPROVED} para que el dueño vuelva a pagar.
     */
    public static final String STATUS_AWAITING_PROVIDER_CONFIRMATION = "AWAITING_PROVIDER_CONFIRM";
    public static final String STATUS_COMPLETED = "COMPLETED";
    public static final String STATUS_CANCELLED = "CANCELLED";
    public static final String STATUS_REJECTED_BY_OWNER = "REJECTED_BY_OWNER";

    @Id
    private String id = UUID.randomUUID().toString();

    @Column(name = "owner_id", nullable = false)
    private String ownerId;

    @Column(name = "property_id", nullable = false)
    private String propertyId;

    @Column(name = "tenant_profile_id")
    private String tenantProfileId;

    @Column(nullable = false)
    private String title;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(length = 20)
    private String urgency = "NORMAL"; // LOW, NORMAL, HIGH, CRITICAL

    @Column(nullable = false, length = 30)
    private String status = "OPEN";

    @Column(name = "assigned_provider_id")
    private String assignedProviderId;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "resolved_at")
    private LocalDateTime resolvedAt;

    @Column(name = "provider_accepted_at")
    private LocalDateTime providerAcceptedAt;

    @Column(name = "awaiting_owner_auth")
    private Boolean awaitingOwnerAuth;

    @Column(name = "authorized_at")
    private LocalDateTime authorizedAt;

    @Column(name = "authorized_by", length = 64)
    private String authorizedBy;

    @Column(name = "owner_chosen_provider_id", length = 64)
    private String ownerChosenProviderId;

    @Column(name = "rejection_reason", columnDefinition = "TEXT")
    private String rejectionReason;

    @Column(name = "platform_discount_pct", precision = 6, scale = 4)
    private BigDecimal platformDiscountPct;

    @Column(name = "platform_discount_amount", precision = 14, scale = 2)
    private BigDecimal platformDiscountAmount;

    @Column(name = "photo_file_ids", columnDefinition = "jsonb")
    private String photoFileIdsJson;

    public MaintenanceTicketEntity() {}

    public String getId() { return id; } public void setId(String id) { this.id = id; }
    public String getOwnerId() { return ownerId; } public void setOwnerId(String v) { this.ownerId = v; }
    public String getPropertyId() { return propertyId; } public void setPropertyId(String v) { this.propertyId = v; }
    public String getTenantProfileId() { return tenantProfileId; } public void setTenantProfileId(String v) { this.tenantProfileId = v; }
    public String getTitle() { return title; } public void setTitle(String v) { this.title = v; }
    public String getDescription() { return description; } public void setDescription(String v) { this.description = v; }
    public String getUrgency() { return urgency; } public void setUrgency(String v) { this.urgency = v; }
    public String getStatus() { return status; } public void setStatus(String v) { this.status = v; }
    public String getAssignedProviderId() { return assignedProviderId; } public void setAssignedProviderId(String v) { this.assignedProviderId = v; }
    public LocalDateTime getCreatedAt() { return createdAt; } public void setCreatedAt(LocalDateTime v) { this.createdAt = v; }
    public LocalDateTime getResolvedAt() { return resolvedAt; } public void setResolvedAt(LocalDateTime v) { this.resolvedAt = v; }
    public LocalDateTime getProviderAcceptedAt() { return providerAcceptedAt; }
    public void setProviderAcceptedAt(LocalDateTime v) { this.providerAcceptedAt = v; }
    public Boolean getAwaitingOwnerAuth() { return awaitingOwnerAuth; }
    public void setAwaitingOwnerAuth(Boolean awaitingOwnerAuth) { this.awaitingOwnerAuth = awaitingOwnerAuth; }
    public LocalDateTime getAuthorizedAt() { return authorizedAt; }
    public void setAuthorizedAt(LocalDateTime authorizedAt) { this.authorizedAt = authorizedAt; }
    public String getAuthorizedBy() { return authorizedBy; }
    public void setAuthorizedBy(String authorizedBy) { this.authorizedBy = authorizedBy; }
    public String getOwnerChosenProviderId() { return ownerChosenProviderId; }
    public void setOwnerChosenProviderId(String ownerChosenProviderId) { this.ownerChosenProviderId = ownerChosenProviderId; }
    public String getRejectionReason() { return rejectionReason; }
    public void setRejectionReason(String rejectionReason) { this.rejectionReason = rejectionReason; }
    public BigDecimal getPlatformDiscountPct() { return platformDiscountPct; }
    public void setPlatformDiscountPct(BigDecimal platformDiscountPct) { this.platformDiscountPct = platformDiscountPct; }
    public BigDecimal getPlatformDiscountAmount() { return platformDiscountAmount; }
    public void setPlatformDiscountAmount(BigDecimal platformDiscountAmount) { this.platformDiscountAmount = platformDiscountAmount; }
    public String getPhotoFileIdsJson() { return photoFileIdsJson; }
    public void setPhotoFileIdsJson(String photoFileIdsJson) { this.photoFileIdsJson = photoFileIdsJson; }
}
