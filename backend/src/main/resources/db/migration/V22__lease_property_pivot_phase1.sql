-- Paso 1 / 1A: pivot Lease -> Property (columna nullable, backfill, FK, indice; unit_id opcional legacy)
-- UTF-8 sin BOM

ALTER TABLE leases ADD COLUMN IF NOT EXISTS property_id VARCHAR(255);

UPDATE leases l
SET property_id = u.property_id
FROM units u
WHERE l.unit_id = u.id
  AND l.property_id IS NULL;

ALTER TABLE leases ALTER COLUMN unit_id DROP NOT NULL;

ALTER TABLE leases
  ADD CONSTRAINT fk_leases_property
  FOREIGN KEY (property_id) REFERENCES properties(id) ON DELETE RESTRICT;

CREATE INDEX IF NOT EXISTS idx_leases_property_status ON leases (property_id, status);