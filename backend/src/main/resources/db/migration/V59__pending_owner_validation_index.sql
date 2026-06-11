-- V59 — Índice generalizado para comprobantes PENDING_OWNER_VALIDATION.
--
-- CONTEXTO
-- --------
-- En V57 se creó `idx_transfer_proof_cash_expiring` como índice PARCIAL
-- filtrado por `payment_type = 'CASH'`. Eso cubría el flujo original de pago
-- en efectivo pero dejaba fuera a los SPEI que caen a validación manual del
-- dueño por dos causas nuevas (V58):
--   1. Banxico CEP no disponible (HTTP error / red / timeout).
--   2. El dueño no tiene CLABE registrada en `users.clabe`.
-- En ambos casos el proof queda `status='PENDING_OWNER_VALIDATION'` con
-- `payment_type='SPEI'` y `expires_at = submitted_at + 120h`. El índice viejo
-- no los cubría, lo que provocaba:
--   a) El scheduler de expiración (query sobre expires_at) hacía seq scan
--      en volúmenes grandes porque el plan preferido no era aplicable.
--   b) La bandeja del dueño no los encontraba con el query CASH-only.
--
-- CAMBIO
-- ------
-- Este migración:
--   * Dropea el índice `idx_transfer_proof_cash_expiring` (CASH-only) porque
--     queda obsoleto con el nuevo que lo generaliza.
--   * Crea `idx_transfer_proof_pending_owner_validation` — índice PARCIAL sobre
--     `(owner_id, expires_at)` con `WHERE status='PENDING_OWNER_VALIDATION'`.
--     Esto cubre:
--       - La query del scheduler (expires_at < now()).
--       - La query del panel del dueño (owner_id + ordenar por expires_at).
--     Ambos filtros están en la misma clase de índice porque el status es el
--     mismo; PostgreSQL ignora filas fuera del predicado.
--
-- SEGURIDAD / IMPACTO
-- -------------------
-- * El índice es PARCIAL, así que solo indexa filas pendientes de decisión.
--   El almacenamiento sobrante es mínimo.
-- * No hay cambio de datos: solo estructura física. Idempotente.
-- * DROP + CREATE dentro de una transacción → rollback atómico si algo falla.

BEGIN;

-- ─────────────────────────────────────────────────────────────────────────────
-- 1) Drop del índice CASH-only obsoleto.
-- ─────────────────────────────────────────────────────────────────────────────
DROP INDEX IF EXISTS idx_transfer_proof_cash_expiring;

-- ─────────────────────────────────────────────────────────────────────────────
-- 2) Índice generalizado para PENDING_OWNER_VALIDATION (CASH + SPEI manual).
--
-- Incluimos owner_id primero para que sirva tanto al query del panel del dueño
-- como al del scheduler (que no usa owner_id pero el predicado `status=PENDING`
-- restringe la cardinalidad lo suficiente para que el planificador use un
-- bitmap heap scan por rango sobre expires_at).
-- ─────────────────────────────────────────────────────────────────────────────
CREATE INDEX IF NOT EXISTS idx_transfer_proof_pending_owner_validation
  ON transfer_proof_submissions (owner_id, expires_at)
  WHERE status = 'PENDING_OWNER_VALIDATION';

-- Comentario documental sobre el índice.
COMMENT ON INDEX idx_transfer_proof_pending_owner_validation IS
  'V59: cubre bandeja del dueño y scheduler de expiración. Parcial por '
  'status=PENDING_OWNER_VALIDATION — incluye CASH y SPEI que cayó a '
  'validación manual (Banxico caído / dueño sin CLABE).';

COMMIT;
