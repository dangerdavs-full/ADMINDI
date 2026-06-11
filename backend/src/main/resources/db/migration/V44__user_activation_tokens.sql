-- V44: Tokens de activación para alta segura de agentes/proveedores/staff.
--
-- Contexto: antes esto se resolvía mostrando un password temporal en la UI del
-- creador (StaffDTO.tempPassword y MaintenanceProviderDTO.tempPassword). Eso
-- tiene tres problemas serios:
--   · Quien crea la cuenta aprende el password del creado (insider risk).
--   · El password queda en el portapapeles / logs del navegador / capturas.
--   · Si el creador se equivoca al pasárselo, el dueño nuevo queda sin acceso.
--
-- Este diseño reemplaza ese flujo por un token de activación de un solo uso con
-- TTL corto (por defecto 24h) que se entrega al usuario nuevo por EMAIL y por
-- WHATSAPP (canales del propio user, no del creador). El user abre el link
-- {frontend}/activate?token=xxx, establece SU contraseña y opcionalmente
-- configura MFA. El backend valida el token, consume la fila y activa la
-- cuenta. Nadie más que el destinatario puede abrirlo.
--
-- Diseño:
--   · Guardamos el HASH del token (SHA-256 en hex), nunca el token en claro.
--     Así una lectura de la base no expone los tokens activos.
--   · token_hash es UNIQUE para prevenir colisiones (probabilidad nula con
--     32 bytes random, pero el índice también acelera el lookup por token).
--   · user_id es FK con ON DELETE CASCADE: si el user se borra (hard-delete),
--     sus tokens pendientes mueren con él.
--   · channel registra por dónde se emitió para auditoría y para la UI
--     "reenviar": si ya hay un token por EMAIL, reenviar también por WHATSAPP
--     emite uno NUEVO (y revoca el anterior).
--   · issued_by = email del actor (super_admin/owner/admin) que creó la cuenta.
--   · consumed_at y consumed_ip registran el uso real (se marca al final del
--     flujo de activación exitoso).
--
-- Housekeeping: un scheduler futuro podrá borrar tokens expirados/consumidos
-- con más de N días para no dejar cola indefinida. Por ahora solo los creamos
-- y consumimos; el volumen es bajo (un token por alta de staff/provider).

CREATE TABLE IF NOT EXISTS user_activation_tokens (
    id            VARCHAR(64)  PRIMARY KEY,
    user_id       VARCHAR(64)  NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    token_hash    VARCHAR(128) NOT NULL UNIQUE,
    channel       VARCHAR(16)  NOT NULL,
    issued_at     TIMESTAMP    NOT NULL DEFAULT NOW(),
    expires_at    TIMESTAMP    NOT NULL,
    consumed_at   TIMESTAMP,
    revoked_at    TIMESTAMP,
    issued_by     VARCHAR(255),
    issued_ip     VARCHAR(64),
    consumed_ip   VARCHAR(64)
);

CREATE INDEX IF NOT EXISTS idx_uat_user_id      ON user_activation_tokens (user_id);
CREATE INDEX IF NOT EXISTS idx_uat_expires_at   ON user_activation_tokens (expires_at);
CREATE INDEX IF NOT EXISTS idx_uat_user_pending ON user_activation_tokens (user_id)
    WHERE consumed_at IS NULL AND revoked_at IS NULL;
