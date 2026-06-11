package com.admindi.backend.controller;

import com.admindi.backend.dto.MaintenanceProviderDTO;
import com.admindi.backend.service.MaintenanceProviderService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin/platform-providers")
@PreAuthorize("hasRole('SUPER_ADMIN')")
public class MaintenanceProviderController {

    private final MaintenanceProviderService providerService;

    public MaintenanceProviderController(MaintenanceProviderService providerService) {
        this.providerService = providerService;
    }

    @GetMapping
    public ResponseEntity<List<MaintenanceProviderDTO>> listAll(
            @RequestParam(required = false) String type) {
        if (type != null && !type.isBlank()) {
            return ResponseEntity.ok(providerService.getProvidersByType(type));
        }
        return ResponseEntity.ok(providerService.getAllProviders());
    }

    @GetMapping("/{id}")
    public ResponseEntity<MaintenanceProviderDTO> getById(@PathVariable String id) {
        return ResponseEntity.ok(providerService.getProvider(id));
    }

    @PostMapping
    public ResponseEntity<MaintenanceProviderDTO> create(@RequestBody MaintenanceProviderDTO dto) {
        return ResponseEntity.ok(providerService.createProvider(dto));
    }

    @PutMapping("/{id}")
    public ResponseEntity<MaintenanceProviderDTO> updateContact(@PathVariable String id, @RequestBody MaintenanceProviderDTO dto) {
        return ResponseEntity.ok(providerService.updateProviderContact(id, dto));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deactivate(@PathVariable String id) {
        providerService.deactivateProvider(id);
        return ResponseEntity.noContent().build();
    }

    /**
     * Reenvía el link de activación al proveedor (SUPER_ADMIN sobre el catálogo
     * de plataforma). Cualquier token previo del provider se revoca.
     */
    @PostMapping("/{id}/resend-activation")
    public ResponseEntity<Map<String, Object>> resendActivation(@PathVariable String id) {
        return ResponseEntity.ok(providerService.resendActivation(id, null));
    }
}
