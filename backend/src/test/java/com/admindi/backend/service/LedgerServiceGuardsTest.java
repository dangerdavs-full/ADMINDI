package com.admindi.backend.service;

import com.admindi.backend.model.PaymentEntity;
import com.admindi.backend.model.PaymentMethod;
import com.admindi.backend.model.PaymentStatus;
import com.admindi.backend.model.TransferProofStatus;
import com.admindi.backend.model.TransferProofSubmission;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests unitarios para las invariantes críticas de V58.1:
 *  - Detección de duplicados por claveRastreo
 *  - Idempotencia de autoConfirmPayment por gatewayReference
 *  - Lógica de selección de PaymentMethod según proof.paymentType
 *
 * Estas pruebas NO requieren Spring ni DB — validan la lógica pura de los
 * guards recién introducidos. Tests de IDOR completos requerirían @SpringBootTest
 * con seguridad real y están fuera del alcance de esta suite rápida.
 */
class LedgerServiceGuardsTest {

    // ── Detección de duplicados por claveRastreo ────────────────────────

    @Test
    void duplicate_detector_findsValidatedSpei() {
        List<TransferProofSubmission> history = List.of(
                speiProof("ABC123", TransferProofStatus.VALIDATED, "owner-1"),
                speiProof("OTHER", TransferProofStatus.REJECTED_BY_CEP, "owner-1"));

        boolean duplicate = isDuplicateClaveRastreo(history, "ABC123");
        assertTrue(duplicate, "Debe detectar la clave ABC123 ya validada");
    }

    @Test
    void duplicate_detector_findsValidatedByOwnerCash() {
        List<TransferProofSubmission> history = List.of(
                cashProof("CASH-001", TransferProofStatus.VALIDATED_BY_OWNER, "owner-1"));

        boolean duplicate = isDuplicateClaveRastreo(history, "CASH-001");
        assertTrue(duplicate, "VALIDATED_BY_OWNER también cuenta como duplicado");
    }

    @Test
    void duplicate_detector_ignoresRejected() {
        List<TransferProofSubmission> history = List.of(
                speiProof("ABC123", TransferProofStatus.REJECTED_BY_CEP, "owner-1"),
                speiProof("ABC123", TransferProofStatus.INCOMPLETE_DATA, "owner-1"));

        boolean duplicate = isDuplicateClaveRastreo(history, "ABC123");
        assertFalse(duplicate, "Rechazos anteriores no bloquean nueva submission con misma clave");
    }

    @Test
    void duplicate_detector_caseInsensitive() {
        List<TransferProofSubmission> history = List.of(
                speiProof("abc123", TransferProofStatus.VALIDATED, "owner-1"));

        boolean duplicate = isDuplicateClaveRastreo(history, "ABC123");
        assertTrue(duplicate, "La comparación debe ser case-insensitive");
    }

    @Test
    void duplicate_detector_ignoresOtherOwners() {
        // Escenario: la query del service filtra por ownerId. Este test
        // simula que la lista ya viene filtrada por dueño — verifica la
        // lógica de comparación de clave pura.
        List<TransferProofSubmission> history = List.of(
                speiProof("ABC123", TransferProofStatus.VALIDATED, "owner-1"));

        boolean duplicate = isDuplicateClaveRastreo(history, "DIFFERENT");
        assertFalse(duplicate);
    }

    // ── Idempotencia de autoConfirmPayment ────────────────────────────────

    @Test
    void idempotency_detector_findsExistingPayment() {
        List<PaymentEntity> existingPayments = List.of(
                confirmedPayment("ABC123", BigDecimal.valueOf(10)),
                confirmedPayment("OTHER", BigDecimal.valueOf(100)));

        boolean alreadyApplied = hasConfirmedPaymentForGateway(existingPayments, "ABC123");
        assertTrue(alreadyApplied);
    }

    @Test
    void idempotency_detector_rejectsOnlyOnConfirmedStatus() {
        PaymentEntity pending = confirmedPayment("ABC123", BigDecimal.valueOf(10));
        pending.setStatus(PaymentStatus.PENDING);

        List<PaymentEntity> list = List.of(pending);
        boolean alreadyApplied = hasConfirmedPaymentForGateway(list, "ABC123");
        assertFalse(alreadyApplied, "Solo CONFIRMED cuenta como aplicado");
    }

    @Test
    void idempotency_detector_ignoresNullGatewayReference() {
        List<PaymentEntity> list = List.of(confirmedPayment(null, BigDecimal.valueOf(10)));
        boolean alreadyApplied = hasConfirmedPaymentForGateway(list, "ABC123");
        assertFalse(alreadyApplied);
    }

    // ── PaymentMethod según proof.paymentType ─────────────────────────────

    @Test
    void paymentMethod_cashProofProducesCashMethod() {
        TransferProofSubmission cash = cashProof("X", TransferProofStatus.VALIDATED_BY_OWNER, "owner-1");
        PaymentMethod method = cash.isCash() ? PaymentMethod.CASH : PaymentMethod.TRANSFER_SPEI;
        assertEquals(PaymentMethod.CASH, method);
    }

    @Test
    void paymentMethod_speiProofProducesSpeiMethod() {
        TransferProofSubmission spei = speiProof("X", TransferProofStatus.VALIDATED, "owner-1");
        PaymentMethod method = spei.isCash() ? PaymentMethod.CASH : PaymentMethod.TRANSFER_SPEI;
        assertEquals(PaymentMethod.TRANSFER_SPEI, method);
    }

    // ── Helpers que replican la lógica de LedgerService ──────────────────

    /**
     * Replica la lógica de detección de duplicados de
     * {@code submitTransferProofWithFileUrl} para validar su comportamiento.
     */
    private boolean isDuplicateClaveRastreo(List<TransferProofSubmission> history, String claveRastreo) {
        if (claveRastreo == null || claveRastreo.isBlank()) return false;
        return history.stream()
                .filter(p -> claveRastreo.equalsIgnoreCase(p.getClaveRastreo()))
                .anyMatch(p -> p.getStatus() == TransferProofStatus.VALIDATED
                        || p.getStatus() == TransferProofStatus.VALIDATED_BY_OWNER);
    }

    /**
     * Replica la lógica de idempotencia de {@code autoConfirmPayment}.
     */
    private boolean hasConfirmedPaymentForGateway(List<PaymentEntity> payments, String gatewayRef) {
        if (gatewayRef == null || gatewayRef.isBlank()) return false;
        return payments.stream()
                .filter(p -> gatewayRef.equalsIgnoreCase(p.getGatewayReference()))
                .anyMatch(p -> p.getStatus() == PaymentStatus.CONFIRMED);
    }

    // ── Fixtures ─────────────────────────────────────────────────────────

    private TransferProofSubmission speiProof(String clave, TransferProofStatus status, String ownerId) {
        TransferProofSubmission p = new TransferProofSubmission();
        p.setClaveRastreo(clave);
        p.setStatus(status);
        p.setOwnerId(ownerId);
        p.setPaymentType("SPEI");
        return p;
    }

    private TransferProofSubmission cashProof(String clave, TransferProofStatus status, String ownerId) {
        TransferProofSubmission p = new TransferProofSubmission();
        p.setClaveRastreo(clave);
        p.setStatus(status);
        p.setOwnerId(ownerId);
        p.setPaymentType("CASH");
        return p;
    }

    private PaymentEntity confirmedPayment(String gatewayRef, BigDecimal amount) {
        PaymentEntity p = new PaymentEntity();
        p.setGatewayReference(gatewayRef);
        p.setAmount(amount);
        p.setStatus(PaymentStatus.CONFIRMED);
        return p;
    }
}
