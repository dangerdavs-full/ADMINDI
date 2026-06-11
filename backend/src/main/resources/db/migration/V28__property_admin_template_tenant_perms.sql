-- Etapa 1: plantilla coherente con TenantController (TENANT_* + PROPERTY_ARCHIVE_TENANT en rutas de archivo).
-- Id estable: tpl-property-admin-operational (nombre ya no implica "operativo" genérico sin cubrir expediente).
UPDATE permission_templates
SET
  name = 'Property admin — expediente y archivo',
  description = 'Listado y edición de inquilinos (TENANT_VIEW/UPDATE/CREATE); baja operativa vía PROPERTY_ARCHIVE_TENANT o TENANT_DELETE (DELETE y POST .../archive). Cotización, gastos, reporte.',
  permissions = '["TENANT_VIEW","TENANT_UPDATE","TENANT_CREATE","QUOTE_APPROVE","QUOTE_REJECT","EXPENSE_PAY","EXPENSE_SETTLEMENT_CONFIRM","VACANCY_ROUTE","TEAM_MANAGE","PROPERTY_ARCHIVE_TENANT","REPORT_EXPORT"]'::jsonb
WHERE id = 'tpl-property-admin-operational';
