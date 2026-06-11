package com.admindi.backend.controller;

import com.admindi.backend.model.ExpenseEntity;
import com.admindi.backend.model.MaintenanceQuoteEntity;
import com.admindi.backend.model.MaintenanceTicketEntity;
import com.admindi.backend.service.MaintenanceService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/maintenance")
public class MaintenanceController {

    private final MaintenanceService maintenanceService;

    @Autowired
    public MaintenanceController(MaintenanceService maintenanceService) {
        this.maintenanceService = maintenanceService;
    }

    @PostMapping("/tickets")
    @PreAuthorize("hasRole('TENANT')")
    public ResponseEntity<MaintenanceTicketEntity> createTicket(@RequestBody Map<String, String> body) {
        // #region agent log
        try {
            java.nio.file.Files.writeString(
                java.nio.file.Paths.get("C:\\Users\\Arfax\\Desktop\\plataforma inmubles\\debug-93290f.log"),
                "{\"sessionId\":\"93290f\",\"hypothesisId\":\"H2\",\"location\":\"MaintenanceController.createTicket(LEGACY)\",\"message\":\"POST /api/maintenance/tickets (legacy path) invoked\",\"data\":{\"propertyId\":\"" + dbgSafe(body.get("propertyId")) + "\",\"title\":\"" + dbgSafe(body.get("title")) + "\"},\"timestamp\":" + System.currentTimeMillis() + "}\n",
                java.nio.charset.StandardCharsets.UTF_8,
                java.nio.file.StandardOpenOption.APPEND,
                java.nio.file.StandardOpenOption.CREATE);
        } catch (Exception _dbgIgnored) {}
        // #endregion
        return ResponseEntity.ok(maintenanceService.createTicket(
                body.get("propertyId"), body.get("title"), body.get("description"), body.get("urgency"),
                body.get("tenantProfileId")));
    }

    @PostMapping("/tickets/{ticketId}/accept")
    @PreAuthorize("hasRole('MAINTENANCE_PROVIDER')")
    public ResponseEntity<MaintenanceTicketEntity> acceptTicket(@PathVariable String ticketId) {
        return ResponseEntity.ok(maintenanceService.acceptTicket(ticketId));
    }

    @GetMapping("/tickets/my")
    @PreAuthorize("hasRole('MAINTENANCE_PROVIDER')")
    public ResponseEntity<List<MaintenanceTicketEntity>> listMyTickets() {
        return ResponseEntity.ok(maintenanceService.listTicketsForCurrentProvider());
    }

    @GetMapping("/tickets")
    @PreAuthorize("hasAnyRole('OWNER','PROPERTY_ADMIN','ACCOUNTANT')")
    public ResponseEntity<List<MaintenanceTicketEntity>> listOrgTickets(
            @RequestParam(required = false) String propertyId) {
        // #region agent log
        try {
            java.nio.file.Files.writeString(
                java.nio.file.Paths.get("C:\\Users\\Arfax\\Desktop\\plataforma inmubles\\debug-93290f.log"),
                "{\"sessionId\":\"93290f\",\"hypothesisId\":\"H3\",\"location\":\"MaintenanceController.listOrgTickets\",\"message\":\"GET /api/maintenance/tickets invoked\",\"data\":{\"propertyId\":\"" + dbgSafe(propertyId) + "\"},\"timestamp\":" + System.currentTimeMillis() + "}\n",
                java.nio.charset.StandardCharsets.UTF_8,
                java.nio.file.StandardOpenOption.APPEND,
                java.nio.file.StandardOpenOption.CREATE);
        } catch (Exception _dbgIgnored) {}
        // #endregion
        return ResponseEntity.ok(maintenanceService.listTicketsForOrganization(propertyId));
    }

    // #region agent log
    private static String dbgSafe(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "'");
    }
    // #endregion

    @PostMapping("/quotes")
    @PreAuthorize("hasRole('MAINTENANCE_PROVIDER')")
    public ResponseEntity<MaintenanceQuoteEntity> submitQuote(@RequestBody Map<String, Object> body) {
        BigDecimal amt = new BigDecimal(body.get("amount").toString());
        String evidence = body.get("evidenceFileId") != null ? body.get("evidenceFileId").toString() : null;
        String provider = body.get("providerId") != null ? body.get("providerId").toString() : null;
        String visitNotes = body.get("visitNotes") != null ? body.get("visitNotes").toString() : null;
        return ResponseEntity.ok(maintenanceService.submitQuote(
                body.get("ticketId").toString(), amt,
                body.get("description") != null ? body.get("description").toString() : null,
                evidence, provider, visitNotes));
    }

    @PostMapping("/quotes/{quoteId}/approve")
    @PreAuthorize("hasAnyRole('OWNER','PROPERTY_ADMIN')")
    public ResponseEntity<Void> approveQuote(@PathVariable String quoteId) {
        maintenanceService.approveQuote(quoteId);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/quotes/{quoteId}/reject")
    @PreAuthorize("hasAnyRole('OWNER','PROPERTY_ADMIN')")
    public ResponseEntity<Void> rejectQuote(@PathVariable String quoteId) {
        maintenanceService.rejectQuote(quoteId);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/expenses/{expenseId}/payments")
    @PreAuthorize("hasAnyRole('OWNER','PROPERTY_ADMIN')")
    public ResponseEntity<ExpenseEntity> recordPayment(
            @PathVariable String expenseId,
            @RequestBody Map<String, Object> body) {
        BigDecimal amt = new BigDecimal(body.get("amount").toString());
        String method = body.get("paymentMethod") != null ? body.get("paymentMethod").toString() : "OTHER";
        return ResponseEntity.ok(maintenanceService.recordExpensePayment(expenseId, amt, method));
    }

    @PutMapping("/expenses/{expenseId}/provider-confirmation")
    @PreAuthorize("hasRole('MAINTENANCE_PROVIDER')")
    public ResponseEntity<ExpenseEntity> providerConfirmation(
            @PathVariable String expenseId,
            @RequestBody Map<String, String> body) {
        return ResponseEntity.ok(maintenanceService.providerConfirmExpense(
                expenseId,
                body.get("outcome"),
                body.get("note")));
    }

    /** Compat: liquida el saldo pendiente en un solo pago. */
    @PostMapping("/payments")
    @PreAuthorize("hasAnyRole('OWNER','PROPERTY_ADMIN')")
    public ResponseEntity<Void> legacyPayFull(@RequestBody Map<String, String> body) {
        maintenanceService.payExpense(body.get("expenseId"));
        return ResponseEntity.ok().build();
    }
}
