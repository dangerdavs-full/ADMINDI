-- V61 — Corrección de FKs fk_expenses_budget_file / fk_expenses_payment_proof_file.
--
-- CONTEXTO
-- --------
-- V49 agregó las columnas expenses.budget_file_id y payment_proof_file_id con
-- FK apuntando a file_upload_claims(id). El supuesto era: "el servicio guarda
-- el UUID del claim". Pero el contrato real del sistema es distinto — los
-- controllers de upload devuelven el `file_path` (un path interno como
-- "/uploads/quote-evidence/<uuid>.pdf") y los consumidores
-- (MaintenanceWorkflowService.ownerApproveQuote, ownerPayAndClose, etc.)
-- persisten ese path directamente en las columnas *_file_id.
--
-- PostgreSQL reclama al intentar INSERTAR un expense porque el valor
-- "/uploads/quote-evidence/<uuid>.pdf" NO existe como `id` en claims (sí
-- existe como `file_path`). Error reportado por el dueño al aprobar una
-- cotización:
--
--   ERROR: inserción o actualización en la tabla «expenses» viola la llave
--          foránea «fk_expenses_budget_file»
--   Detail: La llave (budget_file_id)=(/uploads/...) no está presente en la
--           tabla «file_upload_claims».
--
-- Dos formas de arreglar:
--   A) Cambiar el contrato del controller: devolver claim.id en lugar de
--      file_path. Requiere refactor de todos los consumidores y migración
--      de datos existentes. INVASIVO.
--   B) Redirigir la FK al campo que SÍ recibe el valor. file_upload_claims.
--      file_path ya tiene UNIQUE (uq_file_claims_path de V46), cualificándolo
--      como target válido de FK. Una sola migración, cero código. SEGURO.
--
-- Elegimos B. La semántica de ON DELETE SET NULL se preserva.
--
-- IMPACTO
-- -------
-- * Filas ya presentes en expenses con *_file_id (o NULL) siguen válidas. Si
--   existe alguna fila con un path inválido (sin claim correspondiente), la
--   migración falla aquí y conviene ejecutar: DELETE/UPDATE correctivo antes.
--   En los entornos actuales no debería haber — al haber fallado la inserción
--   previamente, no quedan datos inconsistentes.

BEGIN;

-- ─────────────────────────────────────────────────────────────────────────────
-- 1) Drop de las FKs viejas (apuntan a claims.id, bug).
-- ─────────────────────────────────────────────────────────────────────────────
DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.table_constraints
        WHERE constraint_name = 'fk_expenses_budget_file' AND table_name = 'expenses'
    ) THEN
        ALTER TABLE expenses DROP CONSTRAINT fk_expenses_budget_file;
    END IF;
    IF EXISTS (
        SELECT 1 FROM information_schema.table_constraints
        WHERE constraint_name = 'fk_expenses_payment_proof_file' AND table_name = 'expenses'
    ) THEN
        ALTER TABLE expenses DROP CONSTRAINT fk_expenses_payment_proof_file;
    END IF;
END $$;

-- ─────────────────────────────────────────────────────────────────────────────
-- 2) Sanitizar valores huérfanos antes de aplicar la nueva FK. Si alguna fila
--    tiene budget_file_id / payment_proof_file_id que no existen como
--    file_path en claims, los dejamos NULL para no romper la migración.
--    Conservan su historia contable en las demás columnas.
-- ─────────────────────────────────────────────────────────────────────────────
UPDATE expenses
   SET budget_file_id = NULL
 WHERE budget_file_id IS NOT NULL
   AND NOT EXISTS (
        SELECT 1 FROM file_upload_claims c WHERE c.file_path = expenses.budget_file_id
   );

UPDATE expenses
   SET payment_proof_file_id = NULL
 WHERE payment_proof_file_id IS NOT NULL
   AND NOT EXISTS (
        SELECT 1 FROM file_upload_claims c WHERE c.file_path = expenses.payment_proof_file_id
   );

-- ─────────────────────────────────────────────────────────────────────────────
-- 3) Ampliar las columnas a 512 chars para alinearse con file_upload_claims.
--    file_path (V46: VARCHAR(512)). V49 las dejó en VARCHAR(64), margen
--    insuficiente para paths largos como "/uploads/maintenance-evidence/<uuid>.pdf".
-- ─────────────────────────────────────────────────────────────────────────────
ALTER TABLE expenses
    ALTER COLUMN budget_file_id TYPE VARCHAR(512);

ALTER TABLE expenses
    ALTER COLUMN payment_proof_file_id TYPE VARCHAR(512);

-- ─────────────────────────────────────────────────────────────────────────────
-- 4) Recrear las FKs apuntando a file_upload_claims(file_path). Preservamos
--    ON DELETE SET NULL para que purga de claims no rompa expenses históricos.
-- ─────────────────────────────────────────────────────────────────────────────
ALTER TABLE expenses
    ADD CONSTRAINT fk_expenses_budget_file
    FOREIGN KEY (budget_file_id)
    REFERENCES file_upload_claims(file_path)
    ON DELETE SET NULL;

ALTER TABLE expenses
    ADD CONSTRAINT fk_expenses_payment_proof_file
    FOREIGN KEY (payment_proof_file_id)
    REFERENCES file_upload_claims(file_path)
    ON DELETE SET NULL;

COMMENT ON CONSTRAINT fk_expenses_budget_file ON expenses IS
  'V61: apunta a file_upload_claims.file_path porque el código persiste el '
  'path interno como fileId, no el UUID del claim.';
COMMENT ON CONSTRAINT fk_expenses_payment_proof_file ON expenses IS
  'V61: apunta a file_upload_claims.file_path por la misma razón que '
  'fk_expenses_budget_file.';

COMMIT;
