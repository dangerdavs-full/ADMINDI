package com.admindi.backend.controller;

import com.admindi.backend.model.CommercialActivityEntity;
import com.admindi.backend.model.ExpenseEntity;
import com.admindi.backend.service.CommercialActivityService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.Map;

@RestController
@RequestMapping("/api/commercial-activity")
public class CommercialActivityController {

    private final CommercialActivityService commercialActivityService;

    @Autowired
    public CommercialActivityController(CommercialActivityService commercialActivityService) {
        this.commercialActivityService = commercialActivityService;
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('REAL_ESTATE_AGENT','PROPERTY_ADMIN')")
    public ResponseEntity<CommercialActivityEntity> create(@RequestBody Map<String, Object> body) {
        String vacancyId = (String) body.get("vacancyId");
        String activityType = body.get("activityType") != null ? body.get("activityType").toString() : "VISIT";
        String notes = body.get("notes") != null ? body.get("notes").toString() : null;
        BigDecimal commission = body.get("commissionAmount") != null ? new BigDecimal(body.get("commissionAmount").toString()) : null;
        String evidenceFileId = body.get("evidenceFileId") != null ? body.get("evidenceFileId").toString() : null;
        return ResponseEntity.ok(commercialActivityService.logActivity(vacancyId, activityType, notes, commission, evidenceFileId));
    }

    @PostMapping("/{id}/commission-decision")
    @PreAuthorize("hasAnyRole('OWNER','PROPERTY_ADMIN')")
    public ResponseEntity<CommercialActivityEntity> commissionDecision(@PathVariable String id, @RequestBody Map<String, Object> body) {
        boolean approve = Boolean.TRUE.equals(body.get("approve"));
        return ResponseEntity.ok(commercialActivityService.decideCommission(id, approve));
    }

    @PostMapping("/expenses/{expenseId}/commission-payment")
    @PreAuthorize("hasAnyRole('OWNER','PROPERTY_ADMIN')")
    public ResponseEntity<ExpenseEntity> commissionPayment(@PathVariable String expenseId, @RequestBody Map<String, Object> body) {
        BigDecimal amount = new BigDecimal(body.get("amount").toString());
        String method = body.get("paymentMethod") != null ? body.get("paymentMethod").toString() : "OTHER";
        return ResponseEntity.ok(commercialActivityService.recordCommercialCommissionPayment(expenseId, amount, method));
    }

    @PostMapping("/expenses/{expenseId}/agent-confirm")
    @PreAuthorize("hasRole('REAL_ESTATE_AGENT')")
    public ResponseEntity<ExpenseEntity> agentConfirm(@PathVariable String expenseId, @RequestBody Map<String, Object> body) {
        String outcome = body.get("outcome") != null ? body.get("outcome").toString() : "CONFIRM";
        String note = body.get("note") != null ? body.get("note").toString() : null;
        return ResponseEntity.ok(commercialActivityService.agentConfirmCommercialPayment(expenseId, outcome, note));
    }
}