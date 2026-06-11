package com.admindi.backend.controller;

import com.admindi.backend.dto.PermissionTemplateDTO;
import com.admindi.backend.model.PermissionGrantEntity;
import com.admindi.backend.model.Role;
import com.admindi.backend.model.UserEntity;
import com.admindi.backend.repository.UserRepository;
import com.admindi.backend.service.PermissionService;
import com.admindi.backend.service.ReauthService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Controlador de permisos. V52 — separación estricta de planos:
 *
 *  * Templates (plano PLATAFORMA): catálogo global de roles-plantilla que define
 *    qué authorities puede delegar un dueño a su staff. Administrado por SUPER_ADMIN.
 *    OWNER solo lee el catálogo para poder escoger qué plantilla asignar.
 *
 *  * Grants (plano OWNER): asignaciones per-owner de una plantilla a un usuario
 *    staff. SUPER_ADMIN NO participa: este endpoint es estrictamente del dueño.
 */
@RestController
@RequestMapping("/api/permissions")
public class PermissionController {

    private final PermissionService permissionService;
    private final UserRepository userRepository;
    private final ReauthService reauthService;

    @Autowired
    public PermissionController(PermissionService permissionService, UserRepository userRepository,
                                ReauthService reauthService) {
        this.permissionService = permissionService;
        this.userRepository = userRepository;
        this.reauthService = reauthService;
    }

    // ────────────────────────────────────────────────────────────────────────
    // Templates (catálogo GLOBAL — read: OWNER+SUPER_ADMIN, write: SUPER_ADMIN)
    // ────────────────────────────────────────────────────────────────────────

    @GetMapping("/templates")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','OWNER')")
    public ResponseEntity<List<PermissionTemplateDTO>> listTemplates() {
        return ResponseEntity.ok(permissionService.listTemplateDtos());
    }

    @GetMapping("/templates/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','OWNER')")
    public ResponseEntity<PermissionTemplateDTO> getTemplate(@PathVariable String id) {
        return ResponseEntity.ok(permissionService.getTemplateDto(id));
    }

    @PostMapping("/templates")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ResponseEntity<PermissionTemplateDTO> createTemplate(@RequestBody PermissionTemplateDTO template) {
        return ResponseEntity.ok(permissionService.createTemplate(template));
    }

    @PutMapping("/templates/{id}")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ResponseEntity<PermissionTemplateDTO> updateTemplate(@PathVariable String id, @RequestBody PermissionTemplateDTO template) {
        return ResponseEntity.ok(permissionService.updateTemplate(id, template));
    }

    @DeleteMapping("/templates/{id}")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ResponseEntity<Void> deleteTemplate(@PathVariable String id) {
        permissionService.deleteTemplate(id);
        return ResponseEntity.ok().build();
    }

    // ────────────────────────────────────────────────────────────────────────
    // Grants (OWNER-scoped — SUPER_ADMIN queda fuera por diseño V52)
    // ────────────────────────────────────────────────────────────────────────

    /**
     * GET /grants — lista los grants del owner del caller. El parámetro {@code ownerId}
     * de query se ignora a propósito: el contexto siempre viene del token.
     */
    @GetMapping("/grants")
    @PreAuthorize("hasRole('OWNER')")
    public ResponseEntity<List<PermissionGrantEntity>> getGrants(@RequestParam(required = false) String ownerId) {
        ActorContext actor = resolveActor();
        if (actor.ownerId == null) {
            throw new RuntimeException("No se pudo determinar tu contexto de propietario.");
        }
        return ResponseEntity.ok(permissionService.getGrantsForOwner(actor.ownerId));
    }

    public static class GrantRequest {
        public String userId;
        public String ownerId;
        public String templateId;
        /** Reauth fields — required for grant operations */
        public String password;
        public String mfaCode;
    }

    /**
     * POST /grants — crea un grant. Requiere reauth (password + MFA si el OWNER tiene
     * MFA activo). El {@code ownerId} se deriva del token; el del body se ignora.
     */
    @PostMapping("/grants")
    @PreAuthorize("hasRole('OWNER')")
    public ResponseEntity<PermissionGrantEntity> grantPermission(@RequestBody GrantRequest request) {
        reauthService.verifyReauth(request.password, request.mfaCode, "PERMISSION_GRANT");

        ActorContext actor = resolveActor();
        if (actor.ownerId == null) {
            throw new RuntimeException("No se pudo determinar tu contexto de propietario.");
        }

        UserEntity targetUser = userRepository.findById(request.userId).orElse(null);
        if (targetUser == null) {
            throw new RuntimeException("Usuario objetivo no encontrado: " + request.userId);
        }

        String targetOwnerId = targetUser.getOwnerId();
        if (targetOwnerId == null || !targetOwnerId.equals(actor.ownerId)) {
            throw new RuntimeException("El usuario no pertenece a tu contexto. No puedes asignarle permisos.");
        }

        return ResponseEntity.ok(permissionService.grantPermission(
                request.userId, actor.ownerId, request.templateId, actor.userId
        ));
    }

    public static class RevokeGrantRequest {
        public String password;
        public String mfaCode;
    }

    /**
     * POST /grants/{id}/revoke — revoca un grant propio. Requiere reauth.
     */
    @PostMapping("/grants/{id}/revoke")
    @PreAuthorize("hasRole('OWNER')")
    public ResponseEntity<Void> revokeGrant(@PathVariable String id, @RequestBody RevokeGrantRequest request) {
        reauthService.verifyReauth(request.password, request.mfaCode, "PERMISSION_REVOKE");

        ActorContext actor = resolveActor();
        PermissionGrantEntity grant = permissionService.getGrant(id);
        if (actor.ownerId == null || !actor.ownerId.equals(grant.getOwnerId())) {
            throw new RuntimeException("No tienes permiso para revocar este grant. No pertenece a tu contexto.");
        }

        permissionService.revokeGrant(id);
        return ResponseEntity.ok().build();
    }

    /**
     * DELETE /grants/{id} — legacy (sin reauth). Conservado para compatibilidad,
     * pero se prefiere POST /grants/{id}/revoke para nuevos clientes.
     */
    @DeleteMapping("/grants/{id}")
    @PreAuthorize("hasRole('OWNER')")
    public ResponseEntity<Void> revokeGrantLegacy(@PathVariable String id) {
        ActorContext actor = resolveActor();
        PermissionGrantEntity grant = permissionService.getGrant(id);
        if (actor.ownerId == null || !actor.ownerId.equals(grant.getOwnerId())) {
            throw new RuntimeException("No tienes permiso para revocar este grant. No pertenece a tu contexto.");
        }

        permissionService.revokeGrant(id);
        return ResponseEntity.ok().build();
    }

    // ────────────────────────────────────────────────────────────────────────
    // Helpers
    // ────────────────────────────────────────────────────────────────────────

    private static class ActorContext {
        String userId;
        String ownerId;
        Role role;
    }

    private ActorContext resolveActor() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        String email = auth.getName();
        UserEntity actor = userRepository.findByLoginIdentifier(email)
                .orElseThrow(() -> new RuntimeException("Actor not found: " + email));

        ActorContext ctx = new ActorContext();
        ctx.userId = actor.getId();
        ctx.role = actor.getRole();

        if (actor.getRole() == Role.OWNER) {
            ctx.ownerId = actor.getOwnerId() != null ? actor.getOwnerId() : actor.getId();
        }
        return ctx;
    }
}
