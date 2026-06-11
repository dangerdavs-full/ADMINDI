-- V68 — Cuenta Mercado Pago por dueño (OAuth / token de vendedor).
-- Los cobros de renta usan el access token del OWNER de la factura, no el de la plataforma.

ALTER TABLE users
    ADD COLUMN IF NOT EXISTS mp_user_id VARCHAR(64),
    ADD COLUMN IF NOT EXISTS mp_access_token VARCHAR(512),
    ADD COLUMN IF NOT EXISTS mp_refresh_token VARCHAR(512),
    ADD COLUMN IF NOT EXISTS mp_token_expires_at TIMESTAMP,
    ADD COLUMN IF NOT EXISTS mp_connected_at TIMESTAMP;

CREATE INDEX IF NOT EXISTS idx_users_mp_user_id ON users (mp_user_id)
    WHERE mp_user_id IS NOT NULL;
