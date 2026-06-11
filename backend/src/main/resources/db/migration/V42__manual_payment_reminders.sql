-- V42: Recordatorios manuales de pago disparados por dueño/admin desde el perfil del tenant.
--
-- Contexto (Fase B2 notificaciones - cobranza real)
-- -------------------------------------------------
-- Los recordatorios automáticos (V41 + PaymentReminderScheduler) viven con unicidad
-- fuerte por (invoice_id, days_before, channel, recipient_user_id). Esa semántica NO
-- sirve para acciones manuales donde el mismo dueño puede disparar 2 envíos al mismo
-- inquilino el mismo día para la misma factura. Por eso esta tabla es dedicada,
-- sin unique constraint: el rate limit se calcula en Java como COUNT() en ventana 24h.
--
-- Columnas
-- --------
--   tenant_user_id  → destinatario real (el UserEntity del inquilino).
--   actor_user_id   → quién presionó el botón (dueño o property admin).
--   owner_id        → contexto del dueño (para queries por owner + auditoría cruzada).
--   invoice_id      → factura que el recordatorio referenció al momento del envío.
--   sent_at         → para ventana de rate limit.
--
-- Índices
-- -------
--   (tenant_user_id, sent_at) permite la consulta caliente del rate limit 24h.
--   (owner_id)                permite listar "qué recordatorios manuales salieron hoy en esta cartera".
--
-- No se guarda el contenido; vive en NotificationEntity + audit_events.

CREATE TABLE IF NOT EXISTS manual_payment_reminders_sent (
    id                  VARCHAR(64)  PRIMARY KEY,
    invoice_id          VARCHAR(64)  NOT NULL,
    tenant_user_id      VARCHAR(64)  NOT NULL,
    actor_user_id       VARCHAR(64)  NOT NULL,
    owner_id            VARCHAR(64)  NOT NULL,
    sent_at             TIMESTAMP    NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_manual_reminders_tenant_sentat
    ON manual_payment_reminders_sent (tenant_user_id, sent_at);

CREATE INDEX IF NOT EXISTS idx_manual_reminders_owner
    ON manual_payment_reminders_sent (owner_id);

-- Permiso granular para PROPERTY_ADMIN con "Acceso Total": disparar recordatorios manuales.
-- SUPER_ADMIN NO recibe este permiso aunque el rol tenga ALL authorities en Security:
-- la decisión arquitectónica es que superadmin no opera cobranza. El @PreAuthorize del
-- endpoint lo excluye explícitamente ("hasRole('OWNER') or hasAuthority('TENANT_REMIND_MANUAL')")
-- — ver TenantController#sendManualReminder.
UPDATE permission_templates
SET permissions = (
    CASE
        WHEN permissions::text LIKE '%TENANT_REMIND_MANUAL%' THEN permissions
        ELSE (permissions - 'REPORT_EXPORT')
              || '["REPORT_EXPORT","TENANT_REMIND_MANUAL"]'::jsonb
    END
)
WHERE id = 'tpl-full-access';

-- Checkpoint legible en log de Flyway.
DO $$
DECLARE
    has_perm BOOLEAN;
BEGIN
    SELECT permissions::text LIKE '%TENANT_REMIND_MANUAL%'
      INTO has_perm
      FROM permission_templates
     WHERE id = 'tpl-full-access';
    IF NOT has_perm THEN
        RAISE EXCEPTION '[V42] tpl-full-access no quedó con TENANT_REMIND_MANUAL. Revisar migración.';
    END IF;
    RAISE NOTICE '[V42] tpl-full-access ahora incluye TENANT_REMIND_MANUAL.';
END $$;
