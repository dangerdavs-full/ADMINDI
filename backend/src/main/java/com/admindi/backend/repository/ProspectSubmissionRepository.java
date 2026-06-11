package com.admindi.backend.repository;

import com.admindi.backend.model.ProspectSubmissionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface ProspectSubmissionRepository extends JpaRepository<ProspectSubmissionEntity, String> {

    /** Propuesta activa (PENDING o ACCEPTED sin firma) en una vacancia. */
    Optional<ProspectSubmissionEntity>
        findFirstByVacancyIdAndOwnerDecisionOrderBySubmittedAtDesc(String vacancyId, String ownerDecision);

    List<ProspectSubmissionEntity> findByVacancyIdOrderBySubmittedAtDesc(String vacancyId);

    /** Bandeja del agente: sus propuestas, cualquier estado, ordenadas recientes primero. */
    List<ProspectSubmissionEntity> findByAgentUserIdOrderBySubmittedAtDesc(String agentUserId);

    /** Bandeja del dueño: todas las PENDING en sus vacancias. */
    List<ProspectSubmissionEntity> findByOwnerIdAndOwnerDecisionOrderBySubmittedAtDesc(
            String ownerId, String ownerDecision);

    /** Para scheduler de recordatorios 24h. */
    List<ProspectSubmissionEntity> findByOwnerDecisionAndSubmittedAtBefore(
            String ownerDecision, LocalDateTime cutoff);
}
