package com.admindi.backend.service;

import com.admindi.backend.model.TransferProofStatus;
import com.admindi.backend.model.TransferProofSubmission;

import java.util.List;

/**
 * Política de intentos para comprobantes de pago.
 *
 * <h3>V58 — Reglas por método de captura</h3>
 *
 * <dl>
 *   <dt>SPEI con captura AI (foto + OCR Claude)</dt>
 *   <dd>Máximo <b>6 intentos por mes calendario</b> por inquilino. Protege el
 *       presupuesto de Claude: cada OCR cuesta tokens. El conteo es global
 *       para el inquilino (suma sus facturas del mes); se resetea el día 1.</dd>
 *
 *   <dt>SPEI con captura manual (datos tecleados)</dt>
 *   <dd><b>Ilimitado</b>. No hay costo de IA; el inquilino puede reintentar
 *       las veces que necesite hasta que Banxico le valide o él desista.</dd>
 *
 *   <dt>CASH (pago en efectivo, V57)</dt>
 *   <dd>Reglas propias por factura: máximo 3 pendientes simultáneos + máximo
 *       3 fallos duros históricos (rechazos + expiraciones). Ver
 *       {@link #evaluateCash}.</dd>
 * </dl>
 *
 * <h3>¿Por qué los pagos parciales no cuentan?</h3>
 * <p>Un SPEI validado o un CASH aprobado representan pagos legítimos, aunque
 * sean parciales. El inquilino puede necesitar varios para liquidar la factura
 * completa — por eso solo las submissions <em>fallidas/en espera</em> consumen
 * intentos.
 */
public final class PaymentProofAttemptPolicy {

    /** Límite mensual de intentos AI_OCR para SPEI (V58). */
    public static final int SPEI_AI_MONTHLY_LIMIT = 6;

    /** Para CASH: máximo pendientes simultáneos y máximo fallos duros (V57). */
    public static final int CASH_MAX_ATTEMPTS = 3;

    private PaymentProofAttemptPolicy() {}

    /** Resultado de evaluar si un inquilino puede subir otro comprobante. */
    public record Decision(
            boolean allowed,
            int attemptNumber,         // número del próximo intento (1..N)
            int attemptsRemaining,     // cuántos le quedan después de éste (o Integer.MAX_VALUE si ilimitado)
            String denyReason          // null si allowed=true
    ) {
        public static Decision allowed(int attemptNumber, int remaining) {
            return new Decision(true, attemptNumber, remaining, null);
        }
        public static Decision denied(String reason) {
            return new Decision(false, 0, 0, reason);
        }
        /** Ilimitado: permitido con remaining=Integer.MAX_VALUE como señal. */
        public static Decision unlimited(int attemptNumber) {
            return new Decision(true, attemptNumber, Integer.MAX_VALUE, null);
        }
    }

    /**
     * Evalúa SPEI con captura AI (foto + OCR). Límite de 6/mes.
     *
     * @param submissionsThisMonth TODAS las submissions AI_OCR SPEI del inquilino
     *                             en el mes calendario actual (cualquier estado).
     */
    public static Decision evaluateSpeiAiOcrMonthly(long submissionsThisMonth) {
        if (submissionsThisMonth >= SPEI_AI_MONTHLY_LIMIT) {
            return Decision.denied("spei_ai_monthly_limit_reached");
        }
        int attemptNumber = (int) submissionsThisMonth + 1;
        int remaining = SPEI_AI_MONTHLY_LIMIT - attemptNumber;
        return Decision.allowed(attemptNumber, remaining);
    }

    /**
     * Evalúa SPEI con captura manual. Ilimitado — siempre permite.
     *
     * @param submissionsThisMonth TODAS las submissions MANUAL SPEI del mes
     *                             (solo se usa para numerar, no para limitar).
     */
    public static Decision evaluateSpeiManualMonthly(long submissionsThisMonth) {
        int attemptNumber = (int) submissionsThisMonth + 1;
        return Decision.unlimited(attemptNumber);
    }

    /**
     * Evalúa si el inquilino puede subir otro comprobante CASH.
     *
     * @param cashSubmissions TODAS las submissions CASH de la factura.
     */
    public static Decision evaluateCash(List<TransferProofSubmission> cashSubmissions) {
        if (cashSubmissions == null) cashSubmissions = List.of();

        long pendientes = cashSubmissions.stream()
                .filter(p -> p.getStatus() == TransferProofStatus.PENDING_OWNER_VALIDATION)
                .count();

        long fallos = cashSubmissions.stream()
                .filter(p -> p.getStatus() == TransferProofStatus.REJECTED_BY_OWNER
                        || p.getStatus() == TransferProofStatus.EXPIRED_AWAITING_OWNER)
                .count();

        if (pendientes >= CASH_MAX_ATTEMPTS) {
            return Decision.denied("cash_too_many_pending");
        }
        if (fallos >= CASH_MAX_ATTEMPTS) {
            return Decision.denied("cash_max_failures_reached");
        }

        int nextAttempt = (int) (pendientes + fallos) + 1;
        int remaining = CASH_MAX_ATTEMPTS - nextAttempt;
        if (remaining < 0) remaining = 0;
        return Decision.allowed(nextAttempt, remaining);
    }

    /** Mensaje amigable para el inquilino según el motivo del deny. */
    public static String userFriendlyDenyMessage(String denyReason) {
        if (denyReason == null) return "";
        return switch (denyReason) {
            case "spei_ai_monthly_limit_reached" -> "Llegaste al límite de " + SPEI_AI_MONTHLY_LIMIT +
                    " validaciones con foto este mes. Puedes seguir capturando los datos manualmente " +
                    "(clave de rastreo, banco emisor, monto y fecha) sin límite.";
            case "cash_too_many_pending" -> "Ya tienes 3 comprobantes de efectivo esperando que tu " +
                    "arrendador los valide. Espera a que los revise para subir más.";
            case "cash_max_failures_reached" -> "Se alcanzó el máximo de 3 rechazos en comprobantes " +
                    "de efectivo. Contacta directamente a tu arrendador.";
            case "owner_clabe_not_configured" -> "Tu arrendador no ha registrado su CLABE para recibir " +
                    "transferencias. Tu comprobante se guardó para validación manual (120 horas).";
            case "account_mismatch" -> "La cuenta receptora de tu transferencia NO corresponde al " +
                    "arrendador al que intentas pagar. Verifica la CLABE antes de volver a intentar.";
            default -> "No puedes subir más comprobantes para esta factura en este momento.";
        };
    }
}
