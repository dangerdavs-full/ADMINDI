package com.admindi.backend.model;

import jakarta.persistence.*;

import java.time.LocalDateTime;

/**
 * Fila por cada recordatorio de pago disparado manualmente desde el perfil del inquilino
 * (botón "Enviar recordatorio de pago ahora" en el portal del dueño/admin).
 *
 * <p>Tabla creada en {@code V42__manual_payment_reminders.sql}.
 *
 * <h3>Por qué no reusar {@code payment_reminders_sent} (V41)</h3>
 * Esa tabla lleva unique constraint fuerte por (invoice, days_before, channel, recipient)
 * — pensada para idempotencia del scheduler. No permite 2 envíos manuales al mismo tenant
 * el mismo día. Separar tablas deja la semántica limpia y evita workarounds con días
 * negativos o canales artificiales.
 *
 * <h3>Rate limit</h3>
 * El control "máximo 2 envíos manuales por inquilino por 24 horas" se calcula en Java
 * consultando {@code countByTenantUserIdAndSentAtAfter}.
 *
 * <h3>Trazabilidad</h3>
 * {@code actorUserId} identifica quién disparó el envío (dueño o staff). Combinado con
 * {@code audit_events} permite reconstruir "quién presionó el botón, cuándo, sobre qué
 * inquilino, por qué factura".
 */
@Entity
@Table(name = "manual_payment_reminders_sent")
public class ManualPaymentReminderSentEntity {

    @Id
    @Column(length = 64)
    private String id;

    @Column(name = "invoice_id", nullable = false, length = 64)
    private String invoiceId;

    @Column(name = "tenant_user_id", nullable = false, length = 64)
    private String tenantUserId;

    @Column(name = "actor_user_id", nullable = false, length = 64)
    private String actorUserId;

    @Column(name = "owner_id", nullable = false, length = 64)
    private String ownerId;

    @Column(name = "sent_at", nullable = false)
    private LocalDateTime sentAt;

    public ManualPaymentReminderSentEntity() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getInvoiceId() { return invoiceId; }
    public void setInvoiceId(String invoiceId) { this.invoiceId = invoiceId; }
    public String getTenantUserId() { return tenantUserId; }
    public void setTenantUserId(String tenantUserId) { this.tenantUserId = tenantUserId; }
    public String getActorUserId() { return actorUserId; }
    public void setActorUserId(String actorUserId) { this.actorUserId = actorUserId; }
    public String getOwnerId() { return ownerId; }
    public void setOwnerId(String ownerId) { this.ownerId = ownerId; }
    public LocalDateTime getSentAt() { return sentAt; }
    public void setSentAt(LocalDateTime sentAt) { this.sentAt = sentAt; }
}
