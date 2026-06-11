-- V64 — Descuento 15% de plataforma registrado correctamente en expenses.
--
-- CONTEXTO
-- --------
-- Cuando el proveedor de mantenimiento está marcado como PLATFORM (inscrito
-- en el catálogo de ADMINDI), la plataforma absorbe un 15% del costo. V45
-- agregó platform_discount_pct y platform_discount_amount en maintenance_tickets
-- para congelar ese descuento al aprobar la cotización. Pero el ExpenseEntity
-- se creaba con amount = monto BRUTO de la cotización, sin reflejar el
-- descuento en contabilidad — el reporte anual del dueño mostraba egresos
-- inflados un 15% vs. lo que realmente salió de su cuenta.
--
-- CAMBIO
-- ------
-- Añadimos dos columnas al expense:
--   * platform_credit_amount  — 15% que la plataforma absorbe (crédito a
--     favor del dueño). Si el proveedor es PRIVATE/legacy se queda en 0.
--   * net_expense_amount      — lo que realmente le salió al dueño
--     (= amount - platform_credit_amount). Es lo que deben sumar los
--     reportes contables como "egreso real".
--
-- Se hace backfill: las filas previas usan platform_credit_amount=0 y
-- net_expense_amount=amount (back-compat total — los reportes existentes
-- siguen sumando el mismo total si nunca hubo descuento registrado).
--
-- Para los tickets recientes aprobados en la cadena V45+ con PLATFORM,
-- el backfill mira maintenance_tickets.platform_discount_amount y actualiza
-- el expense vinculado para que el primer reporte post-migración ya refleje
-- el ahorro histórico.

BEGIN;

-- ─────────────────────────────────────────────────────────────────────────────
-- 1) Nuevas columnas.
-- ─────────────────────────────────────────────────────────────────────────────
ALTER TABLE expenses
    ADD COLUMN IF NOT EXISTS platform_credit_amount NUMERIC(14,2) NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS net_expense_amount NUMERIC(14,2);

-- ─────────────────────────────────────────────────────────────────────────────
-- 2) Backfill general: para filas previas sin descuento, net = amount.
--    Importante: asignamos NET aunque la plataforma no haya absorbido nada
--    (platform_credit_amount = 0) porque los reportes post-V64 usarán
--    net_expense_amount como fuente canónica. Filas con NET null romperían.
-- ─────────────────────────────────────────────────────────────────────────────
UPDATE expenses
   SET net_expense_amount = COALESCE(amount, 0)
 WHERE net_expense_amount IS NULL;

-- ─────────────────────────────────────────────────────────────────────────────
-- 3) Backfill de descuentos ya congelados en tickets (V45+).
--    Para expenses ligados a MAINTENANCE_TICKET cuyo ticket tenga
--    platform_discount_amount > 0, reconstruimos el desglose:
--      platform_credit_amount = ticket.platform_discount_amount
--      net_expense_amount     = amount - platform_discount_amount
--    El amount bruto NO cambia (el proveedor cobró ese total).
-- ─────────────────────────────────────────────────────────────────────────────
UPDATE expenses e
   SET platform_credit_amount = t.platform_discount_amount,
       net_expense_amount     = GREATEST(
                                    COALESCE(e.amount, 0) - COALESCE(t.platform_discount_amount, 0),
                                    0)
  FROM maintenance_tickets t
 WHERE e.linked_resource_type = 'MAINTENANCE_TICKET'
   AND e.linked_resource_id   = t.id
   AND t.platform_discount_amount IS NOT NULL
   AND t.platform_discount_amount > 0
   AND e.platform_credit_amount = 0;

-- ─────────────────────────────────────────────────────────────────────────────
-- 4) Check ligero: net_expense_amount no debe ser mayor que amount ni negativo.
--    Constraint permisivo (>=0) para no bloquear writes en edge cases de
--    redondeo; validación estricta vive en el service.
-- ─────────────────────────────────────────────────────────────────────────────
ALTER TABLE expenses
    DROP CONSTRAINT IF EXISTS ck_expenses_net_nonneg;
ALTER TABLE expenses
    ADD CONSTRAINT ck_expenses_net_nonneg
    CHECK (net_expense_amount IS NULL OR net_expense_amount >= 0);

-- ─────────────────────────────────────────────────────────────────────────────
-- 5) Comentarios documentales.
-- ─────────────────────────────────────────────────────────────────────────────
COMMENT ON COLUMN expenses.platform_credit_amount IS
  'V64: monto que la plataforma ADMINDI absorbe como descuento al dueño '
  '(ej. 15% cuando el proveedor está vinculado como PLATFORM). 0 si no aplica.';
COMMENT ON COLUMN expenses.net_expense_amount IS
  'V64: egreso real del dueño = amount - platform_credit_amount. '
  'Los reportes contables deben usar este campo (no amount) para el gasto real.';

COMMIT;
