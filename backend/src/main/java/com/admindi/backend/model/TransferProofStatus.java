package com.admindi.backend.model;

/**
 * Estados del comprobante de pago (SPEI o CASH).
 *
 * <h3>Flujo SPEI (payment_type=SPEI)</h3>
 * <pre>
 *   RECEIVED          transitorio mientras se llama a Banxico CEP
 *   VALIDATED         CEP confirmó → auto-confirma pago
 *   INCOMPLETE_DATA   faltan campos; inquilino puede completar y reintentar sin consumir intento
 *   REJECTED_BY_CEP   CEP rechazó con todos los campos presentes → consume intento
 *   REJECTED          override manual del dueño (legacy SPEI)
 * </pre>
 *
 * <h3>Flujo CASH (payment_type=CASH, V57+)</h3>
 * <pre>
 *   PENDING_OWNER_VALIDATION   esperando al dueño; expires_at = submitted+120h
 *   VALIDATED_BY_OWNER         dueño aprobó → auto-confirma pago, archiva comprobante
 *   REJECTED_BY_OWNER          dueño rechazó con owner_validation_notes → consume intento
 *   EXPIRED_AWAITING_OWNER     pasaron 120h sin decisión → consume intento
 * </pre>
 *
 * <p>Los estados originales (RECEIVED, INCOMPLETE_DATA, VALIDATED, REJECTED,
 * REJECTED_BY_CEP) se mantienen tal cual para back-compat con el flujo SPEI.
 */
public enum TransferProofStatus {

    // ── SPEI ──────────────────────────────────────────────────────────────
    RECEIVED,
    INCOMPLETE_DATA,
    VALIDATED,
    REJECTED,           // Manual override rejection (legacy)
    REJECTED_BY_CEP,    // Automatic CEP rejection

    // ── CASH (V57) ─────────────────────────────────────────────────────────
    /** Comprobante de efectivo esperando validación del dueño (120h). */
    PENDING_OWNER_VALIDATION,

    /** Dueño aprobó un comprobante de efectivo. Equivalente a VALIDATED pero
     *  vía decisión humana en vez de CEP. */
    VALIDATED_BY_OWNER,

    /** Dueño rechazó un comprobante de efectivo con motivo en owner_validation_notes. */
    REJECTED_BY_OWNER,

    /** Pasaron 120h sin decisión del dueño. El inquilino puede volver a subir
     *  si no agotó el límite de 3 fallos. */
    EXPIRED_AWAITING_OWNER
}
