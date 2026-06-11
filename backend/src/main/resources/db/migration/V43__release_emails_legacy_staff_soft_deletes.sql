-- V43: Liberación retroactiva de emails de staff/provider eliminados con el bug viejo.
--
-- Contexto de negocio: antes de esta versión, eliminar un REAL_ESTATE_AGENT,
-- MAINTENANCE_PROVIDER, PROPERTY_ADMIN o ACCOUNTANT solo hacía
--   UPDATE users SET active=false, deleted_at=NOW() WHERE id = ?
-- El email quedaba retenido por el índice único `users.email`, así que cualquier
-- intento posterior de registrar una nueva cuenta (del tipo que fuera) con ese
-- mismo correo fallaba con "Ya existe un usuario con ese email".
--
-- Desde el fix del ciclo de vida de staff/provider (OwnerCascadeDeletionService
-- .deleteOrTombstoneStaffOrProvider) las bajas nuevas:
--   · Hacen hard-delete cuando el user no tiene actividad histórica (cero
--     cotizaciones, tickets, egresos, movimientos como actor).
--   · Tombstonean el email si el user sí tiene actividad, renombrando
--     `users.email` a `deleted-<id8>-<ts>@deleted.admindi.local` para liberar
--     el índice único sin romper la FK hacia las filas del histórico.
--
-- Esta migración aplica la misma lógica a los staff/provider que ya quedaron
-- atrapados con el bug antiguo (active=false AND deleted_at IS NOT NULL),
-- liberando sus emails originales de manera atómica al primer arranque.
--
-- Alcance:
--   · Solo filas con role IN (REAL_ESTATE_AGENT, MAINTENANCE_PROVIDER,
--     PROPERTY_ADMIN, ACCOUNTANT).
--   · Solo si active=false AND deleted_at IS NOT NULL.
--   · SUPER_ADMIN, OWNER, TENANT no se tocan (ver V37/V38/V39 para owners).
--   · Si el user tiene actividad en tablas operativas (ver lista abajo) se
--     tombstonea el email y se limpian datos personales colaterales.
--   · Si no tiene actividad se hace hard-delete (como el path nuevo en el
--     runtime).
--
-- Tablas consideradas "actividad bloqueante" (cuya FK apunta a users.id por
-- valor y no tiene cascade): maintenance_quotes.provider_id,
-- maintenance_tickets.assigned_provider_id, expenses.provider_user_id,
-- maintenance_budgets.provider_user_id / submitted_by_user_id /
-- decided_by_user_id, commercial_activities.agent_user_id,
-- property_movements.actor_user_id, manual_payment_reminders_sent.actor_user_id,
-- payment_reminders_sent.recipient_user_id, tenant_archive_snapshots
-- .archived_by_user_id.
--
-- Seguridad: idempotente. Si ninguna fila cumple la condición, el loop no entra.
-- Cualquier fallo de FK en un DELETE rompe toda la migración (rollback total)
-- sin dejar estado intermedio. El placeholder usado para tombstone se genera
-- con md5(id) para evitar colisiones al correr en batch.

DO $$
DECLARE
    legacy_user_id   VARCHAR(255);
    legacy_email     VARCHAR(255);
    has_activity     BOOLEAN;
    tombstone_email  VARCHAR(255);
    ts_suffix        VARCHAR(32);
BEGIN
    ts_suffix := to_char(NOW(), 'YYYYMMDDHH24MISS');

    FOR legacy_user_id, legacy_email IN
        SELECT id, email FROM users
         WHERE active = false
           AND deleted_at IS NOT NULL
           AND role IN ('REAL_ESTATE_AGENT', 'MAINTENANCE_PROVIDER',
                        'PROPERTY_ADMIN', 'ACCOUNTANT')
           AND email NOT LIKE 'deleted-%@deleted.admindi.local'
    LOOP
        -- 1) Determinar si hay actividad histórica bloqueante.
        has_activity := false;

        IF EXISTS (SELECT 1 FROM maintenance_quotes WHERE provider_id = legacy_user_id) THEN
            has_activity := true;
        ELSIF EXISTS (SELECT 1 FROM maintenance_tickets WHERE assigned_provider_id = legacy_user_id) THEN
            has_activity := true;
        ELSIF EXISTS (SELECT 1 FROM expenses WHERE provider_user_id = legacy_user_id) THEN
            has_activity := true;
        ELSIF EXISTS (SELECT 1 FROM maintenance_budgets
                       WHERE provider_user_id = legacy_user_id
                          OR submitted_by_user_id = legacy_user_id
                          OR decided_by_user_id = legacy_user_id) THEN
            has_activity := true;
        ELSIF EXISTS (SELECT 1 FROM commercial_activities WHERE agent_user_id = legacy_user_id) THEN
            has_activity := true;
        ELSIF EXISTS (SELECT 1 FROM property_movements WHERE actor_user_id = legacy_user_id) THEN
            has_activity := true;
        ELSIF EXISTS (SELECT 1 FROM manual_payment_reminders_sent WHERE actor_user_id = legacy_user_id) THEN
            has_activity := true;
        ELSIF EXISTS (SELECT 1 FROM payment_reminders_sent WHERE recipient_user_id = legacy_user_id) THEN
            has_activity := true;
        ELSIF EXISTS (SELECT 1 FROM tenant_archive_snapshots WHERE archived_by_user_id = legacy_user_id) THEN
            has_activity := true;
        END IF;

        IF has_activity THEN
            -- 2a) Tombstone: renombrar email + limpiar datos personales.
            --
            --     El placeholder combina id truncado a 8 chars + md5 breve para
            --     unicidad dentro del batch (dos users distintos con mismo prefijo
            --     de id nunca colisionarían) y timestamp de la migración.
            tombstone_email := 'deleted-'
                || substr(legacy_user_id, 1, 8) || '-'
                || substr(md5(legacy_user_id), 1, 6) || '-'
                || ts_suffix || '@deleted.admindi.local';

            RAISE NOTICE '[V43] TOMBSTONE user=% originalEmail=% newEmail=%',
                legacy_user_id, legacy_email, tombstone_email;

            UPDATE users
               SET email                 = tombstone_email,
                   contact_email         = NULL,
                   contact_phone         = NULL,
                   contact_country_code  = NULL,
                   phone                 = NULL,
                   mfa_enabled           = false,
                   mfa_secret            = NULL,
                   must_change_password  = true
             WHERE id = legacy_user_id;

            -- Limpiar datos personales (PII) y accesos.
            DELETE FROM refresh_token_sessions  WHERE user_id = legacy_user_id;
            DELETE FROM notification_preferences WHERE user_id = legacy_user_id;
            DELETE FROM notifications           WHERE user_id = legacy_user_id;
            DELETE FROM action_tasks            WHERE user_id = legacy_user_id;
            DELETE FROM owner_memberships       WHERE user_id = legacy_user_id;
            DELETE FROM permission_grants       WHERE user_id = legacy_user_id;
            DELETE FROM user_permissions        WHERE user_id = legacy_user_id;

            -- Desactivar assignments (no borrar: pueden estar referenciadas
            -- desde maintenance_tickets.assigned_provider_id).
            UPDATE platform_provider_assignments
               SET active = false
             WHERE provider_id = legacy_user_id;
        ELSE
            -- 2b) Hard-delete: sin actividad, barrer todo y liberar email.
            RAISE NOTICE '[V43] HARD_DELETE user=% email=%', legacy_user_id, legacy_email;

            DELETE FROM refresh_token_sessions          WHERE user_id     = legacy_user_id;
            DELETE FROM notification_preferences        WHERE user_id     = legacy_user_id;
            DELETE FROM notifications                   WHERE user_id     = legacy_user_id;
            DELETE FROM action_tasks                    WHERE user_id     = legacy_user_id;
            DELETE FROM platform_provider_assignments   WHERE provider_id = legacy_user_id;
            DELETE FROM owner_memberships               WHERE user_id     = legacy_user_id;
            DELETE FROM permission_grants               WHERE user_id     = legacy_user_id;
            DELETE FROM user_permissions                WHERE user_id     = legacy_user_id;

            -- audit_events del user con actor_id apuntando a él se conservan
            -- como historia general. No las borramos porque el user ya no va
            -- a existir y no hay FK hacia users.id desde audit_events.
            DELETE FROM users WHERE id = legacy_user_id;
        END IF;
    END LOOP;
END $$;
