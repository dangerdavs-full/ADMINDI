package com.admindi.backend.service;

import com.admindi.backend.model.TransferProofStatus;
import com.admindi.backend.model.TransferProofSubmission;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests de política de intentos V58:
 *  - SPEI AI_OCR: 6/mes por inquilino
 *  - SPEI MANUAL: ilimitado
 *  - CASH: reglas propias V57 (3 pendientes + 3 fallos)
 */
class PaymentProofAttemptPolicyTest {

    // ── SPEI con IA (AI_OCR, 6/mes) ──

    @Test
    void speiAiOcr_noPriorSubmissions_allowsFirstAttempt() {
        var d = PaymentProofAttemptPolicy.evaluateSpeiAiOcrMonthly(0);
        assertTrue(d.allowed());
        assertEquals(1, d.attemptNumber());
        assertEquals(5, d.attemptsRemaining());
    }

    @Test
    void speiAiOcr_fiveAttempts_allowsSixth() {
        var d = PaymentProofAttemptPolicy.evaluateSpeiAiOcrMonthly(5);
        assertTrue(d.allowed());
        assertEquals(6, d.attemptNumber());
        assertEquals(0, d.attemptsRemaining());
    }

    @Test
    void speiAiOcr_sixAttempts_blocks() {
        var d = PaymentProofAttemptPolicy.evaluateSpeiAiOcrMonthly(6);
        assertFalse(d.allowed());
        assertEquals("spei_ai_monthly_limit_reached", d.denyReason());
    }

    @Test
    void speiAiOcr_manyMore_stillBlocked() {
        var d = PaymentProofAttemptPolicy.evaluateSpeiAiOcrMonthly(100);
        assertFalse(d.allowed());
    }

    // ── SPEI manual (ilimitado) ──

    @Test
    void speiManual_unlimitedEvenWithManyPriorAttempts() {
        var d = PaymentProofAttemptPolicy.evaluateSpeiManualMonthly(50);
        assertTrue(d.allowed());
        assertEquals(51, d.attemptNumber());
        assertEquals(Integer.MAX_VALUE, d.attemptsRemaining(),
                "MANUAL debe ser ilimitado: remaining=MAX_VALUE");
    }

    @Test
    void speiManual_zeroPrior_allowsFirst() {
        var d = PaymentProofAttemptPolicy.evaluateSpeiManualMonthly(0);
        assertTrue(d.allowed());
        assertEquals(1, d.attemptNumber());
        assertEquals(Integer.MAX_VALUE, d.attemptsRemaining());
    }

    // ── CASH (heredado V57) ──

    @Test
    void cash_historyEmpty_allowsFirstAttempt() {
        var d = PaymentProofAttemptPolicy.evaluateCash(List.of());
        assertTrue(d.allowed());
        assertEquals(1, d.attemptNumber());
    }

    @Test
    void cash_threePendingBlocksNewUpload() {
        var history = List.of(
                proof(TransferProofStatus.PENDING_OWNER_VALIDATION, "CASH"),
                proof(TransferProofStatus.PENDING_OWNER_VALIDATION, "CASH"),
                proof(TransferProofStatus.PENDING_OWNER_VALIDATION, "CASH"));
        var d = PaymentProofAttemptPolicy.evaluateCash(history);
        assertFalse(d.allowed());
        assertEquals("cash_too_many_pending", d.denyReason());
    }

    @Test
    void cash_ownerApproves_freesUpSlot() {
        var history = new ArrayList<>(List.of(
                proof(TransferProofStatus.VALIDATED_BY_OWNER, "CASH"),
                proof(TransferProofStatus.PENDING_OWNER_VALIDATION, "CASH"),
                proof(TransferProofStatus.PENDING_OWNER_VALIDATION, "CASH")));
        var d = PaymentProofAttemptPolicy.evaluateCash(history);
        assertTrue(d.allowed(),
                "Si el dueño aprueba uno, el pendientes baja y se libera un slot");
    }

    @Test
    void cash_threeRejects_blocksForever() {
        var history = List.of(
                proof(TransferProofStatus.REJECTED_BY_OWNER, "CASH"),
                proof(TransferProofStatus.REJECTED_BY_OWNER, "CASH"),
                proof(TransferProofStatus.REJECTED_BY_OWNER, "CASH"));
        var d = PaymentProofAttemptPolicy.evaluateCash(history);
        assertFalse(d.allowed());
        assertEquals("cash_max_failures_reached", d.denyReason());
    }

    @Test
    void cash_mixedRejectsAndExpirations_blocks() {
        var history = List.of(
                proof(TransferProofStatus.REJECTED_BY_OWNER, "CASH"),
                proof(TransferProofStatus.EXPIRED_AWAITING_OWNER, "CASH"),
                proof(TransferProofStatus.REJECTED_BY_OWNER, "CASH"));
        var d = PaymentProofAttemptPolicy.evaluateCash(history);
        assertFalse(d.allowed());
        assertEquals("cash_max_failures_reached", d.denyReason());
    }

    @Test
    void cash_validatedDoesNotCountAsFailure() {
        var history = List.of(
                proof(TransferProofStatus.VALIDATED_BY_OWNER, "CASH"),
                proof(TransferProofStatus.VALIDATED_BY_OWNER, "CASH"),
                proof(TransferProofStatus.VALIDATED_BY_OWNER, "CASH"),
                proof(TransferProofStatus.REJECTED_BY_OWNER, "CASH"),
                proof(TransferProofStatus.REJECTED_BY_OWNER, "CASH"));
        var d = PaymentProofAttemptPolicy.evaluateCash(history);
        assertTrue(d.allowed(),
                "Los validados no cuentan como fallo; puede seguir pagando parcial");
    }

    @Test
    void cash_twoPendingOneRejected_stillAllows() {
        var history = List.of(
                proof(TransferProofStatus.PENDING_OWNER_VALIDATION, "CASH"),
                proof(TransferProofStatus.PENDING_OWNER_VALIDATION, "CASH"),
                proof(TransferProofStatus.REJECTED_BY_OWNER, "CASH"));
        var d = PaymentProofAttemptPolicy.evaluateCash(history);
        assertTrue(d.allowed());
    }

    // ── Mensajes amigables ──

    @Test
    void message_speiAiLimit_mentionsManualFallback() {
        String m = PaymentProofAttemptPolicy.userFriendlyDenyMessage("spei_ai_monthly_limit_reached");
        assertTrue(m.toLowerCase().contains("6"), "Debe mencionar el límite de 6");
        assertTrue(m.toLowerCase().contains("manual"), "Debe invitar a captura manual");
        assertTrue(m.toLowerCase().contains("sin límite") || m.toLowerCase().contains("sin limite"));
    }

    @Test
    void message_cashTooManyPending() {
        String m = PaymentProofAttemptPolicy.userFriendlyDenyMessage("cash_too_many_pending");
        assertTrue(m.toLowerCase().contains("3 comprobantes"));
    }

    @Test
    void message_ownerClabeNotConfigured() {
        String m = PaymentProofAttemptPolicy.userFriendlyDenyMessage("owner_clabe_not_configured");
        assertTrue(m.toLowerCase().contains("clabe"));
        assertTrue(m.toLowerCase().contains("manual") || m.toLowerCase().contains("120"));
    }

    @Test
    void message_accountMismatch_clear() {
        String m = PaymentProofAttemptPolicy.userFriendlyDenyMessage("account_mismatch");
        assertTrue(m.toLowerCase().contains("cuenta"));
        assertTrue(m.toLowerCase().contains("arrendador") || m.toLowerCase().contains("dueño"));
    }

    @Test
    void message_nullReason_returnsEmpty() {
        assertEquals("", PaymentProofAttemptPolicy.userFriendlyDenyMessage(null));
    }

    @Test
    void message_unknownReason_fallback() {
        String m = PaymentProofAttemptPolicy.userFriendlyDenyMessage("whatever_unknown");
        assertFalse(m.isBlank());
    }

    // ── helpers ──

    private TransferProofSubmission proof(TransferProofStatus status, String type) {
        TransferProofSubmission p = new TransferProofSubmission();
        p.setStatus(status);
        p.setPaymentType(type);
        return p;
    }
}
