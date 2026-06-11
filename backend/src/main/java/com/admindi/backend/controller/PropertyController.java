package com.admindi.backend.controller;

import com.admindi.backend.dto.InvoiceDTO;
import com.admindi.backend.dto.PropertyAnnualReportDTO;
import com.admindi.backend.dto.PropertyDTO;
import com.admindi.backend.dto.PropertyExpedienteDTO;
import com.admindi.backend.dto.PropertyMonthlyReportDTO;
import com.admindi.backend.dto.PropertyMovementDTO;
import com.admindi.backend.dto.ReportingPeriodBoundsDTO;
import com.admindi.backend.service.LedgerService;
import com.admindi.backend.service.PropertyExpedienteService;
import com.admindi.backend.service.PropertyMovementService;
import com.admindi.backend.service.PropertyReportService;
import com.admindi.backend.service.PropertyService;
import com.admindi.backend.service.ReportingPeriodService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/properties")
public class PropertyController {

    private final PropertyService service;
    private final PropertyMovementService propertyMovementService;
    private final LedgerService ledgerService;
    private final PropertyReportService propertyReportService;
    private final ReportingPeriodService reportingPeriodService;
    private final PropertyExpedienteService propertyExpedienteService;

    @Autowired
    public PropertyController(PropertyService service, PropertyMovementService propertyMovementService,
                              LedgerService ledgerService, PropertyReportService propertyReportService,
                              ReportingPeriodService reportingPeriodService,
                              PropertyExpedienteService propertyExpedienteService) {
        this.service = service;
        this.propertyMovementService = propertyMovementService;
        this.ledgerService = ledgerService;
        this.propertyReportService = propertyReportService;
        this.reportingPeriodService = reportingPeriodService;
        this.propertyExpedienteService = propertyExpedienteService;
    }

    /** Reauth request — password + optional MFA code */
    public static class ReauthRequest {
        private String password;
        private String mfaCode;
        public String getPassword() { return password; }
        public void setPassword(String password) { this.password = password; }
        public String getMfaCode() { return mfaCode; }
        public void setMfaCode(String mfaCode) { this.mfaCode = mfaCode; }
    }

    @PostMapping
    @PreAuthorize("hasRole('OWNER') or hasAuthority('PROPERTY_CREATE')")
    public ResponseEntity<PropertyDTO> create(@RequestBody PropertyDTO dto) {
        return ResponseEntity.ok(service.createProperty(dto));
    }

    @GetMapping
    @PreAuthorize("hasRole('OWNER') or hasAuthority('PROPERTY_VIEW')")
    public ResponseEntity<List<PropertyDTO>> getAll() {
        return ResponseEntity.ok(service.getMyProperties());
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasRole('OWNER') or hasAuthority('PROPERTY_VIEW')")
    public ResponseEntity<PropertyDTO> getDetail(@PathVariable String id) {
        return ResponseEntity.ok(service.getPropertyDetail(id));
    }

    @GetMapping("/{id}/timeline")
    @PreAuthorize("hasAnyRole('OWNER','PROPERTY_ADMIN','ACCOUNTANT') or hasAuthority('PROPERTY_VIEW')")
    public ResponseEntity<List<PropertyMovementDTO>> getTimeline(@PathVariable String id) {
        return ResponseEntity.ok(propertyMovementService.getTimelineForProperty(id));
    }

    /**
     * V49 / Bloque 4: expediente consolidado del inmueble. Incluye metadatos,
     * resumen financiero (ingresos cobrados / egresos pagados / balance),
     * inquilinos activos, contratos, facturas (con leaseId resuelto), egresos
     * con referencias a presupuesto y comprobante, y timeline agregado.
     * Recomendado como pantalla principal del inmueble para dueños y admins.
     */
    @GetMapping("/{id}/expediente")
    @PreAuthorize("hasAnyRole('OWNER','PROPERTY_ADMIN','ACCOUNTANT') or hasAuthority('PROPERTY_VIEW')")
    public ResponseEntity<PropertyExpedienteDTO> getExpediente(@PathVariable String id) {
        return ResponseEntity.ok(propertyExpedienteService.getExpediente(id));
    }

    @GetMapping("/{id}/invoices")
    @PreAuthorize("hasAnyRole('OWNER','PROPERTY_ADMIN','ACCOUNTANT') or hasAuthority('PROPERTY_VIEW')")
    public ResponseEntity<List<InvoiceDTO>> getInvoicesForProperty(@PathVariable String id) {
        return ResponseEntity.ok(ledgerService.getInvoicesForProperty(id));
    }

    @GetMapping("/{id}/reports/monthly")
    @PreAuthorize("hasAnyRole('OWNER','PROPERTY_ADMIN','ACCOUNTANT') or hasAuthority('PROPERTY_VIEW')")
    public ResponseEntity<PropertyMonthlyReportDTO> monthlyReport(@PathVariable String id,
                                                                  @org.springframework.web.bind.annotation.RequestParam String monthYear) {
        return ResponseEntity.ok(propertyReportService.monthly(id, monthYear));
    }

    @GetMapping("/{id}/reporting-period-bounds")
    @PreAuthorize("hasAnyRole('OWNER','PROPERTY_ADMIN','ACCOUNTANT') or hasAuthority('PROPERTY_VIEW')")
    public ResponseEntity<ReportingPeriodBoundsDTO> propertyReportingBounds(@PathVariable String id) {
        return ResponseEntity.ok(reportingPeriodService.getPropertyBounds(id));
    }

    @GetMapping("/{id}/reports/annual")
    @PreAuthorize("hasAnyRole('OWNER','PROPERTY_ADMIN','ACCOUNTANT') or hasAuthority('PROPERTY_VIEW')")
    public ResponseEntity<PropertyAnnualReportDTO> annualReport(@PathVariable String id,
                                                                @org.springframework.web.bind.annotation.RequestParam int year) {
        return ResponseEntity.ok(propertyReportService.annual(id, year));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('OWNER') or hasAuthority('PROPERTY_UPDATE')")
    public ResponseEntity<PropertyDTO> update(@PathVariable String id, @RequestBody PropertyDTO dto) {
        return ResponseEntity.ok(service.updateProperty(id, dto));
    }

    /**
     * V66 — Hard delete con cascada total — SOLO OWNER.
     *
     * <p>Borra el inmueble y TODO lo relacionado: archivos físicos (fotos,
     * contratos, cotizaciones, comprobantes SPEI, comprobantes de renta),
     * tickets, cotizaciones, egresos, movimientos, facturas, pagos, unidades,
     * tenant_profiles del inmueble. Es irreversible.</p>
     *
     * <p>Requiere reauth (password + MFA). Si el staff intenta llamar este
     * endpoint obtiene 403; debe usar {@code /request-delete} que genera una
     * tarea para el dueño.</p>
     */
    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('OWNER')")
    public ResponseEntity<Void> delete(@PathVariable String id, @RequestBody ReauthRequest reauth) {
        service.softDeleteWithReauth(id, reauth.getPassword(), reauth.getMfaCode());
        return ResponseEntity.noContent().build();
    }

    /**
     * V66 — Preview de impacto del hard-delete para el modal de confirmación.
     * Devuelve contadores de lo que se borraría. Read-only.
     */
    @GetMapping("/{id}/delete-impact")
    @PreAuthorize("hasRole('OWNER')")
    public ResponseEntity<java.util.Map<String, Object>> deleteImpact(@PathVariable String id) {
        return ResponseEntity.ok(service.previewDeleteImpact(id));
    }

    /**
     * Staff requests property deletion → creates an ActionTask for the OWNER.
     *
     * <p><b>Contract change (Fase 2 approval framework):</b> the request body now requires
     * the staff's password and MFA, mirroring the double-reauth policy enforced on every
     * sensitive action. Legacy body-less calls return 400.
     */
    @PostMapping("/{id}/request-delete")
    @PreAuthorize("hasRole('OWNER') or hasAuthority('PROPERTY_DELETE')")
    public ResponseEntity<java.util.Map<String, String>> requestDelete(
            @PathVariable String id,
            @RequestBody com.admindi.backend.controller.ApprovalRequestController.ApprovalRequestBody body) {
        String taskId = service.requestDeleteProperty(id, body.getPassword(), body.getMfaCode(), body.getReason());
        return ResponseEntity.accepted().body(java.util.Map.of("taskId", taskId, "eventType", "PROPERTY_DELETE_REQUESTED"));
    }

    /** OWNER approves pending delete task — requires reauth (password + MFA) */
    @PostMapping("/delete-tasks/{taskId}/approve")
    @PreAuthorize("hasRole('OWNER')")
    public ResponseEntity<Void> approveDelete(@PathVariable String taskId, @RequestBody ReauthRequest reauth) {
        service.approveDeleteWithReauth(taskId, reauth.getPassword(), reauth.getMfaCode());
        return ResponseEntity.ok().build();
    }

    /**
     * OWNER rejects a pending delete task. Accepts an optional {@code reason} that is
     * forwarded to the initiating staff in the REJECTED notification. Body is optional for
     * backward compatibility with older frontends that posted an empty request.
     */
    @PostMapping("/delete-tasks/{taskId}/reject")
    @PreAuthorize("hasRole('OWNER')")
    public ResponseEntity<Void> rejectDelete(@PathVariable String taskId,
                                             @RequestBody(required = false) java.util.Map<String, String> body) {
        String reason = body != null ? body.get("reason") : null;
        service.rejectDeleteProperty(taskId, reason);
        return ResponseEntity.ok().build();
    }
}
