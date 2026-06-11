-- V52 — Re-imponer unicidad global del campo users.email.
--
-- Contexto: V50 retiró el UNIQUE de email para permitir un modelo donde el
-- humano se reutilizaba por email con distinto rol. El producto decidió
-- revertir esa política: cada humano = una única cuenta en users, con email
-- único en toda la tabla. El identificador de login sigue siendo username
-- (V48) — la unicidad del email es complementaria, no reemplaza al username.
--
-- Esta migración:
--   1. Verifica que NO existan duplicados activos antes de crear el índice
--      (si existen, aborta con mensaje claro para que operaciones limpie).
--   2. Crea un índice UNIQUE case-insensitive sobre LOWER(email). Postgres
--      trata NULL como distinto por defecto en UNIQUE, así que SUPER_ADMIN
--      (email NULL) y cualquier cuenta legítimamente sin email conviven.
--   3. Los tombstones (deleted-<id8>-<ts>@deleted.admindi.local) y los
--      emails purgados (purged_<userId>@anonymous.local) son únicos por
--      construcción (incluyen el userId), así que no colisionan.
--   4. Actualiza el comentario Postgres del campo para reflejar la política.
--
-- Ver docs/ARCHITECTURE_IDENTITY_AND_EXPEDIENTES.md §3.3 (revisión V52).

BEGIN;

-- 1. Safety check: no debe haber duplicados case-insensitive. Si los hay,
--    el operador debe resolverlos manualmente antes de correr la migración.
DO $$
DECLARE
    v_dup_count INTEGER;
    v_sample TEXT;
BEGIN
    SELECT COUNT(*) INTO v_dup_count FROM (
        SELECT LOWER(email) AS e
        FROM users
        WHERE email IS NOT NULL
        GROUP BY LOWER(email)
        HAVING COUNT(*) > 1
    ) d;

    IF v_dup_count > 0 THEN
        SELECT string_agg(e, ', ') INTO v_sample FROM (
            SELECT LOWER(email) AS e
            FROM users
            WHERE email IS NOT NULL
            GROUP BY LOWER(email)
            HAVING COUNT(*) > 1
            LIMIT 5
        ) s;
        RAISE EXCEPTION
            'V52 aborted: % duplicate email(s) found in users table. Resolve before migrating. Examples: %',
            v_dup_count, v_sample;
    END IF;
END $$;

-- 2. Crear UNIQUE index case-insensitive. Nombre distinto al que usaba V1
--    (users_email_key) para no confundir auditorías. Usamos CREATE UNIQUE
--    INDEX (no ADD CONSTRAINT) porque necesitamos la expresión LOWER(email).
DROP INDEX IF EXISTS idx_users_email_lower_unique;
CREATE UNIQUE INDEX idx_users_email_lower_unique
    ON users (LOWER(email));

-- 3. Reflejar la política en el comentario de la columna.
COMMENT ON COLUMN users.email IS
  'Email de contacto/notificación, UNIQUE case-insensitive (V52). NULL permitido para cuentas sistema (SUPER_ADMIN). NO es identificador de login: el login sigue siendo username (V48). Tombstones y purgados incluyen user_id en el placeholder y son únicos por construcción.';

COMMIT;
