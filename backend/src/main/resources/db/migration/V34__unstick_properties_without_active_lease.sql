-- V34: Des-pegar inmuebles marcados como OCCUPIED sin contrato ACTIVE.
--
-- Contexto:
--   V32/V33 usaban la presencia de un tenant_profile no archivado como señal de ocupación,
--   además del lease ACTIVE. Eso dejaba inmuebles "pegados" en OCCUPIED cuando el operador
--   terminaba el contrato directamente desde la pantalla de Contratos (botón "Terminar")
--   sin archivar el expediente del arrendatario. El banner del detalle del inmueble mostraba
--   entonces la contradicción visible:
--       "Estado: Ocupado · Sin contrato ACTIVO · Vacancia: Abierta (OPEN)"
--
--   A partir de V34 la fuente de verdad operacional es exclusivamente el lease ACTIVE:
--   un inmueble está OCCUPIED si y solo si tiene al menos un lease ACTIVE; en caso contrario
--   es AVAILABLE. Se respeta MAINTENANCE y DELETED sin tocar.
--
-- Esta migración es idempotente y no toca PAID/historial financiero.

-- 1) OCCUPIED sin lease ACTIVE -> AVAILABLE (respetando DELETED y MAINTENANCE).
UPDATE properties p
   SET status = 'AVAILABLE'
 WHERE p.status = 'OCCUPIED'
   AND NOT EXISTS (
        SELECT 1 FROM leases l
         WHERE l.property_id = p.id
           AND l.owner_id    = p.owner_id
           AND l.status      = 'ACTIVE'
   );

-- 2) Simétrico: inmuebles que deberían estar OCCUPIED pero quedaron AVAILABLE por fallo
--    de refresh (p.ej. transacción fallida a mitad). Si tienen lease ACTIVE, corregir.
UPDATE properties p
   SET status = 'OCCUPIED'
 WHERE p.status = 'AVAILABLE'
   AND EXISTS (
        SELECT 1 FROM leases l
         WHERE l.property_id = p.id
           AND l.owner_id    = p.owner_id
           AND l.status      = 'ACTIVE'
   );
