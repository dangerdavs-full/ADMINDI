package com.admindi.backend.dto;

import java.time.LocalDateTime;

/**
 * Una fila del historial de notificaciones enviadas (Bloque C).
 *
 * <p>Parsea los {@code event_type} que dejan {@link com.admindi.backend.service.EmailService}
 * y {@link com.admindi.backend.service.TwilioWhatsAppService} en {@code audit_events} y los
 * expone en estructura limpia para UI.
 *
 * <p>Contrato de parseo reconstruido en {@code NotificationHistoryService}:
 * <ul>
 *   <li>WhatsApp → event_type={@code WHATSAPP_{SENT|FAILED|SKIPPED}_{tipo}}.</li>
 *   <li>Email    → event_type={@code MAIL_EMAIL_{SENT|FAILED}_{tipo}}.</li>
 * </ul>
 *
 * <p>{@code recipientEmail} y {@code recipientPhone} ya van enmascarados — la UI NUNCA recibe
 * el dato crudo para minimizar superficie de privacidad al exportar CSV.
 */
public class NotificationHistoryEntryDTO {

    private String id;                 // audit_events.id (pk; usado para retry endpoint).
    private LocalDateTime timestamp;   // timestamp crudo (UI decide si mostrar hora o solo fecha).
    private String channel;            // "EMAIL" | "WHATSAPP"
    private String outcome;            // "SENT" | "FAILED" | "SKIPPED"
    private String eventType;          // evento original, p.ej. "MANUAL_PAYMENT_REMINDER"
    private String recipientUserId;    // UserEntity.id del destinatario (para lookups secundarios)
    private String recipientName;      // nombre del destinatario para visualización
    private String recipientEmail;     // email enmascarado ("ar***@gmail.com") o null
    private String recipientPhone;     // teléfono enmascarado ("+521***952") o null
    private String actorEmail;         // quien dispar\u00f3 (email) o "SYSTEM" si fue cron
    private String detail;             // texto libre del campo new_values.detail (razón de skip/fail)

    public NotificationHistoryEntryDTO() {}

    public NotificationHistoryEntryDTO(String id, LocalDateTime timestamp, String channel,
                                       String outcome, String eventType, String recipientUserId,
                                       String recipientName, String recipientEmail,
                                       String recipientPhone, String actorEmail, String detail) {
        this.id = id;
        this.timestamp = timestamp;
        this.channel = channel;
        this.outcome = outcome;
        this.eventType = eventType;
        this.recipientUserId = recipientUserId;
        this.recipientName = recipientName;
        this.recipientEmail = recipientEmail;
        this.recipientPhone = recipientPhone;
        this.actorEmail = actorEmail;
        this.detail = detail;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public LocalDateTime getTimestamp() { return timestamp; }
    public void setTimestamp(LocalDateTime timestamp) { this.timestamp = timestamp; }
    public String getChannel() { return channel; }
    public void setChannel(String channel) { this.channel = channel; }
    public String getOutcome() { return outcome; }
    public void setOutcome(String outcome) { this.outcome = outcome; }
    public String getEventType() { return eventType; }
    public void setEventType(String eventType) { this.eventType = eventType; }
    public String getRecipientUserId() { return recipientUserId; }
    public void setRecipientUserId(String recipientUserId) { this.recipientUserId = recipientUserId; }
    public String getRecipientName() { return recipientName; }
    public void setRecipientName(String recipientName) { this.recipientName = recipientName; }
    public String getRecipientEmail() { return recipientEmail; }
    public void setRecipientEmail(String recipientEmail) { this.recipientEmail = recipientEmail; }
    public String getRecipientPhone() { return recipientPhone; }
    public void setRecipientPhone(String recipientPhone) { this.recipientPhone = recipientPhone; }
    public String getActorEmail() { return actorEmail; }
    public void setActorEmail(String actorEmail) { this.actorEmail = actorEmail; }
    public String getDetail() { return detail; }
    public void setDetail(String detail) { this.detail = detail; }
}
