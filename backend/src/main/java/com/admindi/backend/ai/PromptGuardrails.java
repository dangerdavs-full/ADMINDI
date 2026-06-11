package com.admindi.backend.ai;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Guardrails defensivos para prompts antes de enviarlos a Claude.
 *
 * No pretende ser un filtro exhaustivo anti-prompt-injection (imposible en el
 * caso general), sino una capa de reducción de superficie para entradas de
 * WhatsApp o uploads del inquilino:
 *
 *  - Truncado de longitud (evita runaway tokens).
 *  - Normalización de whitespace y control chars.
 *  - Detección de patrones típicos de inyección (se loggea y, opcionalmente,
 *    se rechaza con {@link InjectionAttemptException} para que el orquestador
 *    responda al usuario con un mensaje seguro sin llamar al modelo).
 *  - Redacción de secretos conocidos del sistema que NUNCA deben salir
 *    (tokens Twilio, SIDs, passwords). Defensa en profundidad.
 *
 * El flujo del chatbot usa {@link #sanitize(String)} antes de armar el prompt.
 * Si se detecta injection, se registra en audit y se responde al user con un
 * mensaje genérico, sin perder un token de Claude.
 */
public final class PromptGuardrails {

    private static final int MAX_LEN = 1500;

    /**
     * Patrones que típicamente delatan un intento de manipular el sistema.
     * Son heurísticas — un usuario malicioso puede evadir fácilmente. Sirven
     * para disparar audit + logging cuando ocurren, no como defensa única.
     */
    private static final List<Pattern> INJECTION_MARKERS = List.of(
            Pattern.compile("(?i)ignore (all )?(previous|above|prior) (instructions|prompts)"),
            Pattern.compile("(?i)disregard (all )?(previous|above|prior) (instructions|prompts)"),
            Pattern.compile("(?i)you are now"),
            Pattern.compile("(?i)new (system )?prompt"),
            Pattern.compile("(?i)reveal (your|the) (system )?prompt"),
            Pattern.compile("(?i)print (your|the) (system )?prompt"),
            Pattern.compile("(?i)jailbreak"),
            Pattern.compile("(?i)</?(system|assistant|human)>"),
            Pattern.compile("(?i)\\[\\[(system|assistant|human)\\]\\]")
    );

    /**
     * Marcadores de secretos. Si alguno aparece dentro del prompt de salida
     * lo redactamos como "[REDACTED]" antes de enviar. Nunca confiamos en el
     * resto del código para no filtrar secretos.
     */
    private static final List<Pattern> SECRET_MARKERS = List.of(
            Pattern.compile("SK[a-zA-Z0-9]{30,}"),          // Twilio auth tokens
            Pattern.compile("AC[a-zA-Z0-9]{30,}"),          // Twilio account SIDs
            Pattern.compile("sk-ant-[a-zA-Z0-9_\\-]{20,}"), // Anthropic keys
            Pattern.compile("(?i)password\\s*[:=]\\s*\\S+"),
            Pattern.compile("(?i)api[_-]?key\\s*[:=]\\s*\\S+")
    );

    private PromptGuardrails() {}

    /**
     * Sanitiza texto de usuario: recorta longitud, normaliza whitespace,
     * elimina control chars, y delata patrones de injection.
     *
     * @throws InjectionAttemptException si detecta un patrón de injection
     *         claro (el caller debe decidir si audita y rechaza o si continúa).
     */
    public static String sanitize(String raw) {
        if (raw == null) return "";

        String work = raw.trim();
        if (work.length() > MAX_LEN) {
            work = work.substring(0, MAX_LEN);
        }

        // Eliminar control chars excepto \n \t
        StringBuilder sb = new StringBuilder(work.length());
        for (int i = 0; i < work.length(); i++) {
            char c = work.charAt(i);
            if (c == '\n' || c == '\t' || c >= 0x20) {
                sb.append(c);
            } else {
                sb.append(' ');
            }
        }
        work = sb.toString();

        // Redactar secretos conocidos por defensa en profundidad.
        work = redactSecrets(work);

        // Detectar injection markers.
        String lower = work.toLowerCase(Locale.ROOT);
        for (Pattern p : INJECTION_MARKERS) {
            if (p.matcher(lower).find()) {
                throw new InjectionAttemptException(
                        "Possible prompt injection pattern detected: " + p.pattern());
            }
        }

        return work;
    }

    /**
     * Como {@link #sanitize(String)} pero sin lanzar excepción: si detecta
     * injection devuelve string vacío para que el caller decida. Útil cuando
     * queremos loguear pero continuar con fallback seguro.
     */
    public static String sanitizeOrEmpty(String raw) {
        try {
            return sanitize(raw);
        } catch (InjectionAttemptException ex) {
            return "";
        }
    }

    /**
     * Redacción reactiva de secretos — siempre aplicar antes de enviar un
     * prompt o guardarlo en audit. Jamás confiamos en el caller.
     */
    public static String redactSecrets(String input) {
        if (input == null) return "";
        String out = input;
        for (Pattern p : SECRET_MARKERS) {
            out = p.matcher(out).replaceAll("[REDACTED]");
        }
        return out;
    }

    public static class InjectionAttemptException extends RuntimeException {
        public InjectionAttemptException(String message) { super(message); }
    }
}
