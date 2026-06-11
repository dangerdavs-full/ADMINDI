package com.admindi.backend.repository;

import com.admindi.backend.model.TenantProfileEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface TenantProfileRepository extends JpaRepository<TenantProfileEntity, String> {
    // Aislar por Tenant
    List<TenantProfileEntity> findByOwnerId(String ownerId);
    
    // Obtener todos los Contratos/Perfiles de un Inquilino
    List<TenantProfileEntity> findByUserId(String userId);

    List<TenantProfileEntity> findByUserIdAndOwnerId(String userId, String ownerId);

    List<TenantProfileEntity> findByUserIdAndOwnerIdAndArchivedAtIsNull(String userId, String ownerId);

    long countByUserIdAndArchivedAtIsNull(String userId);

    long countByUserIdAndOwnerIdAndArchivedAtIsNull(String userId, String ownerId);

    boolean existsByUserIdAndOwnerId(String userId, String ownerId);

    boolean existsByUserIdAndOwnerIdAndPropertyId(String userId, String ownerId, String propertyId);

    boolean existsByUserIdAndOwnerIdAndPropertyIdAndArchivedAtIsNull(String userId, String ownerId, String propertyId);
    
    // Validar acceso seguro
    Optional<TenantProfileEntity> findByIdAndUserId(String id, String userId);

    boolean existsByUserIdAndOwnerIdAndArchivedAtIsNull(String userId, String ownerId);

    long countByOwnerIdAndPropertyIdAndArchivedAtIsNull(String ownerId, String propertyId);

    List<TenantProfileEntity> findByUserIdAndArchivedAtIsNull(String userId);

    /** V49 / Bloque 4: expediente del inmueble — lista de perfiles activos. */
    List<TenantProfileEntity> findByOwnerIdAndPropertyIdAndArchivedAtIsNull(String ownerId, String propertyId);

    /**
     * Perf — cron de facturación (LedgerService): solo expedientes vigentes.
     * Reemplaza findAll() + filtro en memoria en el tick nocturno.
     */
    List<TenantProfileEntity> findByArchivedAtIsNull();
}
