-- V53 — Unicidad del campo users.contact_email vía columna hash paralela.
--
-- Problema: users.contact_email está cifrado columnwise con AES-256-GCM (IV
-- aleatorio, ver EncryptedStringConverter). El ciphertext es NO-determinístico,
-- por lo que un índice UNIQUE directo sobre la columna no protege unicidad:
-- dos filas con el mismo email en claro tendrán ciphertext distinto y el
-- índice las aceptaría como distintas.
--
-- Solución: columna paralela {@code contact_email_hash} con SHA-256 hex del
-- email normalizado (LOWER + TRIM). El hash es determinístico, comparable,
-- y UNIQUE a nivel DB. La lógica de sincronización vive en UserEntity vía
-- @PrePersist/@PreUpdate — cualquier cambio a contact_email recalcula el hash
-- automáticamente, indep. del call-site (there are ~20).
--
-- Esta migración:
--   1. Crea la columna contact_email_hash VARCHAR(64) NULLABLE.
--   2. Backfill inmediato para valores plaintext (sin prefijo 'enc:v1:') —
--      usable en dev/local/test y también en prod para filas legacy pre-cifrado.
--   3. Safety check: aborta si el backfill genera duplicados.
--   4. UNIQUE index parcial (WHERE contact_email_hash IS NOT NULL) — en prod
--      las filas cifradas quedan con hash NULL hasta que el runner Java
--      (ContactEmailHashBackfillRunner) las procesa al arranque. NULLs son
--      tratados como distintos por defecto, permite el backfill gradual sin
--      bloquear arranques.
--   5. Documenta la columna con COMMENT.

BEGIN;

-- 1. Columna hash paralela.
ALTER TABLE users ADD COLUMN IF NOT EXISTS contact_email_hash VARCHAR(64);

-- 2. Backfill directo para valores plaintext. digest() está en pgcrypto; lo
--    activamos si no está (requiere permisos estándar de una DB Postgres).
CREATE EXTENSION IF NOT EXISTS pgcrypto;

UPDATE users
   SET contact_email_hash = encode(digest(LOWER(TRIM(contact_email)), 'sha256'), 'hex')
 WHERE contact_email IS NOT NULL
   AND contact_email NOT LIKE 'enc:v1:%'
   AND contact_email_hash IS NULL;

-- 3. Safety check: no deben existir duplicados post-backfill. Si hay, el
--    operador debe resolverlos manualmente antes de correr la migración.
DO $$
DECLARE
    v_dup_count INTEGER;
    v_sample TEXT;
BEGIN
    SELECT COUNT(*) INTO v_dup_count FROM (
        SELECT contact_email_hash
        FROM users
        WHERE contact_email_hash IS NOT NULL
        GROUP BY contact_email_hash
        HAVING COUNT(*) > 1
    ) d;

    IF v_dup_count > 0 THEN
        SELECT string_agg(contact_email_hash, ', ') INTO v_sample FROM (
            SELECT contact_email_hash
            FROM users
            WHERE contact_email_hash IS NOT NULL
            GROUP BY contact_email_hash
            HAVING COUNT(*) > 1
            LIMIT 5
        ) s;
        RAISE EXCEPTION
            'V53 aborted: % duplicate contact_email hash(es) after backfill. Resolve before migrating. Hashes (prefix): %',
            v_dup_count, v_sample;
    END IF;
END $$;

-- 4. UNIQUE index parcial sobre el hash. Postgres trata NULLs como distintos
--    por defecto, así que las filas cifradas pendientes (contact_email_hash IS
--    NULL) no bloquean el índice; el runner Java las hashea al arranque.
DROP INDEX IF EXISTS idx_users_contact_email_hash_unique;
CREATE UNIQUE INDEX idx_users_contact_email_hash_unique
    ON users (contact_email_hash)
    WHERE contact_email_hash IS NOT NULL;

-- 5. Documentar columna.
COMMENT ON COLUMN users.contact_email_hash IS
  'SHA-256 hex de LOWER(TRIM(contact_email)), UNIQUE global (V53). Columna derivada: se recalcula en @PrePersist/@PreUpdate de UserEntity cuando contact_email cambia. Existe para imponer unicidad sobre un campo cifrado columnwise AES-GCM (no determinístico). NULL permitido para cuentas sin contact_email o pendientes de backfill post-upgrade.';

COMMIT;
