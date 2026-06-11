package com.admindi.backend.controller;

import com.admindi.backend.dto.UnitDTO;
import com.admindi.backend.service.UnitService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/units")
public class UnitController {

    private final UnitService unitService;

    @Autowired
    public UnitController(UnitService unitService) {
        this.unitService = unitService;
    }

    @PostMapping
    @PreAuthorize("hasRole('OWNER') or hasAuthority('PROPERTY_CREATE')")
    public ResponseEntity<UnitDTO> createUnit(@RequestBody UnitDTO dto) {
        return ResponseEntity.ok(unitService.createUnit(dto));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('OWNER') or hasAuthority('PROPERTY_UPDATE')")
    public ResponseEntity<UnitDTO> updateUnit(@PathVariable String id, @RequestBody UnitDTO dto) {
        return ResponseEntity.ok(unitService.updateUnit(id, dto));
    }

    @GetMapping("/property/{propertyId}")
    @PreAuthorize("hasRole('OWNER') or hasAuthority('PROPERTY_VIEW')")
    public ResponseEntity<List<UnitDTO>> getUnitsForProperty(@PathVariable String propertyId) {
        return ResponseEntity.ok(unitService.getUnitsForProperty(propertyId));
    }
}
