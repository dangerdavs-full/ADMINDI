package com.admindi.backend.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Orden en el que un owner quiere que sean notificados sus agentes para un flujo
 * determinado (mantenimiento o captación de vacancia). Hay una fila por cada par
 * (owner, agente, flow_type).
 *
 * <p>El orden se materializa en {@link #priorityOrder} (1 = primero en la cadena).
 * El sistema usa esta tabla para construir la cadena de notificaciones uno-a-uno:
 * el agente con orden 1 recibe WhatsApp/email; si rechaza o agota las 72h de
 * timeout, se pasa al de orden 2, y así sucesivamente.
 *
 * <p>Separamos por {@link #flowType} porque el dueño puede tener preferencias
 * distintas: "para mantenimiento quiero a Juan primero, para captación quiero a
 * María primero". Mantenerlas en una sola tabla nos permite consultar rápido el
 * arreglo ordenado filtrando por (owner_id, flow_type).
 */
@Entity
@Table(name = "owner_agent_priorities")
public class OwnerAgentPriorityEntity {

    public static final String FLOW_MAINTENANCE = "MAINTENANCE";
    public static final String FLOW_VACANCY = "VACANCY";

    @Id
    @Column(name = "id", length = 64, nullable = false)
    private String id = UUID.randomUUID().toString();

    @Column(name = "owner_id", length = 64, nullable = false)
    private String ownerId;

    @Column(name = "flow_type", length = 16, nullable = false)
    private String flowType;

    @Column(name = "agent_user_id", length = 64, nullable = false)
    private String agentUserId;

    @Column(name = "priority_order", nullable = false)
    private Integer priorityOrder;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt = LocalDateTime.now();

    public OwnerAgentPriorityEntity() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getOwnerId() { return ownerId; }
    public void setOwnerId(String ownerId) { this.ownerId = ownerId; }
    public String getFlowType() { return flowType; }
    public void setFlowType(String flowType) { this.flowType = flowType; }
    public String getAgentUserId() { return agentUserId; }
    public void setAgentUserId(String agentUserId) { this.agentUserId = agentUserId; }
    public Integer getPriorityOrder() { return priorityOrder; }
    public void setPriorityOrder(Integer priorityOrder) { this.priorityOrder = priorityOrder; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
