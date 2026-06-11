-- V55 — Infra chatbot WhatsApp con IA, NIP del inquilino y esquema Banxico adaptativo.
--
-- Este migrate introduce las tablas de soporte para la Fase 3 (chatbot Claude):
--   1. tenant_whatsapp_pin       : NIP del inquilino con hash bcrypt, intentos fallidos y bloqueo temporal.
--   2. whatsapp_conversation_state: máquina de estados por número de teléfono.
--   3. ai_usage_log              : trazabilidad de llamadas a Claude (tokens, costo, límite diario).
--   4. banxico_scrape_schema     : versiones de selectores CSS/regex para parsear el HTML de Banxico CEP.
--   5. banxico_scrape_failure    : auditoría de fallos y auto-resoluciones por IA.
--
-- Convenciones:
--   - id TEXT para consistencia con el resto de tablas (UUIDs strings).
--   - timestamps sin zona: el backend los produce con LocalDateTime.now() (UTC lógico).
--   - jsonb para payloads estructurados; consultas con operadores nativos de PG.
--   - Índices parciales donde aplica para evitar scans en datasets grandes.
--
-- La tabla users NO se modifica aquí: el phone del inquilino ya vive cifrado en users.phone /
-- users.contact_phone (V*, AES-GCM columnwise). La resolución por teléfono la hace el backend.

BEGIN;

-- ─────────────────────────────────────────────────────────────────────────────
-- 1) tenant_whatsapp_pin
-- NIP numérico corto (4-6 dígitos) usado por el inquilino en WhatsApp para
-- confirmar operaciones sensibles (subir comprobante, crear ticket). No reemplaza
-- al MFA del portal web (TOTP): es un segundo factor ligero específico de WhatsApp.
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS tenant_whatsapp_pin (
    user_id TEXT PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    pin_hash TEXT NOT NULL,
    -- Contador de intentos fallidos consecutivos. Se resetea tras un acierto.
    failed_attempts INTEGER NOT NULL DEFAULT 0,
    -- Hasta cuándo está bloqueado el NIP. NULL = no bloqueado. El SUPER_ADMIN/OWNER
    -- puede forzar un reset con el flujo de recovery (V55, PIN_RESET).
    locked_until TIMESTAMP,
    last_used_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_tenant_whatsapp_pin_locked
    ON tenant_whatsapp_pin (locked_until)
    WHERE locked_until IS NOT NULL;

COMMENT ON TABLE tenant_whatsapp_pin IS
  'NIP del inquilino para WhatsApp (Fase 3). Hash con bcrypt strength 12. SUPER_ADMIN/OWNER pueden resetear vía AccountRecoveryService.PIN_RESET.';

-- ─────────────────────────────────────────────────────────────────────────────
-- 2) whatsapp_conversation_state
-- Máquina de estados del chatbot por número de teléfono en formato E.164.
-- TTL 15 min: si el usuario tarda más, se vuelve a pedir NIP en la siguiente
-- interacción (reinicio seguro, sin filtrar contexto anterior).
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS whatsapp_conversation_state (
    phone_e164 TEXT PRIMARY KEY,
    user_id TEXT REFERENCES users(id) ON DELETE SET NULL,
    current_state TEXT NOT NULL,
    -- Contexto efímero de la conversación (descripción del ticket en curso,
    -- archivos subidos, datos OCR a confirmar, etc.). JSONB para consultas
    -- ad-hoc desde admin tools sin deserializar en Java.
    context_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    -- Referencias opcionales al recurso pendiente de confirmación.
    pending_proof_id TEXT,
    pending_ticket_id TEXT,
    expires_at TIMESTAMP NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_whatsapp_conv_user
    ON whatsapp_conversation_state (user_id);

CREATE INDEX IF NOT EXISTS idx_whatsapp_conv_expires
    ON whatsapp_conversation_state (expires_at);

COMMENT ON TABLE whatsapp_conversation_state IS
  'Estado conversacional del chatbot WhatsApp. Un row por número E.164 activo. TTL 15 min.';

-- ─────────────────────────────────────────────────────────────────────────────
-- 3) ai_usage_log
-- Cada llamada a Anthropic Claude se registra para:
--   - enforce presupuesto diario por usuario (anthropic.daily-budget-usd-per-user)
--   - auditoría de costos agregados por organización
--   - detectar abuso (un user consumiendo tokens anómalos)
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS ai_usage_log (
    id TEXT PRIMARY KEY,
    user_id TEXT REFERENCES users(id) ON DELETE SET NULL,
    owner_id TEXT,
    model TEXT NOT NULL,
    -- Identificador del flujo que generó la llamada: OCR_RECEIPT, BOT_CHAT,
    -- BANXICO_ADAPTIVE, ACCOUNTING_CATEGORIZE, REPORT_NARRATIVE, etc.
    purpose TEXT NOT NULL,
    input_tokens INTEGER NOT NULL DEFAULT 0,
    output_tokens INTEGER NOT NULL DEFAULT 0,
    cost_usd NUMERIC(10,6) NOT NULL DEFAULT 0,
    -- HTTP status devuelto por Anthropic; NULL si hubo error de red/timeout.
    http_status INTEGER,
    success BOOLEAN NOT NULL DEFAULT TRUE,
    error_message TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_ai_usage_user_day
    ON ai_usage_log (user_id, created_at);

CREATE INDEX IF NOT EXISTS idx_ai_usage_owner
    ON ai_usage_log (owner_id, created_at);

CREATE INDEX IF NOT EXISTS idx_ai_usage_purpose
    ON ai_usage_log (purpose, created_at);

COMMENT ON TABLE ai_usage_log IS
  'Trazabilidad y presupuesto de llamadas a Anthropic Claude. Usado por ClaudeService para enforce límite diario por user.';

-- ─────────────────────────────────────────────────────────────────────────────
-- 4) banxico_scrape_schema
-- Versiones de selectores CSS/regex para parsear la respuesta HTML del
-- Validador Banxico CEP. Cuando el parser falla, BanxicoAdaptiveAi pide a
-- Claude regenerar los selectores a partir del HTML actual. Si los nuevos
-- selectores pasan validación contra fixtures conocidos, se crea una nueva
-- versión con active=true y la anterior queda inactiva.
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS banxico_scrape_schema (
    id TEXT PRIMARY KEY,
    version INTEGER NOT NULL,
    -- JSON con selectors por campo: {"claveRastreo":"td.rastreo","monto":"#monto", ...}
    selectors_json JSONB NOT NULL,
    active BOOLEAN NOT NULL DEFAULT FALSE,
    -- Última vez que este schema extrajo datos correctos de una respuesta real.
    last_success_at TIMESTAMP,
    -- Origen: 'SEED' (manual, bootstrap), 'AI_AUTO' (Claude regeneró), 'MANUAL' (SUPER_ADMIN editó).
    source TEXT NOT NULL DEFAULT 'SEED',
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    deactivated_at TIMESTAMP
);

-- Solo puede haber un schema activo a la vez (invariante).
CREATE UNIQUE INDEX IF NOT EXISTS idx_banxico_schema_active_unique
    ON banxico_scrape_schema ((1))
    WHERE active = TRUE;

CREATE UNIQUE INDEX IF NOT EXISTS idx_banxico_schema_version_unique
    ON banxico_scrape_schema (version);

COMMENT ON TABLE banxico_scrape_schema IS
  'Versionado de selectores para parsear Banxico CEP. active=TRUE marca la versión vigente (solo una).';

-- ─────────────────────────────────────────────────────────────────────────────
-- 5) banxico_scrape_failure
-- Audit trail de cambios detectados en la estructura de Banxico CEP.
-- Cada fallo de parsing dispara una entrada; si Claude resuelve y genera
-- un nuevo schema válido, se registra con resolved_by_ai_at + new_schema_id.
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS banxico_scrape_failure (
    id TEXT PRIMARY KEY,
    url TEXT NOT NULL,
    -- Hash del snippet HTML ofensivo (no guardamos el HTML completo por espacio).
    html_snippet_hash TEXT NOT NULL,
    -- Snippet acortado (primeros 4KB) para diagnóstico manual en UI.
    html_snippet_preview TEXT,
    detected_at TIMESTAMP NOT NULL DEFAULT NOW(),
    resolved_by_ai_at TIMESTAMP,
    new_schema_id TEXT REFERENCES banxico_scrape_schema(id),
    -- Si la IA no pudo resolver, se registra el error para revisión manual.
    ai_error TEXT,
    notified_super_admin BOOLEAN NOT NULL DEFAULT FALSE
);

CREATE INDEX IF NOT EXISTS idx_banxico_failure_unresolved
    ON banxico_scrape_failure (detected_at)
    WHERE resolved_by_ai_at IS NULL;

COMMENT ON TABLE banxico_scrape_failure IS
  'Registro de fallos de parsing de Banxico CEP y su resolución (automática vía IA o manual por SUPER_ADMIN).';

-- ─────────────────────────────────────────────────────────────────────────────
-- Seed inicial del schema Banxico (versión 1). Los selectores reales se ajustan
-- en código al arrancar el backend si el HTML actual difiere. Este seed sirve
-- para que la tabla nunca esté vacía y el adapter pueda intentar siempre.
-- Si cambia el HTML oficial, BanxicoAdaptiveAi genera una versión 2.
-- ─────────────────────────────────────────────────────────────────────────────
INSERT INTO banxico_scrape_schema (id, version, selectors_json, active, source, created_at)
VALUES (
    'banxico-schema-seed-v1',
    1,
    '{
        "formSelector": "form[name=\"formCep\"]",
        "resultTable": "table.cepCabecera",
        "claveRastreo": "tr:contains(Clave de rastreo) td:last-child",
        "monto": "tr:contains(Monto) td:last-child",
        "fechaOperacion": "tr:contains(Fecha de operación) td:last-child",
        "fechaAbono": "tr:contains(Fecha de abono) td:last-child",
        "bancoEmisor": "tr:contains(Institución emisora) td:last-child",
        "bancoReceptor": "tr:contains(Institución receptora) td:last-child",
        "cuentaBeneficiario": "tr:contains(Cuenta beneficiaria) td:last-child",
        "beneficiario": "tr:contains(Beneficiario) td:last-child",
        "sellDigital": "tr:contains(Sello digital) td:last-child",
        "notFoundMarker": ".errorMessage, .noResults"
    }'::jsonb,
    TRUE,
    'SEED',
    NOW()
)
ON CONFLICT DO NOTHING;

COMMIT;
