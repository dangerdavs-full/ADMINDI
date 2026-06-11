package com.admindi.backend.repository;

import com.admindi.backend.model.AgentBankAccountEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface AgentBankAccountRepository extends JpaRepository<AgentBankAccountEntity, String> {

    Optional<AgentBankAccountEntity> findByAgentUserId(String agentUserId);
}
