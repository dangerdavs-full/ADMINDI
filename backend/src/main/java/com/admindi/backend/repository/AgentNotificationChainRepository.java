package com.admindi.backend.repository;

import com.admindi.backend.model.AgentNotificationChainEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface AgentNotificationChainRepository extends JpaRepository<AgentNotificationChainEntity, String> {

    /** Última (más reciente) fila PENDING del recurso — representa la "cadena activa". */
    Optional<AgentNotificationChainEntity>
        findFirstByResourceTypeAndResourceIdAndDecisionOrderByPriorityOrderDesc(
            String resourceType, String resourceId, String decision);

    /** Todas las filas del recurso, ordenadas cronológicamente. */
    List<AgentNotificationChainEntity> findByResourceTypeAndResourceIdOrderByPriorityOrderAsc(
            String resourceType, String resourceId);

    /** Buzón del agente: todas las filas PENDING dirigidas a él. */
    List<AgentNotificationChainEntity> findByAgentUserIdAndDecisionOrderByNotifiedAtDesc(
            String agentUserId, String decision);

    /** Candidatas a timeout: PENDING con expires_at < now. */
    List<AgentNotificationChainEntity> findByDecisionAndExpiresAtBefore(String decision, LocalDateTime cutoff);
}
