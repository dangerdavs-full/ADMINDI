-- V29: Etapa 1 — canales visibles de notificación.
-- Reglas:
--   * El dominio público expone SOLO: IN_APP, EMAIL, WHATSAPP.
--   * N8N era un canal legacy técnico; ahora WhatsApp es el canal funcional y n8n queda
--     encapsulado como salida técnica de WhatsApp.
--   * IN_APP es obligatorio: enabled=TRUE siempre.
--   * OWNER_CREATED y otros eventos de bootstrap no deben persistir como preferencia
--     visible al usuario; se borran las filas huérfanas para evitar drift UI/BD.

-- 1. Normalizar legacy N8N -> WHATSAPP (si ya existe fila WHATSAPP para ese user+event,
--    se queda con la WHATSAPP y se descarta la N8N).
DELETE FROM notification_preferences p1
USING notification_preferences p2
WHERE p1.channel = 'N8N'
  AND p2.channel = 'WHATSAPP'
  AND p1.user_id = p2.user_id
  AND p1.event_type = p2.event_type;

UPDATE notification_preferences
SET channel = 'WHATSAPP'
WHERE channel = 'N8N';

-- 2. IN_APP obligatorio: cualquier fila con IN_APP apagada se corrige a TRUE.
UPDATE notification_preferences
SET enabled = TRUE
WHERE channel = 'IN_APP' AND enabled = FALSE;

-- 3. Eliminar preferencias de eventos NO configurables por usuario (ruido legacy).
DELETE FROM notification_preferences
WHERE event_type IN ('OWNER_CREATED', 'OWNER_PURGED', 'OWNER_DEACTIVATED', 'OWNER_ROUTING_UPDATED');

-- 4. Constraint de canales visibles. Se coloca como CHECK con nombre para poder
--    evolucionar (añadir/retirar) en futuras etapas.
ALTER TABLE notification_preferences
    DROP CONSTRAINT IF EXISTS notification_preferences_channel_chk;

ALTER TABLE notification_preferences
    ADD CONSTRAINT notification_preferences_channel_chk
    CHECK (channel IN ('IN_APP', 'EMAIL', 'WHATSAPP'));
