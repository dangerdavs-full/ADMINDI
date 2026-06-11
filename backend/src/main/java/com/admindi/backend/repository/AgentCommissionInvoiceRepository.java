package com.admindi.backend.repository;

import com.admindi.backend.model.AgentCommissionInvoiceEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface AgentCommissionInvoiceRepository extends JpaRepository<AgentCommissionInvoiceEntity, String> {

    List<AgentCommissionInvoiceEntity> findByOwnerIdOrderByCreatedAtDesc(String ownerId);

    List<AgentCommissionInvoiceEntity> findByAgentUserIdOrderByCreatedAtDesc(String agentUserId);

    Optional<AgentCommissionInvoiceEntity> findByLeaseId(String leaseId);

    List<AgentCommissionInvoiceEntity> findByOwnerIdAndStatus(String ownerId, String status);
}
