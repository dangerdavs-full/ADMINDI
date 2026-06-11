package com.admindi.backend.repository;

import com.admindi.backend.model.VacancyEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface VacancyRepository extends JpaRepository<VacancyEntity, String> {
    List<VacancyEntity> findByOwnerId(String ownerId);
    List<VacancyEntity> findByPropertyId(String propertyId);
    List<VacancyEntity> findByOwnerIdAndStatus(String ownerId, String status);

    Optional<VacancyEntity> findFirstByPropertyIdAndClosedAtIsNullOrderByOpenedAtDesc(String propertyId);

    List<VacancyEntity> findByAssignedAgentIdAndClosedAtIsNullOrderByOpenedAtDesc(String assignedAgentId);
}
