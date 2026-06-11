package com.admindi.backend.controller;

import com.admindi.backend.model.AgentBankAccountEntity;
import com.admindi.backend.model.AgentCommissionInvoiceEntity;
import com.admindi.backend.model.MaintenanceQuoteEntity;
import com.admindi.backend.model.MaintenanceTicketEntity;
import com.admindi.backend.model.ProspectSubmissionEntity;
import com.admindi.backend.model.UserEntity;
import com.admindi.backend.model.VacancyEntity;
import com.admindi.backend.repository.UserRepository;
import com.admindi.backend.service.AgentBankAccountService;
import com.admindi.backend.service.AgentCommissionService;
import com.admindi.backend.service.FileOwnershipService;
import com.admindi.backend.service.FileStorageService;
import com.admindi.backend.service.MaintenanceWorkflowService;
import com.admindi.backend.service.ProspectService;
import com.admindi.backend.service.VacancyAgentOrchestrationService;
import com.admindi.backend.service.VacancyService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Endpoints del dueño para tomar las decisiones propias del flujo Fase 2:
 * autorizar mantenimiento, aprobar/rechazar cotización, pagar, aceptar/rechazar
 * prospectos, pagar comisiones.
 */
@RestController
@RequestMapping("/api/owner/workflow")
@PreAuthorize("hasRole('OWNER')")
public class OwnerWorkflowController {

    private final MaintenanceWorkflowService maintenance;
    private final ProspectService prospectService;
    private final AgentCommissionService commissionService;
    private final VacancyAgentOrchestrationService vacancyOrchestrator;
    private final VacancyService vacancyService;
    private final FileStorageService fileStorage;
    private final FileOwnershipService fileOwnership;
    private final UserRepository userRepository;
    private final AgentBankAccountService agentBankAccountService;

    public OwnerWorkflowController(MaintenanceWorkflowService maintenance,
                                   ProspectService prospectService,
                                   AgentCommissionService commissionService,
                                   VacancyAgentOrchestrationService vacancyOrchestrator,
                                   VacancyService vacancyService,
                                   FileStorageService fileStorage,
                                   FileOwnershipService fileOwnership,
                                   UserRepository userRepository,
                                   AgentBankAccountService agentBankAccountService) {
        this.maintenance = maintenance;
        this.prospectService = prospectService;
        this.commissionService = commissionService;
        this.vacancyOrchestrator = vacancyOrchestrator;
        this.vacancyService = vacancyService;
        this.fileStorage = fileStorage;
        this.fileOwnership = fileOwnership;
        this.userRepository = userRepository;
        this.agentBankAccountService = agentBankAccountService;
    }

    // ── Upload genérico (para SPEI proof, evidencias) ──────────────────────────

    /**
     * Sube un archivo y devuelve su {@code fileId} (string URL interno). Utilizado
     * para SPEI proofs y evidencias que luego se referencian por id desde los
     * endpoints de comisiones y mantenimiento.
     *
     * @param category carpeta lógica (p.ej. {@code spei-proof}, {@code maintenance-evidence}).
     */
    @PostMapping(path = "/files/upload", consumes = "multipart/form-data")
    public ResponseEntity<Map<String, String>> uploadFile(@RequestParam("file") MultipartFile file,
                                                           @RequestParam(value = "category", defaultValue = "workflow") String category) {
        String fileId = fileStorage.store(file, category);
        // Registramos la "claim" del archivo contra el owner/super_admin que lo
        // sube. Ese registro lo consumen después MaintenanceWorkflowService y
        // AgentCommissionService al cerrar pagos — impide que alguien suba un
        // archivo y lo use otro usuario (IDOR contra comprobantes SPEI).
        String actorEmail = SecurityContextHolder.getContext().getAuthentication().getName();
        userRepository.findByLoginIdentifier(actorEmail).ifPresent(u ->
                fileOwnership.registerClaim(fileId, u.getId(), category));
        return ResponseEntity.ok(Map.of("fileId", fileId));
    }

    // ── Vacancia: iniciar cadena de agentes (decisión manual del dueño) ────────

    /**
     * Arranca la notificación a los agentes inmobiliarios del dueño según sus
     * prioridades VACANCY configuradas. Es el "gatillo manual": no se hace solo.
     *
     * <p>Si el inmueble no tiene vacancia abierta, se abre una aquí mismo. Si ya
     * hay cadena activa, no abre una segunda (idempotente).
     *
     * @return 200 con la vacancia actualizada si arrancó, 409 si no hay prioridades
     *         configuradas (caller debe mostrar modal "configura tus agentes").
     */
    @PostMapping("/vacancies/start-agent-chain")
    public ResponseEntity<?> startAgentChain(@RequestBody Map<String, String> body) {
        String propertyId = body.get("propertyId");
        if (propertyId == null || propertyId.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "propertyId obligatorio"));
        }
        Optional<VacancyEntity> existing = vacancyService.findOpenVacancyForProperty(propertyId);
        VacancyEntity vacancy = existing.orElseGet(() -> vacancyService.createVacancyManual(propertyId));
        try {
            vacancyOrchestrator.startChainIfApplicable(vacancy);
        } catch (IllegalStateException ex) {
            // V51 — distinguimos el error de "sin historial" para que el frontend
            // muestre un modal explicativo en lugar del genérico de "configura agentes".
            String msg = ex.getMessage() != null ? ex.getMessage() : "No se pudo iniciar la cadena.";
            if (msg.startsWith("NO_RENTAL_HISTORY")) {
                return ResponseEntity.status(409).body(Map.of(
                        "error", "NO_RENTAL_HISTORY",
                        "message", msg.substring("NO_RENTAL_HISTORY — ".length()),
                        "hint", "Contacta un agente manualmente para esta primera colocación. La difusión automática se activa al cumplir " + com.admindi.backend.service.VacancyAgentOrchestrationService.MIN_RENTAL_HISTORY_DAYS + " días con un inquilino."));
            }
            return ResponseEntity.status(409).body(Map.of("error", msg));
        } catch (Exception ex) {
            return ResponseEntity.status(409).body(Map.of(
                    "error", ex.getMessage() != null ? ex.getMessage() : "No se pudo iniciar la cadena."));
        }

        // Si el chainState cambió a AWAITING_AGENT, arrancó correctamente. Si quedó
        // en el valor inicial (sin prioridades configuradas), devolvemos 409 con hint
        // para que el frontend muestre el modal "configura tus agentes".
        VacancyEntity refreshed = vacancyService.findOpenVacancyForProperty(propertyId).orElse(vacancy);
        if (refreshed.getChainState() == null
                || !VacancyEntity.CHAIN_AWAITING_AGENT.equals(refreshed.getChainState())) {
            return ResponseEntity.status(409).body(Map.of(
                    "error", "No se inició la cadena. Configura al menos un agente inmobiliario en tus prioridades.",
                    "hint", "Ve a Equipo y proveedores → Prioridades de agentes y agrega al menos uno."));
        }
        return ResponseEntity.ok(refreshed);
    }

    // ── Mantenimiento ─────────────────────────────────────────────────────────

    /** Tickets esperando autorización del dueño. */
    @GetMapping("/maintenance/tickets/pending-authorization")
    public ResponseEntity<List<MaintenanceTicketEntity>> pendingAuthTickets() {
        return ResponseEntity.ok(maintenance.listPendingAuthForOwner());
    }

    /** Cotizaciones SUBMITTED pendientes de aprobación. */
    @GetMapping("/maintenance/quotes/pending-approval")
    public ResponseEntity<List<MaintenanceQuoteEntity>> pendingApprovalQuotes() {
        return ResponseEntity.ok(maintenance.listPendingQuotesForOwner());
    }

    /** Tickets APPROVED listos para pago. */
    @GetMapping("/maintenance/tickets/ready-to-pay")
    public ResponseEntity<List<MaintenanceTicketEntity>> readyToPayTickets() {
        return ResponseEntity.ok(maintenance.listReadyToPayForOwner());
    }

    /** Todos los tickets del dueño (para historial/resumen). */
    @GetMapping("/maintenance/tickets")
    public ResponseEntity<List<MaintenanceTicketEntity>> allTicketsForOwner() {
        return ResponseEntity.ok(maintenance.listAllForOwner());
    }

    @GetMapping("/maintenance/tickets/{ticketId}/quotes")
    public ResponseEntity<List<MaintenanceQuoteEntity>> ticketQuotes(@PathVariable String ticketId) {
        return ResponseEntity.ok(maintenance.listQuotesByTicket(ticketId));
    }

    @PostMapping("/maintenance/tickets/{ticketId}/authorize")
    public ResponseEntity<MaintenanceTicketEntity> authorize(@PathVariable String ticketId,
                                                              @RequestBody(required = false) Map<String, String> body) {
        String providerUserId = body != null ? body.get("providerUserId") : null;
        return ResponseEntity.ok(maintenance.ownerAuthorize(ticketId, providerUserId));
    }

    @PostMapping("/maintenance/tickets/{ticketId}/reject")
    public ResponseEntity<MaintenanceTicketEntity> rejectTicket(@PathVariable String ticketId,
                                                                 @RequestBody(required = false) Map<String, String> body) {
        String reason = body != null ? body.get("reason") : null;
        return ResponseEntity.ok(maintenance.ownerReject(ticketId, reason));
    }

    @PostMapping("/maintenance/quotes/{quoteId}/approve")
    public ResponseEntity<MaintenanceTicketEntity> approveQuote(@PathVariable String quoteId) {
        return ResponseEntity.ok(maintenance.ownerApproveQuote(quoteId));
    }

    @PostMapping("/maintenance/quotes/{quoteId}/reject")
    public ResponseEntity<MaintenanceQuoteEntity> rejectQuote(@PathVariable String quoteId,
                                                               @RequestBody(required = false) Map<String, String> body) {
        String reason = body != null ? body.get("reason") : null;
        return ResponseEntity.ok(maintenance.ownerRejectQuote(quoteId, reason));
    }

    @PostMapping("/maintenance/tickets/{ticketId}/pay-and-close")
    public ResponseEntity<MaintenanceTicketEntity> payAndClose(@PathVariable String ticketId,
                                                                @RequestBody Map<String, Object> body) {
        BigDecimal paidAmount = new BigDecimal(body.get("paidAmount").toString());
        String speiProofFileId = (String) body.get("speiProofFileId");
        // V63 — ahora el ticket NO se cierra; queda AWAITING_PROVIDER_CONFIRMATION
        // hasta que el proveedor confirme o dispute. Mantenemos nombre del
        // endpoint por back-compat con el frontend; el comportamiento cambió.
        return ResponseEntity.ok(maintenance.ownerRecordPayment(ticketId, paidAmount, speiProofFileId));
    }

    // ── V63 — Confirmación bidireccional del pago ─────────────────────────

    /** Tickets que el dueño pagó y esperan confirmación del proveedor. */
    @GetMapping("/maintenance/tickets/awaiting-payment-confirmation")
    public ResponseEntity<List<MaintenanceTicketEntity>> awaitingPaymentConfirmation() {
        return ResponseEntity.ok(maintenance.listAwaitingPaymentConfirmationForOwner());
    }

    // ── V65 — Resumen consolidado de pendientes para el dashboard ─────────
    //
    // Devuelve los totales por sección para que el badge global del sidebar y
    // el banner del dashboard sepan cuánto hay que atender sin hacer N requests.
    // Si alguna fuente falla, el conteo para ese bucket cae a 0 para no
    // bloquear el dashboard entero.

    @GetMapping("/pending-summary")
    public ResponseEntity<Map<String, Object>> pendingSummary() {
        int maintAuth = safeCount(() -> maintenance.listPendingAuthForOwner().size());
        int maintQuote = safeCount(() -> maintenance.listPendingQuotesForOwner().size());
        int maintPay = safeCount(() -> maintenance.listReadyToPayForOwner().size());
        int maintPayConfirm = safeCount(() -> maintenance.listAwaitingPaymentConfirmationForOwner().size());
        int prospects = safeCount(() -> prospectService.listPendingForOwner().size());
        int commissions = safeCount(() -> (int) commissionService.listForOwner().stream()
                .filter(i -> "PENDING_PAYMENT".equals(i.getStatus()) || "FAILED".equals(i.getStatus()))
                .count());

        int total = maintAuth + maintQuote + maintPay + maintPayConfirm + prospects + commissions;

        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("total", total);
        out.put("maintAuth", maintAuth);
        out.put("maintQuote", maintQuote);
        out.put("maintPay", maintPay);
        out.put("maintPayConfirm", maintPayConfirm);
        out.put("prospects", prospects);
        out.put("commissions", commissions);
        return ResponseEntity.ok(out);
    }

    private int safeCount(java.util.function.IntSupplier sup) {
        try { return sup.getAsInt(); }
        catch (Exception ignore) { return 0; }
    }

    /**
     * Preview de los datos bancarios del proveedor asignado al ticket, para
     * mostrar al dueño antes de que transfiera. CLABE se devuelve enmascarada
     * (primeros 4 + *** + últimos 3) para que la UI no exponga los 18 dígitos
     * completos; sí devuelve banco y titular tal cual para cotejo.
     */
    @GetMapping("/maintenance/tickets/{ticketId}/provider-bank-preview")
    public ResponseEntity<Map<String, Object>> providerBankPreview(@PathVariable String ticketId) {
        // Guard: solo el owner del ticket puede ver datos del provider.
        MaintenanceTicketEntity t = maintenance.listAllForOwner().stream()
                .filter(x -> ticketId.equals(x.getId()))
                .findFirst()
                .orElse(null);
        if (t == null) {
            return ResponseEntity.status(404).body(Map.of("error", "Ticket no encontrado o no te pertenece."));
        }
        if (t.getAssignedProviderId() == null) {
            return ResponseEntity.status(409).body(Map.of("error", "Ticket sin proveedor asignado."));
        }
        UserEntity provider = userRepository.findById(t.getAssignedProviderId()).orElse(null);
        if (provider == null) {
            return ResponseEntity.status(404).body(Map.of("error", "Proveedor no encontrado."));
        }
        Optional<AgentBankAccountEntity> acct = agentBankAccountService.getForAgent(provider.getId());
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("providerUserId", provider.getId());
        out.put("providerName", provider.getName());
        out.put("accountActive", acct.isPresent()
                && acct.get().getClabe() != null && !acct.get().getClabe().isBlank()
                && acct.get().getBankName() != null && !acct.get().getBankName().isBlank()
                && acct.get().getAccountHolder() != null && !acct.get().getAccountHolder().isBlank());
        if (acct.isPresent()) {
            AgentBankAccountEntity a = acct.get();
            out.put("clabeMasked", a.maskedClabe());
            out.put("bankName", a.getBankName());
            out.put("accountHolder", a.getAccountHolder());
            out.put("validationStatus", a.getValidationStatus());
        }
        return ResponseEntity.ok(out);
    }

    // ── Prospectos ────────────────────────────────────────────────────────────

    @GetMapping("/prospects/pending")
    public ResponseEntity<List<ProspectSubmissionEntity>> pendingProspects() {
        return ResponseEntity.ok(prospectService.listPendingForOwner());
    }

    @PostMapping("/prospects/{prospectId}/accept")
    public ResponseEntity<ProspectSubmissionEntity> acceptProspect(@PathVariable String prospectId) {
        return ResponseEntity.ok(prospectService.ownerAccept(prospectId));
    }

    @PostMapping("/prospects/{prospectId}/reject")
    public ResponseEntity<ProspectSubmissionEntity> rejectProspect(@PathVariable String prospectId,
                                                                    @RequestBody(required = false) Map<String, String> body) {
        String reason = body != null ? body.get("reason") : null;
        return ResponseEntity.ok(prospectService.ownerReject(prospectId, reason));
    }

    // ── Comisiones ────────────────────────────────────────────────────────────

    @GetMapping("/commissions")
    public ResponseEntity<List<AgentCommissionInvoiceEntity>> listCommissions() {
        return ResponseEntity.ok(commissionService.listForOwner());
    }

    @PostMapping("/commissions/{invoiceId}/spei-proof")
    public ResponseEntity<AgentCommissionInvoiceEntity> submitSpeiProof(@PathVariable String invoiceId,
                                                                         @RequestBody Map<String, Object> body) {
        BigDecimal declaredAmount = body.get("declaredAmount") != null
                ? new BigDecimal(body.get("declaredAmount").toString()) : null;
        return ResponseEntity.ok(commissionService.submitSpeiProof(
                invoiceId,
                (String) body.get("proofFileId"),
                declaredAmount,
                (String) body.get("claveRastreo"),
                (String) body.get("bankEmitter")));
    }

    @PostMapping("/commissions/{invoiceId}/void")
    public ResponseEntity<AgentCommissionInvoiceEntity> voidCommission(@PathVariable String invoiceId,
                                                                        @RequestBody(required = false) Map<String, String> body) {
        String reason = body != null ? body.get("reason") : null;
        return ResponseEntity.ok(commissionService.voidInvoice(invoiceId, reason));
    }
}
