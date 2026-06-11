package com.admindi.backend.notifications;

import java.util.Set;

/**
 * Canales de notificación del dominio público (Etapa 1).
 *
 * Decisiones de producto:
 *  - El usuario SOLO ve: IN_APP, EMAIL, WHATSAPP.
 *  - IN_APP es obligatorio (no editable, no apagable).
 *  - EMAIL lo envía el backend (SMTP).
 *  - WHATSAPP lo envía el backend vía Twilio directo; WHATSAPP NO es un canal
 *    visible al usuario, es sólo mecanismo técnico de WhatsApp.
 *  - El literal "N8N" queda marcado como LEGACY: se ignora en respuestas públicas
 *    y se rechaza en inputs; si queda en BD se normaliza a WHATSAPP en caliente.
 */
public final class NotificationChannels {
    public static final String IN_APP = "IN_APP";
    public static final String EMAIL = "EMAIL";
    public static final String WHATSAPP = "WHATSAPP";

    /** Legacy, no-visible. Cualquier fila en BD con este valor se trata como WHATSAPP. */
    public static final String LEGACY_N8N = "N8N";

    /** Canales visibles para el usuario y aceptados por la API pública de preferencias. */
    public static final Set<String> VISIBLE = Set.of(IN_APP, EMAIL, WHATSAPP);

    /** Canales que el dispatcher interno usa al resolver preferencias. */
    public static final Set<String> DISPATCHABLE = Set.of(IN_APP, EMAIL, WHATSAPP);

    private NotificationChannels() {}

    /** Normaliza un string a canal público; devuelve null si no se reconoce. */
    public static String normalize(String raw) {
        if (raw == null) return null;
        String up = raw.trim().toUpperCase();
        if (up.isEmpty()) return null;
        if (LEGACY_N8N.equals(up)) return WHATSAPP;
        if (VISIBLE.contains(up)) return up;
        return null;
    }
}
