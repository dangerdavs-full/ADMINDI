package com.admindi.backend.controller;

import com.admindi.backend.model.AuditEventEntity;
import com.admindi.backend.repository.AuditEventRepository;
import com.admindi.backend.whatsapp.WhatsAppBotOrchestrator;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.*;

/**
 * Webhook entrante de Twilio WhatsApp.
 *
 * Responsabilidad: validar la firma HMAC-SHA1 de Twilio y delegar el mensaje
 * verificado al {@link WhatsAppBotOrchestrator} (Fase 3 chatbot). Si Twilio
 * está deshabilitado o la firma es inválida, respondemos con los códigos HTTP
 * apropiados sin leer el payload como confiable.
 *
 * Seguridad:
 *  - Firma {@code X-Twilio-Signature}: HMAC-SHA1 sobre URL + params ordenados
 *    (spec oficial de Twilio Request Validation). Rechazo con 403 + audit.
 *  - El webhook SIEMPRE responde con TwiML vacío: las respuestas reales al
 *    inquilino las envía el orquestador por Twilio API en un thread async
 *    para desacoplar la latencia del webhook de la latencia del chatbot.
 *  - El orquestador se ejecuta aunque la respuesta HTTP ya haya salido: el
 *    sistema de audit deja rastro de cualquier falla del bot.
 *
 * Comportamiento operativo:
 *  - Si {@code whatsapp.bot.enabled=false}, se acepta la firma pero el bot
 *    no procesa (auditado). Esto permite probar firma sin activar el bot.
 *  - Respuesta TwiML vacía: indica a Twilio "no hay respuesta síncrona" y
 *    cierra la petición; la respuesta real viaja fuera de banda por API.
 */
@RestController
@RequestMapping("/api/webhooks/twilio")
public class TwilioWebhookController {

    private static final Logger logger = LoggerFactory.getLogger(TwilioWebhookController.class);

    private final AuditEventRepository auditRepo;
    private final WhatsAppBotOrchestrator orchestrator;

    @Value("${twilio.auth-token:}")
    private String authToken;

    @Value("${twilio.enabled:false}")
    private boolean enabled;

    /**
     * URL pública esperada (usada para validar la firma). Twilio firma el POST sobre
     * esta URL exacta; en producción debe apuntar al dominio real detrás del proxy.
     */
    @Value("${twilio.webhook-public-url:}")
    private String webhookPublicUrl;

    public TwilioWebhookController(AuditEventRepository auditRepo,
                                    WhatsAppBotOrchestrator orchestrator) {
        this.auditRepo = auditRepo;
        this.orchestrator = orchestrator;
    }

    @PostMapping(value = "/whatsapp", consumes = "application/x-www-form-urlencoded")
    public ResponseEntity<String> inboundWhatsapp(HttpServletRequest request,
                                                  @RequestParam Map<String, String> params) {
        if (!enabled || authToken == null || authToken.isBlank()) {
            // Si Twilio no está configurado, no aceptamos tráfico entrante para
            // evitar abuso. Se audita como skipped.
            audit("WEBHOOK_INBOUND_SKIPPED", null, "twilio disabled or no auth-token");
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body("");
        }

        String signature = request.getHeader("X-Twilio-Signature");
        if (signature == null || signature.isBlank()) {
            audit("WEBHOOK_INBOUND_REJECTED", params.get("From"), "missing X-Twilio-Signature");
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body("");
        }

        String url = webhookPublicUrl != null && !webhookPublicUrl.isBlank()
                ? webhookPublicUrl
                : request.getRequestURL().toString();

        if (!isValidSignature(url, params, signature)) {
            audit("WEBHOOK_INBOUND_REJECTED", params.get("From"),
                    "bad signature url=" + url);
            logger.warn("[TWILIO-WEBHOOK] rejected inbound: invalid signature from={}",
                    redact(params.get("From")));
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body("");
        }

        // Post-firma: defensa en profundidad. Aunque la firma valide, exigimos
        // que el payload traiga los campos mínimos (From + MessageSid) que
        // Twilio siempre incluye. Si faltan, algo está fuera de protocolo y
        // rechazamos para no procesar entradas malformadas.
        String messageSid = params.get("MessageSid");
        if (params.get("From") == null || messageSid == null || messageSid.isBlank()) {
            audit("WEBHOOK_INBOUND_REJECTED", params.get("From"),
                    "missing From or MessageSid post-signature");
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("");
        }

        audit("WEBHOOK_INBOUND_ACCEPTED", params.get("From"),
                "messageSid=" + messageSid
                        + " body.len=" + Objects.toString(params.get("Body"), "").length()
                        + " numMedia=" + params.getOrDefault("NumMedia", "0"));

        // Delegamos al orquestador. Capturamos excepciones para que jamás
        // filtren detalles en la respuesta HTTP a Twilio — un fallo del bot
        // queda en el audit pero el webhook responde 200 para que Twilio no
        // reintente (los reintentos repetirían el fallo y molestarían al user).
        try {
            orchestrator.handleInbound(params);
        } catch (Exception ex) {
            logger.error("[TWILIO-WEBHOOK] orchestrator failed: {}", ex.getClass().getSimpleName(), ex);
            audit("WEBHOOK_INBOUND_BOT_FAILED", params.get("From"),
                    ex.getClass().getSimpleName());
        }

        // TwiML vacío: Twilio espera una respuesta XML válida. Un <Response/> vacío
        // indica "sin respuesta al remitente" y cierra la petición.
        String twiml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><Response></Response>";
        return ResponseEntity.ok().header("Content-Type", "application/xml").body(twiml);
    }

    /**
     * Validación oficial Twilio:
     *   1. Tomar la URL completa del request tal como la recibe Twilio.
     *   2. Concatenar, ordenadas alfabéticamente por key, todas las (key+value) del body.
     *   3. HMAC-SHA1 con auth-token como secreto; Base64 del resultado.
     *   4. Comparar en tiempo constante contra X-Twilio-Signature.
     */
    private boolean isValidSignature(String url, Map<String, String> params, String signature) {
        try {
            List<String> keys = new ArrayList<>(params.keySet());
            Collections.sort(keys);
            StringBuilder data = new StringBuilder(url);
            for (String k : keys) {
                data.append(k).append(params.get(k) == null ? "" : params.get(k));
            }
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(authToken.getBytes(StandardCharsets.UTF_8), "HmacSHA1"));
            byte[] digest = mac.doFinal(data.toString().getBytes(StandardCharsets.UTF_8));
            String expected = Base64.getEncoder().encodeToString(digest);
            return constantTimeEquals(expected, signature);
        } catch (Exception ex) {
            logger.error("[TWILIO-WEBHOOK] signature verification failed: {}",
                    ex.getClass().getSimpleName());
            return false;
        }
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) return false;
        if (a.length() != b.length()) return false;
        int r = 0;
        for (int i = 0; i < a.length(); i++) r |= a.charAt(i) ^ b.charAt(i);
        return r == 0;
    }

    private void audit(String eventType, String from, String detail) {
        try {
            AuditEventEntity a = new AuditEventEntity();
            a.setId(UUID.randomUUID().toString());
            a.setTimestamp(LocalDateTime.now());
            a.setActorId("TWILIO");
            a.setActorRole("SYSTEM");
            a.setEventType(eventType);
            a.setResourceType("WHATSAPP_INBOUND");
            a.setResourceId(redact(from));
            a.setNewValues("{\"detail\":\"" + (detail == null ? "" : detail.replace("\"", "'")) + "\"}");
            auditRepo.save(a);
        } catch (Exception e) {
            logger.warn("[TWILIO-WEBHOOK] audit save failed: {}", e.getMessage());
        }
    }

    private static String redact(String phone) {
        if (phone == null) return "(none)";
        String digits = phone.replaceAll("[^0-9+]", "");
        if (digits.length() < 6) return "***";
        return digits.substring(0, Math.min(4, digits.length())) + "***"
                + digits.substring(digits.length() - 3);
    }
}
