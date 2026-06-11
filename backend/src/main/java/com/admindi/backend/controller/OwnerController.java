package com.admindi.backend.controller;

import com.admindi.backend.dto.OwnerDTO;
import com.admindi.backend.model.Role;
import com.admindi.backend.model.UserEntity;
import com.admindi.backend.repository.UserRepository;
import com.admindi.backend.service.MaintenanceProviderService;
import com.admindi.backend.service.OwnerCascadeDeletionService;
import com.admindi.backend.service.OwnerService;
import com.admindi.backend.service.ReauthService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

@RestController
public class OwnerController {

    private final OwnerService ownerService;
    private final UserRepository userRepository;
    private final MaintenanceProviderService providerService;
    private final OwnerCascadeDeletionService cascadeDeletionService;
    private final ReauthService reauthService;

    @Autowired
    public OwnerController(OwnerService ownerService, UserRepository userRepository,
                           MaintenanceProviderService providerService,
                           OwnerCascadeDeletionService cascadeDeletionService,
                           ReauthService reauthService) {
        this.ownerService = ownerService;
        this.userRepository = userRepository;
        this.providerService = providerService;
        this.cascadeDeletionService = cascadeDeletionService;
        this.reauthService = reauthService;
    }

    // ---- SUPER_ADMIN endpoints (admin/owners) ----

    @GetMapping("/api/admin/owners")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ResponseEntity<List<OwnerDTO>> getOwners() {
        return ResponseEntity.ok(ownerService.getAllOwners());
    }

    @PostMapping("/api/admin/owners")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ResponseEntity<?> createOwner(@RequestBody OwnerDTO request) {
        // V54 — el email ya no es único, así que la antigua colisión con
        // "residuo soft-deleted por email" desapareció. Una colisión de
        // username residual sigue existiendo (ese sí es único) y se maneja
        // en el propio servicio vía UsernameTakenException.
        return ResponseEntity.ok(ownerService.createOwner(request));
    }

    /**
     * GET /api/admin/owners/residual
     *
     * Lista los dueños en estado soft-deleted (active=false o deleted_at != null)
     * que aún tienen datos residuales en la base. El listado normal de
     * {@code GET /api/admin/owners} los filtra, por eso eran invisibles y bloqueaban
     * emails sin ofrecer al SUPERADMIN visibilidad para resolverlo.
     *
     * La purga se hace con el endpoint existente
     * {@code DELETE /api/admin/owners/{id}} que exige password + MFA + motivo.
     * No se expone una ruta de "purge silencioso" desde esta lista.
     */
    @GetMapping("/api/admin/owners/residual")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ResponseEntity<List<Map<String, Object>>> getResidualOwners() {
        List<Map<String, Object>> out = userRepository.findAll().stream()
                .filter(u -> u.getRole() == Role.OWNER)
                .filter(u -> !u.isActive() || u.getDeletedAt() != null)
                .map(u -> {
                    Map<String, Object> m = new java.util.HashMap<>();
                    m.put("id", u.getId());
                    m.put("name", u.getName());
                    // V54 — username es el único identificador único (V48).
                    // contactEmail es el único correo del user (NO único, puede
                    // ser null en residuos que fueron tombstoneados con wipe de PII).
                    m.put("username", u.getLoginUsername());
                    m.put("contactEmail", u.getContactEmail());
                    m.put("active", u.isActive());
                    m.put("deletedAt", u.getDeletedAt());
                    m.put("phone", u.getPhone());
                    return m;
                })
                .collect(java.util.stream.Collectors.toList());
        return ResponseEntity.ok(out);
    }

    @GetMapping("/api/admin/owners/{id}")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ResponseEntity<OwnerDTO> getOwner(@PathVariable String id) {
        return ResponseEntity.ok(ownerService.getOwner(id));
    }

    @PutMapping("/api/admin/owners/{id}")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ResponseEntity<OwnerDTO> updateOwner(@PathVariable String id, @RequestBody OwnerDTO request) {
        return ResponseEntity.ok(ownerService.updateOwnerContact(id, request));
    }

    /**
     * DELETE /api/admin/owners/{id}
     *
     * Borra por completo al dueño y todo su universo operativo. Requiere reauth
     * (password + MFA) del SUPER_ADMIN y un motivo auditable.
     *
     * Antes este endpoint sólo hacía soft-delete sin reauth. Eso era inseguro
     * (un token robado podía inhabilitar dueños sin MFA) y dejaba el email
     * bloqueado por el índice único. Desde esta versión delega en
     * `OwnerCascadeDeletionService.hardDeleteOwner`, que:
     *   · Exige reauth con MFA (ver bloque inicial).
     *   · Borra inmuebles, contratos, facturas, pagos, convenios, comprobantes,
     *     mantenimiento, egresos, archivos, expedientes, snapshots, memberships
     *     y notifications del dueño.
     *   · Elimina también a los usuarios que sólo tenían contexto con este dueño
     *     (los que tienen otro dueño se conservan y solo pierden acceso).
     *   · Libera el email para re-registrar.
     *
     * Body: { "password": "...", "mfaCode": "...", "reason": "..." }
     */
    @DeleteMapping("/api/admin/owners/{id}")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ResponseEntity<Map<String, Object>> deleteOwner(@PathVariable String id,
                                                           @RequestBody Map<String, String> body) {
        String password = body == null ? null : body.get("password");
        String mfaCode = body == null ? null : body.get("mfaCode");
        String reason = body == null ? null : body.get("reason");
        if (reason == null || reason.trim().length() < 10) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Se requiere un motivo de al menos 10 caracteres para eliminar un dueño.");
        }
        reauthService.verifyReauth(password, mfaCode, "OWNER_DELETE");

        UserEntity owner = userRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Dueño no encontrado."));
        if (owner.getRole() == null || !"OWNER".equals(owner.getRole().name())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "El usuario objetivo no es un dueño (role=" + owner.getRole() + ").");
        }

        OwnerCascadeDeletionService.CascadeResult c = cascadeDeletionService.hardDeleteOwner(id);
        Map<String, Object> resp = new java.util.HashMap<>();
        resp.put("ownerId", id);
        resp.put("hardDeleted", true);
        resp.put("reason", reason.trim());
        Map<String, Integer> counters = new java.util.HashMap<>();
        counters.put("properties", c.properties);
        counters.put("units", c.units);
        counters.put("leases", c.leases);
        counters.put("invoices", c.invoices);
        counters.put("payments", c.payments);
        counters.put("agreements", c.agreements);
        counters.put("transferProofs", c.transferProofs);
        counters.put("tickets", c.tickets);
        counters.put("budgets", c.budgets);
        counters.put("expenses", c.expenses);
        counters.put("vacancies", c.vacancies);
        counters.put("tenantProfiles", c.tenantProfiles);
        counters.put("archiveSnapshots", c.archiveSnapshots);
        counters.put("memberships", c.memberships);
        counters.put("relatedUsersDeleted", c.relatedUsersDeleted);
        counters.put("relatedUsersKept", c.relatedUsersKept);
        counters.put("filesDeleted", c.filesDeleted);
        counters.put("filesMissing", c.filesMissing);
        resp.put("cascade", counters);
        return ResponseEntity.ok(resp);
    }

    public static class PurgeOwnerRequest {
        public boolean confirmPurge;
        public String password;
        public String mfaCode;
        public String reason;
    }

    /**
     * POST /api/admin/owners/{id}/purge — endpoint legado.
     *
     * Se mantiene por compatibilidad con clientes antiguos pero hoy delega al
     * mismo cascade que DELETE /api/admin/owners/{id} para evitar dos rutas con
     * semánticas distintas.
     */
    @PostMapping("/api/admin/owners/{id}/purge")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ResponseEntity<java.util.Map<String, Object>> purgeOwner(
            @PathVariable String id,
            @RequestBody PurgeOwnerRequest body) {
        if (body == null || !body.confirmPurge) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Se requiere confirmPurge=true para ejecutar el purge total.");
        }
        String reason = body.reason != null && body.reason.trim().length() >= 10
                ? body.reason.trim()
                : "PURGE_OWNER legacy (sin motivo explícito)";
        Map<String, String> forward = new java.util.HashMap<>();
        forward.put("password", body.password);
        forward.put("mfaCode", body.mfaCode);
        forward.put("reason", reason);
        return deleteOwner(id, forward);
    }

    @GetMapping("/api/admin/owners/{id}/providers")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ResponseEntity<?> getOwnerProviders(@PathVariable String id) {
        return ResponseEntity.ok(providerService.getProvidersByOwner(id));
    }


    // ---- OWNER self-service: update own contact info ----

    @GetMapping("/api/owner/contact")
    @PreAuthorize("hasRole('OWNER')")
    public ResponseEntity<OwnerDTO> getMyContact() {
        UserEntity me = resolveCurrentUser();
        return ResponseEntity.ok(ownerService.getOwner(me.getId()));
    }

    @PutMapping("/api/owner/contact")
    @PreAuthorize("hasRole('OWNER')")
    public ResponseEntity<OwnerDTO> updateMyContact(@RequestBody OwnerDTO request) {
        UserEntity me = resolveCurrentUser();
        return ResponseEntity.ok(ownerService.updateOwnerContact(me.getId(), request));
    }

    @GetMapping("/api/owner/routing-preferences")
    @PreAuthorize("hasRole('OWNER')")
    public ResponseEntity<Map<String, String>> getRoutingPreferences() {
        UserEntity me = resolveCurrentUser();
        return ResponseEntity.ok(ownerService.getRoutingPreferencesForOwner(me.getId()));
    }

    @PutMapping("/api/owner/routing-preferences")
    @PreAuthorize("hasRole('OWNER')")
    public ResponseEntity<Map<String, String>> updateRoutingPreferences(@RequestBody Map<String, String> body) {
        UserEntity me = resolveCurrentUser();
        return ResponseEntity.ok(ownerService.updateRoutingPreferencesForOwner(
                me.getId(),
                body.get("maintenanceRoutingMode"),
                body.get("vacancyRoutingMode")));
    }

    private UserEntity resolveCurrentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return userRepository.findByLoginIdentifier(auth.getName())
                .orElseThrow(() -> new RuntimeException("User not found"));
    }
}
