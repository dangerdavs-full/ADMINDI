-- V65 — Backfill de consumed_resource_type/id para claims vinculados a expenses.
--
-- CONTEXTO
-- --------
-- V62 hizo backfill de claims consumidos por MAINTENANCE_QUOTE y
-- MAINTENANCE_TICKET, pero se quedó corto: los expenses también referencian
-- archivos (evidencia, presupuesto, comprobante de pago) en tres columnas:
--   · expenses.evidence_file_id         → evidencia/factura del gasto
--   · expenses.budget_file_id           → PDF de presupuesto (cuando el expense
--                                         nació de una cotización)
--   · expenses.payment_proof_file_id    → comprobante SPEI que el dueño subió
--                                         al pagar al proveedor
--
-- Los claims creados antes de V61 quedaron con consumed_* = NULL. Sin el
-- resource_type/id, SecureFileController.downloadFileAttachment cae al
-- fallback "uploader only": eso significa que un dueño no puede abrir el
-- PDF de presupuesto subido por su proveedor, aunque el expense pertenezca
-- a su organización — el control de acceso basado en recurso (por ticket,
-- por expense) queda inoperante.
--
-- Esta migración es idempotente: sólo toca filas con consumed_resource_type
-- = NULL. Si un mismo file_path aparece duplicado en varios expenses (casi
-- imposible pero teóricamente posible), DISTINCT ON garantiza una única
-- consumición estable.
--
-- Orden de precedencia cuando un path aparece en varias columnas/tablas:
--   1) Si V62 ya lo marcó como MAINTENANCE_QUOTE / MAINTENANCE_TICKET,
--      esta migración lo respeta (WHERE consumed_resource_type IS NULL).
--   2) Si el mismo path aparece en expenses.evidence_file_id Y
--      expenses.budget_file_id (raro, sólo si el user subió el mismo archivo
--      a dos campos), gana evidence_file_id por ser el campo canónico.

BEGIN;

-- 1) Expense.evidence_file_id
UPDATE file_upload_claims c
   SET consumed_at = COALESCE(c.consumed_at, NOW()),
       consumed_resource_type = 'EXPENSE',
       consumed_resource_id = sub.expense_id
  FROM (
        SELECT DISTINCT ON (e.evidence_file_id)
               e.id AS expense_id,
               e.evidence_file_id AS file_path
          FROM expenses e
         WHERE e.evidence_file_id IS NOT NULL
         ORDER BY e.evidence_file_id, e.created_at ASC
       ) sub
 WHERE sub.file_path = c.file_path
   AND c.consumed_resource_type IS NULL;

-- 2) Expense.budget_file_id
UPDATE file_upload_claims c
   SET consumed_at = COALESCE(c.consumed_at, NOW()),
       consumed_resource_type = 'EXPENSE',
       consumed_resource_id = sub.expense_id
  FROM (
        SELECT DISTINCT ON (e.budget_file_id)
               e.id AS expense_id,
               e.budget_file_id AS file_path
          FROM expenses e
         WHERE e.budget_file_id IS NOT NULL
         ORDER BY e.budget_file_id, e.created_at ASC
       ) sub
 WHERE sub.file_path = c.file_path
   AND c.consumed_resource_type IS NULL;

-- 3) Expense.payment_proof_file_id
UPDATE file_upload_claims c
   SET consumed_at = COALESCE(c.consumed_at, NOW()),
       consumed_resource_type = 'EXPENSE',
       consumed_resource_id = sub.expense_id
  FROM (
        SELECT DISTINCT ON (e.payment_proof_file_id)
               e.id AS expense_id,
               e.payment_proof_file_id AS file_path
          FROM expenses e
         WHERE e.payment_proof_file_id IS NOT NULL
         ORDER BY e.payment_proof_file_id, e.created_at ASC
       ) sub
 WHERE sub.file_path = c.file_path
   AND c.consumed_resource_type IS NULL;

COMMIT;
