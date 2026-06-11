package com.admindi.backend.controller;

import com.admindi.backend.model.TenantArchiveSnapshotEntity;
import com.admindi.backend.repository.TenantArchiveSnapshotRepository;
import com.admindi.backend.repository.UserRepository;
import com.admindi.backend.security.TenantContext;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Lectura de snapshots de expedientes archivados.
 * Alcance:
 *  * Por inmueble: usado en la pestaña/expediente del inmueble (historial de inquilinos).
 *  * Por owner: usado en vistas contables.
 *
 * Aislamiento: OWNER / PROPERTY_ADMIN / ACCOUNTANT solo ven los del owner de su contexto.
 *
 * V52 — SUPER_ADMIN queda fuera. El archivo trimestral global (NotificationArchivePanel)
 * cubre la visión de plataforma; esta vista es operativa y pertenece al dueño.
 */
@RestController
@RequestMapping("/api/tenant-archive-snapshots")
public class TenantArchiveSnapshotController {

    private final TenantArchiveSnapshotRepository repo;
    private final UserRepository userRepository;

    public TenantArchiveSnapshotController(TenantArchiveSnapshotRepository repo, UserRepository userRepository) {
        this.repo = repo;
        this.userRepository = userRepository;
    }

    @GetMapping("/by-property/{propertyId}")
    @PreAuthorize("hasAnyRole('OWNER','PROPERTY_ADMIN','ACCOUNTANT')")
    public ResponseEntity<List<TenantArchiveSnapshotEntity>> byProperty(@PathVariable String propertyId) {
        List<TenantArchiveSnapshotEntity> all = repo.findByPropertyIdOrderByArchivedAtDesc(propertyId);
        return ResponseEntity.ok(filterByScope(all));
    }

    @GetMapping("/mine")
    @PreAuthorize("hasAnyRole('OWNER','PROPERTY_ADMIN','ACCOUNTANT')")
    public ResponseEntity<List<TenantArchiveSnapshotEntity>> mine() {
        String ownerId = TenantContext.resolveOwnerId(userRepository);
        return ResponseEntity.ok(repo.findByOwnerIdOrderByArchivedAtDesc(ownerId));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('OWNER','PROPERTY_ADMIN','ACCOUNTANT')")
    public ResponseEntity<TenantArchiveSnapshotEntity> get(@PathVariable String id) {
        TenantArchiveSnapshotEntity s = repo.findById(id).orElseThrow(() -> new RuntimeException("No encontrado."));
        String ownerId = TenantContext.resolveOwnerId(userRepository);
        if (!ownerId.equals(s.getOwnerId())) {
            throw new RuntimeException("No autorizado.");
        }
        return ResponseEntity.ok(s);
    }

    private List<TenantArchiveSnapshotEntity> filterByScope(List<TenantArchiveSnapshotEntity> all) {
        String ownerId = TenantContext.resolveOwnerId(userRepository);
        return all.stream().filter(s -> ownerId.equals(s.getOwnerId())).toList();
    }
}
