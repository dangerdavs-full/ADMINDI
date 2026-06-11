package com.admindi.backend.controller;

import com.admindi.backend.model.AuditEventEntity;
import com.admindi.backend.model.UserEntity;
import com.admindi.backend.notifications.TemplateSamples;
import com.admindi.backend.notifications.TwilioTemplateRegistry;
import com.admindi.backend.repository.AuditEventRepository;
import com.admindi.backend.repository.UserRepository;
import com.admindi.backend.service.DomainEventDispatcher;
import com.admindi.backend.service.ReauthService;
import com.admindi.backend.service.TwilioWhatsAppService;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Panel de <strong>smoke test</strong> de plantillas WhatsApp (solo SUPER_ADMIN).
 *
 * <p>Objetivo: permitir al equipo validar end-to-end cada una de las 35 plantillas (34 aprobadas + 1 pendiente)
 * Meta-aprobadas sin tener que disparar el flujo de negocio real (crear un vacante
 * falsa, recuperar un ticket, etc.). Contempla tres operaciones:</p>
 *
 * <ol>
 *   <li>{@code GET /api/admin/notifications/templates}
 *       — lista catálogo con estado (habilitada / SID redactado / slots esperados).</li>
 *   <li>{@code POST /api/admin/notifications/templates/{eventType}/render}
 *       — <em>dry-run</em>: devuelve el payload que se enviaría a Twilio con los
 *       samples (o con variables override). No envía nada.</li>
 *   <li>{@code POST /api/admin/notifications/templates/{eventType}/send-test}
 *       — envía la plantilla real al propio SUPER_ADMIN (no a terceros). Requiere
 *       <strong>reauth</strong> (password + MFA), fuerza todos los canales y queda
 *       registrado en {@code audit_events}.</li>
 * </ol>
 *
 * <h3>Controles de seguridad</h3>
 * <ul>
 *   <li><b>AuthZ</b>: {@code @PreAuthorize("hasRole('SUPER_ADMIN')")} a nivel de clase.</li>
 *   <li><b>Reauth</b>: el envío real exige MFA+password (misma política que otras
 *       operaciones sensibles del panel SUPERADMIN).</li>
 *   <li><b>Bucket por usuario</b>: como máximo {@value #SEND_TEST_MAX_PER_HOUR}
 *       envíos por hora por SUPER_ADMIN. La interceptora global de rate limit es
 *       IP-based y puede compartirse detrás de NAT corporativo — este bucket
 *       adicional evita spam aunque el IP quede oculto.</li>
 *   <li><b>Recipientes cerrados</b>: el envío SOLO se dirige al propio actor
 *       autenticado. No se permite enviar test a terceros (otro user, número
 *       arbitrario) para evitar abuso del canal WhatsApp/SMS de la empresa.</li>
 *   <li><b>SID redactado por default</b>: en los listados se devuelve el SID
 *       truncado (HXXX…YYY). El payload dry-run sí revela el SID completo porque
 *       lo necesita el equipo para validar contra Twilio Console.</li>
 *   <li><b>Auditoría</b>: cada render/send-test persiste {@code ADMIN_WHATSAPP_SMOKE_*}.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/admin/notifications")
@PreAuthorize("hasRole('SUPER_ADMIN')")
public class AdminNotificationTestController {

    private static final Logger logger =
            LoggerFactory.getLogger(AdminNotificationTestController.class);

    /** Máximo de envíos de test por SUPER_ADMIN por hora. */
    private static final int SEND_TEST_MAX_PER_HOUR = 10;

    /** Ventana de 1h en ms para el bucket de rate limit por usuario. */
    private static final long SEND_TEST_WINDOW_MS = 60L * 60L * 1000L;

    /**
     * Buckets en memoria por userId. Para un servicio single-node basta; si se
     * escala horizontalmente habría que mover a Redis. Se elige in-memory para
     * no requerir nueva infra por una pantalla que solo usan los roots.
     */
    private final ConcurrentHashMap<String, Bucket> sendTestBuckets = new ConcurrentHashMap<>();

    private final TwilioTemplateRegistry templateRegistry;
    private final DomainEventDispatcher dispatcher;
    private final UserRepository userRepository;
    private final ReauthService reauthService;
    private final AuditEventRepository auditEventRepository;
    private final TwilioWhatsAppService twilioService;

    public AdminNotificationTestController(TwilioTemplateRegistry templateRegistry,
                                           DomainEventDispatcher dispatcher,
                                           UserRepository userRepository,
                                           ReauthService reauthService,
                                           AuditEventRepository auditEventRepository,
                                           TwilioWhatsAppService twilioService) {
        this.templateRegistry = templateRegistry;
        this.dispatcher = dispatcher;
        this.userRepository = userRepository;
        this.reauthService = reauthService;
        this.auditEventRepository = auditEventRepository;
        this.twilioService = twilioService;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 1) Catálogo: lista todos los eventTypes con estado de configuración.
    // ─────────────────────────────────────────────────────────────────────────

    @GetMapping("/templates")
    public ResponseEntity<Map<String, Object>> listTemplates() {
        Map<String, String> redacted = templateRegistry.getRedactedSnapshot();
        List<String> known = templateRegistry.getKnownEventTypes();

        List<Map<String, Object>> items = new ArrayList<>(known.size());
        int configured = 0;
        for (String eventType : known) {
            Map<String, String> sample = TemplateSamples.sampleFor(eventType);
            String sid = redacted.get(eventType);
            boolean hasSid = sid != null;
            if (hasSid) configured++;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("eventType", eventType);
            row.put("description", TemplateSamples.humanDescription(eventType));
            row.put("hasSid", hasSid);
            row.put("redactedSid", hasSid ? sid : null);
            row.put("slotCount", sample.size());
            row.put("sampleVariables", sample);
            items.add(row);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("templatesEnabled", templateRegistry.isTemplatesEnabled());
        out.put("twilioEnabled", twilioService.isEnabled());
        out.put("total", known.size());
        out.put("configured", configured);
        out.put("pending", known.size() - configured);
        out.put("items", items);
        return ResponseEntity.ok(out);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 2) Render (dry-run): devuelve el payload que iría a Twilio.
    // ─────────────────────────────────────────────────────────────────────────

    @PostMapping("/templates/{eventType}/render")
    public ResponseEntity<Map<String, Object>> renderTemplate(
            @PathVariable String eventType,
            @RequestBody(required = false) Map<String, Object> body) {
        String actorEmail = currentActorEmail();
        Map<String, String> variables = resolveVariables(eventType, body);

        String fullSid = templateRegistry.getContentSid(eventType);
        boolean hasSid = fullSid != null;

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("eventType", eventType);
        payload.put("description", TemplateSamples.humanDescription(eventType));
        payload.put("templatesEnabled", templateRegistry.isTemplatesEnabled());
        payload.put("hasSid", hasSid);
        // SID completo solo en dry-run (SUPER_ADMIN ya está autenticado y
        // necesita copiar-pegar el SID en Twilio Console para verificar).
        payload.put("contentSid", hasSid ? fullSid : null);
        payload.put("redactedSid", hasSid ? TwilioTemplateRegistry.redactSid(fullSid) : null);
        payload.put("templateVariables", variables);
        payload.put("slotCount", variables.size());
        payload.put("fallbackBody", buildFallbackBody(eventType, variables));

        persistAudit("ADMIN_WHATSAPP_SMOKE_RENDER", actorEmail, eventType, hasSid, null);
        logger.info("[SMOKE] render eventType={} actor={} slots={}",
                eventType, actorEmail, variables.size());
        return ResponseEntity.ok(payload);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 3) Send-test: envío real al propio SUPER_ADMIN.
    // ─────────────────────────────────────────────────────────────────────────

    @PostMapping("/templates/{eventType}/send-test")
    public ResponseEntity<Map<String, Object>> sendTest(
            @PathVariable String eventType,
            @RequestBody Map<String, Object> body) {
        String actorEmail = currentActorEmail();
        UserEntity actor = userRepository.findByLoginIdentifier(actorEmail)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                        "No se pudo resolver el SUPER_ADMIN actor."));

        // Reauth (password + MFA). Antes del rate limit para no filtrar ventanas
        // abiertas a un atacante que haya robado token pero no password.
        reauthService.verifyReauth(
                asString(body.get("password")),
                asString(body.get("mfaCode")),
                "ADMIN_WHATSAPP_SMOKE_SEND"
        );

        // Rate limit por actor.
        if (!acquireSendSlot(actor.getId())) {
            logger.warn("[SMOKE] rate-limit hit actor={} eventType={}", actorEmail, eventType);
            persistAudit("ADMIN_WHATSAPP_SMOKE_RATE_LIMIT", actorEmail, eventType, false, null);
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    "Solo se permiten " + SEND_TEST_MAX_PER_HOUR + " envíos de prueba por hora.");
        }

        Map<String, String> variables = resolveVariables(eventType, body);
        boolean hasSid = templateRegistry.hasTemplateForEvent(eventType);
        String fallbackBody = buildFallbackBody(eventType, variables);
        String title = "[TEST] " + TemplateSamples.humanDescription(eventType);

        // Disparamos con forceAllChannels para asegurar que WhatsApp salga incluso
        // si el SUPER_ADMIN tiene opt-out de este eventType en sus preferencias.
        dispatcher.dispatch(
                eventType,
                title,
                fallbackBody,
                null,
                actorEmail,
                List.of(actor.getId()),
                variables,
                null,
                true
        );

        persistAudit("ADMIN_WHATSAPP_SMOKE_SEND", actorEmail, eventType, hasSid,
                "recipient=" + actor.getId());
        logger.info("[SMOKE] send-test eventType={} actor={} hasSid={} variables={}",
                eventType, actorEmail, hasSid, variables.size());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("eventType", eventType);
        out.put("dispatched", true);
        out.put("usedTemplate", hasSid);
        out.put("recipientUserId", actor.getId());
        out.put("templateVariables", variables);
        out.put("note", hasSid
                ? "Enviado con plantilla aprobada. Revisa WhatsApp/email del SUPER_ADMIN."
                : "Sin plantilla configurada: se intentó body libre (solo entrega en ventana 24h).");
        return ResponseEntity.ok(out);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Resuelve las variables finales para un eventType: si el body trae
     * {@code templateVariables} no vacío, se usan esas; si no, el sample default.
     * Las keys se fuerzan a {@code String}.
     */
    private Map<String, String> resolveVariables(String eventType, Map<String, Object> body) {
        if (body != null) {
            Object raw = body.get("templateVariables");
            if (raw instanceof Map<?, ?> m && !m.isEmpty()) {
                Map<String, String> out = new LinkedHashMap<>();
                for (Map.Entry<?, ?> e : m.entrySet()) {
                    if (e.getKey() != null && e.getValue() != null) {
                        out.put(String.valueOf(e.getKey()), String.valueOf(e.getValue()));
                    }
                }
                if (!out.isEmpty()) return out;
            }
        }
        return TemplateSamples.sampleFor(eventType);
    }

    /**
     * Construye un body libre razonable a partir de las variables, apto como
     * fallback cuando el eventType no tiene plantilla configurada o cuando
     * Twilio cae a fallback por ventana 24h.
     */
    private String buildFallbackBody(String eventType, Map<String, String> variables) {
        StringBuilder sb = new StringBuilder();
        sb.append("[TEST] ").append(TemplateSamples.humanDescription(eventType));
        if (!variables.isEmpty()) {
            sb.append(" — Datos: ");
            boolean first = true;
            for (Map.Entry<String, String> e : variables.entrySet()) {
                if (!first) sb.append(", ");
                sb.append("{{").append(e.getKey()).append("}}=").append(e.getValue());
                first = false;
            }
        }
        return sb.toString();
    }

    private boolean acquireSendSlot(String userId) {
        long now = System.currentTimeMillis();
        Bucket bucket = sendTestBuckets.compute(userId, (k, current) -> {
            if (current == null || (now - current.windowStart) > SEND_TEST_WINDOW_MS) {
                return new Bucket(new AtomicInteger(0), now);
            }
            return current;
        });
        return bucket.count.incrementAndGet() <= SEND_TEST_MAX_PER_HOUR;
    }

    private String currentActorEmail() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getName() == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sesión no válida.");
        }
        return auth.getName();
    }

    private void persistAudit(String eventType, String actorEmail, String template,
                              boolean hasSid, String extra) {
        try {
            AuditEventEntity ev = new AuditEventEntity();
            ev.setId(UUID.randomUUID().toString());
            ev.setTimestamp(LocalDateTime.now());
            ev.setEventType(eventType);
            ev.setResourceType("WhatsAppTemplate");
            ev.setResourceId(template);
            if (actorEmail != null) {
                userRepository.findByLoginIdentifier(actorEmail).ifPresent(u -> {
                    ev.setActorId(u.getId());
                    ev.setActorRole(u.getRole() != null ? u.getRole().name() : null);
                });
            }
            Map<String, Object> nv = new HashMap<>();
            nv.put("template", template);
            nv.put("hasSid", hasSid);
            if (extra != null) nv.put("extra", extra);
            ev.setNewValues(toJson(nv));

            HttpServletRequest req = currentRequest();
            if (req != null) {
                ev.setIpAddress(req.getRemoteAddr());
                ev.setUserAgent(req.getHeader("User-Agent"));
                ev.setRequestId(req.getHeader("X-Request-Id"));
            }
            auditEventRepository.save(ev);
        } catch (Exception ex) {
            // No queremos que un fallo de auditoría tire el request — solo log.
            logger.warn("[SMOKE] audit failed eventType={} err={}", eventType, ex.getMessage());
        }
    }

    private static String asString(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static HttpServletRequest currentRequest() {
        try {
            ServletRequestAttributes attrs =
                    (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            return attrs != null ? attrs.getRequest() : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    /**
     * Serializador mínimo para {@code new_values}. Evitamos pull-in de Jackson
     * porque el entity ya está anotado con JdbcTypeCode(JSON) — nos basta con
     * que el string sea JSON válido.
     */
    private static String toJson(Map<String, Object> m) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> e : m.entrySet()) {
            if (!first) sb.append(',');
            first = false;
            sb.append('"').append(escape(e.getKey())).append('"').append(':');
            Object v = e.getValue();
            if (v == null) sb.append("null");
            else if (v instanceof Boolean || v instanceof Number) sb.append(v);
            else sb.append('"').append(escape(String.valueOf(v))).append('"');
        }
        sb.append('}');
        return sb.toString();
    }

    private static String escape(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        return sb.toString();
    }

    private static final class Bucket {
        final AtomicInteger count;
        final long windowStart;
        Bucket(AtomicInteger count, long windowStart) {
            this.count = count;
            this.windowStart = windowStart;
        }
    }
}
