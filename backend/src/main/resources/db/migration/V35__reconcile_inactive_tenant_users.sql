-- V35: Reconciliar expedientes cuyos usuarios fueron desactivados pero quedaron sin archivar.
--
-- Contexto:
--   Antes del fix de reautenticación en el modal de baja de arrendatario (endpoint
--   DELETE /api/tenants/{id}), ciertos flujos administrativos podían desactivar al
--   usuario tenant (users.active=false) sin completar la baja operativa del expediente
--   (tenant_profiles.archived_at seguía en NULL). Esto dejaba "expedientes fantasma":
--     · TenantService.getMyTenants() los ocultaba (filtra por user.isActive()),
--     · pero MetricsService y OwnerAccountingSummaryService los sumaban como renta
--       esperada / morosidad (solo filtraban archived_at IS NULL).
--
--   Síntoma visible reportado por dueño:
--     "Inquilinos en adeudo: 2" y "Suma de rentas ligadas: $70,000" cuando la pantalla
--     de Arrendatarios estaba vacía (0 inquilinos visibles).
--
-- Regla de negocio (V35+):
--   · Un inquilino con user.active=false ya no debe contar como renta esperada ni como
--     moroso. Si lo eliminamos, no va a pagar: su expediente queda solo como historia.
--   · Se archiva el profile (archived_at=NOW()) para unificar el criterio con el resto
--     del sistema (mismo filtro que getMyTenants y que la nueva versión de MetricsService).
--   · Las facturas abiertas (no PAID y no VOID/CANCELLED) se anulan (status=VOID,
--     settlement_status=CANCELLED, outstanding_amount=0) — representan meses "NO PAGÓ"
--     que NO deben seguir infiltrándose como cuentas por cobrar del mes vigente.
--   · Las facturas PAID se preservan INTACTAS: son ingresos reales ya cobrados y forman
--     parte del histórico financiero inmutable del expediente. El snapshot (cuando
--     existe en tenant_archive_snapshots) ya registra "entró en enero, pagó enero, no
--     pagó feb-jul, se fue en agosto" como corresponde.
--
-- Esta migración es idempotente: si vuelve a correr sin registros sucios, no hace nada.

-- 1) Archivar profiles cuyo user está inactivo y el profile nunca se archivó.
UPDATE tenant_profiles tp
   SET archived_at = COALESCE(tp.archived_at, NOW())
 WHERE tp.archived_at IS NULL
   AND EXISTS (
        SELECT 1 FROM users u
         WHERE u.id = tp.user_id
           AND u.active = false
   );

-- 2) Anular facturas abiertas de esos expedientes (recién archivados o con user inactivo).
--    Se respetan PAID (historial de ingreso) y los estados terminales VOID/VOIDED/CANCELLED.
UPDATE invoices i
   SET status              = 'VOID',
       settlement_status   = 'CANCELLED',
       outstanding_amount  = 0
 WHERE UPPER(COALESCE(i.status, ''))            NOT IN ('PAID','VOID','VOIDED','CANCELLED','CANCELED')
   AND i.tenant_profile_id IS NOT NULL
   AND EXISTS (
        SELECT 1
          FROM tenant_profiles tp
          JOIN users u ON u.id = tp.user_id
         WHERE tp.id = i.tenant_profile_id
           AND (tp.archived_at IS NOT NULL OR u.active = false)
   );

-- 3) Simétrico: garantizar coherencia de property.status con V34. Si al archivar profiles
--    quedó algún inmueble marcado OCCUPIED sin lease ACTIVE ni expediente activo, V34 ya
--    lo cubre en la próxima lectura; esta migración no toca properties.
