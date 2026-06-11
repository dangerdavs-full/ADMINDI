package com.admindi.backend.repository;

import com.admindi.backend.model.TransferProofSubmission;
import com.admindi.backend.model.TransferProofStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface TransferProofSubmissionRepository extends JpaRepository<TransferProofSubmission, String> {
    List<TransferProofSubmission> findByInvoiceId(String invoiceId);
    List<TransferProofSubmission> findByOwnerId(String ownerId);
    List<TransferProofSubmission> findByOwnerIdAndStatus(String ownerId, TransferProofStatus status);
    List<TransferProofSubmission> findByTenantProfileId(String tenantProfileId);

    // ── V57 — Contador de intentos por factura + tipo de pago ────────────

    /**
     * Todas las submissions de un tipo para una factura (para calcular intentos).
     */
    List<TransferProofSubmission> findByInvoiceIdAndPaymentType(String invoiceId, String paymentType);

    /**
     * Conteo de submissions de un tipo específico para una factura. Usado por
     * LedgerService para calcular {@code attempt_number} del próximo intento.
     */
    long countByInvoiceIdAndPaymentType(String invoiceId, String paymentType);

    // ── V57/V59 — Expiración de comprobantes PENDING_OWNER_VALIDATION ────
    //
    // V59 — Antes filtraba por payment_type='CASH' y dejaba zombis los SPEI
    // que caen en PENDING_OWNER_VALIDATION cuando Banxico no está disponible
    // o cuando el dueño no tiene CLABE configurada. Ahora el scheduler expira
    // cualquier proof en PENDING_OWNER_VALIDATION cuyo expires_at ya pasó,
    // independientemente del paymentType.

    /**
     * Proofs en PENDING_OWNER_VALIDATION que ya vencieron su ventana y siguen
     * sin decisión del dueño. Aplica a CASH y a SPEI que cayeron a validación
     * manual (Banxico caído / dueño sin CLABE). El scheduler los marca
     * EXPIRED_AWAITING_OWNER.
     */
    @Query("SELECT p FROM TransferProofSubmission p " +
           "WHERE p.status = com.admindi.backend.model.TransferProofStatus.PENDING_OWNER_VALIDATION " +
           "AND p.expiresAt IS NOT NULL " +
           "AND p.expiresAt < :cutoff")
    List<TransferProofSubmission> findExpiredPendingOwnerValidation(@Param("cutoff") LocalDateTime cutoff);

    // ── V57/V59 — Panel dueño: comprobantes pendientes de validación ──────

    /**
     * Proofs PENDING_OWNER_VALIDATION pertenecientes a un dueño, sin filtrar
     * por paymentType. Incluye tanto CASH (flujo normal) como SPEI que cayó a
     * validación manual (Banxico no disponible o dueño sin CLABE). Orden por
     * expires_at ascendente (los que vencen primero, arriba) con NULL al
     * final como red de seguridad.
     */
    @Query("SELECT p FROM TransferProofSubmission p " +
           "WHERE p.ownerId = :ownerId " +
           "AND p.status = com.admindi.backend.model.TransferProofStatus.PENDING_OWNER_VALIDATION " +
           "ORDER BY p.expiresAt ASC NULLS LAST")
    List<TransferProofSubmission> findPendingOwnerValidationForOwner(@Param("ownerId") String ownerId);

    /**
     * V58.1 — Scheduler CASH: lista los proofs que quedaron en un estado
     * específico con reviewedAt reciente. Usado para notificar solo a los que
     * realmente transicionaron (y evitar notificaciones fantasma).
     */
    List<TransferProofSubmission> findByStatusAndReviewedAtAfter(
            TransferProofStatus status, java.time.LocalDateTime cutoff);

    // ── V58 — Conteo mensual de submissions SPEI con capture_method ──────

    /**
     * Cuenta cuántas submissions del inquilino de un tipo (captureMethod) tiene
     * entre {@code from} (inclusive) y {@code until} (exclusive). Usado por la
     * política mensual (AI_OCR: 6/mes, MANUAL: ilimitado).
     *
     * <p>V58.1 — Excluye {@code PENDING_OWNER_VALIDATION} y
     * {@code EXPIRED_AWAITING_OWNER} porque esos estados significan que Banxico
     * o el flujo falló por causas ajenas al inquilino (servicio caído, dueño sin
     * CLABE). No debería consumir su cuota mensual por eso.
     *
     * <p>Se agrupa por {@code tenantProfileId} porque un mismo inquilino puede
     * tener varias facturas en el mes y el límite es global del user.
     */
    @Query("SELECT COUNT(p) FROM TransferProofSubmission p " +
           "WHERE p.tenantProfileId = :tenantProfileId " +
           "AND p.paymentType = 'SPEI' " +
           "AND p.captureMethod = :captureMethod " +
           "AND p.submittedAt >= :from " +
           "AND p.submittedAt < :until " +
           "AND p.status NOT IN (" +
           "    com.admindi.backend.model.TransferProofStatus.PENDING_OWNER_VALIDATION," +
           "    com.admindi.backend.model.TransferProofStatus.EXPIRED_AWAITING_OWNER" +
           ")")
    long countSpeiSubmissionsInWindow(
            @Param("tenantProfileId") String tenantProfileId,
            @Param("captureMethod") String captureMethod,
            @Param("from") java.time.LocalDateTime from,
            @Param("until") java.time.LocalDateTime until);

    // ── Perf — Detección de duplicados por clave de rastreo ──────────────

    /**
     * ¿Existe ya un proof VALIDATED / VALIDATED_BY_OWNER con esta clave de
     * rastreo dentro de la organización del dueño? Reemplaza el patrón
     * findByOwnerId().stream() que cargaba todos los proofs del dueño en
     * memoria por cada envío de comprobante (hot path web + WhatsApp).
     * Respaldado por índice funcional (V70).
     */
    @Query("SELECT COUNT(p) > 0 FROM TransferProofSubmission p " +
           "WHERE p.ownerId = :ownerId " +
           "AND LOWER(p.claveRastreo) = LOWER(:claveRastreo) " +
           "AND p.status IN (" +
           "    com.admindi.backend.model.TransferProofStatus.VALIDATED," +
           "    com.admindi.backend.model.TransferProofStatus.VALIDATED_BY_OWNER" +
           ")")
    boolean existsValidatedDuplicateClaveRastreo(
            @Param("ownerId") String ownerId,
            @Param("claveRastreo") String claveRastreo);
}
