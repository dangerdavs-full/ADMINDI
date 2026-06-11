-- V32: Reconciliación one-shot de estado derivado a nivel DB.
--
-- Contexto:
--   * Un cron previo generaba invoices PENDING/LATE para expedientes archivados ("contabilidad fantasma").
--   * Al archivar, solo se anulaba la invoice del mes en curso -> quedaban invoices viejas vivas.
--   * properties.status quedaba cacheada como OCCUPIED aunque el único expediente activo ya estaba archivado
--     ("inmueble ocupado sin arrendatario").
--
-- Esta migración limpia la DB de forma idempotente:
--   1. Anula invoices fantasma (tenant_profile archivado o huérfano) si no están PAID/VOID/CANCELLED.
--   2. Recomputa properties.status en AVAILABLE/OCCUPIED respetando DELETED y MAINTENANCE.
--
-- Nunca modifica PAID: el historial financiero es inmutable.

-- 1) Anular invoices fantasma por expediente archivado --------------------------------------------
UPDATE invoices i
   SET status              = 'VOID',
       settlement_status   = 'CANCELLED',
       outstanding_amount  = 0
  FROM tenant_profiles tp
 WHERE i.tenant_profile_id = tp.id
   AND tp.archived_at IS NOT NULL
   AND UPPER(COALESCE(i.status, '')) NOT IN ('PAID', 'VOID', 'VOIDED', 'CANCELLED', 'CANCELED');

-- 2) Anular invoices huérfanas (tenant_profile eliminado físicamente en purgas) ---------------------
UPDATE invoices i
   SET status              = 'VOID',
       settlement_status   = 'CANCELLED',
       outstanding_amount  = 0
 WHERE i.tenant_profile_id IS NOT NULL
   AND NOT EXISTS (SELECT 1 FROM tenant_profiles tp WHERE tp.id = i.tenant_profile_id)
   AND UPPER(COALESCE(i.status, '')) NOT IN ('PAID', 'VOID', 'VOIDED', 'CANCELLED', 'CANCELED');

-- 3) Recomputar occupancy de cada inmueble -------------------------------------------------------
-- 3.a Inmuebles con al menos un expediente activo o lease ACTIVE -> OCCUPIED
--     (respetamos DELETED y MAINTENANCE: no los tocamos).
UPDATE properties p
   SET status = 'OCCUPIED'
 WHERE p.status NOT IN ('DELETED', 'MAINTENANCE')
   AND (
        EXISTS (
            SELECT 1 FROM tenant_profiles tp
             WHERE tp.property_id = p.id
               AND tp.owner_id    = p.owner_id
               AND tp.archived_at IS NULL
        )
        OR EXISTS (
            SELECT 1 FROM leases l
             WHERE l.property_id = p.id
               AND l.owner_id    = p.owner_id
               AND l.status      = 'ACTIVE'
        )
   )
   AND p.status <> 'OCCUPIED';

-- 3.b Inmuebles sin expediente activo ni lease ACTIVE -> AVAILABLE
--     (solo si estaban OCCUPIED; si estaban DELETED o MAINTENANCE no tocamos).
UPDATE properties p
   SET status = 'AVAILABLE'
 WHERE p.status = 'OCCUPIED'
   AND NOT EXISTS (
        SELECT 1 FROM tenant_profiles tp
         WHERE tp.property_id = p.id
           AND tp.owner_id    = p.owner_id
           AND tp.archived_at IS NULL
   )
   AND NOT EXISTS (
        SELECT 1 FROM leases l
         WHERE l.property_id = p.id
           AND l.owner_id    = p.owner_id
           AND l.status      = 'ACTIVE'
   );
