-- V37: Hard-delete de owners con soft-delete pendiente (libera emails).
--
-- Contexto de negocio: antes de esta versión, `DELETE /api/admin/users/{id}` sobre
-- un rol OWNER solo desactivaba la cuenta (active=false, deleted_at=NOW). El email
-- quedaba reservado por el índice único de `users.email`, bloqueando cualquier
-- intento posterior de re-registrar un dueño con ese mismo correo ("Ya existe un
-- usuario con ese email"). Además, toda la data operativa del dueño (inmuebles,
-- contratos, facturas, convenios, expedientes...) permanecía en la base sin
-- propósito, inflando reportes y métricas.
--
-- Regla del operador: "si el SUPERADMIN elimina un dueño debe eliminar todo
-- registro del dueño en el sistema". Desde V37 el controller ya hace hard-delete
-- en cascada (ver OwnerCascadeDeletionService). Esta migración barre el estado
-- sucio de owners que quedaron en soft-delete ANTES del fix, para dejar la base
-- consistente y liberar los emails.
--
-- Alcance: solo toca filas con role='OWNER' y deleted_at IS NOT NULL. Los
-- arrendatarios/staff vinculados a esos dueños no se eliminan aquí (si tenían un
-- solo contexto podrían quedar huérfanos sin inmuebles/memberships; el SUPERADMIN
-- puede revisarlos en el panel). El objetivo único de esta migración es desatascar
-- el email y eliminar los datos operativos colgantes.
--
-- Seguridad: si no hay owners en ese estado, el loop simplemente no entra y la
-- migración es no-op.

DO $$
DECLARE
    dead_owner_id VARCHAR(255);
BEGIN
    FOR dead_owner_id IN
        SELECT id
          FROM users
         WHERE role = 'OWNER'
           AND deleted_at IS NOT NULL
    LOOP
        -- 1) Convenios: hijos → padre.
        DELETE FROM agreement_installments
         WHERE agreement_id IN (SELECT id FROM payment_agreements WHERE owner_id = dead_owner_id);
        DELETE FROM payment_agreements WHERE owner_id = dead_owner_id;

        -- 2) Comprobantes: CEP → transfer proofs.
        DELETE FROM cep_validation_attempts
         WHERE transfer_proof_id IN (SELECT id FROM transfer_proof_submissions WHERE owner_id = dead_owner_id);
        DELETE FROM transfer_proof_submissions WHERE owner_id = dead_owner_id;

        -- 3) Cobranza.
        DELETE FROM payments WHERE owner_id = dead_owner_id;
        DELETE FROM invoices WHERE owner_id = dead_owner_id;

        -- 4) Contratos y sus archivos.
        DELETE FROM lease_files
         WHERE lease_id IN (SELECT id FROM leases WHERE owner_id = dead_owner_id);
        DELETE FROM leases WHERE owner_id = dead_owner_id;

        -- 5) Vacancias y actividades comerciales.
        DELETE FROM commercial_activities
         WHERE vacancy_id IN (SELECT id FROM vacancies WHERE owner_id = dead_owner_id);
        DELETE FROM vacancies WHERE owner_id = dead_owner_id;

        -- 6) Mantenimiento.
        DELETE FROM maintenance_quotes
         WHERE ticket_id IN (SELECT id FROM maintenance_tickets WHERE owner_id = dead_owner_id);
        DELETE FROM maintenance_tickets WHERE owner_id = dead_owner_id;
        DELETE FROM maintenance_budgets WHERE owner_id = dead_owner_id;

        -- 7) Egresos y movimientos.
        DELETE FROM expenses WHERE owner_id = dead_owner_id;
        DELETE FROM property_movements WHERE owner_id = dead_owner_id;

        -- 8) Inmuebles: archivos → unidades → properties.
        DELETE FROM property_files
         WHERE property_id IN (SELECT id FROM properties WHERE owner_id = dead_owner_id);
        DELETE FROM units WHERE owner_id = dead_owner_id;
        DELETE FROM properties WHERE owner_id = dead_owner_id;

        -- 9) Expedientes y snapshots archivados.
        DELETE FROM tenant_profiles WHERE owner_id = dead_owner_id;
        DELETE FROM tenant_archive_snapshots WHERE owner_id = dead_owner_id;

        -- 10) Plataforma: asignaciones de proveedores.
        DELETE FROM platform_provider_assignments WHERE owner_id = dead_owner_id;

        -- 11) Permisos y memberships (ON DELETE CASCADE los barre al eliminar el user,
        --     pero los limpiamos explícitamente por claridad del orden).
        DELETE FROM permission_grants WHERE owner_id = dead_owner_id;
        DELETE FROM owner_memberships WHERE owner_id = dead_owner_id;

        -- 12) Notificaciones y action_tasks con owner_id del eliminado.
        DELETE FROM notifications WHERE owner_id = dead_owner_id;
        DELETE FROM action_tasks WHERE owner_id = dead_owner_id;

        -- 13) Audit events del dueño.
        DELETE FROM audit_events WHERE owner_id = dead_owner_id;

        -- 14) Dependencias del propio user.
        DELETE FROM refresh_token_sessions WHERE user_id = dead_owner_id;
        DELETE FROM notification_preferences WHERE user_id = dead_owner_id;

        -- 15) Finalmente el propio user. ON DELETE CASCADE en owner_memberships /
        --     permission_grants / user_permissions termina de barrer residuales.
        DELETE FROM users WHERE id = dead_owner_id;
    END LOOP;
END $$;
