package com.admindi.backend.repository;

import com.admindi.backend.model.PlatformProviderAssignmentEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PlatformProviderAssignmentRepository extends JpaRepository<PlatformProviderAssignmentEntity, String> {
    List<PlatformProviderAssignmentEntity> findByOwnerIdAndActiveTrue(String ownerId);
    Optional<PlatformProviderAssignmentEntity> findByProviderIdAndOwnerId(String providerId, String ownerId);
    List<PlatformProviderAssignmentEntity> findByProviderIdAndActiveTrue(String providerId);
    List<PlatformProviderAssignmentEntity> findByOwnerId(String ownerId);
    List<PlatformProviderAssignmentEntity> findByProviderId(String providerId);
    @org.springframework.data.jpa.repository.Modifying
    void deleteByOwnerId(String ownerId);
    @org.springframework.data.jpa.repository.Modifying
    void deleteByProviderId(String providerId);
}
