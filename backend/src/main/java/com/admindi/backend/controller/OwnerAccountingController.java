package com.admindi.backend.controller;

import com.admindi.backend.dto.OwnerAccountingSummaryDTO;
import com.admindi.backend.dto.ReportingPeriodBoundsDTO;
import com.admindi.backend.service.OwnerAccountingSummaryService;
import com.admindi.backend.service.ReportingPeriodService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/owner")
public class OwnerAccountingController {

    private final OwnerAccountingSummaryService ownerAccountingSummaryService;
    private final ReportingPeriodService reportingPeriodService;

    @Autowired
    public OwnerAccountingController(OwnerAccountingSummaryService ownerAccountingSummaryService,
                                     ReportingPeriodService reportingPeriodService) {
        this.ownerAccountingSummaryService = ownerAccountingSummaryService;
        this.reportingPeriodService = reportingPeriodService;
    }

    @GetMapping("/accounting-summary")
    @PreAuthorize("hasAnyRole('OWNER','PROPERTY_ADMIN','ACCOUNTANT')")
    public ResponseEntity<OwnerAccountingSummaryDTO> summary(@RequestParam String monthYear) {
        return ResponseEntity.ok(ownerAccountingSummaryService.buildSummary(monthYear));
    }

    /** Min/max month for owner-level reports (system timezone; max = current month). */
    @GetMapping("/reporting-period-bounds")
    @PreAuthorize("hasAnyRole('OWNER','PROPERTY_ADMIN','ACCOUNTANT')")
    public ResponseEntity<ReportingPeriodBoundsDTO> reportingBounds() {
        return ResponseEntity.ok(reportingPeriodService.getOwnerBounds());
    }
}