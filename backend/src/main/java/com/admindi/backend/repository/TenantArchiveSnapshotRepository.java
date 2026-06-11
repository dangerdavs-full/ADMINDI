package com.admindi.backend.repository;

import com.admindi.backend.model.TenantArchiveSnapshotEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TenantArchiveSnapshotRepository extends JpaRepository<TenantArchiveSnapshotEntity, String> {
    List<TenantArchiveSnapshotEntity> findByOwnerIdOrderByArchivedAtDesc(String ownerId);
    List<TenantArchiveSnapshotEntity> findByPropertyIdOrderByArchivedAtDesc(String propertyId);
    List<TenantArchiveSnapshotEntity> findByTenantUserIdOrderByArchivedAtDesc(String tenantUserId);
    List<TenantArchiveSnapshotEntity> findByTenantProfileIdOrderByArchivedAtDesc(String tenantProfileId);
}
