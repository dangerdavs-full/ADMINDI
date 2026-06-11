package com.admindi.backend.controller;

import com.admindi.backend.dto.TenantExpedienteListItemDTO;
import com.admindi.backend.dto.TenantExpedienteSummaryDTO;
import com.admindi.backend.service.TenantExpedienteService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/tenant")
public class TenantExpedienteController {

    private final TenantExpedienteService tenantExpedienteService;

    @Autowired
    public TenantExpedienteController(TenantExpedienteService tenantExpedienteService) {
        this.tenantExpedienteService = tenantExpedienteService;
    }

    @GetMapping("/expedientes")
    @PreAuthorize("hasRole('TENANT')")
    public ResponseEntity<List<TenantExpedienteListItemDTO>> listExpedientes() {
        return ResponseEntity.ok(tenantExpedienteService.listExpedientesForActiveOwner());
    }

    @GetMapping("/expedientes/{tenantProfileId}/summary")
    @PreAuthorize("hasRole('TENANT')")
    public ResponseEntity<TenantExpedienteSummaryDTO> getSummary(@PathVariable String tenantProfileId) {
        return ResponseEntity.ok(tenantExpedienteService.getSummary(tenantProfileId));
    }
}
