package com.admindi.backend.controller;

import com.admindi.backend.ai.AiAccountingService;
import com.admindi.backend.model.ExpenseEntity;
import com.admindi.backend.model.PaymentEntity;
import com.admindi.backend.repository.ExpenseRepository;
import com.admindi.backend.repository.PaymentRepository;
import com.admindi.backend.security.TenantContext;
import com.admindi.backend.repository.UserRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;

/**
 * Endpoints para que el dueño / contador revise y ajuste la categorización
 * asistida por IA (V56).
 *
 * Reglas:
 *  - OWNER o ACCOUNTANT dentro del propio contexto pueden editar la categoría.
 *  - Al guardar manualmente, se marca {@code ai_reviewed_by_user=true} para
 *    que el scheduler mensual NO sobreescriba la decisión humana.
 *  - Se puede re-ejecutar la IA manualmente si los metadatos del pago/gasto
 *    cambiaron.
 */
@RestController
@RequestMapping("/api/ai-accounting")
public class AiAccountingController {

    private final PaymentRepository paymentRepo;
    private final ExpenseRepository expenseRepo;
    private final UserRepository userRepo;
    private final AiAccountingService aiAccounting;

    public AiAccountingController(PaymentRepository paymentRepo,
                                   ExpenseRepository expenseRepo,
                                   UserRepository userRepo,
                                   AiAccountingService aiAccounting) {
        this.paymentRepo = paymentRepo;
        this.expenseRepo = expenseRepo;
        this.userRepo = userRepo;
        this.aiAccounting = aiAccounting;
    }

    @PostMapping("/payments/{id}/category")
    @PreAuthorize("hasAnyRole('OWNER','PROPERTY_ADMIN','ACCOUNTANT')")
    public ResponseEntity<Map<String, Object>> updatePaymentCategory(
            @PathVariable String id,
            @RequestBody Map<String, Object> body) {
        Optional<PaymentEntity> opt = paymentRepo.findById(id);
        if (opt.isEmpty()) return ResponseEntity.notFound().build();
        PaymentEntity p = opt.get();
        enforceOwner(p.getOwnerId());

        if (body.containsKey("aiCategory")) p.setAiCategory(asStr(body.get("aiCategory")));
        if (body.containsKey("aiCfdiUse")) p.setAiCfdiUse(asStr(body.get("aiCfdiUse")));
        if (body.get("aiTaxDeductible") instanceof Boolean b) p.setAiTaxDeductible(b);
        p.setAiReviewedByUser(true);
        if (p.getAiLastRunAt() == null) p.setAiLastRunAt(LocalDateTime.now());
        paymentRepo.save(p);
        return ResponseEntity.ok(Map.of("ok", true));
    }

    @PostMapping("/payments/{id}/recategorize")
    @PreAuthorize("hasAnyRole('OWNER','PROPERTY_ADMIN','ACCOUNTANT')")
    public ResponseEntity<Map<String, Object>> recategorizePayment(@PathVariable String id) {
        Optional<PaymentEntity> opt = paymentRepo.findById(id);
        if (opt.isEmpty()) return ResponseEntity.notFound().build();
        PaymentEntity p = opt.get();
        enforceOwner(p.getOwnerId());
        aiAccounting.categorizePayment(p);
        return ResponseEntity.ok(Map.of(
                "ok", true,
                "aiCategory", p.getAiCategory(),
                "aiCfdiUse", p.getAiCfdiUse(),
                "aiTaxDeductible", p.getAiTaxDeductible(),
                "aiConfidence", p.getAiConfidence()));
    }

    @PostMapping("/expenses/{id}/category")
    @PreAuthorize("hasAnyRole('OWNER','PROPERTY_ADMIN','ACCOUNTANT')")
    public ResponseEntity<Map<String, Object>> updateExpenseCategory(
            @PathVariable String id,
            @RequestBody Map<String, Object> body) {
        Optional<ExpenseEntity> opt = expenseRepo.findById(id);
        if (opt.isEmpty()) return ResponseEntity.notFound().build();
        ExpenseEntity e = opt.get();
        enforceOwner(e.getOwnerId());

        if (body.containsKey("aiCategory")) e.setAiCategory(asStr(body.get("aiCategory")));
        if (body.containsKey("aiCfdiUse")) e.setAiCfdiUse(asStr(body.get("aiCfdiUse")));
        if (body.get("aiTaxDeductible") instanceof Boolean b) e.setAiTaxDeductible(b);
        e.setAiReviewedByUser(true);
        if (e.getAiLastRunAt() == null) e.setAiLastRunAt(LocalDateTime.now());
        expenseRepo.save(e);
        return ResponseEntity.ok(Map.of("ok", true));
    }

    private void enforceOwner(String resourceOwnerId) {
        String ownerId = TenantContext.resolveOwnerId(userRepo);
        if (!ownerId.equals(resourceOwnerId)) {
            throw new RuntimeException("IDOR: Recurso pertenece a otra organización.");
        }
    }

    private String asStr(Object o) {
        if (o == null) return null;
        String s = o.toString().trim();
        return s.isBlank() ? null : s;
    }
}
