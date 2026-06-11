package com.admindi.backend.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Registro histórico de cada intento de notificar a un agente dentro de la cadena
 * de prioridades. Una fila por agente, por recurso (ticket o vacancia), por vuelta
 * de la cadena.
 *
 * <p>La fila activa es la que tiene {@link #decision} = PENDING: el scheduler revisa
 * {@link #expiresAt} para marcar {@code AUTO_REJECTED_TIMEOUT} y abrir la siguiente
 * fila con {@code priority_order + 1}.
 *
 * <p>Decisiones posibles:
 * <ul>
 *   <li>PENDING — aún esperando respuesta (default al insertar).</li>
 *   <li>ACCEPTED — el agente aceptó; el resto de la cadena se marca SUPERSEDED.</li>
 *   <li>REJECTED — el agente rechazó explícitamente; se abre siguiente eslabón.</li>
 *   <li>AUTO_REJECTED_TIMEOUT — expiró {@link #expiresAt} sin respuesta.</li>
 *   <li>SUPERSEDED — fue reemplazado por una aceptación posterior (no cuenta como
 *       rechazo real, solo cierra el ciclo de vida).</li>
 * </ul>
 */
@Entity
@Table(name = "agent_notification_chain")
public class AgentNotificationChainEntity {

    public static final String DECISION_PENDING = "PENDING";
    public static final String DECISION_ACCEPTED = "ACCEPTED";
    public static final String DECISION_REJECTED = "REJECTED";
    public static final String DECISION_AUTO_REJECTED = "AUTO_REJECTED_TIMEOUT";
    public static final String DECISION_SUPERSEDED = "SUPERSEDED";

    public static final String FLOW_MAINTENANCE = "MAINTENANCE";
    public static final String FLOW_VACANCY = "VACANCY";

    public static final String RESOURCE_MAINTENANCE_TICKET = "MAINTENANCE_TICKET";
    public static final String RESOURCE_VACANCY = "VACANCY";

    @Id
    @Column(name = "id", length = 64, nullable = false)
    private String id = UUID.randomUUID().toString();

    @Column(name = "flow_type", length = 16, nullable = false)
    private String flowType;

    @Column(name = "resource_type", length = 32, nullable = false)
    private String resourceType;

    @Column(name = "resource_id", length = 64, nullable = false)
    private String resourceId;

    @Column(name = "owner_id", length = 64, nullable = false)
    private String ownerId;

    @Column(name = "agent_user_id", length = 64, nullable = false)
    private String agentUserId;

    @Column(name = "priority_order", nullable = false)
    private Integer priorityOrder;

    @Column(name = "notified_at", nullable = false)
    private LocalDateTime notifiedAt = LocalDateTime.now();

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Column(name = "decision", length = 24, nullable = false)
    private String decision = DECISION_PENDING;

    @Column(name = "responded_at")
    private LocalDateTime respondedAt;

    @Column(name = "reason", columnDefinition = "TEXT")
    private String reason;

    public AgentNotificationChainEntity() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getFlowType() { return flowType; }
    public void setFlowType(String flowType) { this.flowType = flowType; }
    public String getResourceType() { return resourceType; }
    public void setResourceType(String resourceType) { this.resourceType = resourceType; }
    public String getResourceId() { return resourceId; }
    public void setResourceId(String resourceId) { this.resourceId = resourceId; }
    public String getOwnerId() { return ownerId; }
    public void setOwnerId(String ownerId) { this.ownerId = ownerId; }
    public String getAgentUserId() { return agentUserId; }
    public void setAgentUserId(String agentUserId) { this.agentUserId = agentUserId; }
    public Integer getPriorityOrder() { return priorityOrder; }
    public void setPriorityOrder(Integer priorityOrder) { this.priorityOrder = priorityOrder; }
    public LocalDateTime getNotifiedAt() { return notifiedAt; }
    public void setNotifiedAt(LocalDateTime notifiedAt) { this.notifiedAt = notifiedAt; }
    public LocalDateTime getExpiresAt() { return expiresAt; }
    public void setExpiresAt(LocalDateTime expiresAt) { this.expiresAt = expiresAt; }
    public String getDecision() { return decision; }
    public void setDecision(String decision) { this.decision = decision; }
    public LocalDateTime getRespondedAt() { return respondedAt; }
    public void setRespondedAt(LocalDateTime respondedAt) { this.respondedAt = respondedAt; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
}
