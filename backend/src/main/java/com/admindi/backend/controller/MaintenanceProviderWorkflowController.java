package com.admindi.backend.controller;

import com.admindi.backend.model.AgentBankAccountEntity;
import com.admindi.backend.model.AgentNotificationChainEntity;
import com.admindi.backend.model.MaintenanceQuoteEntity;
import com.admindi.backend.model.MaintenanceTicketEntity;
import com.admindi.backend.model.PropertyEntity;
import com.admindi.backend.model.Role;
import com.admindi.backend.model.UserEntity;
import com.admindi.backend.repository.AgentNotificationChainRepository;
import com.admindi.backend.repository.MaintenanceQuoteRepository;
import com.admindi.backend.repository.MaintenanceTicketRepository;
import com.admindi.backend.repository.PropertyRepository;
import com.admindi.backend.repository.UserRepository;
import com.admindi.backend.service.AgentBankAccountService;
import com.admindi.backend.service.FileOwnershipService;
import com.admindi.backend.service.FileStorageService;
import com.admindi.backend.service.MaintenanceWorkflowService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Panel del proveedor de mantenimiento (flujo Fase 2): tickets asignados, aceptar/rechazar,
 * cotización, CLABE.
 */
@RestController
@RequestMapping("/api/maintenance-provider")
@PreAuthorize("hasRole('MAINTENANCE_PROVIDER')")
public class MaintenanceProviderWorkflowController {

    private final MaintenanceWorkflowService workflow;
    private final AgentBankAccountService bankAccountService;
    private final MaintenanceTicketRepository ticketRepository;
    private final MaintenanceQuoteRepository quoteRepository;
    private final AgentNotificationChainRepository chainRepository;
    private final PropertyRepository propertyRepository;
    private final UserRepository userRepository;
    private final FileStorageService fileStorage;
    private final FileOwnershipService fileOwnership;

    public MaintenanceProviderWorkflowController(MaintenanceWorkflowService workflow,
                                                  AgentBankAccountService bankAccountService,
                                                  MaintenanceTicketRepository ticketRepository,
                                                  MaintenanceQuoteRepository quoteRepository,
                                                  AgentNotificationChainRepository chainRepository,
                                                  PropertyRepository propertyRepository,
                                                  UserRepository userRepository,
                                                  FileStorageService fileStorage,
                                                  FileOwnershipService fileOwnership) {
        this.workflow = workflow;
        this.bankAccountService = bankAccountService;
        this.ticketRepository = ticketRepository;
        this.quoteRepository = quoteRepository;
        this.chainRepository = chainRepository;
        this.propertyRepository = propertyRepository;
        this.userRepository = userRepository;
        this.fileStorage = fileStorage;
        this.fileOwnership = fileOwnership;
    }

    // ── V63 — estado de onboarding de la cuenta bancaria ───────────────────
    //
    // El frontend llama a este endpoint al mount del dashboard del provider
    // para decidir si bloquea la UI con el wizard de onboarding (CLABE + banco
    // + titular). La misma precondición se valida en los endpoints operativos
    // (accept/quote) para defensa en profundidad.

    @GetMapping("/bank-account/status")
    public ResponseEntity<Map<String, Object>> bankAccountStatus() {
        String providerId = currentProviderId();
        boolean complete = bankAccountService.isAccountComplete(providerId);
        // #region agent log
        writeDebug("STATUS", "bankAccountStatus:check", java.util.Map.of(
                "providerId", providerId,
                "complete", complete));
        // #endregion
        return ResponseEntity.ok(Map.of(
                "complete", complete,
                "accountActive", complete
        ));
    }

    // ── Upload genérico (evidencia de cotización) ────────────────────────────

    @PostMapping(path = "/files/upload", consumes = "multipart/form-data")
    public ResponseEntity<Map<String, String>> uploadFile(@RequestParam("file") MultipartFile file,
                                                           @RequestParam(value = "category", defaultValue = "provider-evidence") String category) {
        String fileId = fileStorage.store(file, category);
        String actorEmail = SecurityContextHolder.getContext().getAuthentication().getName();
        userRepository.findByLoginIdentifier(actorEmail).ifPresent(u ->
                fileOwnership.registerClaim(fileId, u.getId(), category));
        return ResponseEntity.ok(Map.of("fileId", fileId));
    }

    // ── Tickets: listados ─────────────────────────────────────────────────────

    /**
     * Invitaciones vigentes (PENDING) para este proveedor. Son tickets donde el
     * dueño lo tiene en su lista y le corresponde turno ahora (72h para aceptar).
     */
    @GetMapping("/tickets/invitations")
    public ResponseEntity<List<Map<String, Object>>> listInvitations() {
        String providerId = currentProviderId();
        List<AgentNotificationChainEntity> pending = chainRepository
                .findByAgentUserIdAndDecisionOrderByNotifiedAtDesc(
                        providerId, AgentNotificationChainEntity.DECISION_PENDING);
        List<Map<String, Object>> out = new ArrayList<>();
        for (AgentNotificationChainEntity link : pending) {
            if (!AgentNotificationChainEntity.RESOURCE_MAINTENANCE_TICKET.equals(link.getResourceType())) continue;
            MaintenanceTicketEntity t = ticketRepository.findById(link.getResourceId()).orElse(null);
            if (t == null) continue;
            if (isClosedStatus(t.getStatus())) continue;
            out.add(buildTicketView(t, link));
        }
        return ResponseEntity.ok(out);
    }

    /**
     * Tickets asignados al proveedor (ACCEPTED, QUOTED, APPROVED, COMPLETED).
     * Este es el buzón principal del proveedor.
     */
    @GetMapping("/tickets")
    public ResponseEntity<List<Map<String, Object>>> listTickets() {
        String providerId = currentProviderId();
        List<MaintenanceTicketEntity> mine = ticketRepository
                .findByAssignedProviderIdOrderByCreatedAtDesc(providerId);
        List<Map<String, Object>> out = new ArrayList<>();
        for (MaintenanceTicketEntity t : mine) {
            out.add(buildTicketView(t, null));
        }
        return ResponseEntity.ok(out);
    }

    /**
     * Cotizaciones de un ticket (para que el proveedor vea su propia cotización
     * y el estado de aprobación del dueño).
     */
    @GetMapping("/tickets/{ticketId}/quotes")
    public ResponseEntity<List<MaintenanceQuoteEntity>> listQuotes(@PathVariable String ticketId) {
        String providerId = currentProviderId();
        MaintenanceTicketEntity t = ticketRepository.findById(ticketId).orElse(null);
        if (t == null) return ResponseEntity.notFound().build();
        if (!providerId.equals(t.getAssignedProviderId())) {
            return ResponseEntity.status(403).build();
        }
        return ResponseEntity.ok(quoteRepository.findByTicketId(ticketId));
    }

    @PostMapping("/tickets/{ticketId}/accept")
    public ResponseEntity<MaintenanceTicketEntity> accept(@PathVariable String ticketId) {
        // #region agent log
        writeDebug("ACCEPT", "accept:entry", java.util.Map.of("ticketId", ticketId));
        // #endregion
        try {
            // V63 — no puede aceptar si no tiene CLABE completa (sería imposible pagarle).
            bankAccountService.requireCompleteAccountForCurrentAgent();
            MaintenanceTicketEntity result = workflow.providerAccept(ticketId);
            // #region agent log
            writeDebug("ACCEPT", "accept:success", java.util.Map.of(
                    "ticketId", ticketId,
                    "status", String.valueOf(result.getStatus())));
            // #endregion
            return ResponseEntity.ok(result);
        } catch (RuntimeException ex) {
            // #region agent log
            writeDebug("ACCEPT", "accept:failed", java.util.Map.of(
                    "ticketId", ticketId,
                    "exceptionClass", ex.getClass().getSimpleName(),
                    "message", String.valueOf(ex.getMessage())));
            // #endregion
            throw ex;
        }
    }

    // ── V63 — Confirmación bidireccional del pago al proveedor ──────────────

    /**
     * Tickets del proveedor esperando que confirme o dispute el pago que el
     * dueño ya registró. Vista del tab "Confirmar pagos recibidos".
     */
    @GetMapping("/tickets/awaiting-payment-confirmation")
    public ResponseEntity<List<MaintenanceTicketEntity>> listAwaitingPayment() {
        return ResponseEntity.ok(workflow.listAwaitingPaymentConfirmationForProvider());
    }

    /**
     * V63 — Devuelve el fileId del comprobante SPEI registrado por el dueño
     * para este ticket, para que el provider lo visualice desde su panel
     * antes de confirmar. Autorización vive en el service.
     */
    @GetMapping("/tickets/{ticketId}/payment-proof-id")
    public ResponseEntity<Map<String, String>> paymentProofId(@PathVariable String ticketId) {
        String fileId = workflow.getPaymentProofFileIdForParty(ticketId);
        Map<String, String> out = new java.util.HashMap<>();
        out.put("fileId", fileId);
        return ResponseEntity.ok(out);
    }

    /**
     * El proveedor confirma que recibió el SPEI → cierre contable del ticket.
     * Solo el proveedor asignado puede invocarlo; solo aplica si el ticket
     * está {@code AWAITING_PROVIDER_CONFIRMATION}.
     */
    @PostMapping("/tickets/{ticketId}/payment-confirm")
    public ResponseEntity<MaintenanceTicketEntity> confirmPayment(@PathVariable String ticketId) {
        return ResponseEntity.ok(workflow.providerConfirmPayment(ticketId));
    }

    /**
     * El proveedor disputa el pago (no lo ve en su cuenta / monto incorrecto).
     * El ticket regresa a APPROVED para que el dueño vuelva a registrar pago,
     * y el expense queda VOID con el motivo.
     */
    @PostMapping("/tickets/{ticketId}/payment-dispute")
    public ResponseEntity<MaintenanceTicketEntity> disputePayment(@PathVariable String ticketId,
                                                                   @RequestBody(required = false) Map<String, String> body) {
        String reason = body != null ? body.get("reason") : null;
        return ResponseEntity.ok(workflow.providerDisputePayment(ticketId, reason));
    }

    /**
     * V67 — El proveedor cancela un ticket ya aceptado (duplicado, ya resuelto,
     * fuera de su oficio, etc.). Permitido sólo mientras no haya compromiso
     * económico (ACCEPTED, QUOTED; también AWAITING_PROVIDER_ACCEPT por robustez).
     */
    @PostMapping("/tickets/{ticketId}/cancel")
    public ResponseEntity<MaintenanceTicketEntity> cancel(@PathVariable String ticketId,
                                                           @RequestBody(required = false) Map<String, String> body) {
        String reason = body != null ? body.get("reason") : null;
        return ResponseEntity.ok(workflow.providerCancel(ticketId, reason));
    }

    @PostMapping("/tickets/{ticketId}/reject")
    public ResponseEntity<MaintenanceTicketEntity> reject(@PathVariable String ticketId,
                                                           @RequestBody(required = false) Map<String, String> body) {
        String reason = body != null ? body.get("reason") : null;
        // #region agent log
        writeDebug("REJECT", "reject:entry", java.util.Map.of(
                "ticketId", ticketId,
                "hasReason", reason != null && !reason.isBlank()));
        // #endregion
        try {
            MaintenanceTicketEntity result = workflow.providerReject(ticketId, reason);
            // #region agent log
            writeDebug("REJECT", "reject:success", java.util.Map.of(
                    "ticketId", ticketId,
                    "status", String.valueOf(result.getStatus())));
            // #endregion
            return ResponseEntity.ok(result);
        } catch (RuntimeException ex) {
            // #region agent log
            writeDebug("REJECT", "reject:failed", java.util.Map.of(
                    "ticketId", ticketId,
                    "exceptionClass", ex.getClass().getSimpleName(),
                    "message", String.valueOf(ex.getMessage())));
            // #endregion
            throw ex;
        }
    }

    @PostMapping("/tickets/{ticketId}/quote")
    public ResponseEntity<MaintenanceQuoteEntity> submitQuote(@PathVariable String ticketId,
                                                               @RequestBody Map<String, Object> body) {
        // V63 — guard: la cotización implica pago futuro; exigimos CLABE completa.
        bankAccountService.requireCompleteAccountForCurrentAgent();
        BigDecimal amount = new BigDecimal(body.get("amount").toString());
        String description = (String) body.get("description");
        String evidenceFileId = (String) body.get("evidenceFileId");
        // visitNotes: opcional — texto libre que el proveedor escribe tras la
        // visita para aclarar conceptos o hallazgos. Se muestra al dueño en el
        // panel y va en el cuerpo del email. No forma parte de la plantilla
        // WhatsApp aprobada (mantiene sus 6 slots fijos).
        String visitNotes = (String) body.get("visitNotes");

        // #region agent log
        try {
            java.nio.file.Files.writeString(
                java.nio.file.Paths.get("C:\\Users\\Arfax\\Desktop\\plataforma inmubles\\debug-93290f.log"),
                "{\"sessionId\":\"93290f\",\"runId\":\"post-fix\",\"hypothesisId\":\"H11\",\"location\":\"MaintenanceProviderWorkflowController.submitQuote\",\"message\":\"POST /quote invoked\",\"data\":{\"ticketId\":\"" + dbgQuoteSafe(ticketId) + "\",\"evidenceFileIdLen\":" + (evidenceFileId == null ? 0 : evidenceFileId.length()) + ",\"evidenceFileIdSample\":\"" + dbgQuoteSafe(evidenceFileId) + "\",\"amount\":\"" + dbgQuoteSafe(amount == null ? null : amount.toPlainString()) + "\"},\"timestamp\":" + System.currentTimeMillis() + "}\n",
                java.nio.charset.StandardCharsets.UTF_8,
                java.nio.file.StandardOpenOption.APPEND,
                java.nio.file.StandardOpenOption.CREATE);
        } catch (Exception _dbgIgnored) {}
        // #endregion

        try {
            MaintenanceQuoteEntity saved = workflow.providerSubmitQuote(
                    ticketId, amount, description, evidenceFileId, visitNotes);
            // #region agent log
            try {
                java.nio.file.Files.writeString(
                    java.nio.file.Paths.get("C:\\Users\\Arfax\\Desktop\\plataforma inmubles\\debug-93290f.log"),
                    "{\"sessionId\":\"93290f\",\"runId\":\"post-fix\",\"hypothesisId\":\"H11\",\"location\":\"MaintenanceProviderWorkflowController.submitQuote\",\"message\":\"quote persisted OK (post-V60 fix)\",\"data\":{\"quoteId\":\"" + dbgQuoteSafe(saved == null ? null : saved.getId()) + "\",\"ticketId\":\"" + dbgQuoteSafe(ticketId) + "\",\"evidenceFileIdLen\":" + (evidenceFileId == null ? 0 : evidenceFileId.length()) + "},\"timestamp\":" + System.currentTimeMillis() + "}\n",
                    java.nio.charset.StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.APPEND,
                    java.nio.file.StandardOpenOption.CREATE);
            } catch (Exception _dbgIgnored) {}
            // #endregion
            return ResponseEntity.ok(saved);
        } catch (Exception e) {
            // #region agent log
            dbgLogQuoteError("MaintenanceProviderWorkflowController.submitQuote", "H11", e, evidenceFileId);
            // #endregion
            throw e;
        }
    }

    // #region agent log
    private static String dbgQuoteSafe(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "'");
    }

    /**
     * Captura stack trace de la excepción y lo emite como NDJSON al log de la
     * sesión de debug. Útil para confirmar (o refutar) que tras V60 ya no
     * ocurre el "value too long for type character varying(36)" en
     * maintenance_quotes.evidence_file_id.
     */
    private static void dbgLogQuoteError(String location, String hypothesisId, Throwable err, String evidenceFileId) {
        try {
            StringBuilder sb = new StringBuilder();
            Throwable cur = err;
            int depth = 0;
            while (cur != null && depth < 6) {
                sb.append("[").append(depth).append("] ")
                  .append(cur.getClass().getName()).append(": ")
                  .append(cur.getMessage() == null ? "" : cur.getMessage())
                  .append(" | ");
                depth++;
                cur = cur.getCause();
            }
            java.io.StringWriter sw = new java.io.StringWriter();
            err.printStackTrace(new java.io.PrintWriter(sw));
            String stack = sw.toString();
            if (stack.length() > 4000) stack = stack.substring(0, 4000);
            java.nio.file.Files.writeString(
                java.nio.file.Paths.get("C:\\Users\\Arfax\\Desktop\\plataforma inmubles\\debug-93290f.log"),
                "{\"sessionId\":\"93290f\",\"hypothesisId\":\"" + hypothesisId + "\",\"location\":\"" + location + "\",\"message\":\"EXCEPTION at submitQuote\",\"data\":{\"evidenceFileIdLen\":" + (evidenceFileId == null ? 0 : evidenceFileId.length()) + ",\"chain\":\"" + dbgQuoteSafe(sb.toString()) + "\",\"stack\":\"" + dbgQuoteSafe(stack) + "\"},\"timestamp\":" + System.currentTimeMillis() + "}\n",
                java.nio.charset.StandardCharsets.UTF_8,
                java.nio.file.StandardOpenOption.APPEND,
                java.nio.file.StandardOpenOption.CREATE);
        } catch (Exception _dbgIgnored) {}
    }
    // #endregion

    // ── CLABE ────────────────────────────────────────────────────────────────

    @GetMapping("/bank-account")
    public ResponseEntity<AgentBankAccountEntity> getBankAccount() {
        return bankAccountService.getMine().map(ResponseEntity::ok).orElse(ResponseEntity.noContent().build());
    }

    @PutMapping("/bank-account")
    public ResponseEntity<AgentBankAccountEntity> upsertBankAccount(@RequestBody Map<String, String> body) {
        return ResponseEntity.ok(bankAccountService.upsertMine(
                body.get("clabe"), body.get("bankName"), body.get("accountHolder")));
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private Map<String, Object> buildTicketView(MaintenanceTicketEntity t, AgentNotificationChainEntity link) {
        PropertyEntity p = propertyRepository.findById(t.getPropertyId()).orElse(null);
        UserEntity owner = userRepository.findById(t.getOwnerId()).orElse(null);
        Map<String, Object> m = new HashMap<>();
        m.put("id", t.getId());
        m.put("title", t.getTitle());
        m.put("description", t.getDescription());
        m.put("urgency", t.getUrgency());
        m.put("status", t.getStatus());
        m.put("createdAt", t.getCreatedAt());
        m.put("providerAcceptedAt", t.getProviderAcceptedAt());
        m.put("resolvedAt", t.getResolvedAt());
        m.put("photoFileIdsJson", t.getPhotoFileIdsJson());
        m.put("propertyId", t.getPropertyId());
        m.put("propertyName", p != null ? p.getName() : null);
        m.put("propertyAddress", p != null ? p.getAddress() : null);
        m.put("ownerId", t.getOwnerId());
        m.put("ownerName", owner != null ? owner.getName() : null);
        m.put("ownerEmail", owner != null ? owner.getContactEmail() : null);
        m.put("ownerPhone", owner != null ? owner.getPhone() : null);
        if (link != null) {
            Map<String, Object> inv = new HashMap<>();
            inv.put("linkId", link.getId());
            inv.put("notifiedAt", link.getNotifiedAt());
            inv.put("expiresAt", link.getExpiresAt());
            inv.put("priorityOrder", link.getPriorityOrder());
            m.put("invitation", inv);
            m.put("expiresInHours", hoursUntil(link.getExpiresAt()));
        }
        return m;
    }

    private Long hoursUntil(LocalDateTime target) {
        if (target == null) return null;
        long mins = java.time.Duration.between(LocalDateTime.now(), target).toMinutes();
        return Math.max(0L, mins / 60L);
    }

    private boolean isClosedStatus(String status) {
        return MaintenanceTicketEntity.STATUS_COMPLETED.equals(status)
                || MaintenanceTicketEntity.STATUS_CANCELLED.equals(status)
                || MaintenanceTicketEntity.STATUS_REJECTED_BY_OWNER.equals(status);
    }

    private String currentProviderId() {
        String email = SecurityContextHolder.getContext().getAuthentication().getName();
        UserEntity u = userRepository.findByLoginIdentifier(email).orElseThrow(
                () -> new SecurityException("Usuario no encontrado"));
        if (u.getRole() != Role.MAINTENANCE_PROVIDER) {
            throw new SecurityException("Acción reservada al proveedor de mantenimiento.");
        }
        return u.getId();
    }

    // #region agent log
    private static final java.nio.file.Path DEBUG_LOG_PATH =
            java.nio.file.Path.of("..", "debug-93290f.log").toAbsolutePath().normalize();
    private static void writeDebug(String hypothesisId, String msg, java.util.Map<String, Object> data) {
        try {
            StringBuilder sb = new StringBuilder();
            sb.append("{\"sessionId\":\"93290f\",\"hypothesisId\":\"").append(hypothesisId)
              .append("\",\"location\":\"MaintenanceProviderWorkflowController.java\",\"message\":\"")
              .append(msg.replace("\"", "\\\"")).append("\",\"timestamp\":")
              .append(System.currentTimeMillis()).append(",\"data\":{");
            boolean first = true;
            for (java.util.Map.Entry<String, Object> e : data.entrySet()) {
                if (!first) sb.append(",");
                first = false;
                sb.append("\"").append(e.getKey()).append("\":");
                Object v = e.getValue();
                if (v == null) sb.append("null");
                else if (v instanceof Number || v instanceof Boolean) sb.append(v);
                else sb.append("\"").append(String.valueOf(v).replace("\\", "\\\\").replace("\"", "\\\"")).append("\"");
            }
            sb.append("}}\n");
            java.nio.file.Files.writeString(DEBUG_LOG_PATH, sb.toString(),
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.APPEND);
        } catch (Exception ignore) {}
    }
    // #endregion
}
