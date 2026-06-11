package com.admindi.backend.repository;

import com.admindi.backend.model.MaintenanceTicketEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;

public interface MaintenanceTicketRepository extends JpaRepository<MaintenanceTicketEntity, String> {
    List<MaintenanceTicketEntity> findByOwnerId(String ownerId);
    List<MaintenanceTicketEntity> findByPropertyId(String propertyId);
    List<MaintenanceTicketEntity> findByOwnerIdAndStatus(String ownerId, String status);

    List<MaintenanceTicketEntity> findByAssignedProviderIdOrderByCreatedAtDesc(String assignedProviderId);

    List<MaintenanceTicketEntity> findByOwnerIdOrderByCreatedAtDesc(String ownerId);

    List<MaintenanceTicketEntity> findByOwnerIdAndPropertyIdOrderByCreatedAtDesc(String ownerId, String propertyId);

    /**
     * Tickets abiertos por un inquilino (vía su tenant_profile). Usado por el
     * panel del inquilino para listar su propio histórico de reportes.
     */
    List<MaintenanceTicketEntity> findByTenantProfileIdOrderByCreatedAtDesc(String tenantProfileId);

    /**
     * V62 — Fallback usado por SecureFileController: localiza tickets cuyo
     * {@code photo_file_ids_json} (jsonb array de paths) contiene el path
     * indicado. Implementado con operador nativo {@code @>} sobre JSONB +
     * {@code jsonb_build_array} para envolver el string.
     *
     * <p>Normalmente un path de foto pertenece a UN solo ticket, pero
     * devolvemos lista por defensa. El caller toma el primer elemento.</p>
     */
    @Query(value = "SELECT * FROM maintenance_tickets "
            + "WHERE photo_file_ids IS NOT NULL "
            + "AND photo_file_ids @> jsonb_build_array(CAST(:path AS text)) "
            + "LIMIT 2",
           nativeQuery = true)
    List<MaintenanceTicketEntity> findByPhotoFilePath(@Param("path") String path);
}
