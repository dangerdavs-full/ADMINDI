package com.admindi.backend.model;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;

/**
 * Estado efímero de la conversación del chatbot de WhatsApp.
 *
 * Una fila por número E.164 que haya iniciado conversación en los últimos
 * {@code whatsapp.session.ttl-minutes} (default 15). Al expirar, la siguiente
 * interacción vuelve a pedir NIP — decisión de seguridad para no mantener
 * sesiones largas abiertas contra un dispositivo potencialmente robado.
 */
@Entity
@Table(name = "whatsapp_conversation_state")
public class WhatsappConversationStateEntity {

    @Id
    @Column(name = "phone_e164")
    private String phoneE164;

    @Column(name = "user_id")
    private String userId;

    @Column(name = "current_state", nullable = false)
    private String currentState;

    /**
     * Contexto de la conversación en curso: descripción del ticket siendo creado,
     * datos OCR pendientes de confirmación, IDs de archivos subidos, etc.
     *
     * Almacenado como {@code jsonb} nativo de PostgreSQL para que el panel
     * superadmin pueda inspeccionarlo sin deserializar en Java. Hibernate 6
     * mapea String ↔ jsonb con {@link JdbcTypeCode}.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "context_json", columnDefinition = "jsonb")
    private String contextJson;

    @Column(name = "pending_proof_id")
    private String pendingProofId;

    @Column(name = "pending_ticket_id")
    private String pendingTicketId;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt = LocalDateTime.now();

    public WhatsappConversationStateEntity() {}

    public String getPhoneE164() { return phoneE164; }
    public void setPhoneE164(String phoneE164) { this.phoneE164 = phoneE164; }
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public String getCurrentState() { return currentState; }
    public void setCurrentState(String currentState) { this.currentState = currentState; }
    public String getContextJson() { return contextJson; }
    public void setContextJson(String contextJson) { this.contextJson = contextJson; }
    public String getPendingProofId() { return pendingProofId; }
    public void setPendingProofId(String pendingProofId) { this.pendingProofId = pendingProofId; }
    public String getPendingTicketId() { return pendingTicketId; }
    public void setPendingTicketId(String pendingTicketId) { this.pendingTicketId = pendingTicketId; }
    public LocalDateTime getExpiresAt() { return expiresAt; }
    public void setExpiresAt(LocalDateTime expiresAt) { this.expiresAt = expiresAt; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }

    @PreUpdate
    public void touch() {
        this.updatedAt = LocalDateTime.now();
    }
}
