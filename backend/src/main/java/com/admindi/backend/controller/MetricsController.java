package com.admindi.backend.controller;

import com.admindi.backend.dto.GlobalMetricsDTO;
import com.admindi.backend.dto.PropertyMetricsDTO;
import com.admindi.backend.service.MetricsService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/metrics")
@CrossOrigin(origins = "*") // En producción restringir orígenes permitidos
public class MetricsController {

    @Autowired
    private MetricsService metricsService;

    @GetMapping("/global")
    @PreAuthorize("hasRole('OWNER') or hasAuthority('REPORT_VIEW')")
    public ResponseEntity<GlobalMetricsDTO> getGlobalMetrics() {
        return ResponseEntity.ok(metricsService.getGlobalMetrics());
    }

    @GetMapping("/property/{propertyId}")
    @PreAuthorize("hasRole('OWNER') or hasAuthority('REPORT_VIEW')")
    public ResponseEntity<PropertyMetricsDTO> getPropertyMetrics(@PathVariable String propertyId) {
        return ResponseEntity.ok(metricsService.getPropertyMetrics(propertyId));
    }

    @GetMapping("/property/{propertyId}/history")
    @PreAuthorize("hasRole('OWNER') or hasAuthority('REPORT_VIEW')")
    public ResponseEntity<java.util.List<com.admindi.backend.dto.InvoiceHistoryDTO>> getPropertyHistory(@PathVariable String propertyId) {
        return ResponseEntity.ok(metricsService.listPropertyInvoiceFinancialHistory(propertyId));
    }

    /** Historial financiero (recibos) por inmueble; el historial operativo es la timeline del inmueble. */
    @GetMapping("/property/{propertyId}/invoice-financial-history")
    @PreAuthorize("hasRole('OWNER') or hasAuthority('REPORT_VIEW')")
    public ResponseEntity<java.util.List<com.admindi.backend.dto.InvoiceHistoryDTO>> getPropertyInvoiceFinancialHistory(
            @PathVariable String propertyId) {
        return ResponseEntity.ok(metricsService.listPropertyInvoiceFinancialHistory(propertyId));
    }
}
