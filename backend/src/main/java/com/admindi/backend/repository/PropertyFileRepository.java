package com.admindi.backend.repository;

import com.admindi.backend.model.PropertyFileEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface PropertyFileRepository extends JpaRepository<PropertyFileEntity, String> {
    List<PropertyFileEntity> findByPropertyId(String propertyId);
    List<PropertyFileEntity> findByPropertyIdAndCategory(String propertyId, String category);

    /** V57 — Idempotencia para el archivado automático de comprobantes. */
    boolean existsByFilePath(String filePath);
}
