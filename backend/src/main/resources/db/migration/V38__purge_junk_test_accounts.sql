-- V38: Purga de cuentas basura (QA / smoke tests / fixtures) y su data colgada.
--
-- Contexto de negocio: después de varias rondas de QA manual y pruebas de humo
-- la tabla `users` quedó con múltiples registros con emails tipo @test.local,
-- @test.com, qa.*, cierre-*, tenant@admindi.com, admin.prueba@*, juan.perez@*,
-- juanp@*, test*@*, que inflan listados (la pantalla "Gestión Global de
-- Cuentas" muestra todos) y dejan data operativa huérfana (inmuebles ficticios,
-- contratos, facturas, convenios, archivos, etc.).
--
-- Objetivo: eliminarlos por completo junto con todo su universo. El SUPERADMIN
-- real y los dueños reales NO se tocan (filtros explícitos por role y patrón).
--
-- Algoritmo:
--   1. Identificar y purgar OWNERS basura (cascade full estilo V37).
--   2. Identificar y purgar tenants/staff basura remanentes (los que no se
--      barrieron con sus dueños basura en el paso 1).
--
-- Invariantes:
--   · role = 'SUPER_ADMIN' nunca se toca.
--   · email = 'superadmin@admindi.com' nunca se toca (seed oficial del sistema).
--   · Solo se elimina si el email calza alguno de los patrones de prueba.
--
-- Patrones de basura (no mayúsculas, ya que email se guarda en minúsculas):
--   · email LIKE '%@test.local'
--   · email LIKE '%@test.com'
--   · email LIKE 'qa.%' OR email LIKE 'qa-%'
--   · email LIKE 'cierre-%'
--   · email LIKE 'test%@%'  -- cubre test1@..., testowner@..., etc.
--   · email = 'tenant@admindi.com'   -- seed de tenant de prueba
--   · email LIKE 'admin.prueba%@%'
--   · email LIKE 'juan.perez@%' OR email LIKE 'juanp@%'  -- fixtures legacy
--
-- Seguridad: DO block idempotente. Si una fila no existe, el DELETE es no-op.
-- Si una FK bloquea (caso raro tras ordenar bien), la transacción rompe completa
-- y el deploy aborta sin dejar estado intermedio — NO enmascara errores.

DO $$
DECLARE
    junk_owner_id VARCHAR(255);
    junk_user_id  VARCHAR(255);
BEGIN
    ---------------------------------------------------------------------
    -- 1) OWNERS basura: cascade full (ver V37 para rationale).
    ---------------------------------------------------------------------
    FOR junk_owner_id IN
        SELECT id FROM users
         WHERE role = 'OWNER'
           AND email <> 'superadmin@admindi.com'
           AND (
               email LIKE '%@test.local'
            OR email LIKE '%@test.com'
            OR email LIKE 'qa.%'
            OR email LIKE 'qa-%'
            OR email LIKE 'cierre-%'
            OR email LIKE 'test%@%'
            OR email = 'tenant@admindi.com'
            OR email LIKE 'admin.prueba%@%'
            OR email LIKE 'juan.perez@%'
            OR email LIKE 'juanp@%'
           )
    LOOP
        RAISE NOTICE '[V38] purging junk OWNER %', junk_owner_id;

        DELETE FROM agreement_installments
         WHERE agreement_id IN (SELECT id FROM payment_agreements WHERE owner_id = junk_owner_id);
        DELETE FROM payment_agreements WHERE owner_id = junk_owner_id;

        DELETE FROM cep_validation_attempts
         WHERE transfer_proof_id IN (SELECT id FROM transfer_proof_submissions WHERE owner_id = junk_owner_id);
        DELETE FROM transfer_proof_submissions WHERE owner_id = junk_owner_id;

        DELETE FROM payments WHERE owner_id = junk_owner_id;
        DELETE FROM invoices WHERE owner_id = junk_owner_id;

        DELETE FROM lease_files
         WHERE lease_id IN (SELECT id FROM leases WHERE owner_id = junk_owner_id);
        DELETE FROM leases WHERE owner_id = junk_owner_id;

        DELETE FROM commercial_activities
         WHERE vacancy_id IN (SELECT id FROM vacancies WHERE owner_id = junk_owner_id);
        DELETE FROM vacancies WHERE owner_id = junk_owner_id;

        DELETE FROM maintenance_quotes
         WHERE ticket_id IN (SELECT id FROM maintenance_tickets WHERE owner_id = junk_owner_id);
        DELETE FROM maintenance_tickets WHERE owner_id = junk_owner_id;
        DELETE FROM maintenance_budgets WHERE owner_id = junk_owner_id;

        DELETE FROM expenses WHERE owner_id = junk_owner_id;
        DELETE FROM property_movements WHERE owner_id = junk_owner_id;

        DELETE FROM property_files
         WHERE property_id IN (SELECT id FROM properties WHERE owner_id = junk_owner_id);
        DELETE FROM units WHERE owner_id = junk_owner_id;
        DELETE FROM properties WHERE owner_id = junk_owner_id;

        DELETE FROM tenant_profiles WHERE owner_id = junk_owner_id;
        DELETE FROM tenant_archive_snapshots WHERE owner_id = junk_owner_id;

        DELETE FROM platform_provider_assignments WHERE owner_id = junk_owner_id;
        DELETE FROM permission_grants WHERE owner_id = junk_owner_id;
        DELETE FROM owner_memberships WHERE owner_id = junk_owner_id;

        DELETE FROM notifications WHERE owner_id = junk_owner_id;
        DELETE FROM action_tasks WHERE owner_id = junk_owner_id;

        DELETE FROM audit_events WHERE owner_id = junk_owner_id;

        DELETE FROM refresh_token_sessions WHERE user_id = junk_owner_id;
        DELETE FROM notification_preferences WHERE user_id = junk_owner_id;

        DELETE FROM users WHERE id = junk_owner_id;
    END LOOP;

    ---------------------------------------------------------------------
    -- 2) USERS basura remanentes (tenants / staff / agentes / proveedores
    --    que no eran OWNER pero matchean patrones de test). Cascade ligero:
    --    sus memberships caen por ON DELETE CASCADE; aquí limpiamos las FKs
    --    sin cascade y las tablas que dependen de user_id directamente.
    ---------------------------------------------------------------------
    FOR junk_user_id IN
        SELECT id FROM users
         WHERE role <> 'SUPER_ADMIN'
           AND role <> 'OWNER'
           AND email <> 'superadmin@admindi.com'
           AND (
               email LIKE '%@test.local'
            OR email LIKE '%@test.com'
            OR email LIKE 'qa.%'
            OR email LIKE 'qa-%'
            OR email LIKE 'cierre-%'
            OR email LIKE 'test%@%'
            OR email = 'tenant@admindi.com'
            OR email LIKE 'admin.prueba%@%'
            OR email LIKE 'juan.perez@%'
            OR email LIKE 'juanp@%'
           )
    LOOP
        RAISE NOTICE '[V38] purging junk user %', junk_user_id;

        -- Cobranza ligada al tenant (si aplica). Buscamos por tenant_profile_id.
        DELETE FROM agreement_installments
         WHERE agreement_id IN (
               SELECT pa.id FROM payment_agreements pa
                JOIN tenant_profiles tp ON tp.id = pa.tenant_profile_id
               WHERE tp.user_id = junk_user_id
         );
        DELETE FROM payment_agreements
         WHERE tenant_profile_id IN (SELECT id FROM tenant_profiles WHERE user_id = junk_user_id);

        DELETE FROM cep_validation_attempts
         WHERE transfer_proof_id IN (
               SELECT tps.id FROM transfer_proof_submissions tps
                JOIN tenant_profiles tp ON tp.id = tps.tenant_profile_id
               WHERE tp.user_id = junk_user_id
         );
        DELETE FROM transfer_proof_submissions
         WHERE tenant_profile_id IN (SELECT id FROM tenant_profiles WHERE user_id = junk_user_id);

        DELETE FROM payments
         WHERE tenant_profile_id IN (SELECT id FROM tenant_profiles WHERE user_id = junk_user_id);
        DELETE FROM invoices
         WHERE tenant_profile_id IN (SELECT id FROM tenant_profiles WHERE user_id = junk_user_id);

        -- Leases donde aparece como tenant (FK users).
        DELETE FROM lease_files
         WHERE lease_id IN (SELECT id FROM leases WHERE tenant_id = junk_user_id);
        DELETE FROM leases WHERE tenant_id = junk_user_id;

        DELETE FROM tenant_archive_snapshots WHERE tenant_user_id = junk_user_id;
        DELETE FROM tenant_profiles WHERE user_id = junk_user_id;

        -- Staff / provider side.
        DELETE FROM platform_provider_assignments WHERE provider_id = junk_user_id;
        DELETE FROM permission_grants WHERE user_id = junk_user_id;
        DELETE FROM owner_memberships WHERE user_id = junk_user_id;

        -- Notificaciones / tasks / preferencias / sesiones del propio user.
        DELETE FROM notifications WHERE user_id = junk_user_id;
        DELETE FROM action_tasks WHERE user_id = junk_user_id;
        DELETE FROM notification_preferences WHERE user_id = junk_user_id;
        DELETE FROM refresh_token_sessions WHERE user_id = junk_user_id;

        -- Finalmente el propio user.
        DELETE FROM users WHERE id = junk_user_id;
    END LOOP;
END $$;
