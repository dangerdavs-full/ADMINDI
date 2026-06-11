package com.admindi.backend.repository;

import com.admindi.backend.model.UnitEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface UnitRepository extends JpaRepository<UnitEntity, String> {
    List<UnitEntity> findByOwnerId(String ownerId);
    List<UnitEntity> findByPropertyIdAndOwnerId(String propertyId, String ownerId);
    List<UnitEntity> findByPropertyId(String propertyId);
    int countByPropertyId(String propertyId);
}
