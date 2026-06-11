package com.admindi.backend.controller;

import com.admindi.backend.dto.LeaseDTO;
import com.admindi.backend.service.LeaseService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/leases")
public class LeaseController {

    private final LeaseService leaseService;

    @Autowired
    public LeaseController(LeaseService leaseService) {
        this.leaseService = leaseService;
    }

    public static class CreateLeaseRequest {
        /** Legado: unidad explicita; el servidor deriva el inmueble y persiste {@code property_id}. */
        public String unitId;
        /** Flujo principal: contrato por inmueble (sin seleccion de unidad). */
        public String propertyId;
        /** Must be {@link com.admindi.backend.model.UserEntity} id of rol TENANT (not TenantProfile id). */
        public String tenantId;
        public LocalDate startDate;
        public LocalDate endDate;
        public BigDecimal monthlyRent;
        public BigDecimal depositAmount;
        public int paymentDay;
        public String documentUrl;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasRole('OWNER') or hasAuthority('LEASE_CREATE')")
    public ResponseEntity<LeaseDTO> createLeaseJson(@RequestBody CreateLeaseRequest req) {
        boolean hasUnit = req.unitId != null && !req.unitId.isBlank();
        LeaseDTO lease;
        if (hasUnit) {
            lease = leaseService.createLease(
                    req.unitId, req.tenantId,
                    req.startDate, req.endDate,
                    req.monthlyRent, req.depositAmount,
                    req.paymentDay, req.documentUrl,
                    null, null
            );
        } else if (req.propertyId != null && !req.propertyId.isBlank()) {
            lease = leaseService.createLeaseForProperty(
                    req.propertyId, req.tenantId,
                    req.startDate, req.endDate,
                    req.monthlyRent, req.depositAmount,
                    req.paymentDay, req.documentUrl,
                    null, null
            );
        } else {
            throw new RuntimeException(
                    "Indique unitId o propertyId. Para expediente use propertyId + tenantId (id de usuario arrendatario TENANT).");
        }
        return ResponseEntity.ok(lease);
    }

    /**
     * Creación de contrato con PDF firmado opcional (multipart). Solo PDF, máx. 10 MB.
     */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasRole('OWNER') or hasAuthority('LEASE_CREATE')")
    public ResponseEntity<LeaseDTO> createLeaseMultipart(
            @RequestParam(required = false) String unitId,
            @RequestParam(required = false) String propertyId,
            @RequestParam String tenantId,
            @RequestParam String startDate,
            @RequestParam String endDate,
            @RequestParam BigDecimal monthlyRent,
            @RequestParam(required = false) BigDecimal depositAmount,
            @RequestParam int paymentDay,
            @RequestParam(required = false) String documentUrl,
            @RequestPart(value = "document", required = false) MultipartFile document
    ) {
        LocalDate start = LocalDate.parse(startDate);
        LocalDate end = LocalDate.parse(endDate);
        BigDecimal dep = depositAmount != null ? depositAmount : BigDecimal.ZERO;
        String ownerId = leaseService.resolveOwnerId();
        String storedUrl = documentUrl;
        String docName = null;
        String docCt = null;
        if (document != null && !document.isEmpty()) {
            storedUrl = leaseService.storeLeaseContractPdf(document, ownerId);
            docName = document.getOriginalFilename();
            docCt = document.getContentType();
        }
        boolean hasUnit = unitId != null && !unitId.isBlank();
        LeaseDTO lease;
        if (hasUnit) {
            lease = leaseService.createLease(
                    unitId, tenantId, start, end, monthlyRent, dep, paymentDay,
                    storedUrl, docName, docCt
            );
        } else if (propertyId != null && !propertyId.isBlank()) {
            lease = leaseService.createLeaseForProperty(
                    propertyId, tenantId, start, end, monthlyRent, dep, paymentDay,
                    storedUrl, docName, docCt
            );
        } else {
            throw new RuntimeException(
                    "Indique unitId o propertyId. Adjunte PDF opcional en la parte document.");
        }
        return ResponseEntity.ok(lease);
    }

    @GetMapping
    @PreAuthorize("hasRole('OWNER') or hasAuthority('LEASE_VIEW')")
    public ResponseEntity<List<LeaseDTO>> getLeases() {
        return ResponseEntity.ok(leaseService.getLeasesForOwner());
    }

    @PutMapping("/{id}/terminate")
    @PreAuthorize("hasRole('OWNER') or hasAuthority('LEASE_CREATE')")
    public ResponseEntity<LeaseDTO> terminateLease(@PathVariable String id) {
        return ResponseEntity.ok(leaseService.terminateLease(id));
    }
}
