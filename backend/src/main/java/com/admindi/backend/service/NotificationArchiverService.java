package com.admindi.backend.service;

import com.admindi.backend.model.AuditEventEntity;
import com.admindi.backend.model.PropertyEntity;
import com.admindi.backend.model.TenantProfileEntity;
import com.admindi.backend.model.UserEntity;
import com.admindi.backend.repository.AuditEventRepository;
import com.admindi.backend.repository.PropertyRepository;
import com.admindi.backend.repository.TenantProfileRepository;
import com.admindi.backend.repository.UserRepository;
import com.admindi.backend.util.SlugUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Archivador trimestral de notificaciones (Bloque C7).
 *
 * <h2>Flujo diario</h2>
 * Cada noche a las 03:15 (zona servidor) revisa {@code audit_events} con más de
 * {@code admindi.notification-archive.threshold-days} días (default 90). Para cada
 * evento elegible:
 *
 * <ol>
 *   <li>Resuelve destinatario (user) y dirección del inmueble del {@link TenantProfileEntity}
 *       activo del user en el owner scope. Si nada existe, usa buckets "unknown"
 *       preservando el dato crudo.</li>
 *   <li>Agrupa por {@code (tenantSlug_propertySlug, YYYYQn)}.</li>
 *   <li>Escribe o appendea a un CSV en
 *       {@code {archive-root}/{tenantSlug}_{propertySlug}/{yearQuarter}.csv}.</li>
 *   <li>Borra los eventos archivados de {@code audit_events}.</li>
 *   <li>Emite un marker {@code AUDIT_ARCHIVED_NOTIFICATIONS} (meta-auditoría permanente).</li>
 * </ol>
 *
 * <h2>Seguridad de directorio</h2>
 * <ul>
 *   <li>Slugs ya son sanitizados por {@link SlugUtil} — imposible hacer path traversal.</li>
 *   <li>El archivo se normaliza contra {@code archiveRoot.toAbsolutePath().normalize()}
 *       como defensa en profundidad.</li>
 *   <li>El directorio debe vivir fuera de cualquier static resource de Tomcat
 *       (caller configura {@code admindi.notification-archive.path} adecuadamente).</li>
 * </ul>
 *
 * <h2>Política de borrado de due\u00f1o</h2>
 * Cuando el superadmin elimina al due\u00f1o, el servicio que hace cascade delete
 * <b>NO toca</b> esta carpeta — los CSV sobreviven por decisión de producto: el archivado
 * es un servicio externo de SUPER_ADMIN independiente del ciclo de vida del due\u00f1o.
 */
@Service
public class NotificationArchiverService {

    private static final Logger logger = LoggerFactory.getLogger(NotificationArchiverService.class);

    private static final String RT_EMAIL = "EMAIL_NOTIFICATION";
    private static final String RT_WHATSAPP = "WHATSAPP_NOTIFICATION";
    private static final int BATCH_SIZE = 1000; // tope defensivo por corrida

    private static final String CSV_HEADER = String.join(",", List.of(
            "event_id", "fecha", "hora", "canal", "resultado", "evento_tipo",
            "destinatario_user_id", "destinatario_nombre", "destinatario_email",
            "destinatario_telefono", "actor_email", "actor_role",
            "owner_id_al_archivar", "detalle"
    ));

    private final AuditEventRepository auditRepository;
    private final UserRepository userRepository;
    private final TenantProfileRepository tenantProfileRepository;
    private final PropertyRepository propertyRepository;

    @Value("${admindi.notification-archive.enabled:true}")
    private boolean enabled;

    @Value("${admindi.notification-archive.path:./archive/notifications}")
    private String archivePath;

    @Value("${admindi.notification-archive.threshold-days:90}")
    private int thresholdDays;

    /** Expuesto para que el browser service pueda calcular el mismo cutoff por default. */
    public int getConfiguredThresholdDays() { return thresholdDays; }

    public NotificationArchiverService(AuditEventRepository auditRepository,
                                       UserRepository userRepository,
                                       TenantProfileRepository tenantProfileRepository,
                                       PropertyRepository propertyRepository) {
        this.auditRepository = auditRepository;
        this.userRepository = userRepository;
        this.tenantProfileRepository = tenantProfileRepository;
        this.propertyRepository = propertyRepository;
    }

    /** Cron: todos los días a las 03:15 AM. Alineado con otros jobs del proyecto. */
    @Scheduled(cron = "0 15 3 * * *")
    @Transactional
    public void runArchiveJob() {
        if (!enabled) {
            logger.info("[ARCHIVER] Deshabilitado vía admindi.notification-archive.enabled=false");
            return;
        }
        archiveOlderThan(LocalDateTime.now().minusDays(thresholdDays));
    }

    /**
     * Entry point invocable también desde tests / comandos manuales.
     * Archiva todos los eventos de notificación con {@code timestamp < cutoff}.
     */
    @Transactional
    public int archiveOlderThan(LocalDateTime cutoff) {
        Specification<AuditEventEntity> spec = (root, q, cb) -> cb.and(
                root.get("resourceType").in(RT_EMAIL, RT_WHATSAPP),
                cb.lessThan(root.get("timestamp"), cutoff)
        );
        var page = auditRepository.findAll(spec,
                PageRequest.of(0, BATCH_SIZE, Sort.by(Sort.Direction.ASC, "timestamp")));
        List<AuditEventEntity> rows = page.getContent();
        if (rows.isEmpty()) {
            logger.debug("[ARCHIVER] Sin eventos elegibles (cutoff={})", cutoff);
            return 0;
        }

        Path root = resolveArchiveRoot();
        try {
            Files.createDirectories(root);
        } catch (IOException e) {
            logger.error("[ARCHIVER] No se pudo crear directorio archive en {}: {}. Abortando.",
                    root, e.getMessage());
            return 0;
        }

        // Caches para evitar N lookups por evento.
        Map<String, UserEntity> userCache = new HashMap<>();
        Map<String, TenantProfileEntity> profileCache = new HashMap<>();
        Map<String, PropertyEntity> propertyCache = new HashMap<>();

        // Agrupa filas por archivo destino.
        Map<Path, List<AuditEventEntity>> grouped = new HashMap<>();
        for (AuditEventEntity row : rows) {
            Path dest = resolveDestination(row, userCache, profileCache, propertyCache, root);
            if (dest == null) continue;
            grouped.computeIfAbsent(dest, p -> new ArrayList<>()).add(row);
        }

        List<String> archivedIds = new ArrayList<>();
        for (Map.Entry<Path, List<AuditEventEntity>> e : grouped.entrySet()) {
            try {
                writeCsv(e.getKey(), e.getValue(), userCache);
                for (AuditEventEntity row : e.getValue()) archivedIds.add(row.getId());
            } catch (IOException ioe) {
                logger.error("[ARCHIVER] fallo escribiendo {}: {}. No se borran sus eventos; se reintentará.",
                        e.getKey(), ioe.getMessage());
            }
        }

        if (!archivedIds.isEmpty()) {
            deleteArchivedEvents(archivedIds);
            emitMetaAudit(archivedIds.size(), grouped.size());
        }

        logger.info("[ARCHIVER] Ejecución completa: eventosElegibles={} archivados={} archivosActualizados={}",
                rows.size(), archivedIds.size(), grouped.size());
        return archivedIds.size();
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Resolución de carpeta + archivo destino
    // ════════════════════════════════════════════════════════════════════════

    private Path resolveDestination(AuditEventEntity row,
                                    Map<String, UserEntity> userCache,
                                    Map<String, TenantProfileEntity> profileCache,
                                    Map<String, PropertyEntity> propertyCache,
                                    Path root) {
        String userId = row.getResourceId();
        String userName = "desconocido";
        String address = "desconocida";

        if (userId != null && !userId.isBlank()) {
            UserEntity u = userCache.computeIfAbsent(userId,
                    id -> userRepository.findById(id).orElse(null));
            if (u != null && u.getName() != null && !u.getName().isBlank()) {
                userName = u.getName();
            }
            // Busca profile activo del user en el owner de la fila
            String ownerId = row.getOwnerId();
            if (ownerId != null) {
                String key = userId + "::" + ownerId;
                TenantProfileEntity profile = profileCache.get(key);
                if (profile == null) {
                    List<TenantProfileEntity> list = tenantProfileRepository
                            .findByUserIdAndOwnerId(userId, ownerId);
                    profile = list.isEmpty() ? null : list.get(0);
                    if (profile != null) profileCache.put(key, profile);
                }
                if (profile != null && profile.getPropertyId() != null) {
                    PropertyEntity prop = propertyCache.computeIfAbsent(profile.getPropertyId(),
                            pid -> propertyRepository.findById(pid).orElse(null));
                    if (prop != null && prop.getAddress() != null && !prop.getAddress().isBlank()) {
                        address = prop.getAddress();
                    } else if (prop != null && prop.getName() != null) {
                        address = prop.getName();
                    }
                }
            }
        }

        // Separador doble "__" para que el split del panel SUPER_ADMIN pueda distinguir
        // con certeza el slug del inquilino del slug de la propiedad (los nombres reales
        // suelen tener espacios que se convierten en '_' dentro de cada slug individual).
        String folder = SlugUtil.slugify(userName) + "__" + SlugUtil.slugify(address);
        String yearQuarter = toYearQuarter(row.getTimestamp());
        Path dest = root.resolve(folder).resolve(yearQuarter + ".csv");

        // Defensa en profundidad: el resultado debe permanecer bajo root aunque SlugUtil
        // haya tenido un bug: normalizamos y comparamos rutas absolutas.
        Path rootNorm = root.toAbsolutePath().normalize();
        Path destNorm = dest.toAbsolutePath().normalize();
        if (!destNorm.startsWith(rootNorm)) {
            logger.error("[ARCHIVER] rechazo por path traversal detectado: {}", destNorm);
            return null;
        }
        return dest;
    }

    private Path resolveArchiveRoot() {
        Path configured = Paths.get(archivePath);
        return configured.isAbsolute() ? configured : configured.toAbsolutePath();
    }

    private static String toYearQuarter(LocalDateTime ts) {
        if (ts == null) return "unknown";
        int q = ((ts.getMonthValue() - 1) / 3) + 1;
        return ts.getYear() + "Q" + q;
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Escritura del CSV (append-safe con header solo al crearlo)
    // ════════════════════════════════════════════════════════════════════════

    private void writeCsv(Path file, List<AuditEventEntity> events, Map<String, UserEntity> userCache) throws IOException {
        Files.createDirectories(file.getParent());
        boolean isNew = !Files.exists(file);
        StringBuilder sb = new StringBuilder();
        if (isNew) {
            sb.append('\uFEFF'); // BOM para Excel con UTF-8
            sb.append(CSV_HEADER).append('\n');
        }
        DateTimeFormatter date = DateTimeFormatter.ofPattern("yyyy-MM-dd");
        DateTimeFormatter time = DateTimeFormatter.ofPattern("HH:mm:ss");
        for (AuditEventEntity e : events) {
            Parsed p = parseEventType(e.getEventType());
            String recipientName = "";
            String recipientEmail = "";
            String recipientPhone = "";
            if (e.getResourceId() != null) {
                UserEntity u = userCache.get(e.getResourceId());
                if (u != null) {
                    recipientName = nz(u.getName());
                    recipientEmail = nz(u.getContactEmail());
                    recipientPhone = nz(u.getPhone());
                }
            }
            String actorEmail = resolveActorEmail(e.getActorId(), userCache);
            String detail = extractDetail(e.getNewValues());

            sb.append(csv(e.getId())).append(',')
              .append(csv(e.getTimestamp() == null ? "" : e.getTimestamp().format(date))).append(',')
              .append(csv(e.getTimestamp() == null ? "" : e.getTimestamp().format(time))).append(',')
              .append(csv(p.channel)).append(',')
              .append(csv(p.outcome)).append(',')
              .append(csv(p.eventType)).append(',')
              .append(csv(nz(e.getResourceId()))).append(',')
              .append(csv(recipientName)).append(',')
              .append(csv(recipientEmail)).append(',')
              .append(csv(recipientPhone)).append(',')
              .append(csv(actorEmail)).append(',')
              .append(csv(nz(e.getActorRole()))).append(',')
              .append(csv(nz(e.getOwnerId()))).append(',')
              .append(csv(nz(detail)))
              .append('\n');
        }
        Files.writeString(file, sb.toString(), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    private String resolveActorEmail(String actorId, Map<String, UserEntity> cache) {
        if (actorId == null || "SYSTEM".equals(actorId)) return "SYSTEM";
        UserEntity u = cache.computeIfAbsent(actorId,
                id -> userRepository.findById(id).orElse(null));
        // V54: el campo `email` desapareció; el único correo del user vive en
        // contactEmail. Si no hay contactEmail devolvemos el actorId tal cual.
        return u != null ? nz(u.getContactEmail()) : actorId;
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Cleanup de audit_events + meta-auditoría
    // ════════════════════════════════════════════════════════════════════════

    private void deleteArchivedEvents(List<String> ids) {
        auditRepository.deleteAllById(ids);
        logger.debug("[ARCHIVER] Borrados {} audit_events ya archivados en CSV.", ids.size());
    }

    private void emitMetaAudit(int eventCount, int fileCount) {
        AuditEventEntity marker = new AuditEventEntity();
        marker.setId(UUID.randomUUID().toString());
        marker.setTimestamp(LocalDateTime.now());
        marker.setActorId("SYSTEM");
        marker.setActorRole("SYSTEM");
        marker.setEventType("AUDIT_ARCHIVED_NOTIFICATIONS");
        marker.setResourceType("NOTIFICATION_ARCHIVE");
        marker.setResourceId("batch");
        marker.setNewValues(String.format(
                "{\"eventCount\":%d,\"fileCount\":%d,\"archivePath\":\"%s\"}",
                eventCount, fileCount, archivePath.replace("\\", "/").replace("\"", "'")));
        auditRepository.save(marker);
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Helpers
    // ════════════════════════════════════════════════════════════════════════

    private static final class Parsed {
        final String channel; final String outcome; final String eventType;
        Parsed(String c, String o, String e) { channel = c; outcome = o; eventType = e; }
    }

    private static Parsed parseEventType(String raw) {
        if (raw == null) return new Parsed("", "", "");
        if (raw.startsWith("MAIL_EMAIL_SENT_"))    return new Parsed("EMAIL", "SENT",    raw.substring(16));
        if (raw.startsWith("MAIL_EMAIL_FAILED_"))  return new Parsed("EMAIL", "FAILED",  raw.substring(18));
        if (raw.startsWith("WHATSAPP_SENT_"))      return new Parsed("WHATSAPP", "SENT",    raw.substring(14));
        if (raw.startsWith("WHATSAPP_FAILED_"))    return new Parsed("WHATSAPP", "FAILED",  raw.substring(16));
        if (raw.startsWith("WHATSAPP_SKIPPED_"))   return new Parsed("WHATSAPP", "SKIPPED", raw.substring(17));
        return new Parsed("", "", raw);
    }

    private static String extractDetail(String newValues) {
        if (newValues == null || newValues.isBlank()) return "";
        // Postgres jsonb normaliza con espacio después de ':' al serializar.
        // Aceptamos ambas formas: "detail":"..." y "detail": "...".
        int idx = newValues.indexOf("\"detail\"");
        if (idx < 0) return "";
        int colon = newValues.indexOf(':', idx);
        if (colon < 0) return "";
        int quote = newValues.indexOf('"', colon);
        if (quote < 0) return "";
        int start = quote + 1;
        int end = newValues.indexOf("\"", start);
        if (end < 0) return "";
        return newValues.substring(start, end);
    }

    private static String nz(String s) { return s == null ? "" : s; }

    private static String csv(String v) {
        if (v == null) return "";
        if (v.indexOf(',') >= 0 || v.indexOf('"') >= 0 || v.indexOf('\n') >= 0 || v.indexOf('\r') >= 0) {
            return "\"" + v.replace("\"", "\"\"") + "\"";
        }
        return v;
    }
}
