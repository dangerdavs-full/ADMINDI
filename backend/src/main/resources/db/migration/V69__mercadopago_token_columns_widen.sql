-- Tokens OAuth de Mercado Pago pueden superar 512 caracteres (refresh token largo).
ALTER TABLE users
    ALTER COLUMN mp_access_token TYPE VARCHAR(2048),
    ALTER COLUMN mp_refresh_token TYPE VARCHAR(2048);
