package com.admindi.backend.controller;

import com.admindi.backend.service.BanxicoInstitutionCatalogService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Catálogo oficial de instituciones Banxico para formularios del frontend.
 */
@RestController
@RequestMapping("/api/banxico")
public class BanxicoInstitutionCatalogController {

    private final BanxicoInstitutionCatalogService catalogService;

    public BanxicoInstitutionCatalogController(BanxicoInstitutionCatalogService catalogService) {
        this.catalogService = catalogService;
    }

    @GetMapping("/institutions")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<BanxicoInstitutionCatalogService.CatalogSnapshot> getInstitutions() {
        return ResponseEntity.ok(catalogService.getCatalog());
    }
}
