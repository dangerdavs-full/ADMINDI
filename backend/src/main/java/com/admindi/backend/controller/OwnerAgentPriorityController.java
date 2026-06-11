package com.admindi.backend.controller;

import com.admindi.backend.model.OwnerAgentPriorityEntity;
import com.admindi.backend.service.OwnerAgentPriorityService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Administración de prioridades de agentes/proveedores por parte del dueño.
 * Un endpoint por tipo de flujo: {@code VACANCY} (agentes inmobiliarios) y
 * {@code MAINTENANCE} (proveedores de mantenimiento).
 */
@RestController
@RequestMapping("/api/owner/agent-priorities")
@PreAuthorize("hasRole('OWNER')")
public class OwnerAgentPriorityController {

    private final OwnerAgentPriorityService service;

    public OwnerAgentPriorityController(OwnerAgentPriorityService service) {
        this.service = service;
    }

    @GetMapping("/{flowType}")
    public ResponseEntity<List<OwnerAgentPriorityEntity>> list(@PathVariable String flowType) {
        return ResponseEntity.ok(service.listMine(flowType));
    }

    /** Reemplaza todo el orden (para UI drag & drop). */
    @PutMapping("/{flowType}")
    public ResponseEntity<List<OwnerAgentPriorityEntity>> replace(@PathVariable String flowType,
                                                                   @RequestBody Map<String, List<String>> body) {
        List<String> order = body.getOrDefault("agentUserIds", List.of());
        return ResponseEntity.ok(service.replaceOrdering(flowType, order));
    }

    /** Mueve un agente una posición arriba/abajo (para UI con flechas). */
    @PostMapping("/{flowType}/move")
    public ResponseEntity<List<OwnerAgentPriorityEntity>> move(@PathVariable String flowType,
                                                                @RequestBody Map<String, Object> body) {
        String agentUserId = (String) body.get("agentUserId");
        int delta = ((Number) body.getOrDefault("delta", -1)).intValue();
        return ResponseEntity.ok(service.move(flowType, agentUserId, delta));
    }
}
