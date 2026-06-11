-- Paso 1 / 1D: endurecer solo tras backfill y auditoria (NOT NULL + un solo ACTIVE por property)
-- Falla explicitamente si quedan datos invalidos.

DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM leases WHERE property_id IS NULL) THEN
    RAISE EXCEPTION 'V23: leases.property_id aun NULL - ejecutar db/audit/paso1_lease_property_audit.sql y corregir datos.';
  END IF;
  IF EXISTS (
    SELECT 1 FROM leases
    WHERE status = 'ACTIVE' AND property_id IS NOT NULL
    GROUP BY property_id
    HAVING COUNT(*) > 1
  ) THEN
    RAISE EXCEPTION 'V23: mas de un lease ACTIVE por property_id - resolver antes de aplicar restriccion unica.';
  END IF;
END $$;

ALTER TABLE leases ALTER COLUMN property_id SET NOT NULL;

CREATE UNIQUE INDEX IF NOT EXISTS ux_leases_one_active_per_property
  ON leases (property_id)
  WHERE status = 'ACTIVE';
