package com.admindi.backend.repository;

import com.admindi.backend.model.MaintenanceQuoteEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface MaintenanceQuoteRepository extends JpaRepository<MaintenanceQuoteEntity, String> {
    List<MaintenanceQuoteEntity> findByTicketId(String ticketId);

    /**
     * V62 — Fallback usado por SecureFileController cuando el
     * file_upload_claim no trae consumed_resource_id (caso pre-V61).
     * Busca el quote cuyo PDF/imagen de evidencia fue subido con ese path.
     * Nota: evidence_file_id no tiene UNIQUE — teóricamente podría haber
     * duplicados si el proveedor reenvía la misma cotización; en la
     * práctica es 1-1 y el primero es suficiente para autorizar.
     */
    Optional<MaintenanceQuoteEntity> findFirstByEvidenceFileId(String evidenceFileId);
}
