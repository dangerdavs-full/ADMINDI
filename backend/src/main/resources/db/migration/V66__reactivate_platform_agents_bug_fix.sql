-- V66 — Reparación de datos: reactivar agentes PLATFORM que quedaron como
-- inactivos por el bug de unlinkProviderFromOwner pre-V67.
--
-- CONTEXTO
-- --------
-- Hasta V67, la función {@code unlinkProviderFromOwner} aplicaba la misma
-- política de tombstone a providers PRIVATE y PLATFORM: si al desvincular no
-- le quedaba ningún assignment activo, desactivaba el usuario. Pero la regla
-- correcta del operador era:
--   · PRIVATE → hard-delete / tombstone (single-context, la cuenta
--     desaparece cuando el dueño la desvincula).
--   · PLATFORM → sólo desactivar el VÍNCULO; la cuenta permanece en el
--     catálogo para poder re-vincularse con este u otro dueño.
--
-- Como consecuencia, usuarios PLATFORM legítimos quedaron con active=false
-- en la tabla users, impidiendo que los dueños los re-vinculen (no aparecen
-- en el catálogo visible porque {@code listPlatformCatalog} filtra por
-- {@code isActive()}).
--
-- ESTRATEGIA
-- ----------
-- Restauramos el estado correcto: cualquier user con rol MAINTENANCE_PROVIDER
-- o REAL_ESTATE_AGENT que tenga al menos UN assignment con
-- {@code assignment_source = 'PLATFORM'} debe estar activo, SALVO que:
--   · su {@code username} empiece con el prefijo de tombstone explícito
--     'tombstone-' (decidido intencionalmente por el lifecycle service, no
--     queremos resucitar esos); o
--   · su campo {@code username_tombstoned_at} esté poblado (mismo criterio).
--
-- Idempotente: sólo afecta usuarios que cumplen la condición de bug.

BEGIN;

UPDATE users u
   SET active = true
 WHERE u.role IN ('MAINTENANCE_PROVIDER', 'REAL_ESTATE_AGENT')
   AND u.active = false
   AND u.username IS NOT NULL
   AND u.username NOT LIKE 'tombstone-%'
   AND u.username_tombstoned_at IS NULL
   AND EXISTS (
         SELECT 1 FROM platform_provider_assignments p
          WHERE p.provider_id = u.id
            AND p.assignment_source = 'PLATFORM'
       );

COMMIT;
