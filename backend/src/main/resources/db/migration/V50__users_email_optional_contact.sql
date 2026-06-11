-- V50 — Identidad final: username es el único login, email queda como dato de
-- contacto opcional (nullable, no único). Esta migración:
--
-- 1. Libera users.email de NOT NULL y UNIQUE (la unicidad que queda es la de
--    users.username, creada por V48). Un humano puede aparecer con el mismo
--    email en distintas cuentas (p.ej. tenant del dueño A y agente del dueño B),
--    sin que eso dispare colisiones de login, porque el identificador único es
--    el username.
-- 2. Borra la fila legacy del SUPER_ADMIN sembrada por V3 (superadmin@admindi.com
--    con id 'admin-0000-0000'). El seed se reescribe en código: AdminSeeder crea
--    ahora el SA 'davidsuperadmin-2026' con email NULL. Esta migración sólo
--    garantiza que la fila vieja no quede residual si alguien reinicia con DB
--    ya poblada. En un wipe limpio es idempotente (DELETE sobre 0 filas).
-- 3. Anota en users.email un comentario Postgres para documentar la invariante.
--
-- Referencias: docs/ARCHITECTURE_IDENTITY_AND_EXPEDIENTES.md §3 y plan rector
-- punto "Endurecimiento final: email deja de ser login".

BEGIN;

-- 1. Liberar UNIQUE en email. El nombre del constraint proviene del auto-naming
--    de V1__init_schema.sql (CONSTRAINT implícito al declarar UNIQUE en la
--    columna). Lo resolvemos dinámicamente para no acoplar a un nombre exacto.
DO $$
DECLARE
    v_constraint_name TEXT;
BEGIN
    SELECT conname INTO v_constraint_name
    FROM pg_constraint
    WHERE conrelid = 'users'::regclass
      AND contype  = 'u'
      AND pg_get_constraintdef(oid) ILIKE '%(email)%';
    IF v_constraint_name IS NOT NULL THEN
        EXECUTE format('ALTER TABLE users DROP CONSTRAINT %I', v_constraint_name);
    END IF;
END $$;

-- Si además existía un índice UNIQUE separado (otros flyway en el pasado):
DROP INDEX IF EXISTS users_email_key;
DROP INDEX IF EXISTS idx_users_email_unique;

-- 2. Liberar NOT NULL. Idempotente: Postgres acepta SET NULL incluso si ya
--    estaba permitido.
ALTER TABLE users ALTER COLUMN email DROP NOT NULL;

-- 3. Barrer la fila legacy del seed V3. Comentarios del V38 ya respetaban esta
--    fila — ahora la eliminamos explícitamente porque el nuevo AdminSeeder
--    emite una identidad distinta (davidsuperadmin-2026) y no queremos dos SA.
DELETE FROM users WHERE id = 'admin-0000-0000';

-- Documentar la nueva semántica del campo para operadores de DB.
COMMENT ON COLUMN users.email IS
  'Email de contacto (opcional, no único). NO es identificador de login desde V50: username es la identidad primaria. SUPER_ADMIN lo tiene NULL porque no recibe notificaciones.';
COMMENT ON COLUMN users.username IS
  'Identificador de login, único globalmente (V48). Obligatorio en alta. Al archivar/eliminar se tombstonea (V48) para liberarlo.';

COMMIT;
