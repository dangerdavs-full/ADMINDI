package com.admindi.backend.controller;

import com.admindi.backend.dto.ManualReminderEligibilityDTO;
import com.admindi.backend.dto.ManualReminderRequestDTO;
import com.admindi.backend.dto.ManualReminderResultDTO;
import com.admindi.backend.dto.TenantDTO;
import com.admindi.backend.dto.TenantExpedienteSummaryDTO;
import com.admindi.backend.service.ManualPaymentReminderService;
import com.admindi.backend.service.TenantExpedienteArchiveService;
import com.admindi.backend.service.TenantExpedienteService;
import com.admindi.backend.service.TenantService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.time.LocalDate;

@RestController
@RequestMapping("/api/tenants")
public class TenantController {

    private final TenantService tenantService;
    private final TenantExpedienteArchiveService tenantExpedienteArchiveService;
    private final TenantExpedienteService tenantExpedienteService;
    private final ManualPaymentReminderService manualReminderService;

    @Autowired
    public TenantController(TenantService tenantService, TenantExpedienteArchiveService tenantExpedienteArchiveService,
                            TenantExpedienteService tenantExpedienteService,
                            ManualPaymentReminderService manualReminderService) {
        this.tenantService = tenantService;
        this.tenantExpedienteArchiveService = tenantExpedienteArchiveService;
        this.tenantExpedienteService = tenantExpedienteService;
        this.manualReminderService = manualReminderService;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasRole('OWNER') or hasAuthority('TENANT_CREATE')")
    public ResponseEntity<TenantDTO> createTenantJson(@RequestBody TenantDTO dto) {
        return ResponseEntity.ok(tenantService.createTenant(dto, null));
    }

    /**
     * Alta integral con PDF opcional (mismo contrato que JSON).
     */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasRole('OWNER') or hasAuthority('TENANT_CREATE')")
    public ResponseEntity<TenantDTO> createTenantMultipart(
            @RequestParam String name,
            @RequestParam(required = false) String username,
            @RequestParam(required = false) String email,
            @RequestParam String phone,
            @RequestParam String propertyId,
            @RequestParam BigDecimal rentAmount,
            @RequestParam int paymentDay,
            @RequestParam String leaseStartDate,
            @RequestParam String leaseEndDate,
            @RequestParam(required = false) BigDecimal depositAmount,
            @RequestParam(defaultValue = "false") boolean hasLateFee,
            @RequestParam(required = false) String lateFeeType,
            @RequestParam(required = false) BigDecimal lateFeeValue,
            @RequestParam(required = false, defaultValue = "0") int gracePeriodDays,
            @RequestPart(value = "contractPdf", required = false) MultipartFile contractPdf
    ) {
        TenantDTO dto = new TenantDTO();
        dto.setName(name);
        dto.setUsername(username);
        dto.setEmail(email);
        dto.setPhone(phone);
        dto.setPropertyId(propertyId);
        dto.setRentAmount(rentAmount);
        dto.setPaymentDay(paymentDay);
        dto.setLeaseStartDate(LocalDate.parse(leaseStartDate));
        dto.setLeaseEndDate(LocalDate.parse(leaseEndDate));
        dto.setDepositAmount(depositAmount);
        dto.setHasLateFee(hasLateFee);
        dto.setLateFeeType(lateFeeType != null ? lateFeeType : "FIXED_AMOUNT");
        dto.setLateFeeValue(lateFeeValue != null ? lateFeeValue : BigDecimal.ZERO);
        dto.setGracePeriodDays(gracePeriodDays);
        return ResponseEntity.ok(tenantService.createTenant(dto, contractPdf));
    }

    @GetMapping
    @PreAuthorize("hasRole('OWNER') or hasAuthority('TENANT_VIEW')")
    public ResponseEntity<java.util.List<TenantDTO>> getMyTenants() {
        return ResponseEntity.ok(tenantService.getMyTenants());
    }

    /** Expediente completo (contrato, PDF, dirección) para la vista de dueño/admin — mismo modelo que el portal inquilino. */
    @GetMapping("/{tenantProfileId}/expediente-summary")
    @PreAuthorize("hasRole('OWNER') or hasAuthority('TENANT_VIEW')")
    public ResponseEntity<TenantExpedienteSummaryDTO> getExpedienteSummary(@PathVariable String tenantProfileId) {
        return ResponseEntity.ok(tenantExpedienteService.getSummaryForOwnerOrganization(tenantProfileId));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('OWNER') or hasAuthority('TENANT_UPDATE')")
    public ResponseEntity<TenantDTO> updateTenant(@PathVariable String id, @RequestBody TenantDTO dto) {
        return ResponseEntity.ok(tenantService.updateTenant(id, dto));
    }

    /**
     * Baja operativa del expediente (mismo efecto que {@code POST .../archive}).
     * Etapa 2 — reauth obligatorio: password + MFA (si el usuario tiene MFA activo).
     * V52 — SUPER_ADMIN no opera aquí; el aislamiento lo garantiza @PreAuthorize.
     */
    @DeleteMapping("/{id}")
    @PreAuthorize(
            "hasRole('OWNER') "
                    + "or hasAuthority('PROPERTY_ARCHIVE_TENANT') "
                    + "or hasAuthority('TENANT_DELETE')")
    public ResponseEntity<Void> deactivateTenant(
            @PathVariable String id,
            @RequestBody(required = false) PropertyController.ReauthRequest reauth) {
        String pw = reauth != null ? reauth.getPassword() : null;
        String mfa = reauth != null ? reauth.getMfaCode() : null;
        tenantExpedienteArchiveService.archiveOperational(id, pw, mfa);
        return ResponseEntity.noContent().build();
    }

    /**
     * Paso 3 — baja operativa por expediente (archivo lógico).
     * Etapa 2 — mismo reauth que {@link #deactivateTenant}.
     */
    @PostMapping("/{tenantProfileId}/archive")
    @PreAuthorize(
            "hasRole('OWNER') "
                    + "or hasAuthority('PROPERTY_ARCHIVE_TENANT') "
                    + "or hasAuthority('TENANT_DELETE')")
    public ResponseEntity<Void> archiveTenantProfile(
            @PathVariable String tenantProfileId,
            @RequestBody(required = false) PropertyController.ReauthRequest reauth) {
        String pw = reauth != null ? reauth.getPassword() : null;
        String mfa = reauth != null ? reauth.getMfaCode() : null;
        tenantExpedienteArchiveService.archiveOperational(tenantProfileId, pw, mfa);
        return ResponseEntity.ok().build();
    }

    // ───────────────────────────────────────────────────────────────────────
    //   Recordatorio de pago MANUAL (Fase B2)
    // ───────────────────────────────────────────────────────────────────────
    //
    // SUPER_ADMIN queda EXCLUIDO del @PreAuthorize a propósito: su rol no opera cobranza.
    // Solo OWNER del portafolio activo o staff con la authority TENANT_REMIND_MANUAL
    // (incluida en el template "Acceso Total" vía V42).
    //
    // El endpoint POST exige password + MFA (reauth) dentro del cuerpo del request, no via
    // un sistema de token — cada envío requiere credenciales frescas. El GET de elegibilidad
    // NO pide reauth porque no ejecuta la acción; solo resuelve si el botón debe habilitarse.

    /**
     * Devuelve si el inquilino califica para recibir un recordatorio manual ahora mismo.
     * No envía nada. Se llama al abrir el perfil del inquilino para saber si mostrar el
     * botón habilitado o deshabilitado (con tooltip del motivo).
     */
    @GetMapping("/{tenantProfileId}/manual-reminder-eligibility")
    @PreAuthorize("hasRole('OWNER') or hasAuthority('TENANT_REMIND_MANUAL')")
    public ResponseEntity<ManualReminderEligibilityDTO> getManualReminderEligibility(
            @PathVariable String tenantProfileId) {
        return ResponseEntity.ok(manualReminderService.checkEligibility(tenantProfileId));
    }

    /**
     * Dispara el envío manual del recordatorio de pago. Requiere reauth con password + MFA
     * en el body; el servicio valida IDOR, rate limit (2 por 24h) y factura pendiente antes
     * de auditar y enviar. Los mapeos HTTP los emite el servicio vía {@code ResponseStatusException}
     * (401 reauth, 403 IDOR, 404 no encontrado, 409 sin factura, 429 rate limit).
     */
    @PostMapping("/{tenantProfileId}/send-manual-reminder")
    @PreAuthorize("hasRole('OWNER') or hasAuthority('TENANT_REMIND_MANUAL')")
    public ResponseEntity<ManualReminderResultDTO> sendManualReminder(
            @PathVariable String tenantProfileId,
            @RequestBody ManualReminderRequestDTO body) {
        String pw = body != null ? body.getPassword() : null;
        String mfa = body != null ? body.getMfaCode() : null;
        return ResponseEntity.ok(manualReminderService.sendManualReminder(tenantProfileId, pw, mfa));
    }
}
