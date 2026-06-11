package com.admindi.backend.repository;

import com.admindi.backend.model.PropertyMovementEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface PropertyMovementRepository extends JpaRepository<PropertyMovementEntity, String> {
    List<PropertyMovementEntity> findByPropertyIdOrderByOccurredAtDesc(String propertyId);
    List<PropertyMovementEntity> findByOwnerIdOrderByOccurredAtDesc(String ownerId);
    List<PropertyMovementEntity> findByPropertyIdAndEventTypeOrderByOccurredAtDesc(String propertyId, String eventType);
}
