-- V54 — Purga del campo users.email (legacy loginEmail).
--
-- Contexto: desde V48 el identificador de login es `username`; desde V52/V53
-- se intentó imponer unicidad de email/contact_email. El producto decidió
-- simplificar: un único campo de email (contact_email) en users, NO único —
-- varias cuentas pueden compartirlo. El login es username (único, V48) y la
-- password es única del user. Email, phone y contact_phone son libres de
-- repetirse entre cuentas.
--
-- Esta migración:
--   1. Backfill: si un user quedó con email pero sin contact_email, copia el
--      valor para no perder el buzón de contacto. Los placeholders de tombstone
--      y purga se excluyen explícitamente.
--   2. Elimina los índices únicos V52/V53 (idx_users_email_lower_unique,
--      idx_users_contact_email_hash_unique).
--   3. Elimina la columna derivada contact_email_hash (V53): ya no se calcula
--      porque no hay unicidad que imponer sobre el campo cifrado.
--   4. Elimina la columna email: fusionada en contact_email, ya no existe.
--
-- Flyway ejecuta en transacción, así que si algo falla no queda estado parcial.

BEGIN;

UPDATE users
   SET contact_email = email
 WHERE contact_email IS NULL
   AND email IS NOT NULL
   AND email NOT LIKE 'deleted-%@deleted.admindi.local'
   AND email NOT LIKE 'purged_%@anonymous.local';

DROP INDEX IF EXISTS idx_users_email_lower_unique;
DROP INDEX IF EXISTS idx_users_contact_email_hash_unique;
DROP INDEX IF EXISTS users_email_key;

ALTER TABLE users DROP COLUMN IF EXISTS contact_email_hash;

ALTER TABLE users DROP COLUMN IF EXISTS email;

COMMENT ON COLUMN users.contact_email IS
  'Único email del user (V54). NO único: varias cuentas pueden compartirlo. Cifrado AES-GCM columnwise. El login es username (V48). NULL permitido para cuentas sistema (SUPER_ADMIN).';

COMMIT;
