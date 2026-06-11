-- =====================================================================
-- V46 — Trazabilidad de subidas de archivos (IDOR / file ownership)
-- ---------------------------------------------------------------------
-- Problema que resuelve (security audit Fase 2):
--   Los endpoints POST /api/owner/workflow/files/upload y equivalentes de
--   agentes / proveedores devuelven un `fileId` (path interno tipo
--   `/uploads/<categoria>/<uuid>.<ext>`) que luego se referencia por body
--   en operaciones sensibles (submitSpeiProof, ownerPayAndClose, evidencia
--   de mantenimiento). Antes de V46 NO existía ningún registro de quién
--   subió qué archivo: cualquier usuario autenticado podía especular con
--   un path ajeno y "adoptarlo" para su propio recurso.
--
--   Aunque el path incluye un UUID (128 bits) y el riesgo de adivinación
--   es bajo, basta con una filtración de logs o un token robado para que
--   el atacante re-use un comprobante ajeno (p.ej. un comprobante SPEI
--   válido de otro dueño para cerrar un ticket sin haber pagado nada).
--
-- Diseño:
--   * Cada upload genera una fila en `file_upload_claims` con
--     `uploader_user_id` y `file_path` (único).
--   * Cuando una operación consume el path (p.ej. `submitSpeiProof`),
--     el servicio verifica `uploader_user_id == expectedUserId` y marca
--     `consumed_at`, `consumed_resource_type`, `consumed_resource_id`.
--   * `consumed_at IS NOT NULL` convierte la claim en "sellada": volver a
--     intentar usarla se considera inválido (idempotencia, anti-replay).
-- =====================================================================

CREATE TABLE IF NOT EXISTS file_upload_claims (
    id                       VARCHAR(36)  PRIMARY KEY,
    file_path                VARCHAR(512) NOT NULL,
    uploader_user_id         VARCHAR(36)  NOT NULL,
    category                 VARCHAR(80),
    -- Contexto opcional cuando el controller ya sabe a quién se dirige
    -- el archivo (p.ej. "se sube para el ticket X"). Ayuda a la auditoría
    -- y evita ataques donde alguien sube un archivo a la categoría X y
    -- lo consume en la categoría Y.
    expected_resource_type   VARCHAR(80),
    expected_resource_id     VARCHAR(80),

    created_at               TIMESTAMP    NOT NULL,
    consumed_at              TIMESTAMP,
    consumed_resource_type   VARCHAR(80),
    consumed_resource_id     VARCHAR(80),

    -- UNIQUE garantiza que el mismo path no pueda ser reclamado por dos
    -- uploaders distintos (el FileStorageService ya genera UUID, este
    -- constraint actúa como defensa en profundidad).
    CONSTRAINT uq_file_claims_path UNIQUE (file_path)
);

CREATE INDEX IF NOT EXISTS idx_file_claims_uploader
    ON file_upload_claims (uploader_user_id);

CREATE INDEX IF NOT EXISTS idx_file_claims_consumed
    ON file_upload_claims (consumed_at);
