-- Etapa 1: plantilla opcional PROPERTY_ADMIN con permisos sensibles mínimos (PLAN_FINAL_5_ETAPAS §1.3).
-- No reemplaza tpl-full-access; asignar explícitamente por grant si el dueño delega estas operaciones.
INSERT INTO permission_templates (id, name, description, is_system, permissions) VALUES
(
  'tpl-property-admin-operational',
  'Property admin — operación sensible',
  'Cotizaciones, gastos, vacantes, equipo, archivo de inquilino en inmueble, exportación de reportes. Sin acceso total.',
  true,
  '["QUOTE_APPROVE","QUOTE_REJECT","EXPENSE_PAY","EXPENSE_SETTLEMENT_CONFIRM","VACANCY_ROUTE","TEAM_MANAGE","PROPERTY_ARCHIVE_TENANT","REPORT_EXPORT"]'
)
ON CONFLICT (id) DO NOTHING;
