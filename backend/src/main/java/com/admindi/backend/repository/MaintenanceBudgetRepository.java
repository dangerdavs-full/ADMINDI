package com.admindi.backend.repository;

import com.admindi.backend.model.MaintenanceBudgetEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface MaintenanceBudgetRepository extends JpaRepository<MaintenanceBudgetEntity, String> {
    List<MaintenanceBudgetEntity> findByOwnerIdOrderBySubmittedAtDesc(String ownerId);
    List<MaintenanceBudgetEntity> findByPropertyIdOrderBySubmittedAtDesc(String propertyId);
    List<MaintenanceBudgetEntity> findByOwnerIdAndStatusOrderBySubmittedAtDesc(String ownerId, String status);
    List<MaintenanceBudgetEntity> findByProviderUserIdOrderBySubmittedAtDesc(String providerUserId);
}
