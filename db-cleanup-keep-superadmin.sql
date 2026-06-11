-- ============================================================================
-- Limpieza total de datos operativos conservando:
--   * users (solo superadmin@admindi.com con su MFA intacto)
--   * permission_templates (plantillas base del sistema)
--   * flyway_schema_history (versionado del schema)
--
-- Todo lo demás queda vacío para empezar pruebas de notificaciones con datos
-- reales creados manualmente desde el portal.
--
-- Ejecución atómica: todo o nada. Si cualquier paso falla, la transacción
-- hace rollback completo y la BD queda intacta.
-- ============================================================================

BEGIN;

-- 1) Validación defensiva: abortar si el superadmin no existe por alguna razón.
DO $$
DECLARE
    sa_id TEXT;
    sa_has_mfa BOOLEAN;
BEGIN
    SELECT id, (mfa_enabled AND mfa_secret IS NOT NULL)
      INTO sa_id, sa_has_mfa
      FROM users
     WHERE email = 'superadmin@admindi.com';

    IF sa_id IS NULL THEN
        RAISE EXCEPTION 'Superadmin superadmin@admindi.com NO encontrado. Aborto limpieza.';
    END IF;
    IF NOT sa_has_mfa THEN
        RAISE EXCEPTION 'Superadmin encontrado pero SIN MFA (mfa_enabled=% o mfa_secret NULL). Aborto para no dejar la cuenta insegura.', sa_has_mfa;
    END IF;

    RAISE NOTICE '[CLEANUP] Superadmin OK (id=%) con MFA activo. Procediendo.', sa_id;
END $$;

-- 2) TRUNCATE de tablas operativas.
--    Se usa CASCADE por defensa: si alguna tabla que olvidé depende de las
--    listadas, Postgres la trunca también en vez de fallar.
--    NOTA: users, permission_templates y flyway_schema_history NO están en la lista.
TRUNCATE TABLE
    action_tasks,
    agreement_installments,
    audit_events,
    cep_validation_attempts,
    commercial_activities,
    expenses,
    invoices,
    lease_files,
    leases,
    maintenance_budgets,
    maintenance_quotes,
    maintenance_tickets,
    manual_payment_reminders_sent,
    notification_preferences,
    notifications,
    owner_memberships,
    payment_agreements,
    payment_reminders_sent,
    payments,
    permission_grants,
    platform_provider_assignments,
    properties,
    property_files,
    property_movements,
    refresh_token_sessions,
    tenant_archive_snapshots,
    tenant_profiles,
    transfer_proof_submissions,
    units,
    user_permissions,
    vacancies
RESTART IDENTITY CASCADE;

-- 3) Borrar todos los usuarios EXCEPTO el superadmin.
DELETE FROM users WHERE email <> 'superadmin@admindi.com';

-- 4) Verificación final: debe quedar exactamente 1 user con MFA intacto.
DO $$
DECLARE
    user_count INTEGER;
    sa_still_has_mfa BOOLEAN;
BEGIN
    SELECT COUNT(*) INTO user_count FROM users;
    IF user_count <> 1 THEN
        RAISE EXCEPTION 'Post-cleanup: se esperaba 1 user, hay %. Rollback.', user_count;
    END IF;

    SELECT (mfa_enabled AND mfa_secret IS NOT NULL)
      INTO sa_still_has_mfa
      FROM users WHERE email = 'superadmin@admindi.com';

    IF NOT sa_still_has_mfa THEN
        RAISE EXCEPTION 'Post-cleanup: el superadmin perdió MFA. Rollback.';
    END IF;

    RAISE NOTICE '[CLEANUP] OK — 1 user preservado (superadmin) con MFA intacto.';
END $$;

COMMIT;
