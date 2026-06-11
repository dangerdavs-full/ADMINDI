-- V57 — Tipificación de comprobantes de pago y sistema de intentos.
--
-- Extiende transfer_proof_submissions para soportar DOS flujos de pago:
--   1. SPEI (transferencia bancaria) — validado automáticamente contra Banxico CEP.
--   2. CASH (efectivo) — validación manual del dueño con ventana de 120 horas.
--
-- Sistema de intentos por factura:
--   - SPEI: máximo 3 submissions sin resultado VALIDATED. Al 3er fallo el
--     inquilino DEBE capturar datos manualmente en vez de volver a subir foto.
--   - CASH: máximo 3 submissions PENDING_OWNER_VALIDATION al mismo tiempo
--     (rotativo — si el dueño valida una, el inquilino puede subir otra)
--     Y máximo 3 rechazos/expiraciones históricos como "fallos duros".
--
-- Los estados nuevos del enum TransferProofStatus (Java) son:
--   PENDING_OWNER_VALIDATION  — cash esperando al dueño (120h)
--   VALIDATED_BY_OWNER        — dueño aprobó un cash (distinto de VALIDATED vía CEP)
--   REJECTED_BY_OWNER         — dueño rechazó un cash
--   EXPIRED_AWAITING_OWNER    — cash que expiró sin que el dueño decidiera
-- Los estados existentes (VALIDATED, REJECTED_BY_CEP, INCOMPLETE_DATA, RECEIVED,
-- REJECTED) se mantienen para SPEI tal cual.

BEGIN;

-- ─────────────────────────────────────────────────────────────────────────────
-- 1) payment_type: SPEI (default — back-compat con filas históricas) o CASH.
-- ─────────────────────────────────────────────────────────────────────────────
ALTER TABLE transfer_proof_submissions
  ADD COLUMN IF NOT EXISTS payment_type VARCHAR(10) NOT NULL DEFAULT 'SPEI';

-- Constraint de valores válidos (defensa en BD, no solo en Java).
ALTER TABLE transfer_proof_submissions
  DROP CONSTRAINT IF EXISTS ck_transfer_proof_payment_type;
ALTER TABLE transfer_proof_submissions
  ADD CONSTRAINT ck_transfer_proof_payment_type
  CHECK (payment_type IN ('SPEI', 'CASH'));

-- ─────────────────────────────────────────────────────────────────────────────
-- 2) attempt_number: 1, 2 o 3 (auto-calculado por LedgerService al submit).
-- ─────────────────────────────────────────────────────────────────────────────
ALTER TABLE transfer_proof_submissions
  ADD COLUMN IF NOT EXISTS attempt_number INT NOT NULL DEFAULT 1;

-- ─────────────────────────────────────────────────────────────────────────────
-- 3) expires_at: solo aplica a CASH. Fecha/hora límite para que el dueño
--    valide. El scheduler CashProofExpirationScheduler marca EXPIRED_AWAITING_OWNER
--    cuando expires_at < NOW() y status sigue en PENDING_OWNER_VALIDATION.
-- ─────────────────────────────────────────────────────────────────────────────
ALTER TABLE transfer_proof_submissions
  ADD COLUMN IF NOT EXISTS expires_at TIMESTAMP;

-- Índice parcial para que el scheduler encuentre rápido los CASH por vencer.
CREATE INDEX IF NOT EXISTS idx_transfer_proof_cash_expiring
  ON transfer_proof_submissions (expires_at)
  WHERE payment_type = 'CASH' AND status = 'PENDING_OWNER_VALIDATION';

-- ─────────────────────────────────────────────────────────────────────────────
-- 4) owner_validation_notes: motivo o notas del dueño al aprobar/rechazar CASH.
--    Útil para la auditoría y para que el inquilino sepa por qué se rechazó.
-- ─────────────────────────────────────────────────────────────────────────────
ALTER TABLE transfer_proof_submissions
  ADD COLUMN IF NOT EXISTS owner_validation_notes TEXT;

-- ─────────────────────────────────────────────────────────────────────────────
-- 5) Índice para el contador de intentos por factura+tipo (hot path en submit).
-- ─────────────────────────────────────────────────────────────────────────────
CREATE INDEX IF NOT EXISTS idx_transfer_proof_invoice_type_status
  ON transfer_proof_submissions (invoice_id, payment_type, status);

-- ─────────────────────────────────────────────────────────────────────────────
-- 6) Comentarios documentales sobre la tabla.
-- ─────────────────────────────────────────────────────────────────────────────
COMMENT ON COLUMN transfer_proof_submissions.payment_type IS
  'V57: SPEI (validación automática Banxico CEP) o CASH (validación manual del dueño con ventana de 120h).';
COMMENT ON COLUMN transfer_proof_submissions.attempt_number IS
  'V57: 1..3. Al 3er fallo SPEI sin validación CEP, el inquilino debe capturar datos manualmente. '
  'Para CASH es el # de submission pendiente o fallida histórica.';
COMMENT ON COLUMN transfer_proof_submissions.expires_at IS
  'V57: solo aplica a CASH. Momento en que el comprobante se marca EXPIRED_AWAITING_OWNER si el dueño no validó. '
  'Default: submitted_at + 120 horas (5 días).';
COMMENT ON COLUMN transfer_proof_submissions.owner_validation_notes IS
  'V57: notas o motivo del dueño al aprobar/rechazar un comprobante CASH. Visible al inquilino en caso de rechazo.';

COMMIT;
