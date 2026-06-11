package com.admindi.backend.controller;

import com.admindi.backend.dto.PaymentAgreementDTO;
import com.admindi.backend.service.PaymentAgreementService;
import com.admindi.backend.service.ReauthService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/agreements")
public class PaymentAgreementController {

    private final PaymentAgreementService agreementService;
    private final ReauthService reauthService;

    @Autowired
    public PaymentAgreementController(PaymentAgreementService agreementService,
                                      ReauthService reauthService) {
        this.agreementService = agreementService;
        this.reauthService = reauthService;
    }

    /** Tenant requests a payment agreement */
    @PostMapping
    @PreAuthorize("hasRole('TENANT')")
    public ResponseEntity<PaymentAgreementDTO> requestAgreement(@RequestBody Map<String, Object> body) {
        String invoiceId = (String) body.get("invoiceId");
        BigDecimal requestedAmount = new BigDecimal(body.get("requestedAmount").toString());
        String reason = (String) body.getOrDefault("reason", null);
        String description = (String) body.getOrDefault("description", null);
        return ResponseEntity.ok(agreementService.requestAgreement(invoiceId, requestedAmount, reason, description));
    }

    /** Tenant gets own agreements */
    @GetMapping("/mine")
    @PreAuthorize("hasRole('TENANT')")
    public ResponseEntity<List<PaymentAgreementDTO>> getMyAgreements(
            @RequestParam(required = false) String tenantProfileId) {
        return ResponseEntity.ok(agreementService.getMyAgreements(tenantProfileId));
    }

    /** Owner gets pending agreements */
    @GetMapping("/pending")
    @PreAuthorize("hasRole('OWNER')")
    public ResponseEntity<List<PaymentAgreementDTO>> getPendingAgreements() {
        return ResponseEntity.ok(agreementService.getPendingAgreements());
    }

    /** Owner/Admin/Accountant gets all agreements */
    @GetMapping
    @PreAuthorize("hasAnyRole('OWNER','PROPERTY_ADMIN','ACCOUNTANT')")
    public ResponseEntity<List<PaymentAgreementDTO>> getAllAgreements() {
        return ResponseEntity.ok(agreementService.getAllAgreements());
    }

    /**
     * V67 — Convenios de un inquilino específico, para el expediente.
     * Autorización vive en el service (el owner solo ve convenios de su
     * organización; el tenant solo los suyos).
     */
    @GetMapping("/tenant/{tenantProfileId}")
    @PreAuthorize("hasAnyRole('OWNER','PROPERTY_ADMIN','ACCOUNTANT')")
    public ResponseEntity<List<PaymentAgreementDTO>> getAgreementsByTenant(
            @PathVariable String tenantProfileId) {
        return ResponseEntity.ok(agreementService.getAgreementsByTenantProfile(tenantProfileId));
    }

    /**
     * Owner approves an agreement. Etapa 2: requiere reauth MFA+password.
     * Body: { approvedAmount, installments[], password, mfaCode }
     */
    @SuppressWarnings("unchecked")
    @PostMapping("/{id}/approve")
    @PreAuthorize("hasRole('OWNER')")
    public ResponseEntity<PaymentAgreementDTO> approveAgreement(
            @PathVariable String id, @RequestBody Map<String, Object> body) {

        String password = (String) body.get("password");
        String mfaCode = (String) body.get("mfaCode");
        reauthService.verifyReauth(password, mfaCode, "AGREEMENT_APPROVE");

        BigDecimal approvedAmount = new BigDecimal(body.get("approvedAmount").toString());

        List<PaymentAgreementService.InstallmentInput> installments = null;
        if (body.containsKey("installments")) {
            List<Map<String, Object>> rawList = (List<Map<String, Object>>) body.get("installments");
            installments = rawList.stream()
                    .map(m -> new PaymentAgreementService.InstallmentInput(
                            LocalDate.parse(m.get("dueDate").toString()),
                            new BigDecimal(m.get("amount").toString())))
                    .collect(Collectors.toList());
        }

        return ResponseEntity.ok(agreementService.approveAgreement(id, approvedAmount, installments));
    }

    /** Owner rejects an agreement. Etapa 2: requiere reauth MFA+password. */
    @PostMapping("/{id}/reject")
    @PreAuthorize("hasRole('OWNER')")
    public ResponseEntity<PaymentAgreementDTO> rejectAgreement(
            @PathVariable String id, @RequestBody Map<String, Object> body) {
        String password = (String) body.get("password");
        String mfaCode = (String) body.get("mfaCode");
        reauthService.verifyReauth(password, mfaCode, "AGREEMENT_REJECT");
        String rejectionReason = (String) body.getOrDefault("rejectionReason", "Sin motivo especificado.");
        return ResponseEntity.ok(agreementService.rejectAgreement(id, rejectionReason));
    }

    /** Owner attaches optional PDF/image evidence to an agreement */
    @PostMapping("/{id}/evidence")
    @PreAuthorize("hasRole('OWNER')")
    public ResponseEntity<PaymentAgreementDTO> attachEvidence(
            @PathVariable String id,
            @RequestParam(value = "file", required = false) MultipartFile file) {
        return ResponseEntity.ok(agreementService.attachEvidence(id, file));
    }
}
