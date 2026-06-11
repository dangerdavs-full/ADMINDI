package com.admindi.backend.controller;

import com.admindi.backend.dto.CreatePrivateProviderRequest;
import com.admindi.backend.dto.LinkPrivateProviderRequest;
import com.admindi.backend.dto.MaintenanceProviderDTO;
import com.admindi.backend.dto.OwnerProviderLinkDTO;
import com.admindi.backend.model.Role;
import com.admindi.backend.model.UserEntity;
import com.admindi.backend.repository.UserRepository;
import com.admindi.backend.service.MaintenanceProviderService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/owner")
public class OwnerLinkedProviderController {

    private final MaintenanceProviderService maintenanceProviderService;
    private final UserRepository userRepository;

    @Autowired
    public OwnerLinkedProviderController(MaintenanceProviderService maintenanceProviderService,
                                         UserRepository userRepository) {
        this.maintenanceProviderService = maintenanceProviderService;
        this.userRepository = userRepository;
    }

    /**
     * Marks owner-provider link as PRIVATE. Target user must be MAINTENANCE_PROVIDER or REAL_ESTATE_AGENT.
     * Legacy path: POST /api/owner/linked-providers/private
     */
    @PostMapping("/linked-providers/private")
    @PreAuthorize("hasAnyRole('OWNER', 'PROPERTY_ADMIN')")
    public ResponseEntity<Void> linkPrivate(@RequestBody LinkPrivateProviderRequest req) {
        if (req.getProviderUserId() == null || req.getProviderUserId().isBlank()) {
            throw new RuntimeException("providerUserId es obligatorio.");
        }
        String ownerOrgId = resolveOrganizationOwnerId();
        maintenanceProviderService.linkPrivateProviderToOwner(req.getProviderUserId().trim(), ownerOrgId);
        return ResponseEntity.ok().build();
    }

    @GetMapping("/team/provider-links")
    @PreAuthorize("hasAnyRole('OWNER', 'PROPERTY_ADMIN')")
    public ResponseEntity<List<OwnerProviderLinkDTO>> teamProviderLinks() {
        return ResponseEntity.ok(maintenanceProviderService.listOwnerProviderLinks(resolveOrganizationOwnerId()));
    }

    @GetMapping("/team/platform-catalog")
    @PreAuthorize("hasAnyRole('OWNER', 'PROPERTY_ADMIN')")
    public ResponseEntity<List<MaintenanceProviderDTO>> teamPlatformCatalog(
            @RequestParam(required = false) String type) {
        return ResponseEntity.ok(maintenanceProviderService.listPlatformCatalog(type));
    }

    @PostMapping("/team/link-platform")
    @PreAuthorize("hasAnyRole('OWNER', 'PROPERTY_ADMIN')")
    public ResponseEntity<Void> teamLinkPlatform(@RequestBody Map<String, String> body) {
        String pid = body != null ? body.get("providerUserId") : null;
        if (pid == null || pid.isBlank()) {
            throw new RuntimeException("providerUserId is required.");
        }
        maintenanceProviderService.assignProviderToOwner(pid.trim(), resolveOrganizationOwnerId());
        return ResponseEntity.ok().build();
    }

    @PostMapping("/team/link-private")
    @PreAuthorize("hasAnyRole('OWNER', 'PROPERTY_ADMIN')")
    public ResponseEntity<Void> teamLinkPrivate(@RequestBody LinkPrivateProviderRequest req) {
        if (req.getProviderUserId() == null || req.getProviderUserId().isBlank()) {
            throw new RuntimeException("providerUserId is required.");
        }
        maintenanceProviderService.linkPrivateProviderToOwner(req.getProviderUserId().trim(), resolveOrganizationOwnerId());
        return ResponseEntity.ok().build();
    }

    /**
     * Alta integral de un proveedor/agente privado: el owner captura todos los datos del
     * contacto (nombre, email de login, email de contacto opcional, teléfono con lada,
     * tipo MAINTENANCE_PROVIDER o REAL_ESTATE_AGENT). Se crea el usuario, se le asigna una
     * contraseña temporal (retornada en la respuesta) y queda vinculado exclusivamente a
     * este owner — no se propaga al catálogo de plataforma.
     */
    @PostMapping("/team/create-private")
    @PreAuthorize("hasAnyRole('OWNER', 'PROPERTY_ADMIN')")
    public ResponseEntity<MaintenanceProviderDTO> teamCreatePrivate(@RequestBody CreatePrivateProviderRequest req) {
        MaintenanceProviderDTO dto = new MaintenanceProviderDTO();
        dto.setName(req.getName());
        dto.setUsername(req.getUsername());
        // V54: `email` y `contactEmail` son el mismo campo en el user (contactEmail).
        // Priorizamos `contactEmail` si viene explícito, si no caemos a `email`.
        String effectiveContactEmail = (req.getContactEmail() != null && !req.getContactEmail().isBlank())
                ? req.getContactEmail()
                : req.getEmail();
        dto.setContactEmail(effectiveContactEmail);
        dto.setCountryCode(req.getCountryCode());
        dto.setRawPhone(req.getRawPhone());
        dto.setProviderType(req.getProviderType());
        MaintenanceProviderDTO created = maintenanceProviderService.createPrivateProviderForOwner(
                dto, resolveOrganizationOwnerId());
        return ResponseEntity.ok(created);
    }

    @DeleteMapping("/team/unlink/{providerUserId}")
    @PreAuthorize("hasAnyRole('OWNER', 'PROPERTY_ADMIN')")
    public ResponseEntity<Void> teamUnlink(@PathVariable String providerUserId) {
        maintenanceProviderService.unlinkProviderFromOwner(providerUserId.trim(), resolveOrganizationOwnerId());
        return ResponseEntity.noContent().build();
    }

    /**
     * Reenvía el link de activación a un provider/agente vinculado a este owner.
     * El service valida que el provider esté asignado al ownerOrgId para cortar
     * IDOR (un OWNER no puede reenviar links a providers de otros).
     */
    @PostMapping("/team/{providerUserId}/resend-activation")
    @PreAuthorize("hasAnyRole('OWNER', 'PROPERTY_ADMIN')")
    public ResponseEntity<Map<String, Object>> teamResendActivation(@PathVariable String providerUserId) {
        return ResponseEntity.ok(
                maintenanceProviderService.resendActivation(providerUserId.trim(), resolveOrganizationOwnerId()));
    }

    private String resolveOrganizationOwnerId() {
        String email = SecurityContextHolder.getContext().getAuthentication().getName();
        UserEntity u = userRepository.findByLoginIdentifier(email).orElseThrow(() -> new RuntimeException("Usuario no encontrado."));
        if (u.getRole() == Role.OWNER) {
            return u.getId();
        }
        if (u.getRole() == Role.PROPERTY_ADMIN && u.getOwnerId() != null) {
            return u.getOwnerId();
        }
        throw new RuntimeException("Sin contexto de organizacion para vincular proveedor.");
    }
}
