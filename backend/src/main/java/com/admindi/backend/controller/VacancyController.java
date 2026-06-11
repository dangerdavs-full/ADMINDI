package com.admindi.backend.controller;

import com.admindi.backend.model.CommercialActivityEntity;
import com.admindi.backend.model.VacancyEntity;
import com.admindi.backend.service.CommercialActivityService;
import com.admindi.backend.service.VacancyService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/vacancies")
public class VacancyController {

    private final VacancyService vacancyService;
    private final CommercialActivityService commercialActivityService;

    @Autowired
    public VacancyController(VacancyService vacancyService, CommercialActivityService commercialActivityService) {
        this.vacancyService = vacancyService;
        this.commercialActivityService = commercialActivityService;
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('OWNER','PROPERTY_ADMIN')")
    public ResponseEntity<VacancyEntity> create(@RequestBody Map<String, String> body) {
        String propertyId = body.get("propertyId");
        return ResponseEntity.ok(vacancyService.createVacancyManual(propertyId));
    }

    @GetMapping("/my")
    @PreAuthorize("hasRole('REAL_ESTATE_AGENT')")
    public ResponseEntity<List<VacancyEntity>> myVacancies() {
        return ResponseEntity.ok(vacancyService.listOpenVacanciesForCurrentAgentUser());
    }

    @GetMapping("/by-property/{propertyId}")
    @PreAuthorize("hasAnyRole('OWNER','PROPERTY_ADMIN','ACCOUNTANT')")
    public ResponseEntity<List<VacancyEntity>> byProperty(@PathVariable String propertyId) {
        return ResponseEntity.ok(vacancyService.listForProperty(propertyId));
    }

    @GetMapping("/organization")
    @PreAuthorize("hasAnyRole('OWNER','PROPERTY_ADMIN','ACCOUNTANT')")
    public ResponseEntity<List<VacancyEntity>> forOrganization() {
        return ResponseEntity.ok(vacancyService.listForCurrentOrganization());
    }

    @PostMapping("/{vacancyId}/activities")
    @PreAuthorize("hasAnyRole('REAL_ESTATE_AGENT','PROPERTY_ADMIN','OWNER')")
    public ResponseEntity<CommercialActivityEntity> postActivity(
            @PathVariable String vacancyId,
            @RequestBody Map<String, Object> body) {
        String activityType = body.get("activityType") != null ? body.get("activityType").toString() : "VISIT";
        String notes = body.get("notes") != null ? body.get("notes").toString() : null;
        BigDecimal commission = body.get("commissionAmount") != null
                ? new BigDecimal(body.get("commissionAmount").toString()) : null;
        String evidenceFileId = body.get("evidenceFileId") != null ? body.get("evidenceFileId").toString() : null;
        return ResponseEntity.ok(commercialActivityService.logActivity(vacancyId, activityType, notes, commission, evidenceFileId));
    }

    /** Alias: fotos u otro archivo ya subido a almacenamiento; reutiliza el mismo log de actividad. */
    @PostMapping("/{vacancyId}/photos")
    @PreAuthorize("hasAnyRole('REAL_ESTATE_AGENT','PROPERTY_ADMIN')")
    public ResponseEntity<CommercialActivityEntity> postPhotos(
            @PathVariable String vacancyId,
            @RequestBody Map<String, Object> body) {
        String notes = body.get("notes") != null ? body.get("notes").toString() : null;
        String evidenceFileId = body.get("evidenceFileId") != null ? body.get("evidenceFileId").toString() : null;
        return ResponseEntity.ok(commercialActivityService.logActivity(vacancyId, "PHOTOS", notes, null, evidenceFileId));
    }

    @PostMapping("/{vacancyId}/commission-quote")
    @PreAuthorize("hasAnyRole('REAL_ESTATE_AGENT','PROPERTY_ADMIN')")
    public ResponseEntity<CommercialActivityEntity> commissionQuote(
            @PathVariable String vacancyId,
            @RequestBody Map<String, Object> body) {
        BigDecimal amount = new BigDecimal(body.get("commissionAmount").toString());
        String notes = body.get("notes") != null ? body.get("notes").toString() : null;
        return ResponseEntity.ok(commercialActivityService.logActivity(vacancyId, "QUOTE", notes, amount, null));
    }
}
