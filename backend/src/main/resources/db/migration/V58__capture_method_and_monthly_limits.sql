-- V58 — Capture method y límites mensuales por método.
--
-- La cuenta receptora (CLABE) ya NO la captura el inquilino al subir su
-- comprobante SPEI. El sistema usa la CLABE registrada por el dueño
-- (users.clabe, cifrada AES-GCM). Esto elimina errores de captura y blinda
-- contra fraudes: si el CEP devuelve una CLABE distinta a la del dueño, el
-- comprobante se rechaza automáticamente con mensaje claro.
--
-- Nuevos límites de intentos:
--   - SPEI con IA (AI_OCR): 6 por mes calendario por inquilino
--     (protege presupuesto Claude: cada OCR cuesta tokens).
--   - SPEI captura manual (MANUAL): ilimitado — el user puede capturar
--     claves de rastreo todo el día sin costo de IA.
--   - CASH: mantiene regla V57 (3 pendientes simultáneos + 3 fallos duros
--     por factura) porque no tiene costo de IA.

BEGIN;

-- ─────────────────────────────────────────────────────────────────────────────
-- 1) capture_method: AI_OCR | MANUAL | CASH.
--    Default MANUAL para filas históricas (conservador: no se les aplica el
--    límite de 6/mes porque se marcan como manual).
-- ─────────────────────────────────────────────────────────────────────────────
ALTER TABLE transfer_proof_submissions
  ADD COLUMN IF NOT EXISTS capture_method VARCHAR(10) NOT NULL DEFAULT 'MANUAL';

ALTER TABLE transfer_proof_submissions
  DROP CONSTRAINT IF EXISTS ck_transfer_proof_capture_method;
ALTER TABLE transfer_proof_submissions
  ADD CONSTRAINT ck_transfer_proof_capture_method
  CHECK (capture_method IN ('AI_OCR', 'MANUAL', 'CASH'));

-- Re-sincronizar filas históricas CASH → capture_method='CASH'
UPDATE transfer_proof_submissions
   SET capture_method = 'CASH'
 WHERE payment_type = 'CASH' AND capture_method = 'MANUAL';

-- ─────────────────────────────────────────────────────────────────────────────
-- 2) Índice para el conteo mensual de AI_OCR (hot path en el submit SPEI).
--    Cubre: (tenant_profile_id, capture_method, submitted_at). Usado por la
--    nueva política PaymentProofAttemptPolicy#evaluateSpeiAiOcrMonthly.
-- ─────────────────────────────────────────────────────────────────────────────
CREATE INDEX IF NOT EXISTS idx_transfer_proof_ai_ocr_monthly
  ON transfer_proof_submissions (tenant_profile_id, submitted_at)
  WHERE capture_method = 'AI_OCR';

COMMENT ON COLUMN transfer_proof_submissions.capture_method IS
  'V58: AI_OCR (foto procesada con Claude Vision, límite 6/mes), '
  'MANUAL (datos tecleados, ilimitado), '
  'CASH (efectivo, reglas propias de V57).';

COMMIT;
