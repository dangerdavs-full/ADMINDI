-- V33: Terminar leases huérfanos + re-reconciliar ocupación.
--
-- Gap detectado después de V32: la reconciliación consideraba OCCUPIED a un inmueble que
-- tuviera cualquier lease en ACTIVE, aunque su expediente respaldo ya estuviera archivado.
-- Archivar al arrendatario = cierre operativo del contrato; un lease ACTIVE que no tiene
-- detrás un tenant_profile vigente (mismo owner + property + tenant) es basura y debe
-- terminarse para liberar al inmueble.

-- 1) Terminar leases ACTIVE sin expediente activo que los respalde --------------------------------
UPDATE leases l
   SET status = 'TERMINATED'
 WHERE l.status = 'ACTIVE'
   AND l.property_id IS NOT NULL
   AND NOT EXISTS (
        SELECT 1 FROM tenant_profiles tp
         WHERE tp.property_id = l.property_id
           AND tp.owner_id    = l.owner_id
           AND tp.user_id     = l.tenant_id
           AND tp.archived_at IS NULL
   );

-- 2) Recalcular properties.status ahora que los leases huérfanos ya están TERMINATED --------------
-- 2.a  -> AVAILABLE si ya no queda expediente activo ni lease ACTIVE (respetando DELETED/MAINTENANCE).
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

-- 2.b  -> OCCUPIED si hay expediente activo o lease ACTIVE y no lo reflejaba.
UPDATE properties p
   SET status = 'OCCUPIED'
 WHERE p.status NOT IN ('DELETED', 'MAINTENANCE', 'OCCUPIED')
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
   );
