-- V56 — Campos IA contables en payments y expenses.
--
-- Agrega metadatos generados por AiAccountingService tras un pago confirmado
-- o un egreso registrado. Los valores son sugerencias: el dueño / contador
-- puede corregirlos desde el panel (ai_reviewed_by_user=true).
--
-- Rol de los campos:
--   ai_category       : taxonomía corta de negocio (RENTA_BASE, SERVICIOS,
--                       COMISION, MANTENIMIENTO_ELECTRICO, etc.).
--   ai_cfdi_use       : clave SAT de "Uso del CFDI" sugerida (G03 - Gastos en
--                       general, I08 - Otra maquinaria, P01 - Por definir...).
--   ai_tax_deductible : boolean sugerido para uso fiscal (personas físicas
--                       arrendadores; el contador valida).
--   ai_confidence     : 0.0-1.0 retornado por Claude.
--   ai_reviewed_by_user: el dueño marcó como revisada la categorización.
--   ai_last_run_at    : cuándo corrió el análisis (para re-ejecutar si cambia el modelo).
--
-- NULL es estado válido: significa "aún no clasificado". El scheduler mensual
-- del reporte lanza re-categorización para filas con ai_last_run_at NULL.

BEGIN;

-- ─── payments ───────────────────────────────────────────────────────────
ALTER TABLE payments ADD COLUMN IF NOT EXISTS ai_category TEXT;
ALTER TABLE payments ADD COLUMN IF NOT EXISTS ai_cfdi_use TEXT;
ALTER TABLE payments ADD COLUMN IF NOT EXISTS ai_tax_deductible BOOLEAN;
ALTER TABLE payments ADD COLUMN IF NOT EXISTS ai_confidence NUMERIC(3,2);
ALTER TABLE payments ADD COLUMN IF NOT EXISTS ai_reviewed_by_user BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE payments ADD COLUMN IF NOT EXISTS ai_last_run_at TIMESTAMP;

CREATE INDEX IF NOT EXISTS idx_payments_ai_category
    ON payments (owner_id, ai_category)
    WHERE ai_category IS NOT NULL;

COMMENT ON COLUMN payments.ai_category IS
  'Categoría sugerida por Claude (V56). El dueño puede corregirla marcando ai_reviewed_by_user=TRUE.';

-- ─── expenses ───────────────────────────────────────────────────────────
ALTER TABLE expenses ADD COLUMN IF NOT EXISTS ai_category TEXT;
ALTER TABLE expenses ADD COLUMN IF NOT EXISTS ai_cfdi_use TEXT;
ALTER TABLE expenses ADD COLUMN IF NOT EXISTS ai_tax_deductible BOOLEAN;
ALTER TABLE expenses ADD COLUMN IF NOT EXISTS ai_confidence NUMERIC(3,2);
ALTER TABLE expenses ADD COLUMN IF NOT EXISTS ai_reviewed_by_user BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE expenses ADD COLUMN IF NOT EXISTS ai_last_run_at TIMESTAMP;

CREATE INDEX IF NOT EXISTS idx_expenses_ai_category
    ON expenses (owner_id, ai_category)
    WHERE ai_category IS NOT NULL;

COMMENT ON COLUMN expenses.ai_category IS
  'Categoría sugerida por Claude (V56). El dueño / contador valida para la declaración SAT.';

COMMIT;
