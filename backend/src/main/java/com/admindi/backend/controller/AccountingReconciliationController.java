package com.admindi.backend.controller;

import com.admindi.backend.model.Role;
import com.admindi.backend.model.UserEntity;
import com.admindi.backend.repository.UserRepository;
import com.admindi.backend.service.AccountingReconciliationService;
import com.admindi.backend.service.AccountingReconciliationService.ReconciliationReport;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

/**
 * Herramienta de sanidad de datos: el dueño (o su PROPERTY_ADMIN) dispara una reconciliación
 * idempotente que recalcula la ocupación real de cada inmueble y anula facturas fantasma
 * ligadas a expedientes archivados. Útil cuando el panel contable muestra residuos heredados
 * de versiones previas del cron o de bajas parciales.
 *
 * V52 — SUPER_ADMIN queda fuera de este endpoint por regla de aislamiento de planos.
 */
@RestController
@RequestMapping("/api/accounting")
public class AccountingReconciliationController {

    private final AccountingReconciliationService reconciliationService;
    private final UserRepository userRepository;

    public AccountingReconciliationController(AccountingReconciliationService reconciliationService,
                                              UserRepository userRepository) {
        this.reconciliationService = reconciliationService;
        this.userRepository = userRepository;
    }

    @PostMapping("/reconcile")
    @PreAuthorize("hasAnyRole('OWNER','PROPERTY_ADMIN')")
    public ResponseEntity<ReconciliationReport> reconcile(
            @RequestParam(required = false) String ownerId) {
        String orgId = resolveOrgId(ownerId);
        return ResponseEntity.ok(reconciliationService.reconcileOwner(orgId));
    }

    private String resolveOrgId(String requestedOwnerId) {
        String email = SecurityContextHolder.getContext().getAuthentication().getName();
        UserEntity u = userRepository.findByLoginIdentifier(email)
                .orElseThrow(() -> new RuntimeException("Usuario no encontrado."));
        if (u.getRole() == Role.OWNER) {
            return u.getId();
        }
        if (u.getRole() == Role.PROPERTY_ADMIN && u.getOwnerId() != null) {
            return u.getOwnerId();
        }
        throw new RuntimeException("Sin contexto de organización para reconciliar.");
    }
}
