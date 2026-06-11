package com.admindi.backend.service;

import com.admindi.backend.model.AuditEventEntity;
import com.admindi.backend.model.UserEntity;
import com.admindi.backend.notifications.TwilioTemplateRegistry;
import com.admindi.backend.repository.AuditEventRepository;
import com.admindi.backend.repository.UserRepository;
import com.twilio.Twilio;
import com.twilio.rest.api.v2010.account.Message;
import com.twilio.rest.api.v2010.account.MessageCreator;
import com.twilio.type.PhoneNumber;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Envío de WhatsApp salientes vía Twilio Business API (Fase 1 notificaciones).
 *
 * Configuración (application.yml / env):
 *   twilio.enabled                (bool, default false)
 *   twilio.account-sid            (SID del proyecto)
 *   twilio.auth-token             (secret)
 *   twilio.whatsapp-from          ("whatsapp:+..." del número aprobado)
 *   twilio.messaging-service-sid  (opcional; si se define se usa en lugar de whatsapp-from)
 *   twilio.default-country-code   (ej "+52" para números sin CC en DB)
 *
 * Reglas:
 *  - Mismo patrón que {@link EmailService}: si enabled=false, secreto faltante o
 *    destinatario sin teléfono, se audita WHATSAPP_SKIPPED y el flujo principal
 *    continúa. Nunca se propagan excepciones al dispatcher.
 *  - Teléfonos se redactan en logs (+521***1234). Body del mensaje se sanitiza
 *    básicamente (truncado) para evitar pegar stacktraces o secretos por error.
 *  - No se loguea nunca el auth-token ni el account-sid en mensajes de error.
 *  - Inicialización perezosa: Twilio.init() solo se llama cuando hay credenciales
 *    reales; así el arranque en QA/local no falla por falta de secretos.
 *
 * Seguridad: fuera de la ventana de 24h de sesión de WhatsApp, Meta exige
 * plantillas pre-aprobadas (HSM). Para Fase 1 las notificaciones salientes son
 * iniciadas por la plataforma (bienvenida, recordatorios) y deben registrarse
 * como plantillas en Twilio/Meta. Si el envío falla por plantilla no autorizada
 * el error queda auditado y EMAIL sigue funcionando como respaldo.
 */
@Service
public class TwilioWhatsAppService {

    private static final Logger logger = LoggerFactory.getLogger(TwilioWhatsAppService.class);
    private static final int MAX_BODY_CHARS = 1500;

    private final UserRepository userRepo;
    private final AuditEventRepository auditRepo;
    private final TwilioTemplateRegistry templateRegistry;

    @Value("${twilio.enabled:false}")
    private boolean enabled;

    @Value("${twilio.account-sid:}")
    private String accountSid;

    @Value("${twilio.auth-token:}")
    private String authToken;

    @Value("${twilio.whatsapp-from:}")
    private String whatsappFrom;

    @Value("${twilio.messaging-service-sid:}")
    private String messagingServiceSid;

    @Value("${twilio.default-country-code:+52}")
    private String defaultCountryCode;

    private final AtomicBoolean initialized = new AtomicBoolean(false);

    public TwilioWhatsAppService(UserRepository userRepo,
                                  AuditEventRepository auditRepo,
                                  TwilioTemplateRegistry templateRegistry) {
        this.userRepo = userRepo;
        this.auditRepo = auditRepo;
        this.templateRegistry = templateRegistry;
    }

    @PostConstruct
    public void initIfReady() {
        if (isReady()) {
            try {
                Twilio.init(accountSid, authToken);
                initialized.set(true);
                logger.info("[TWILIO] WhatsApp client initialized (provider=twilio, from={})",
                        redactPhone(effectiveFrom()));
            } catch (Exception ex) {
                logger.error("[TWILIO] init failed: {}", ex.getClass().getSimpleName());
            }
        } else {
            logger.info("[TWILIO] disabled or missing credentials; WhatsApp sending inert");
        }
    }

    public boolean isEnabled() {
        return enabled && isReady();
    }

    /**
     * Envía un mensaje libre (body, sin plantilla Meta) a un número E.164
     * específico. Pensado para respuestas del chatbot (Fase 3): el usuario
     * acaba de escribir, estamos dentro de la ventana de 24h de sesión, por
     * lo que Meta permite body libre sin plantilla pre-aprobada.
     *
     * <p>Uso típico:
     * <pre>
     *   twilioService.sendFreeformWhatsApp("+5215512345678",
     *       "Hola David, recibí tu comprobante. Procesando...");
     * </pre>
     *
     * <p>Seguridad:
     * <ul>
     *   <li>Si Twilio está deshabilitado, auditamos WHATSAPP_SKIPPED y NO
     *       lanzamos excepción (el orquestador puede continuar con flujo
     *       alterno sin romper la UX del bot).</li>
     *   <li>El cuerpo se trunca a {@code MAX_BODY_CHARS} y se sanitiza
     *       básicamente para evitar fugar control chars.</li>
     *   <li>NO valida que el destinatario sea un usuario conocido — eso lo
     *       hace el caller antes de llamar.</li>
     * </ul>
     *
     * @return true si Twilio aceptó el mensaje, false en skip/error.
     */
    public boolean sendFreeformWhatsApp(String toE164, String body) {
        if (!isEnabled() || !initialized.get()) {
            auditOutcome(null, null, "BOT_REPLY", "WHATSAPP_SKIPPED",
                    "twilio not enabled or not initialized");
            return false;
        }
        if (toE164 == null || toE164.isBlank()) {
            auditOutcome(null, null, "BOT_REPLY", "WHATSAPP_SKIPPED", "no destination");
            return false;
        }
        String safeBody = buildSafeBody(null, body);
        if (safeBody.isBlank()) {
            auditOutcome(null, null, "BOT_REPLY", "WHATSAPP_SKIPPED", "empty body");
            return false;
        }

        try {
            PhoneNumber toNumber = new PhoneNumber("whatsapp:" + toE164);
            MessageCreator creator;
            if (messagingServiceSid != null && !messagingServiceSid.isBlank()) {
                creator = Message.creator(toNumber, messagingServiceSid, safeBody);
            } else {
                creator = Message.creator(toNumber,
                        new PhoneNumber(normalizeWhatsAppFrom(whatsappFrom)),
                        safeBody);
            }
            Message msg = creator.create();
            auditOutcome(null, null, "BOT_REPLY", "WHATSAPP_SENT",
                    "sid=" + (msg.getSid() != null ? msg.getSid() : "(null)")
                            + " to=" + redactPhone(toE164));
            return true;
        } catch (Exception ex) {
            logger.error("[TWILIO-BOT] reply failed to={} err={}",
                    redactPhone(toE164), safeMsg(ex));
            auditOutcome(null, null, "BOT_REPLY", "WHATSAPP_FAILED",
                    "to=" + redactPhone(toE164) + " err=" + safeMsg(ex));
            return false;
        }
    }

    /**
     * Expuesto como package-private para que {@link com.admindi.backend.whatsapp.WhatsAppMediaDownloader}
     * pueda usar las mismas credenciales Basic Auth para descargar multimedia
     * (Twilio exige account-sid:auth-token en la descarga de {@code MediaUrl0}).
     * Nunca se loggea ni sale del backend.
     */
    public String getAccountSidInternal() { return accountSid; }
    public String getAuthTokenInternal() { return authToken; }

    private boolean isReady() {
        if (!enabled) return false;
        if (accountSid == null || accountSid.isBlank()) return false;
        if (authToken == null || authToken.isBlank()) return false;
        boolean hasFrom = whatsappFrom != null && !whatsappFrom.isBlank();
        boolean hasMsgSid = messagingServiceSid != null && !messagingServiceSid.isBlank();
        return hasFrom || hasMsgSid;
    }

    /**
     * Sobrecarga legacy (sin templateVariables). Se mantiene para callers que todavía
     * mandan notificaciones con body libre (dentro de ventana de 24h o eventos sin
     * plantilla aprobada). Delega al overload principal con {@code templateVariables = null}.
     */
    public void sendEventWhatsApp(String recipientUserId,
                                  String eventType,
                                  String ownerId,
                                  String title,
                                  String body) {
        sendEventWhatsApp(recipientUserId, eventType, ownerId, title, body, null);
    }

    /**
     * Envía un mensaje de WhatsApp a un destinatario resuelto desde UserEntity.
     *
     * Ruteo:
     *   1. Si el {@code eventType} tiene una plantilla aprobada en {@link TwilioTemplateRegistry}
     *      Y se pasaron {@code templateVariables}, se manda vía Twilio Content API
     *      ({@code contentSid + contentVariables}). Este es el modo requerido por Meta para
     *      mensajes iniciados por la plataforma fuera de la ventana de 24h.
     *   2. Si no hay plantilla para el evento, cae al modo legacy de body libre (funciona solo
     *      dentro de la ventana de 24h de sesión de WhatsApp; fuera de ella Twilio responde
     *      con error 63016 "template required"). El error queda auditado como WHATSAPP_FAILED.
     *
     * No rompe el flujo si falla: solo audita.
     *
     * @param templateVariables mapa {slot → valor} (slot = "1","2",... igual que en Twilio).
     *                          Si es null o vacío y el evento tiene plantilla configurada, NO se
     *                          envía (audit WHATSAPP_SKIPPED con motivo "template vars missing").
     */
    public void sendEventWhatsApp(String recipientUserId,
                                  String eventType,
                                  String ownerId,
                                  String title,
                                  String body,
                                  Map<String, String> templateVariables) {
        if (!isEnabled()) {
            auditOutcome(recipientUserId, ownerId, eventType, "WHATSAPP_SKIPPED",
                    "twilio.enabled=false or missing credentials");
            return;
        }
        if (!initialized.get()) {
            auditOutcome(recipientUserId, ownerId, eventType, "WHATSAPP_SKIPPED",
                    "twilio client not initialized");
            return;
        }
        if (recipientUserId == null || recipientUserId.isBlank()) {
            auditOutcome(recipientUserId, ownerId, eventType, "WHATSAPP_SKIPPED", "recipient null");
            return;
        }

        UserEntity user = userRepo.findById(recipientUserId).orElse(null);
        if (user == null) {
            auditOutcome(recipientUserId, ownerId, eventType, "WHATSAPP_SKIPPED", "recipient not found");
            return;
        }

        String e164 = resolveRecipientPhone(user);
        if (e164 == null) {
            auditOutcome(recipientUserId, ownerId, eventType, "WHATSAPP_SKIPPED",
                    "recipient has no usable phone");
            return;
        }

        boolean eventHasTemplate = templateRegistry.hasTemplateForEvent(eventType);
        // Regla estricta para cumplir políticas de Meta en producción:
        // si el evento tiene plantilla configurada pero no nos llegaron variables, abortamos
        // con audit claro en lugar de caer al body libre (que Meta rechazaría fuera de 24h).
        if (eventHasTemplate && (templateVariables == null || templateVariables.isEmpty())) {
            auditOutcome(recipientUserId, ownerId, eventType, "WHATSAPP_SKIPPED",
                    "template configured but no templateVariables provided by caller");
            return;
        }

        try {
            MessageCreator creator;
            PhoneNumber toNumber = new PhoneNumber("whatsapp:" + e164);

            if (eventHasTemplate) {
                // ─── Ruta con plantilla (contentSid) ────────────────────────────────
                String contentSid = templateRegistry.getContentSid(eventType);
                String varsJson = toContentVariablesJson(templateVariables);
                if (messagingServiceSid != null && !messagingServiceSid.isBlank()) {
                    creator = Message.creator(toNumber, messagingServiceSid, "")
                            .setContentSid(contentSid)
                            .setContentVariables(varsJson);
                } else {
                    creator = Message.creator(toNumber,
                                    new PhoneNumber(normalizeWhatsAppFrom(whatsappFrom)), "")
                            .setContentSid(contentSid)
                            .setContentVariables(varsJson);
                }
            } else {
                // ─── Ruta legacy (body libre) ───────────────────────────────────────
                // Solo funciona dentro de ventana de 24h. Para notificaciones iniciadas por la
                // plataforma fuera de 24h, DEBE existir plantilla en el registry.
                String payload = buildSafeBody(title, body);
                if (messagingServiceSid != null && !messagingServiceSid.isBlank()) {
                    creator = Message.creator(toNumber, messagingServiceSid, payload);
                } else {
                    // Twilio exige que el From de WhatsApp venga con el prefijo "whatsapp:"
                    // de lo contrario responde 63007 "could not find a Channel with the
                    // specified From address" porque asume SMS.
                    creator = Message.creator(toNumber,
                            new PhoneNumber(normalizeWhatsAppFrom(whatsappFrom)),
                            payload);
                }
            }

            Message msg = creator.create();
            String mode = eventHasTemplate ? "template" : "body";
            String sidDetail = eventHasTemplate
                    ? (" tpl=" + TwilioTemplateRegistry.redactSid(templateRegistry.getContentSid(eventType)))
                    : "";
            auditOutcome(recipientUserId, ownerId, eventType, "WHATSAPP_SENT",
                    "mode=" + mode + " sid=" + (msg.getSid() != null ? msg.getSid() : "(null)")
                            + sidDetail + " to=" + redactPhone(e164));
        } catch (Exception ex) {
            logger.error("[TWILIO] send failed event={} user={} err={}",
                    eventType, recipientUserId, safeMsg(ex));
            auditOutcome(recipientUserId, ownerId, eventType, "WHATSAPP_FAILED",
                    "to=" + redactPhone(e164) + " err=" + safeMsg(ex));
        }
    }

    /**
     * Serializa a JSON el mapa de contentVariables con el formato que Twilio espera:
     *   {"1":"valor1","2":"valor2",...}
     *
     * Reglas:
     *  - Las claves son strings numéricos ("1","2",…) ordenadas ascendentemente para
     *    producir un JSON determinista (útil para logs/tests).
     *  - Los valores se escapan como JSON strings válidos (escape de comillas, barras y
     *    caracteres de control). NO se permite inyectar HTML ni salto de línea crudo.
     *  - Se trunca cada valor a 480 caracteres (el límite duro de Twilio por variable es 1024,
     *    pero queremos dejar colchón para que la composición total no rebase el límite del
     *    mensaje).
     */
    static String toContentVariablesJson(Map<String, String> vars) {
        if (vars == null || vars.isEmpty()) return "{}";
        // Orden numérico ascendente (fallback a orden alfabético si la clave no es numérica).
        TreeMap<String, String> sorted = new TreeMap<>((a, b) -> {
            try {
                return Integer.compare(Integer.parseInt(a), Integer.parseInt(b));
            } catch (NumberFormatException e) {
                return a.compareTo(b);
            }
        });
        sorted.putAll(vars);

        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, String> e : sorted.entrySet()) {
            if (!first) sb.append(',');
            first = false;
            sb.append('"').append(escapeJsonString(e.getKey())).append('"')
              .append(':')
              .append('"').append(escapeJsonString(truncateValue(e.getValue()))).append('"');
        }
        sb.append('}');
        return sb.toString();
    }

    private static String truncateValue(String raw) {
        if (raw == null) return "";
        String safe = raw;
        if (safe.length() > 480) safe = safe.substring(0, 480);
        return safe;
    }

    private static String escapeJsonString(String s) {
        if (s == null) return "";
        StringBuilder out = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\': out.append("\\\\"); break;
                case '"':  out.append("\\\""); break;
                case '\n': out.append("\\n"); break;
                case '\r': out.append("\\r"); break;
                case '\t': out.append("\\t"); break;
                case '\b': out.append("\\b"); break;
                case '\f': out.append("\\f"); break;
                default:
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
            }
        }
        return out.toString();
    }

    /** Helper público para construir mapas de variables de forma concisa desde los callers. */
    public static Map<String, String> vars(String... kv) {
        if (kv == null || kv.length == 0) return Collections.emptyMap();
        if (kv.length % 2 != 0) {
            throw new IllegalArgumentException("vars(...) requiere pares clave/valor; recibí " + kv.length);
        }
        TreeMap<String, String> m = new TreeMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put(kv[i], kv[i + 1] == null ? "" : kv[i + 1]);
        }
        return m;
    }

    private String resolveRecipientPhone(UserEntity user) {
        String cc = user.getContactCountryCode();
        String phone = user.getContactPhone();
        if (phone == null || phone.isBlank()) {
            phone = user.getPhone();
        }
        if (phone == null || phone.isBlank()) return null;

        String digits = phone.replaceAll("[^0-9+]", "");
        if (digits.isEmpty()) return null;

        if (digits.startsWith("+")) {
            return digits;
        }
        // Si hay country code explícito en el usuario, usarlo; si no, default (+52).
        String codeRaw = (cc != null && !cc.isBlank()) ? cc.trim() : defaultCountryCode;
        String code = codeRaw.startsWith("+") ? codeRaw : "+" + codeRaw.replaceAll("[^0-9]", "");
        return code + digits;
    }

    private String effectiveFrom() {
        if (messagingServiceSid != null && !messagingServiceSid.isBlank()) {
            return "MG:" + messagingServiceSid;
        }
        return whatsappFrom != null ? normalizeWhatsAppFrom(whatsappFrom) : "(none)";
    }

    /**
     * Asegura que el FROM tenga el prefijo "whatsapp:" que exige Twilio para el canal
     * WhatsApp. Si el operador configura "+14155238886", lo convertimos a
     * "whatsapp:+14155238886". Si ya viene con el prefijo se respeta tal cual.
     */
    private static String normalizeWhatsAppFrom(String raw) {
        if (raw == null) return "";
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) return "";
        if (trimmed.toLowerCase().startsWith("whatsapp:")) {
            return trimmed;
        }
        return "whatsapp:" + trimmed;
    }

    private String buildSafeBody(String title, String body) {
        StringBuilder sb = new StringBuilder();
        if (title != null && !title.isBlank()) {
            sb.append(title).append("\n\n");
        }
        if (body != null && !body.isBlank()) {
            sb.append(body);
        }
        String out = sb.toString();
        if (out.length() > MAX_BODY_CHARS) out = out.substring(0, MAX_BODY_CHARS);
        return out;
    }

    private void auditOutcome(String userId, String ownerId, String eventType, String outcome, String detail) {
        try {
            AuditEventEntity a = new AuditEventEntity();
            a.setId(UUID.randomUUID().toString());
            a.setTimestamp(LocalDateTime.now());
            a.setActorId("SYSTEM");
            a.setActorRole("SYSTEM");
            a.setEventType(outcome + "_" + (eventType == null ? "UNKNOWN" : eventType));
            a.setResourceType("WHATSAPP_NOTIFICATION");
            a.setResourceId(userId);
            a.setOwnerId(ownerId);
            a.setNewValues("{\"detail\":\"" + (detail == null ? "" : detail.replace("\"", "'")) + "\"}");
            auditRepo.save(a);
        } catch (Exception e) {
            logger.warn("[TWILIO] audit save failed for {}: {}", outcome, e.getMessage());
        }
    }

    private static String redactPhone(String phone) {
        if (phone == null) return "(null)";
        String digits = phone.replaceAll("[^0-9+]", "");
        if (digits.length() < 6) return "***";
        return digits.substring(0, Math.min(4, digits.length())) + "***"
                + digits.substring(digits.length() - 3);
    }

    private static String safeMsg(Exception ex) {
        String m = ex.getMessage();
        if (m == null) return ex.getClass().getSimpleName();
        String lower = m.toLowerCase();
        if (lower.contains("token") || lower.contains("sid") || lower.contains("secret")
                || lower.contains("auth")) {
            return ex.getClass().getSimpleName() + " (redacted)";
        }
        return m.length() <= 240 ? m : m.substring(0, 240);
    }
}
