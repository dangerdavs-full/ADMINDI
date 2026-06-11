package com.admindi.backend.controller;

import com.admindi.backend.model.MaintenanceQuoteEntity;
import com.admindi.backend.model.MaintenanceTicketEntity;
import com.admindi.backend.model.UserEntity;
import com.admindi.backend.security.TenantContext;
import com.admindi.backend.repository.UserRepository;
import com.admindi.backend.service.FileOwnershipService;
import com.admindi.backend.service.FileStorageService;
import com.admindi.backend.service.MaintenanceWorkflowService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

/**
 * Fase 2 del inquilino: crear tickets de mantenimiento, listar y consultar
 * detalle. Todos los endpoints exigen rol TENANT y aplican IDOR guards a
 * nivel del {@link MaintenanceWorkflowService}.
 *
 * <p>Convive con {@link MaintenanceController} legacy. El flujo preferido
 * desde el panel web del inquilino es este (AWAITING_OWNER_AUTH → authorize
 * → provider → ...).
 */
@RestController
@RequestMapping("/api/tenant/workflow/maintenance")
@PreAuthorize("hasRole('TENANT')")
public class TenantMaintenanceWorkflowController {

    private final MaintenanceWorkflowService workflow;
    private final UserRepository userRepository;
    private final FileStorageService fileStorage;
    private final FileOwnershipService fileOwnership;

    public TenantMaintenanceWorkflowController(MaintenanceWorkflowService workflow,
                                                UserRepository userRepository,
                                                FileStorageService fileStorage,
                                                FileOwnershipService fileOwnership) {
        this.workflow = workflow;
        this.userRepository = userRepository;
        this.fileStorage = fileStorage;
        this.fileOwnership = fileOwnership;
    }

    // ─── Upload de fotos del problema ────────────────────────────────────────
    //
    // Mismo patrón que OwnerWorkflowController#uploadFile: se almacena el
    // archivo, se genera un fileId opaco (path interno), y se registra un
    // claim a nombre del inquilino. El claim se consume cuando
    // createTicket() recibe el fileId en su payload — así un file subido
    // por otro usuario no puede ser referenciado por este flujo.

    @PostMapping(path = "/files/upload", consumes = "multipart/form-data")
    public ResponseEntity<Map<String, String>> uploadFile(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "category", defaultValue = "maintenance-photo") String category) {
        String fileId = fileStorage.store(file, category);
        String actorEmail = SecurityContextHolder.getContext().getAuthentication().getName();
        userRepository.findByLoginIdentifier(actorEmail).ifPresent(u ->
                fileOwnership.registerClaim(fileId, u.getId(), category));
        return ResponseEntity.ok(Map.of("fileId", fileId));
    }

    // ─── Crear ticket ────────────────────────────────────────────────────────

    @PostMapping("/tickets")
    public ResponseEntity<MaintenanceTicketEntity> createTicket(@RequestBody Map<String, Object> body) {
        String ownerId = TenantContext.resolveOwnerId(userRepository);
        String propertyId = (String) body.get("propertyId");
        String tenantProfileId = (String) body.get("tenantProfileId");
        String title = (String) body.get("title");
        String description = (String) body.get("description");
        String urgency = (String) body.get("urgency");
        @SuppressWarnings("unchecked")
        List<String> photoFileIds = (List<String>) body.get("photoFileIds");

        // #region agent log
        try {
            java.nio.file.Files.writeString(
                java.nio.file.Paths.get("C:\\Users\\Arfax\\Desktop\\plataforma inmubles\\debug-93290f.log"),
                "{\"sessionId\":\"93290f\",\"hypothesisId\":\"H2\",\"location\":\"TenantMaintenanceWorkflowController.createTicket\",\"message\":\"POST /api/tenant/workflow/maintenance/tickets invoked\",\"data\":{\"ownerId\":\"" + safeDbgStr(ownerId) + "\",\"propertyId\":\"" + safeDbgStr(propertyId) + "\",\"title\":\"" + safeDbgStr(title) + "\",\"urgency\":\"" + safeDbgStr(urgency) + "\",\"photoCount\":" + (photoFileIds == null ? 0 : photoFileIds.size()) + "},\"timestamp\":" + System.currentTimeMillis() + "}\n",
                java.nio.charset.StandardCharsets.UTF_8,
                java.nio.file.StandardOpenOption.APPEND,
                java.nio.file.StandardOpenOption.CREATE);
        } catch (Exception _dbgIgnored) {}
        // #endregion

        return ResponseEntity.ok(workflow.createTicketWithOwnerAuth(
                ownerId, propertyId, tenantProfileId, title, description, urgency, photoFileIds));
    }

    // ─── Consultas para el panel del inquilino ───────────────────────────────

    /**
     * Lista los tickets del inquilino autenticado. Opcional:
     * {@code tenantProfileId} para filtrar a un expediente específico
     * (útil cuando el inquilino tiene múltiples contratos en la org).
     */
    @GetMapping("/tickets/mine")
    public ResponseEntity<List<MaintenanceTicketEntity>> listMyTickets(
            @RequestParam(value = "tenantProfileId", required = false) String tenantProfileId) {
        // #region agent log
        try {
            String actorEmail = SecurityContextHolder.getContext().getAuthentication().getName();
            UserEntity actor = userRepository.findByLoginIdentifier(actorEmail).orElse(null);
            java.nio.file.Files.writeString(
                java.nio.file.Paths.get("C:\\Users\\Arfax\\Desktop\\plataforma inmubles\\debug-93290f.log"),
                "{\"sessionId\":\"93290f\",\"hypothesisId\":\"H2\",\"location\":\"TenantMaintenanceWorkflowController.listMyTickets\",\"message\":\"GET /api/tenant/workflow/maintenance/tickets/mine invoked\",\"data\":{\"actorRole\":\"" + (actor==null?"":String.valueOf(actor.getRole())) + "\",\"tenantProfileId\":\"" + safeDbgStr(tenantProfileId) + "\"},\"timestamp\":" + System.currentTimeMillis() + "}\n",
                java.nio.charset.StandardCharsets.UTF_8,
                java.nio.file.StandardOpenOption.APPEND,
                java.nio.file.StandardOpenOption.CREATE);
        } catch (Exception _dbgIgnored) {}
        // #endregion
        return ResponseEntity.ok(workflow.listTicketsForTenantUser(tenantProfileId));
    }

    /**
     * Detalle de un ticket específico del inquilino. Protegido por IDOR en
     * el service: si el ticket pertenece a otro tenantProfile se responde 403.
     */
    @GetMapping("/tickets/{ticketId}")
    public ResponseEntity<MaintenanceTicketEntity> getTicketDetail(@PathVariable String ticketId) {
        return ResponseEntity.ok(workflow.getTicketForTenantUser(ticketId));
    }

    /**
     * Lista de cotizaciones asociadas al ticket del inquilino. Se valida
     * propiedad del ticket antes de devolver quotes.
     */
    @GetMapping("/tickets/{ticketId}/quotes")
    public ResponseEntity<List<MaintenanceQuoteEntity>> getTicketQuotes(@PathVariable String ticketId) {
        workflow.getTicketForTenantUser(ticketId); // guard
        return ResponseEntity.ok(workflow.listQuotesByTicket(ticketId));
    }

    // #region agent log
    private static String safeDbgStr(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "'");
    }
    // #endregion
}
