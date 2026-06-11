-- V9: Seed system permission templates
INSERT INTO permission_templates (id, name, description, is_system, permissions) VALUES
('tpl-full-access', 'Acceso Total', 'Acceso completo a todas las funcionalidades del dueño', true,
  '["properties:read","properties:write","properties:delete","units:read","units:write","units:delete","tenants:read","tenants:write","leases:read","leases:write","invoices:read","invoices:write","staff:read","staff:write","reports:read"]'),
('tpl-read-only', 'Solo Lectura', 'Solo puede ver información, sin modificar nada', true,
  '["properties:read","units:read","tenants:read","leases:read","invoices:read","reports:read"]'),
('tpl-accountant', 'Contador', 'Acceso a finanzas, facturas y reportes', true,
  '["invoices:read","invoices:write","reports:read","tenants:read","leases:read"]')
ON CONFLICT (id) DO NOTHING;
