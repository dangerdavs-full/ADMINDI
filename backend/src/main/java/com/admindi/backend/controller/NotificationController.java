package com.admindi.backend.controller;

import com.admindi.backend.dto.NotificationPreferenceDTO;
import com.admindi.backend.model.NotificationEntity;
import com.admindi.backend.model.NotificationPreferenceEntity;
import com.admindi.backend.model.UserEntity;
import com.admindi.backend.notifications.NotificationChannels;
import com.admindi.backend.notifications.NotificationEventCatalog;
import com.admindi.backend.repository.NotificationPreferenceRepository;
import com.admindi.backend.repository.NotificationRepository;
import com.admindi.backend.repository.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Endpoints del inbox interno (IN_APP) y de preferencias de notificación.
 *
 * Contrato público de canales (Etapa 1):
 *   - Usuario ve SOLO: IN_APP, EMAIL, WHATSAPP.
 *   - IN_APP es obligatorio: cualquier intento de apagarlo por API -> 422.
 *   - N8N no es un canal público: se rechaza en input y se oculta en output
 *     (si existe legacy en BD, se traduce a WHATSAPP).
 *   - OWNER_CREATED y eventos de bootstrap no aparecen en preferencias visibles.
 *
 * Aislamiento por usuario:
 *   - Todos los endpoints operan con el userId resuelto desde el Authentication;
 *     no se acepta userId en query o body. Esto cierra IDOR entre owners/staff.
 */
@RestController
@RequestMapping("/api/notifications")
public class NotificationController {

    private final NotificationRepository notifRepo;
    private final UserRepository userRepo;
    private final NotificationPreferenceRepository prefRepo;

    public NotificationController(NotificationRepository notifRepo, UserRepository userRepo,
                                  NotificationPreferenceRepository prefRepo) {
        this.notifRepo = notifRepo;
        this.userRepo = userRepo;
        this.prefRepo = prefRepo;
    }

    @GetMapping
    public ResponseEntity<List<NotificationEntity>> getMyNotifications() {
        String userId = resolveUserId();
        return ResponseEntity.ok(notifRepo.findByUserIdOrderByCreatedAtDesc(userId));
    }

    @GetMapping("/unread-count")
    public ResponseEntity<Map<String, Long>> getUnreadCount() {
        String userId = resolveUserId();
        long count = notifRepo.countByUserIdAndReadFalse(userId);
        return ResponseEntity.ok(Map.of("unreadCount", count));
    }

    @PutMapping("/{id}/read")
    public ResponseEntity<Void> markAsRead(@PathVariable String id) {
        String userId = resolveUserId();
        NotificationEntity notif = notifRepo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Notificación no encontrada"));
        if (!notif.getUserId().equals(userId)) {
            // No revelar existencia a otros usuarios: 404 en lugar de 403.
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Notificación no encontrada");
        }
        notif.setRead(true);
        notifRepo.save(notif);
        return ResponseEntity.ok().build();
    }

    @PutMapping("/read-all")
    public ResponseEntity<Void> markAllAsRead() {
        String userId = resolveUserId();
        List<NotificationEntity> unread = notifRepo.findByUserIdAndReadFalseOrderByCreatedAtDesc(userId);
        for (NotificationEntity n : unread) {
            n.setRead(true);
        }
        notifRepo.saveAll(unread);
        return ResponseEntity.ok().build();
    }

    /**
     * Catálogo de eventos y canales VISIBLES al usuario.
     * OWNER_CREATED queda fuera por decisión de producto (bootstrap/auditoría).
     */
    @GetMapping("/preferences/catalog")
    public ResponseEntity<Map<String, Object>> getCatalog() {
        // Sólo autenticado: el catálogo no expone información privada pero
        // tampoco debe quedar público.
        resolveUserId();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("events", NotificationEventCatalog.catalogAsMaps());
        out.put("channels", List.of(
                Map.of("id", NotificationChannels.IN_APP, "label", "En app", "mandatory", true, "editable", false),
                Map.of("id", NotificationChannels.EMAIL, "label", "Email", "mandatory", false, "editable", true),
                Map.of("id", NotificationChannels.WHATSAPP, "label", "WhatsApp", "mandatory", false, "editable", true)));
        return ResponseEntity.ok(out);
    }

    /**
     * Devuelve las preferencias del usuario autenticado, filtrando:
     *  - Canal legacy N8N (se traduce a WHATSAPP en memoria; no se persiste aquí).
     *  - Eventos ocultos (OWNER_CREATED, etc.).
     *  - IN_APP siempre se devuelve como enabled=true (canal obligatorio).
     */
    @GetMapping("/preferences")
    public ResponseEntity<List<NotificationPreferenceDTO>> getPreferences() {
        String userId = resolveUserId();
        Map<String, NotificationPreferenceDTO> byKey = new LinkedHashMap<>();
        for (NotificationPreferenceEntity e : prefRepo.findByUserId(userId)) {
            if (NotificationEventCatalog.isHiddenFromUser(e.getEventType())) continue;
            String canonical = NotificationChannels.normalize(e.getChannel());
            if (canonical == null) continue; // canal no visible -> se omite
            boolean enabled = NotificationChannels.IN_APP.equals(canonical) || e.isEnabled();
            NotificationPreferenceDTO dto = new NotificationPreferenceDTO();
            dto.setEventType(e.getEventType());
            dto.setChannel(canonical);
            dto.setEnabled(enabled);
            byKey.put(e.getEventType() + "|" + canonical, dto);
        }
        return ResponseEntity.ok(new ArrayList<>(byKey.values()));
    }

    public static class UpsertPreferencesRequest {
        private List<NotificationPreferenceDTO> preferences;

        public List<NotificationPreferenceDTO> getPreferences() {
            return preferences;
        }

        public void setPreferences(List<NotificationPreferenceDTO> preferences) {
            this.preferences = preferences;
        }
    }

    /**
     * Crea o actualiza preferencias por (eventType, channel) del usuario autenticado.
     *
     * Reglas estrictas (Etapa 1):
     *  - channel debe estar en {IN_APP, EMAIL, WHATSAPP}; N8N -> 422.
     *  - IN_APP con enabled=false -> 422 (canal obligatorio, nunca se apaga).
     *  - eventType oculto del usuario (OWNER_CREATED, OWNER_PURGED, ...) -> 422.
     *  - eventType vacío -> 422.
     *  - Si aparece N8N o IN_APP enabled=false, NO se persiste nada: respuesta atómica.
     */
    @PutMapping("/preferences")
    @Transactional
    public ResponseEntity<List<NotificationPreferenceDTO>> upsertPreferences(
            @RequestBody UpsertPreferencesRequest body) {
        if (body == null || body.getPreferences() == null || body.getPreferences().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Se requiere preferences: lista no vacía.");
        }
        String userId = resolveUserId();

        // Validación previa: si algo falla, no tocamos la BD.
        List<NotificationPreferenceDTO> toApply = new ArrayList<>();
        for (NotificationPreferenceDTO dto : body.getPreferences()) {
            if (dto == null) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "Preferencia inválida (null).");
            }
            String ev = dto.getEventType() == null ? "" : dto.getEventType().trim();
            if (ev.isEmpty()) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "eventType obligatorio en cada preferencia.");
            }
            if (NotificationEventCatalog.isHiddenFromUser(ev)) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "Evento no configurable por usuario: " + ev);
            }
            String rawCh = dto.getChannel() == null ? "" : dto.getChannel().trim().toUpperCase();
            if (NotificationChannels.LEGACY_N8N.equals(rawCh)) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "Canal N8N no es público. Use WHATSAPP (salida técnica se resuelve internamente).");
            }
            if (!NotificationChannels.VISIBLE.contains(rawCh)) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "Canal no permitido: " + dto.getChannel()
                                + ". Use uno de: IN_APP, EMAIL, WHATSAPP.");
            }
            if (NotificationChannels.IN_APP.equals(rawCh) && !dto.isEnabled()) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "El canal IN_APP es obligatorio y no puede deshabilitarse.");
            }
            NotificationPreferenceDTO normalized = new NotificationPreferenceDTO();
            normalized.setEventType(ev);
            normalized.setChannel(rawCh);
            // IN_APP: forzamos enabled=true defensivamente aunque venga true.
            normalized.setEnabled(NotificationChannels.IN_APP.equals(rawCh) ? true : dto.isEnabled());
            toApply.add(normalized);
        }

        for (NotificationPreferenceDTO dto : toApply) {
            NotificationPreferenceEntity entity = prefRepo
                    .findByUserIdAndEventTypeAndChannel(userId, dto.getEventType(), dto.getChannel())
                    .orElseGet(() -> {
                        NotificationPreferenceEntity n = new NotificationPreferenceEntity();
                        n.setId(UUID.randomUUID().toString());
                        n.setUserId(userId);
                        n.setEventType(dto.getEventType());
                        n.setChannel(dto.getChannel());
                        return n;
                    });
            entity.setEnabled(dto.isEnabled());
            prefRepo.save(entity);
        }

        return ResponseEntity.ok(
                prefRepo.findByUserId(userId).stream()
                        .filter(e -> !NotificationEventCatalog.isHiddenFromUser(e.getEventType()))
                        .map(e -> {
                            String canonical = NotificationChannels.normalize(e.getChannel());
                            if (canonical == null) return null;
                            NotificationPreferenceDTO d = new NotificationPreferenceDTO();
                            d.setEventType(e.getEventType());
                            d.setChannel(canonical);
                            d.setEnabled(NotificationChannels.IN_APP.equals(canonical) || e.isEnabled());
                            return d;
                        })
                        .filter(d -> d != null)
                        .collect(Collectors.toList()));
    }

    private String resolveUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getName() == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "No autenticado");
        }
        UserEntity user = userRepo.findByLoginIdentifier(auth.getName())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                        "Usuario autenticado no encontrado"));
        return user.getId();
    }
}
