-- V40: Colapso de plantillas de permisos a 3 niveles.
--
-- Contexto
-- --------
-- El sistema expuso 4 plantillas del sistema:
--   * tpl-full-access      (Acceso Total)
--   * tpl-read-only        (Solo Lectura)
--   * tpl-accountant       (Contador)
--   * tpl-property-admin-operational  (Property admin operacional — V27/V28)
--
-- La última genera confusión para el dueño no técnico y duplica parcialmente
-- lo que "Acceso Total" debería garantizar. Por decisión de producto se
-- consolidan los niveles a 3 (Acceso Total, Contador, Solo Lectura) y quienes
-- tenían el template operacional pasan a Acceso Total.
--
-- Lo que hace esta migración
-- --------------------------
-- 1. Enriquece tpl-full-access con los permisos granulares que el código
--    chequea literalmente vía @PreAuthorize/hasAuthority:
--      TENANT_VIEW / TENANT_CREATE / TENANT_UPDATE / TENANT_DELETE
--      PROPERTY_ARCHIVE_TENANT  (archivo operativo de inquilinos)
--      REPORT_EXPORT            (exportaciones desde ReportController)
--      VACANCY_ROUTE / TEAM_MANAGE
--    Previamente tpl-full-access carecía de estos, de modo que un staff con
--    "Acceso Total" (rol PROPERTY_ADMIN) no podía archivar inquilinos ni
--    exportar reportes — defecto que se resuelve aquí de paso.
--    Los permisos QUOTE_* y EXPENSE_* ya existían (V24) y se preservan.
--
-- 2. Migra cualquier grant con template_id = 'tpl-property-admin-operational'
--    a 'tpl-full-access'. Es una elevación de permisos deliberada (el usuario
--    lo solicitó explícitamente); ningún usuario pierde capacidades.
--
-- 3. Elimina el template. Los consumidores (FK permission_grants.template_id)
--    ya no apuntan a él tras el paso 2, así que el DELETE es seguro.
--
-- Irreversibilidad: no hay rollback automático. La V27/V28 son inmutables por
-- Flyway; quien quiera re-crear el template operacional debe emitir una
-- migración Vxx explícita.

-- 1) Re-escribe permisos y descripción de Acceso Total.
UPDATE permission_templates
SET
  description = 'Acceso completo: equivale a dar al colaborador las mismas capacidades operativas que el dueño sobre el contexto asignado.',
  permissions = '[
    "properties:read","properties:write","properties:delete",
    "units:read","units:write","units:delete",
    "tenants:read","tenants:write",
    "leases:read","leases:write",
    "invoices:read","invoices:write",
    "staff:read","staff:write",
    "reports:read",
    "maintenance:tickets:read",
    "QUOTE_APPROVE","QUOTE_REJECT",
    "EXPENSE_PAY","EXPENSE_SETTLEMENT_CONFIRM",
    "TENANT_VIEW","TENANT_CREATE","TENANT_UPDATE","TENANT_DELETE",
    "PROPERTY_ARCHIVE_TENANT",
    "VACANCY_ROUTE","TEAM_MANAGE",
    "REPORT_EXPORT"
  ]'::jsonb
WHERE id = 'tpl-full-access';

-- 2) Migra grants existentes al template consolidado.
DO $$
DECLARE
  migrated_count INTEGER;
BEGIN
  UPDATE permission_grants
  SET template_id = 'tpl-full-access'
  WHERE template_id = 'tpl-property-admin-operational';

  GET DIAGNOSTICS migrated_count = ROW_COUNT;
  RAISE NOTICE '[V40] Grants re-asignados de tpl-property-admin-operational -> tpl-full-access: %', migrated_count;
END $$;

-- 3) Elimina el template consolidado.
DO $$
DECLARE
  deleted_count INTEGER;
BEGIN
  DELETE FROM permission_templates WHERE id = 'tpl-property-admin-operational';
  GET DIAGNOSTICS deleted_count = ROW_COUNT;
  RAISE NOTICE '[V40] Templates eliminados: %', deleted_count;
END $$;

-- 4) Checkpoint: debe haber exactamente 3 templates del sistema.
DO $$
DECLARE
  remaining INTEGER;
BEGIN
  SELECT COUNT(*) INTO remaining FROM permission_templates WHERE is_system = true;
  RAISE NOTICE '[V40] Plantillas del sistema restantes: % (esperado: 3)', remaining;
  IF remaining <> 3 THEN
    RAISE WARNING '[V40] Numero inesperado de plantillas del sistema. Revisar permission_templates.';
  END IF;
END $$;
