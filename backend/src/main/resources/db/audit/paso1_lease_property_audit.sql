-- Auditoria Paso 1 (1B): ejecutar despues de V22 y antes de V23.

SELECT id, owner_id, unit_id, property_id, status
FROM leases
WHERE unit_id IS NULL;

SELECT l.id, l.owner_id, l.unit_id, l.property_id, l.status
FROM leases l
LEFT JOIN units u ON u.id = l.unit_id
WHERE l.unit_id IS NOT NULL AND u.id IS NULL;

SELECT id, owner_id, unit_id, status
FROM leases
WHERE property_id IS NULL;

SELECT l.id AS lease_id, l.property_id AS lease_property_id, u.property_id AS unit_property_id
FROM leases l
JOIN units u ON u.id = l.unit_id
WHERE l.unit_id IS NOT NULL AND l.property_id IS DISTINCT FROM u.property_id;

SELECT property_id, COUNT(*) AS active_count
FROM leases
WHERE status = 'ACTIVE' AND property_id IS NOT NULL
GROUP BY property_id
HAVING COUNT(*) > 1;

SELECT id, owner_id, name, address, status
FROM properties
WHERE active = true
  AND (address IS NULL OR trim(address) = '');
