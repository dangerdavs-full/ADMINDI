-- V60 — Ampliar columnas evidence_file_id / attachment_file_id que hoy son
-- VARCHAR(36) pero que reciben el fileId devuelto por FileStorageService —
-- un path interno tipo "/uploads/<categoria>/<uuid>.<ext>" de ~60-80 caracteres.
--
-- CONTEXTO
-- --------
-- V18 (Phase 3 closure) definió estas columnas como VARCHAR(36) asumiendo que
-- almacenarían un UUID puro. Sin embargo, el contrato que quedó en código es
-- distinto: FileStorageService.store() devuelve un path interno completo y
-- ese es el `fileId` que circula por los DTOs del frontend y que los
-- servicios persisten en estas columnas (ver MaintenanceWorkflowService.
-- providerSubmitQuote, ownerPayAndClose, etc.).
--
-- Ejemplo real del path: "/uploads/provider-evidence/9268e9f9-f86b-4ef8-
-- bea4-22792b38dbb2.pdf" = 66 caracteres. Al intentar insertar un valor
-- así en un VARCHAR(36), PostgreSQL rechaza con:
--
--     ERROR: el valor es demasiado largo para el tipo character varying(36)
--
-- Reportado por el agente de mantenimiento al subir el PDF de su cotización:
-- la transacción se rollback pero WhatsApp/Email ya habían salido (son side
-- effects HTTP externos que ocurren antes del commit), generando la confusión
-- de "llegó la notificación pero no se guardó".
--
-- CAMBIO
-- ------
-- Ampliamos a VARCHAR(512) — el mismo tamaño que `file_upload_claims.file_path`
-- (V46), que es el lugar canónico donde ya cabe el path completo. Con 512 se
-- cubre cualquier categoría razonable y se deja margen para futuros prefijos.
--
-- NO se agregan FKs aquí porque las columnas tradicionalmente almacenan el
-- path (no el UUID del claim). Si en el futuro se refactoriza el contrato
-- para guardar claim.id (y no path), vendrá en otra migración con backfill.
--
-- IMPACTO
-- -------
-- * Zero downtime: ALTER COLUMN … TYPE VARCHAR(512) en PostgreSQL es in-place
--   cuando el cambio es "ampliar" (metadatos only, sin reescribir filas).
-- * Filas existentes quedan intactas (los UUIDs de 36 chars caben en 512).
-- * Índices asociados se preservan automáticamente.

BEGIN;

-- ─────────────────────────────────────────────────────────────────────────────
-- 1) maintenance_quotes.evidence_file_id — CAUSANTE DIRECTO del error reportado.
--    El provider sube un PDF y el path del comprobante va aquí.
-- ─────────────────────────────────────────────────────────────────────────────
ALTER TABLE maintenance_quotes
    ALTER COLUMN evidence_file_id TYPE VARCHAR(512);

COMMENT ON COLUMN maintenance_quotes.evidence_file_id IS
  'V60: path interno retornado por FileStorageService para el comprobante del '
  'proveedor (PDF/JPG de la cotización). Antes era VARCHAR(36) — provocaba '
  'rollback silencioso con notificación WhatsApp fantasma.';

-- ─────────────────────────────────────────────────────────────────────────────
-- 2) expenses.evidence_file_id — mismo bug cuando se adjunta evidencia del pago
--    o del gasto en el módulo contable legacy.
-- ─────────────────────────────────────────────────────────────────────────────
ALTER TABLE expenses
    ALTER COLUMN evidence_file_id TYPE VARCHAR(512);

COMMENT ON COLUMN expenses.evidence_file_id IS
  'V60: path interno del documento soporte del gasto. Antes VARCHAR(36).';

-- ─────────────────────────────────────────────────────────────────────────────
-- 3) commercial_activities.evidence_file_id — mismo patrón, usado por el agente
--    inmobiliario al registrar actividad comercial con evidencia.
-- ─────────────────────────────────────────────────────────────────────────────
ALTER TABLE commercial_activities
    ALTER COLUMN evidence_file_id TYPE VARCHAR(512);

COMMENT ON COLUMN commercial_activities.evidence_file_id IS
  'V60: path interno del documento soporte de la actividad comercial. Antes VARCHAR(36).';

-- ─────────────────────────────────────────────────────────────────────────────
-- 4) property_files.attachment_file_id — si la columna existe (schema histórico
--    heterogéneo entre entornos), ampliamos. DO/IF garantiza idempotencia si
--    alguna organización no tiene la columna aún.
-- ─────────────────────────────────────────────────────────────────────────────
DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name = 'property_files' AND column_name = 'attachment_file_id'
    ) THEN
        ALTER TABLE property_files
            ALTER COLUMN attachment_file_id TYPE VARCHAR(512);
    END IF;
END $$;

COMMIT;
