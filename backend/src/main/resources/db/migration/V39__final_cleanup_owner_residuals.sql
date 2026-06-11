-- V39: Garantía de cero residuos de dueños en la base.
--
-- Contexto: V37 ya barrió los OWNER con deleted_at IS NOT NULL. Sin embargo,
-- pudieron existir dueños marcados solo con active=false (sin deleted_at)
-- por flujos legados anteriores a la cascada, crashes parciales durante el
-- soft-delete previo al fix, o imports manuales. Esta migración cierra la
-- puerta definitivamente: después de V39 el invariante es
--
--     NOT EXISTS (SELECT 1 FROM users
--                  WHERE role = 'OWNER'
--                    AND (active = false OR deleted_at IS NOT NULL))
--
-- y la única forma de tener un OWNER en la base es activo. Cualquier baja
-- futura va por el endpoint reauth-protegido DELETE /api/admin/owners/{id}
-- que delega en OwnerCascadeDeletionService.hardDeleteOwner y deja 0 filas
-- del dueño en todas las tablas.
--
-- Regla operativa del SUPERADMIN: "si elimino a un dueño no queda nada de él
-- en el sistema, ni sus datos, ni sus archivos, ni sus usuarios exclusivos".
-- Este requisito es de seguridad y compliance: el hard-delete con MFA +
-- password + motivo es la acción irrevocable; no puede coexistir con un
-- estado "pseudo-eliminado" porque eso generaría ambigüedad legal y de UX
-- (el email bloqueado sin que el SUPERADMIN sepa por qué).
--
-- Alcance: solo toca OWNER en estado sucio. Los arrendatarios/staff que
-- hayan perdido todo contexto con esos dueños se deberán purgar
-- individualmente desde "Gestión Global de Cuentas" (no asumimos que
-- quedaron huérfanos porque pudieron tener otros dueños).
--
-- Idempotencia: el LOOP no entra si no hay filas sucias, la migración es
-- no-op en ambientes ya limpios.

DO $$
DECLARE
    dead_owner_id VARCHAR(255);
    total_purged INTEGER := 0;
BEGIN
    FOR dead_owner_id IN
        SELECT id
          FROM users
         WHERE role = 'OWNER'
           AND (active = false OR deleted_at IS NOT NULL)
    LOOP
        RAISE NOTICE '[V39] purging residual OWNER % (full cascade)', dead_owner_id;

        -- Mismo orden que OwnerCascadeDeletionService.hardDeleteOwner — si algo
        -- se modifica ahí debe replicarse aquí para mantener paridad entre
        -- el cleanup SQL y el cleanup aplicativo.

        -- 1) Convenios (hijos primero).
        DELETE FROM agreement_installments
         WHERE agreement_id IN (SELECT id FROM payment_agreements WHERE owner_id = dead_owner_id);
        DELETE FROM payment_agreements WHERE owner_id = dead_owner_id;

        -- 2) Comprobantes (CEP → transfer proofs).
        DELETE FROM cep_validation_attempts
         WHERE transfer_proof_id IN (SELECT id FROM transfer_proof_submissions WHERE owner_id = dead_owner_id);
        DELETE FROM transfer_proof_submissions WHERE owner_id = dead_owner_id;

        -- 3) Pagos y facturas.
        DELETE FROM payments WHERE owner_id = dead_owner_id;
        DELETE FROM invoices WHERE owner_id = dead_owner_id;

        -- 4) Contratos + archivos.
        DELETE FROM lease_files
         WHERE lease_id IN (SELECT id FROM leases WHERE owner_id = dead_owner_id);
        DELETE FROM leases WHERE owner_id = dead_owner_id;

        -- 5) Vacancias + actividades comerciales.
        DELETE FROM commercial_activities
         WHERE vacancy_id IN (SELECT id FROM vacancies WHERE owner_id = dead_owner_id);
        DELETE FROM vacancies WHERE owner_id = dead_owner_id;

        -- 6) Mantenimiento.
        DELETE FROM maintenance_quotes
         WHERE ticket_id IN (SELECT id FROM maintenance_tickets WHERE owner_id = dead_owner_id);
        DELETE FROM maintenance_tickets WHERE owner_id = dead_owner_id;
        DELETE FROM maintenance_budgets WHERE owner_id = dead_owner_id;

        -- 7) Egresos + movimientos.
        DELETE FROM expenses WHERE owner_id = dead_owner_id;
        DELETE FROM property_movements WHERE owner_id = dead_owner_id;

        -- 8) Inmuebles: archivos → unidades → properties.
        DELETE FROM property_files
         WHERE property_id IN (SELECT id FROM properties WHERE owner_id = dead_owner_id);
        DELETE FROM units WHERE owner_id = dead_owner_id;
        DELETE FROM properties WHERE owner_id = dead_owner_id;

        -- 9) Expedientes + snapshots archivados.
        DELETE FROM tenant_profiles WHERE owner_id = dead_owner_id;
        DELETE FROM tenant_archive_snapshots WHERE owner_id = dead_owner_id;

        -- 10) Plataforma.
        DELETE FROM platform_provider_assignments WHERE owner_id = dead_owner_id;

        -- 11) Permisos + memberships.
        DELETE FROM permission_grants WHERE owner_id = dead_owner_id;
        DELETE FROM owner_memberships WHERE owner_id = dead_owner_id;

        -- 12) Notificaciones + action tasks con owner_id del eliminado.
        DELETE FROM notifications WHERE owner_id = dead_owner_id;
        DELETE FROM action_tasks WHERE owner_id = dead_owner_id;

        -- 13) Audit events del dueño.
        DELETE FROM audit_events WHERE owner_id = dead_owner_id;

        -- 14) Limpieza informativa de users.owner_id en staff/tenants que
        --     apuntan como "primary context" al dueño que se va. Si esos
        --     usuarios tienen memberships/profiles con otros dueños, los
        --     conservamos pero nulamos el apuntador colgante. Si no tienen
        --     otro contexto, el SUPERADMIN decidirá desde Gestión Global de
        --     Cuentas (no auto-purgamos porque pueden tener permissions
        --     aún válidos en otros contextos futuros).
        UPDATE users
           SET owner_id = NULL
         WHERE owner_id = dead_owner_id;

        -- 15) Dependencias personales del propio user dueño.
        DELETE FROM refresh_token_sessions WHERE user_id = dead_owner_id;
        DELETE FROM notification_preferences WHERE user_id = dead_owner_id;

        -- 16) User del dueño. Esto libera el email para reúso.
        DELETE FROM users WHERE id = dead_owner_id;

        total_purged := total_purged + 1;
    END LOOP;

    IF total_purged > 0 THEN
        RAISE NOTICE '[V39] finalizada: % dueños residuales purgados completamente', total_purged;
    ELSE
        RAISE NOTICE '[V39] no-op: no había dueños en estado sucio (base ya consistente)';
    END IF;
END $$;
