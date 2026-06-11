-- V48 — Login por username: identidad independiente del email
--
-- Contexto (ver docs/ARCHITECTURE_IDENTITY_AND_EXPEDIENTES.md §3.2):
--   · El email pasa a ser un campo de contacto libre. Ya no identifica al login.
--   · El login usa `users.username`, único global.
--   · Un email puede repetirse entre cuentas distintas (un humano con cuenta tenant de
--     dueño A y cuenta agente privado de dueño B son dos filas independientes).
--
-- Este bloque (V48) SOLO introduce la columna y el índice único de `username`.
-- El constraint UNIQUE del email se retira en una migración posterior (V48b / Bloque 2)
-- para no romper la deduplicación de los callers legacy mientras se les reescribe.
-- Hasta entonces: `username = email` en la data existente, por backfill.
--
-- `username_tombstoned_at` marca cuentas archivadas cuyo username fue renombrado a
-- `tombstone-<id8>-<yyyyMMddHHmmss>` para liberar el índice unique y permitir recrear
-- la cuenta original por otro creador sin colisión (ver §4.1 del documento rector).

ALTER TABLE users
    ADD COLUMN IF NOT EXISTS username VARCHAR(64);

ALTER TABLE users
    ADD COLUMN IF NOT EXISTS username_tombstoned_at TIMESTAMP NULL;

-- Backfill: cada cuenta existente recibe username = email normalizado.
-- TRIM + LOWER para evitar duplicados por variaciones de case/whitespace que antes
-- colaban por el constraint UNIQUE del email (PostgreSQL en default collation es
-- case-sensitive). Si alguna fila tuviera username colision, la resolvemos
-- deterministicamente añadiendo un sufijo corto del id (raro en prod pero posible
-- en entornos con data de pruebas cargada a mano).
UPDATE users
SET username = LOWER(TRIM(email))
WHERE username IS NULL;

-- Desempata colisiones residuales (ej. mismo email con whitespace/case distinto).
WITH ranked AS (
    SELECT id, username,
           ROW_NUMBER() OVER (PARTITION BY username ORDER BY id) AS rn
    FROM users
)
UPDATE users u
SET username = ranked.username || '-' || LEFT(ranked.id, 6)
FROM ranked
WHERE u.id = ranked.id AND ranked.rn > 1;

ALTER TABLE users
    ALTER COLUMN username SET NOT NULL;

CREATE UNIQUE INDEX IF NOT EXISTS idx_users_username_unique
    ON users (username);
