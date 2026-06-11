package com.admindi.backend.repository;

import com.admindi.backend.model.ExpenseEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ExpenseRepository extends JpaRepository<ExpenseEntity, String> {
    List<ExpenseEntity> findByOwnerId(String ownerId);
    List<ExpenseEntity> findByPropertyId(String propertyId);
    List<ExpenseEntity> findByOwnerIdAndStatus(String ownerId, String status);

    boolean existsByLinkedResourceTypeAndLinkedResourceId(String linkedResourceType, String linkedResourceId);

    Optional<ExpenseEntity> findFirstByLinkedResourceTypeAndLinkedResourceIdOrderByCreatedAtDesc(
            String linkedResourceType, String linkedResourceId);

    /**
     * V65 — busca expenses que referencian un archivo en cualquiera de las 3
     * columnas de path ({@code evidence_file_id}, {@code budget_file_id},
     * {@code payment_proof_file_id}). Se usa en el fallback del
     * {@link com.admindi.backend.controller.SecureFileController} para
     * descubrir el expense dueño cuando el claim está pre-V61 y no tiene
     * {@code consumed_resource_type} estampado.
     */
    @Query("SELECT e FROM ExpenseEntity e WHERE e.evidenceFileId = :filePath "
            + "OR e.budgetFileId = :filePath OR e.paymentProofFileId = :filePath")
    List<ExpenseEntity> findByAnyFileReference(@Param("filePath") String filePath);
}
