package com.admindi.backend.model;

import jakarta.persistence.*;

import java.time.LocalDateTime;

/**
 * Registro idempotente de recordatorios de pago enviados (Fase 1 notificaciones).
 *
 * Unicidad por (invoice_id, days_before, channel, recipient_user_id) para que:
 *  - Un reinicio del scheduler en la ventana de ejecución no duplique mensajes.
 *  - Un fallo intermitente en un canal (WhatsApp) permita reintentar en el siguiente
 *    tick sin spammear a quienes ya recibieron (la check se hace antes de enviar).
 *
 * No se guarda contenido del mensaje aquí (está en NotificationEntity + audit_events).
 */
@Entity
@Table(
        name = "payment_reminders_sent",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_payment_reminder",
                columnNames = {"invoice_id", "days_before", "channel", "recipient_user_id"}
        )
)
public class PaymentReminderSentEntity {

    @Id
    @Column(length = 64)
    private String id;

    @Column(name = "invoice_id", nullable = false, length = 64)
    private String invoiceId;

    @Column(name = "days_before", nullable = false)
    private int daysBefore;

    @Column(name = "channel", nullable = false, length = 20)
    private String channel;

    @Column(name = "recipient_user_id", nullable = false, length = 64)
    private String recipientUserId;

    @Column(name = "sent_at", nullable = false)
    private LocalDateTime sentAt;

    public PaymentReminderSentEntity() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getInvoiceId() { return invoiceId; }
    public void setInvoiceId(String invoiceId) { this.invoiceId = invoiceId; }
    public int getDaysBefore() { return daysBefore; }
    public void setDaysBefore(int daysBefore) { this.daysBefore = daysBefore; }
    public String getChannel() { return channel; }
    public void setChannel(String channel) { this.channel = channel; }
    public String getRecipientUserId() { return recipientUserId; }
    public void setRecipientUserId(String recipientUserId) { this.recipientUserId = recipientUserId; }
    public LocalDateTime getSentAt() { return sentAt; }
    public void setSentAt(LocalDateTime sentAt) { this.sentAt = sentAt; }
}
