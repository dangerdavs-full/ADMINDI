package com.admindi.backend.repository;

import com.admindi.backend.model.PropertyEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface PropertyRepository extends JpaRepository<PropertyEntity, String> {
    // Multi-Tenancy Segura: Buscar Propiedades solo del Dueño
    List<PropertyEntity> findByOwnerId(String ownerId);
    List<PropertyEntity> findByOwnerIdAndActiveTrue(String ownerId);

    /** Semilla QA: nombre estable por dueño (JPA genera id UUID; no forzar id fijo). */
    List<PropertyEntity> findByOwnerIdAndName(String ownerId, String name);
}
