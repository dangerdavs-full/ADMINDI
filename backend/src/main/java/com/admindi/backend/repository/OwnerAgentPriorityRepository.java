package com.admindi.backend.repository;

import com.admindi.backend.model.OwnerAgentPriorityEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Repository
public interface OwnerAgentPriorityRepository extends JpaRepository<OwnerAgentPriorityEntity, String> {

    /** Lista ordenada por prioridad ascendente (1 = primero). */
    List<OwnerAgentPriorityEntity> findByOwnerIdAndFlowTypeOrderByPriorityOrderAsc(String ownerId, String flowType);

    Optional<OwnerAgentPriorityEntity> findByOwnerIdAndFlowTypeAndAgentUserId(
            String ownerId, String flowType, String agentUserId);

    /** Borra la prioridad de un agente al desvincularlo del owner. */
    @Modifying
    @Transactional
    @Query("DELETE FROM OwnerAgentPriorityEntity p "
            + "WHERE p.ownerId = :ownerId AND p.agentUserId = :agentUserId")
    int deleteByOwnerAndAgent(String ownerId, String agentUserId);

    @Modifying
    @Transactional
    @Query("DELETE FROM OwnerAgentPriorityEntity p WHERE p.agentUserId = :agentUserId")
    int deleteByAgent(String agentUserId);
}
