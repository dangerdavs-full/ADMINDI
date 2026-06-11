package com.admindi.backend.controller;

import com.admindi.backend.dto.InvoiceDTO;
import com.admindi.backend.dto.PaymentDTO;
import com.admindi.backend.dto.ShortfallSubmitResultDTO;
import com.admindi.backend.dto.TransferProofDTO;
import com.admindi.backend.service.FileStorageService;
import com.admindi.backend.service.LedgerService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class LedgerController {

    private final LedgerService ledgerService;
    private final FileStorageService fileStorage;

    @Autowired
    public LedgerController(LedgerService ledgerService, FileStorageService fileStorage) {
        this.ledgerService = ledgerService;
        this.fileStorage = fileStorage;
    }

    // ─── Invoice endpoints ──────────────────────────────────────────────

    @GetMapping("/ledger/org")
    @PreAuthorize("hasAnyRole('OWNER','PROPERTY_ADMIN','ACCOUNTANT') or hasAuthority('PAYMENT_VIEW')")
    public ResponseEntity<List<InvoiceDTO>> getOrgInvoices() {
        return ResponseEntity.ok(ledgerService.getInvoicesForMyOrganization());
    }

    @GetMapping("/ledger/tenant")
    @PreAuthorize("hasRole('TENANT')")
    public ResponseEntity<List<InvoiceDTO>> getTenantInvoices(
            @RequestParam(required = false) String tenantProfileId) {
        return ResponseEntity.ok(ledgerService.getMyInvoicesAsTenant(tenantProfileId));
    }

    @PostMapping("/payments/{invoiceId}/shortfall-reason")
    @PreAuthorize("hasRole('TENANT')")
    public ResponseEntity<ShortfallSubmitResultDTO> submitShortfallReason(
            @PathVariable String invoiceId,
            @RequestBody Map<String, Object> body) {
        String reason = (String) body.get("shortfallReason");
        String desc = body.get("shortfallDescription") != null ? body.get("shortfallDescription").toString() : null;
        LocalDate promised = null;
        if (body.get("promisedCompletionDate") != null && !body.get("promisedCompletionDate").toString().isBlank()) {
            promised = LocalDate.parse(body.get("promisedCompletionDate").toString());
        }
        return ResponseEntity.ok(ledgerService.submitShortfallReason(invoiceId, reason, desc, promised));
    }

    // ─── Manual Payment Override — OWNER only, reauth enforced downstream ─
    // Antes era SUPER_ADMIN. V52 reasigna: esta operación es owner-scoped
    // (el SUPER_ADMIN no toca datos operativos de un dueño).

    @PostMapping("/ledger/{invoiceId}/pay")
    @PreAuthorize("hasRole('OWNER')")
    public ResponseEntity<Void> markAsPaidManual(@PathVariable String invoiceId,
                                           @RequestBody(required = false) Map<String, String> payload) {
        String reference = payload != null ? payload.get("paymentReference") : null;
        String notes = payload != null ? payload.get("paymentNotes") : null;
        String method = payload != null ? payload.get("paymentMethod") : null;
        String proofFileId = payload != null ? payload.get("paymentProofFileId") : null;
        ledgerService.markAsPaidManual(invoiceId, reference, notes, method, proofFileId);
        return ResponseEntity.ok().build();
    }

    @GetMapping("/payments")
    @PreAuthorize("hasAnyRole('OWNER','PROPERTY_ADMIN','ACCOUNTANT') or hasAuthority('PAYMENT_VIEW')")
    public ResponseEntity<List<PaymentDTO>> getPayments(@RequestParam(required = false) String monthYear) {
        return ResponseEntity.ok(ledgerService.getPayments(monthYear));
    }

    // ─── Transfer Proof: Tenant submits ─────────────────────────────────
    //
    // V58 — La cuenta receptora YA NO se recibe del inquilino: se toma de
    // {@code owner.clabe}. El parámetro {@code captureMethod} indica si el
    // inquilino usó IA (foto → OCR) o captura manual, para aplicar límite
    // mensual correspondiente (AI_OCR: 6/mes, MANUAL: ilimitado).

    @PostMapping("/payments/proofs")
    @PreAuthorize("hasRole('TENANT')")
    public ResponseEntity<TransferProofDTO> submitProof(
            @RequestParam String invoiceId,
            @RequestParam(required = false) String claveRastreo,
            @RequestParam(required = false) String bankEmitter,
            @RequestParam(required = false) BigDecimal amount,
            @RequestParam(required = false) String transferDate,
            @RequestParam(required = false, defaultValue = "MANUAL") String captureMethod,
            @RequestParam(required = false) MultipartFile file) {

        LocalDate parsedDate = null;
        if (transferDate != null && !transferDate.isBlank()) {
            parsedDate = LocalDate.parse(transferDate);
        }

        TransferProofDTO result = ledgerService.submitTransferProof(
                invoiceId, claveRastreo, bankEmitter, amount, parsedDate, captureMethod, file);
        return ResponseEntity.ok(result);
    }

    /** Tenant completes missing data → system retries CEP automatically */
    @PostMapping("/payments/proofs/{proofId}/complete-data")
    @PreAuthorize("hasRole('TENANT')")
    public ResponseEntity<TransferProofDTO> completeProofData(
            @PathVariable String proofId,
            @RequestBody Map<String, String> payload) {

        BigDecimal amt = payload.get("amount") != null ? new BigDecimal(payload.get("amount")) : null;
        LocalDate date = payload.get("transferDate") != null ? LocalDate.parse(payload.get("transferDate")) : null;

        TransferProofDTO result = ledgerService.completeProofData(
                proofId, payload.get("claveRastreo"), payload.get("bankEmitter"),
                payload.get("accountReceiver"), amt, date);
        return ResponseEntity.ok(result);
    }

    // ─── Proof Trazabilidad (read-only for Owner/Admin) ─────────────────

    @GetMapping("/payments/proofs")
    @PreAuthorize("hasAnyRole('OWNER','PROPERTY_ADMIN','ACCOUNTANT') or hasAuthority('PAYMENT_VIEW')")
    public ResponseEntity<List<TransferProofDTO>> getAllProofs() {
        return ResponseEntity.ok(ledgerService.getAllProofs());
    }

    /**
     * V65 — Histórico de comprobantes filtrado para el expediente (inmueble o
     * inquilino). Devuelve únicamente estados terminales (el dueño ya decidió
     * o el scheduler expiró). Usado por PropertyDetailView para mostrar el
     * historial de pagos sin mezclar con los pendientes.
     */
    @GetMapping("/payments/proofs/history")
    @PreAuthorize("hasAnyRole('OWNER','PROPERTY_ADMIN','ACCOUNTANT','TENANT') or hasAuthority('PAYMENT_VIEW')")
    public ResponseEntity<List<TransferProofDTO>> getProofsHistory(
            @RequestParam(required = false) String propertyId,
            @RequestParam(required = false) String tenantProfileId) {
        return ResponseEntity.ok(ledgerService.getProofsHistory(propertyId, tenantProfileId));
    }

    // ─── V57 — Flujo de pago en EFECTIVO (CASH) ───────────────────────────

    /**
     * El inquilino sube comprobante de pago en efectivo. Solo requiere monto
     * (que la IA puede haber extraído del OCR) y el archivo. NO se valida contra
     * Banxico — queda PENDING_OWNER_VALIDATION hasta que el dueño decida.
     */
    @PostMapping("/payments/proofs/cash")
    @PreAuthorize("hasRole('TENANT')")
    public ResponseEntity<TransferProofDTO> submitCashProof(
            @RequestParam String invoiceId,
            @RequestParam BigDecimal amount,
            @RequestParam(required = false) String tenantNote,
            @RequestParam("file") MultipartFile file) {
        // Almacenamos el archivo primero (el service espera una URL).
        String fileUrl = fileStorage.storeFile(file);
        TransferProofDTO result = ledgerService.submitCashPaymentProof(invoiceId, amount, fileUrl, tenantNote);
        return ResponseEntity.ok(result);
    }

    /**
     * Lista de comprobantes pendientes de validación manual del dueño actual.
     * Incluye CASH (flujo normal) y SPEI que cayó a validación manual porque
     * Banxico estaba caído o el dueño no tenía CLABE registrada.
     *
     * <p>Ordenados por urgencia (expires_at ascendente). Incluye hoursRemaining
     * y paymentType en cada DTO para mostrar el timer y el tag correcto en el
     * frontend.
     *
     * <p>V59 — endpoint canónico. El alias {@code /payments/proofs/cash/pending}
     * se mantiene por back-compat con frontend antiguo y ahora devuelve la
     * misma lista (CASH + SPEI pendientes).
     */
    @GetMapping("/payments/proofs/pending")
    @PreAuthorize("hasAnyRole('OWNER','PROPERTY_ADMIN','ACCOUNTANT')")
    public ResponseEntity<List<TransferProofDTO>> getPendingProofs() {
        return ResponseEntity.ok(ledgerService.getPendingProofsForOwner());
    }

    /**
     * Alias back-compat de {@link #getPendingProofs()}. Antes solo devolvía CASH;
     * desde V59 devuelve TODO lo que está PENDING_OWNER_VALIDATION del dueño
     * actual (CASH + SPEI caído a validación manual).
     */
    @GetMapping("/payments/proofs/cash/pending")
    @PreAuthorize("hasAnyRole('OWNER','PROPERTY_ADMIN','ACCOUNTANT')")
    public ResponseEntity<List<TransferProofDTO>> getPendingCashProofs() {
        return ResponseEntity.ok(ledgerService.getPendingProofsForOwner());
    }

    // ─── Transfer Proof Override — OWNER only (V52). ────────────────────
    // SUPER_ADMIN queda fuera: no opera sobre comprobantes de un dueño.

    @PostMapping("/payments/proofs/{proofId}/override")
    @PreAuthorize("hasRole('OWNER')")
    public ResponseEntity<Void> overrideProof(@PathVariable String proofId,
                                               @RequestBody Map<String, Object> payload) {
        boolean approve = Boolean.TRUE.equals(payload.get("approve"));
        String reason = payload.get("rejectionReason") != null ? payload.get("rejectionReason").toString() : null;
        ledgerService.overrideTransferProof(proofId, approve, reason);
        return ResponseEntity.ok().build();
    }

    // ─── Legacy backward compat ─────────────────────────────────────────

    @PostMapping("/ledger/{invoiceId}/upload-proof")
    @PreAuthorize("hasRole('TENANT')")
    public ResponseEntity<Void> uploadProof(@PathVariable String invoiceId,
                                            @RequestParam("paymentReference") String paymentReference,
                                            @RequestParam("file") MultipartFile file) {
        ledgerService.uploadProof(invoiceId, paymentReference, file);
        return ResponseEntity.ok().build();
    }
}
