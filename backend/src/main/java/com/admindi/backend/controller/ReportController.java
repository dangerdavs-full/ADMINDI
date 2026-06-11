package com.admindi.backend.controller;

import com.admindi.backend.service.ReportService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/reports")
public class ReportController {

    private final ReportService reportService;

    @Autowired
    public ReportController(ReportService reportService) {
        this.reportService = reportService;
    }

    /** Existing ZIP export (CSV + proofs) */
    @GetMapping("/monthly")
    @PreAuthorize("hasAnyRole('OWNER','ACCOUNTANT') or hasAuthority('REPORT_EXPORT')")
    public ResponseEntity<byte[]> downloadMonthlyZip(@RequestParam String monthYear) {
        try {
            byte[] zipBytes = reportService.generateMonthlyAccountantZip(monthYear);
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"Cierre_Contable_" + monthYear + ".zip\"")
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .body(zipBytes);
        } catch (Exception e) {
            return ResponseEntity.internalServerError().build();
        }
    }

    /** Excel export with multiple sheets */
    @GetMapping("/monthly/excel")
    @PreAuthorize("hasAnyRole('OWNER','ACCOUNTANT') or hasAuthority('REPORT_EXPORT')")
    public ResponseEntity<byte[]> downloadMonthlyExcel(@RequestParam String monthYear) {
        try {
            byte[] excelBytes = reportService.generateMonthlyExcel(monthYear);
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"Reporte_" + monthYear + ".xlsx\"")
                    .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                    .body(excelBytes);
        } catch (Exception e) {
            return ResponseEntity.internalServerError().build();
        }
    }
}
