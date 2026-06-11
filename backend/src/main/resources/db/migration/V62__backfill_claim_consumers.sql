-- V62 — Backfill de consumed_resource_type/id en file_upload_claims.
--
-- CONTEXTO
-- --------
-- La tabla file_upload_claims (V46) registra quién subió cada archivo. Desde
-- V61 los servicios que consumen un archivo llaman fileOwnership.markConsumed
-- para estampar consumed_resource_type / consumed_resource_id. Eso habilita
-- que SecureFileController.downloadFileAttachment autorice por recurso
-- (ticket, quote, etc.) en lugar de caer al fallback "solo uploader".
--
-- Los archivos subidos ANTES de ese cable quedaron con consumed_* = NULL,
-- por lo que el dueño recibe 403 al intentar abrir el PDF del proveedor
-- aunque el archivo le pertenezca a su organización.
--
-- Esta migración hace backfill idempotente mirando las tablas que ya guardan
-- los paths (maintenance_quotes.evidence_file_id,
-- maintenance_tickets.photo_file_ids jsonb) y estampa los campos consumed_*
-- correspondientes. Las filas ya marcadas se respetan (primera escritura gana).

BEGIN;

-- ─────────────────────────────────────────────────────────────────────────────
-- 1) Backfill MAINTENANCE_QUOTE — cotizaciones cuyo PDF/imagen apunta a un claim.
-- ─────────────────────────────────────────────────────────────────────────────
UPDATE file_upload_claims c
   SET consumed_at = COALESCE(c.consumed_at, NOW()),
       consumed_resource_type = 'MAINTENANCE_QUOTE',
       consumed_resource_id = q.id
  FROM maintenance_quotes q
 WHERE q.evidence_file_id IS NOT NULL
   AND q.evidence_file_id = c.file_path
   AND c.consumed_resource_type IS NULL;

-- ─────────────────────────────────────────────────────────────────────────────
-- 2) Backfill MAINTENANCE_TICKET — fotos subidas por el inquilino que están
--    referenciadas en maintenance_tickets.photo_file_ids (array jsonb).
--
--    Expandimos el array a filas con jsonb_array_elements_text y hacemos join
--    contra claims. Un mismo path podría aparecer en varios tickets (muy raro
--    pero teóricamente posible si hubo copia de DB); el DISTINCT ON garantiza
--    que cada claim se marca con UN ticket — el de menor id para ser estable.
-- ─────────────────────────────────────────────────────────────────────────────
UPDATE file_upload_claims c
   SET consumed_at = COALESCE(c.consumed_at, NOW()),
       consumed_resource_type = 'MAINTENANCE_TICKET',
       consumed_resource_id = sub.ticket_id
  FROM (
        SELECT DISTINCT ON (photo_path)
               t.id AS ticket_id,
               elem AS photo_path
          FROM maintenance_tickets t,
               LATERAL jsonb_array_elements_text(
                    CASE WHEN jsonb_typeof(t.photo_file_ids) = 'array'
                         THEN t.photo_file_ids
                         ELSE '[]'::jsonb
                    END) AS elem
         WHERE t.photo_file_ids IS NOT NULL
         ORDER BY photo_path, t.id ASC
       ) sub
 WHERE sub.photo_path = c.file_path
   AND c.consumed_resource_type IS NULL;

COMMIT;
