package com.admindi.backend.repository;

import com.admindi.backend.model.LeaseEntity;
import com.admindi.backend.model.LeaseStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface LeaseRepository extends JpaRepository<LeaseEntity, String> {
    List<LeaseEntity> findByOwnerId(String ownerId);
    List<LeaseEntity> findByTenantId(String tenantId);

    Optional<LeaseEntity> findFirstByOwnerIdAndTenant_IdAndProperty_IdAndStatus(
            String ownerId, String tenantUserId, String propertyId, LeaseStatus status);

    long countByOwnerIdAndProperty_IdAndStatus(String ownerId, String propertyId, LeaseStatus status);

    boolean existsByOwnerIdAndProperty_IdAndStatus(String ownerId, String propertyId, LeaseStatus status);

    /** V49 / Bloque 4: expediente del inmueble — contratos históricos y vigentes. */
    List<LeaseEntity> findByOwnerIdAndProperty_Id(String ownerId, String propertyId);
}
