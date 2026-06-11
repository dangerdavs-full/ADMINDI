package com.admindi.backend.service;

import com.admindi.backend.dto.NotificationHistoryEntryDTO;
import com.admindi.backend.dto.NotificationHistoryPageDTO;
import com.admindi.backend.model.AuditEventEntity;
import com.admindi.backend.model.TenantProfileEntity;
import com.admindi.backend.model.UserEntity;
import com.admindi.backend.repository.AuditEventRepository;
import com.admindi.backend.repository.TenantProfileRepository;
import com.admindi.backend.repository.UserRepository;
import com.admindi.backend.security.TenantContext;
import jakarta.persistence.criteria.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Historial de notificaciones (Bloque C - observabilidad de envíos).
 *
 * <h2>¿Qué consulta?</h2>
 * Solo eventos generados por {@link EmailService#auditMailOutcome} y
 * {@link TwilioWhatsAppService#auditOutcome}. Filtramos por {@code resource_type} para
 * aislarnos del resto del audit trail (logins, cambios de perfil, MFA, etc.), que es
 * información de seguridad que nunca debe aparecer aquí.
 *
 * <h3>Canales soportados</h3>
 * Solo {@code EMAIL} y {@code WHATSAPP} — las notificaciones IN_APP (campana) se
 * excluyen por decisión de producto (ruido operativo: 100+ por mes).
 *
 * <h2>Reglas de scope</h2>
 * <ul>
 *   <li>{@link #listForTenant}: due\u00f1o o admin ve lo de un inquilino suyo. Valida que
 *       el inquilino pertenezca al owner activo (defensa IDOR).</li>
 *   <li>{@link #listForOwner}: due\u00f1o/admin ve el historial completo de su cartera.</li>
 *   <li>{@link #listForMe}: inquilino ve SOLO lo dirigido a él y SOLO outcomes exitosos.</li>
 * </ul>
 *
 * <h2>Hard limit de 3 meses</h2>
 * El sistema SIEMPRE rechaza consultas de meses anteriores a {@code YearMonth.now() - 2}.
 * Esto complementa el archivador trimestral (C7) que físicamente borra los eventos
 * más viejos. La defensa en el servicio protege además contra flujos donde el archivador
 * aún no corrió (lag): la UI nunca debe mostrar registros de hace &gt; 3 meses aunque existan.
 */
@Service
public class NotificationHistoryService {

    private static final Logger logger = LoggerFactory.getLogger(NotificationHistoryService.class);
    private static final ZoneId ZONE_CDMX = ZoneId.of("America/Mexico_City");

    // resource_type values emitted by the 2 notification services.
    private static final String RT_EMAIL = "EMAIL_NOTIFICATION";
    private static final String RT_WHATSAPP = "WHATSAPP_NOTIFICATION";

    // event_type prefixes (canonical strings from EmailService / TwilioWhatsAppService).
    // IMPORTANTE: el orden de detección importa (EMAIL_SENT debe probarse antes de
    // FAILED para evitar colisiones accidentales con tipos futuros). Ver parseEventType.
    private static final String PFX_EMAIL_SENT = "MAIL_EMAIL_SENT_";
    private static final String PFX_EMAIL_FAILED = "MAIL_EMAIL_FAILED_";
    private static final String PFX_WA_SENT = "WHATSAPP_SENT_";
    private static final String PFX_WA_FAILED = "WHATSAPP_FAILED_";
    private static final String PFX_WA_SKIPPED = "WHATSAPP_SKIPPED_";

    // Nota sobre bounds: NO inyectamos ReportingPeriodService aquí porque sus bounds
    // son "desde la creación del dueño hasta hoy", mientras que el historial de
    // notificaciones tiene una restricción más estricta: solo últimos 3 meses. La lógica
    // local de effectiveBounds() intersecta ambos límites sin depender de ese servicio.

    private final AuditEventRepository auditRepository;
    private final UserRepository userRepository;
    private final TenantProfileRepository tenantProfileRepository;

    public NotificationHistoryService(AuditEventRepository auditRepository,
                                      UserRepository userRepository,
                                      TenantProfileRepository tenantProfileRepository) {
        this.auditRepository = auditRepository;
        this.userRepository = userRepository;
        this.tenantProfileRepository = tenantProfileRepository;
    }

    // ════════════════════════════════════════════════════════════════════════
    //  API pública
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Historial del inquilino (usado en el expediente del dueño/admin).
     * Valida que el tenantProfile pertenezca al owner activo.
     */
    public NotificationHistoryPageDTO listForTenant(String tenantProfileId, String monthYear) {
        TenantProfileEntity profile = tenantProfileRepository.findById(tenantProfileId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Inquilino no encontrado."));

        String ownerScope = resolveOwnerScopeOrThrow();
        if (!ownerScope.equals(profile.getOwnerId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Este inquilino no pertenece al portafolio activo.");
        }

        YearMonth ym = validateMonthWith3MonthLimit(monthYear, ownerScope);

        Specification<AuditEventEntity> spec = Specification
                .where(inOwnerScope(ownerScope))
                .and(inNotificationResourceTypes())
                .and(inMonth(ym))
                .and(recipientIs(profile.getUserId()))
                .and(channelIn(null));

        return runQuery(spec, ym, ownerScope, false);
    }

    /**
     * Historial global del dueño/admin con filtros opcionales.
     * {@code channelFilter}: "EMAIL" | "WHATSAPP" | null.
     * {@code outcomeFilter}: "SENT" | "FAILED" | "SKIPPED" | null.
     * {@code tenantUserIdFilter}: filtrar por un inquilino específico.
     */
    public NotificationHistoryPageDTO listForOwner(String monthYear,
                                                   String channelFilter,
                                                   String outcomeFilter,
                                                   String tenantUserIdFilter) {
        String ownerScope = resolveOwnerScopeOrThrow();
        YearMonth ym = validateMonthWith3MonthLimit(monthYear, ownerScope);

        Specification<AuditEventEntity> spec = Specification
                .where(inOwnerScope(ownerScope))
                .and(inNotificationResourceTypes())
                .and(inMonth(ym))
                .and(recipientIs(nullSafe(tenantUserIdFilter)))
                .and(channelIn(nullSafe(channelFilter)))
                .and(outcomeIs(nullSafe(outcomeFilter), nullSafe(channelFilter)));

        return runQuery(spec, ym, ownerScope, false);
    }

    /**
     * Historial del usuario autenticado (portal propio).
     *
     * <p>Solo outcomes exitosos para evitar exponer detalles operativos de
     * fallos. El filtro principal es {@code recipientIs(myUserId)} — el
     * usuario solo ve eventos dirigidos a él, independientemente del owner
     * en cuyo contexto se emitieron.</p>
     *
     * <p>Resolución del scope:</p>
     * <ul>
     *   <li>Tenants y staff (property_admin/accountant): tienen {@code
     *       users.owner_id} seteado apuntando a su dueño único → se usa
     *       como scope, consistente con el panel del dueño.</li>
     *   <li>Agentes / proveedores de plataforma: {@code users.owner_id} es
     *       NULL porque trabajan en el catálogo global (ver
     *       MaintenanceProviderService.createProvider). En este caso se
     *       omite el filtro por owner y se devuelven TODAS las
     *       notificaciones dirigidas al usuario, cruzando owners —
     *       que es justamente lo que el agente necesita ver.</li>
     * </ul>
     */
    public NotificationHistoryPageDTO listForMe(String monthYear) {
        String myUserId = resolveCurrentUserIdOrThrow();
        UserEntity me = userRepository.findById(myUserId).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.FORBIDDEN, "Sesión inválida."));

        String ownerScope = (me.getOwnerId() != null && !me.getOwnerId().isBlank())
                ? me.getOwnerId() : null;

        YearMonth ym = validateMonthWith3MonthLimit(monthYear, ownerScope);

        Specification<AuditEventEntity> spec = Specification
                .where(inNotificationResourceTypes())
                .and(inMonth(ym))
                .and(recipientIs(myUserId))
                .and(onlySuccessfulOutcomes());
        if (ownerScope != null) {
            spec = spec.and(inOwnerScope(ownerScope));
        }

        return runQuery(spec, ym, ownerScope, true);
    }

    /**
     * Lookup directo de un evento por id (para retry service).
     */
    public Optional<AuditEventEntity> findById(String auditId) {
        return auditRepository.findById(auditId);
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Ejecución de query + mapeo
    // ════════════════════════════════════════════════════════════════════════

    private NotificationHistoryPageDTO runQuery(Specification<AuditEventEntity> spec,
                                                YearMonth ym, String ownerScope,
                                                boolean onlySent) {
        List<AuditEventEntity> rows = auditRepository.findAll(
                spec, Sort.by(Sort.Direction.DESC, "timestamp"));

        // Cache de lookups para no hacer N queries de users cuando la misma persona
        // aparece en muchas filas (típico en historial por-inquilino).
        Map<String, UserEntity> userCache = new HashMap<>();

        List<NotificationHistoryEntryDTO> entries = new ArrayList<>(rows.size());
        int sent = 0, failed = 0, skipped = 0;
        for (AuditEventEntity row : rows) {
            NotificationHistoryEntryDTO dto = mapRow(row, userCache);
            if (dto == null) continue;
            if (onlySent && !"SENT".equals(dto.getOutcome())) continue;
            entries.add(dto);
            switch (dto.getOutcome()) {
                case "SENT":    sent++; break;
                case "FAILED":  failed++; break;
                case "SKIPPED": skipped++; break;
                default:        break;
            }
        }

        String[] bounds = effectiveBounds(ownerScope);
        return new NotificationHistoryPageDTO(entries, entries.size(),
                sent, failed, skipped, ym.toString(), bounds[0], bounds[1]);
    }

    private NotificationHistoryEntryDTO mapRow(AuditEventEntity row, Map<String, UserEntity> cache) {
        ParsedEventType parsed = parseEventType(row.getEventType());
        if (parsed == null) return null; // evento que ya no nos incumbe (p.ej. audit no-notif)

        UserEntity recipient = resolveUser(row.getResourceId(), cache);
        UserEntity actor = resolveUser(row.getActorId(), cache);

        // V54: el identificador canónico del actor es el username (V48). Si el
        // actor existe devolvemos su username; si solo tenemos el actorId del
        // audit trail, lo usamos directamente.
        String actorEmail;
        if (actor != null) {
            actorEmail = actor.getLoginUsername();
        } else if ("SYSTEM".equals(row.getActorId()) || row.getActorId() == null) {
            actorEmail = "SYSTEM";
        } else {
            actorEmail = row.getActorId();
        }

        String detail = extractDetailFromJson(row.getNewValues());

        return new NotificationHistoryEntryDTO(
                row.getId(),
                row.getTimestamp(),
                parsed.channel,
                parsed.outcome,
                parsed.eventType,
                row.getResourceId(),
                recipient != null ? recipient.getName() : null,
                recipient != null ? redactEmail(recipient.getContactEmail()) : null,
                recipient != null ? redactPhone(recipient.getPhone()) : null,
                actorEmail,
                detail
        );
    }

    private UserEntity resolveUser(String userId, Map<String, UserEntity> cache) {
        if (userId == null || userId.isBlank() || "SYSTEM".equals(userId)) return null;
        if (cache.containsKey(userId)) return cache.get(userId);
        UserEntity u = userRepository.findById(userId).orElse(null);
        cache.put(userId, u);
        return u;
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Parseo del event_type canónico
    // ════════════════════════════════════════════════════════════════════════

    private static final class ParsedEventType {
        final String channel;
        final String outcome;
        final String eventType;
        ParsedEventType(String channel, String outcome, String eventType) {
            this.channel = channel;
            this.outcome = outcome;
            this.eventType = eventType;
        }
    }

    /**
     * Parsea el event_type canónico. Devuelve null si el prefijo no corresponde a
     * un evento de notificación (defensa contra filas que no deberían haber entrado
     * por la Specification pero pudieran colarse).
     */
    private ParsedEventType parseEventType(String raw) {
        if (raw == null) return null;
        // Orden: más específico a más general. Email empieza con MAIL_ para distinguir.
        if (raw.startsWith(PFX_EMAIL_SENT))    return new ParsedEventType("EMAIL", "SENT",    raw.substring(PFX_EMAIL_SENT.length()));
        if (raw.startsWith(PFX_EMAIL_FAILED))  return new ParsedEventType("EMAIL", "FAILED",  raw.substring(PFX_EMAIL_FAILED.length()));
        if (raw.startsWith(PFX_WA_SENT))       return new ParsedEventType("WHATSAPP", "SENT",    raw.substring(PFX_WA_SENT.length()));
        if (raw.startsWith(PFX_WA_FAILED))     return new ParsedEventType("WHATSAPP", "FAILED",  raw.substring(PFX_WA_FAILED.length()));
        if (raw.startsWith(PFX_WA_SKIPPED))    return new ParsedEventType("WHATSAPP", "SKIPPED", raw.substring(PFX_WA_SKIPPED.length()));
        return null;
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Specifications (filtros dinámicos)
    // ════════════════════════════════════════════════════════════════════════

    private Specification<AuditEventEntity> inOwnerScope(String ownerId) {
        return (root, q, cb) -> cb.equal(root.get("ownerId"), ownerId);
    }

    private Specification<AuditEventEntity> inNotificationResourceTypes() {
        return (root, q, cb) -> root.get("resourceType").in(RT_EMAIL, RT_WHATSAPP);
    }

    private Specification<AuditEventEntity> inMonth(YearMonth ym) {
        LocalDateTime start = ym.atDay(1).atStartOfDay();
        LocalDateTime end = ym.plusMonths(1).atDay(1).atStartOfDay();
        return (root, q, cb) -> cb.and(
                cb.greaterThanOrEqualTo(root.get("timestamp"), start),
                cb.lessThan(root.get("timestamp"), end)
        );
    }

    private Specification<AuditEventEntity> recipientIs(String userId) {
        return (root, q, cb) -> userId == null ? cb.conjunction()
                : cb.equal(root.get("resourceId"), userId);
    }

    /**
     * Filtra por resource_type según canal. Reutiliza el predicado base de
     * {@link #inNotificationResourceTypes()} cuando channel=null.
     */
    private Specification<AuditEventEntity> channelIn(String channel) {
        return (root, q, cb) -> {
            if (channel == null) return cb.conjunction();
            if ("EMAIL".equals(channel))    return cb.equal(root.get("resourceType"), RT_EMAIL);
            if ("WHATSAPP".equals(channel)) return cb.equal(root.get("resourceType"), RT_WHATSAPP);
            return cb.disjunction(); // valor inválido → 0 filas (defensa)
        };
    }

    /**
     * Filtra por outcome combinando el prefijo correcto con el canal.
     * Sin channel el filtro se relaja para aceptar outcome en ambos canales.
     */
    private Specification<AuditEventEntity> outcomeIs(String outcome, String channel) {
        return (root, q, cb) -> {
            if (outcome == null) return cb.conjunction();

            List<String> prefixes = new ArrayList<>(3);
            boolean wantEmail = channel == null || "EMAIL".equals(channel);
            boolean wantWa    = channel == null || "WHATSAPP".equals(channel);
            switch (outcome) {
                case "SENT":
                    if (wantEmail) prefixes.add(PFX_EMAIL_SENT);
                    if (wantWa)    prefixes.add(PFX_WA_SENT);
                    break;
                case "FAILED":
                    if (wantEmail) prefixes.add(PFX_EMAIL_FAILED);
                    if (wantWa)    prefixes.add(PFX_WA_FAILED);
                    break;
                case "SKIPPED":
                    // Email no emite SKIPPED; solo WA.
                    if (wantWa)    prefixes.add(PFX_WA_SKIPPED);
                    break;
                default:
                    return cb.disjunction();
            }
            if (prefixes.isEmpty()) return cb.disjunction();

            List<Predicate> ors = new ArrayList<>(prefixes.size());
            for (String p : prefixes) {
                ors.add(cb.like(root.get("eventType"), p + "%"));
            }
            return cb.or(ors.toArray(new Predicate[0]));
        };
    }

    /**
     * Portal del inquilino: solo SENT (email+whatsapp). Implementado como filtro de
     * prefijos para no depender del campo channel ni del outcome string de entrada.
     */
    private Specification<AuditEventEntity> onlySuccessfulOutcomes() {
        return (root, q, cb) -> cb.or(
                cb.like(root.get("eventType"), PFX_EMAIL_SENT + "%"),
                cb.like(root.get("eventType"), PFX_WA_SENT + "%")
        );
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Validación del mes (intersección: owner bounds ∩ últimos 3 meses)
    // ════════════════════════════════════════════════════════════════════════

    private YearMonth validateMonthWith3MonthLimit(String monthYear, String ownerId) {
        YearMonth ym;
        try {
            ym = YearMonth.parse(monthYear);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "monthYear inválido, usa formato YYYY-MM.");
        }
        String[] bounds = effectiveBounds(ownerId);
        YearMonth min = YearMonth.parse(bounds[0]);
        YearMonth max = YearMonth.parse(bounds[1]);
        if (ym.isBefore(min) || ym.isAfter(max)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, String.format(
                    "Fuera de rango: solo los últimos 3 meses están disponibles (%s a %s). " +
                    "Para histórico anterior, contacta a soporte.", min, max));
        }
        return ym;
    }

    /**
     * Calcula los bounds efectivos del historial de notificaciones:
     *   min = max(ownerMin, thisMonth - 2)
     *   max = thisMonth
     *
     * Esto asegura que un dueño nuevo (creado hace 5 días) no vea "meses disponibles"
     * que no puede haber recibido notificaciones, y que un dueño viejo no vea
     * meses archivados que ya no están en audit_events.
     */
    String[] effectiveBounds(String ownerId) {
        YearMonth now = YearMonth.now(ZONE_CDMX);
        YearMonth last3 = now.minusMonths(2); // mes actual + 2 anteriores = 3 meses visibles
        YearMonth ownerMin;
        try {
            // Si el caller es el propio owner usamos el helper existente; si no,
            // hacemos la misma lógica manualmente consultando created_at del owner.
            UserEntity owner = userRepository.findById(ownerId).orElse(null);
            if (owner != null && owner.getCreatedAt() != null) {
                ownerMin = YearMonth.from(owner.getCreatedAt());
            } else {
                ownerMin = last3; // owner sin created_at → asume no tiene histórico
            }
        } catch (Exception e) {
            logger.warn("[NOTIF-HISTORY] no se pudo resolver ownerMin para {}: {}", ownerId, e.getMessage());
            ownerMin = last3;
        }
        YearMonth min = ownerMin.isAfter(last3) ? ownerMin : last3;
        return new String[]{ min.toString(), now.toString() };
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Helpers
    // ════════════════════════════════════════════════════════════════════════

    private String resolveOwnerScopeOrThrow() {
        String ownerId = TenantContext.resolveOwnerId(userRepository);
        if (ownerId == null) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Sin contexto de dueño activo.");
        }
        return ownerId;
    }

    private String resolveCurrentUserIdOrThrow() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getName() == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sesión inválida.");
        }
        UserEntity u = userRepository.findByLoginIdentifier(auth.getName()).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Usuario no encontrado."));
        return u.getId();
    }

    private static String nullSafe(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }

    /** Misma lógica visual que TwilioWhatsAppService#redactPhone para consistencia. */
    private static String redactPhone(String phone) {
        if (phone == null) return null;
        String digits = phone.replaceAll("[^0-9+]", "");
        if (digits.length() < 6) return "***";
        return digits.substring(0, Math.min(4, digits.length())) + "***"
                + digits.substring(digits.length() - 3);
    }

    /** Misma lógica visual que EmailService#redactEmail para consistencia. */
    private static String redactEmail(String addr) {
        if (addr == null) return null;
        int at = addr.indexOf('@');
        if (at <= 0) return "***";
        String local = addr.substring(0, at);
        String domain = addr.substring(at);
        String head = local.length() <= 2 ? local.substring(0, 1) : local.substring(0, 2);
        return head + "***" + domain;
    }

    /**
     * Extrae el campo "detail" del JSON guardado en new_values. Evita dependencia de
     * ObjectMapper — los valores se guardan con un patrón fijo simple y un regex mínimo
     * alcanza sin exponer a fallos por comillas internas.
     */
    private static String extractDetailFromJson(String json) {
        if (json == null || json.isBlank()) return null;
        // Patrón: {"detail":"valor"} — con comillas escapadas a apostrofes antes de guardar.
        int idx = json.indexOf("\"detail\":\"");
        if (idx < 0) return null;
        int start = idx + "\"detail\":\"".length();
        int end = json.indexOf("\"", start);
        if (end < 0) return null;
        return json.substring(start, end);
    }
}
