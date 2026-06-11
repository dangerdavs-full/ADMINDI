package com.admindi.backend.whatsapp;

import com.admindi.backend.model.WhatsappConversationStateEntity;
import com.admindi.backend.repository.WhatsappConversationStateRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Persiste y manipula el estado conversacional del chatbot por número E.164.
 *
 * Responsabilidades:
 *  - load/save del row en {@code whatsapp_conversation_state}.
 *  - TTL: si la sesión expiró, se descarta y la próxima interacción inicia fresh
 *    (el orquestador pedirá NIP de nuevo — decisión de seguridad consciente).
 *  - Context payload como Map<String,Object> — el JSON se serializa a {@code jsonb}.
 *  - Limpieza periódica de sesiones expiradas (cron).
 */
@Service
public class WhatsAppSessionService {

    private static final Logger logger = LoggerFactory.getLogger(WhatsAppSessionService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final WhatsappConversationStateRepository repo;

    @Value("${whatsapp.session.ttl-minutes:15}")
    private int ttlMinutes;

    public WhatsAppSessionService(WhatsappConversationStateRepository repo) {
        this.repo = repo;
    }

    /**
     * Carga la sesión activa (no expirada) para un teléfono E.164. Si no
     * existe o expiró, devuelve {@link Optional#empty()}.
     */
    public Optional<WhatsappConversationStateEntity> load(String phoneE164) {
        if (phoneE164 == null) return Optional.empty();
        Optional<WhatsappConversationStateEntity> opt = repo.findByPhoneE164(phoneE164);
        if (opt.isEmpty()) return Optional.empty();
        WhatsappConversationStateEntity entity = opt.get();
        if (entity.getExpiresAt() != null && entity.getExpiresAt().isBefore(LocalDateTime.now())) {
            return Optional.empty();
        }
        return opt;
    }

    /**
     * Crea una sesión nueva o reinicia la existente en estado {@code initialState}.
     * Útil cuando expira o cuando queremos forzar reset (cambio de user, logout).
     */
    @Transactional
    public WhatsappConversationStateEntity reset(String phoneE164, String userId,
                                                  WhatsAppBotState initialState) {
        WhatsappConversationStateEntity entity = repo.findByPhoneE164(phoneE164)
                .orElseGet(() -> {
                    WhatsappConversationStateEntity fresh = new WhatsappConversationStateEntity();
                    fresh.setPhoneE164(phoneE164);
                    return fresh;
                });
        entity.setUserId(userId);
        entity.setCurrentState(initialState.name());
        entity.setContextJson("{}");
        entity.setPendingProofId(null);
        entity.setPendingTicketId(null);
        entity.setExpiresAt(LocalDateTime.now().plusMinutes(ttlMinutes));
        return repo.save(entity);
    }

    @Transactional
    public WhatsappConversationStateEntity transition(WhatsappConversationStateEntity entity,
                                                       WhatsAppBotState nextState,
                                                       Map<String, Object> contextMerge) {
        entity.setCurrentState(nextState.name());
        if (contextMerge != null && !contextMerge.isEmpty()) {
            Map<String, Object> ctx = parseContext(entity.getContextJson());
            ctx.putAll(contextMerge);
            entity.setContextJson(serialize(ctx));
        }
        entity.setExpiresAt(LocalDateTime.now().plusMinutes(ttlMinutes));
        return repo.save(entity);
    }

    @Transactional
    public void save(WhatsappConversationStateEntity entity) {
        entity.setExpiresAt(LocalDateTime.now().plusMinutes(ttlMinutes));
        repo.save(entity);
    }

    public Map<String, Object> getContext(WhatsappConversationStateEntity entity) {
        return parseContext(entity == null ? null : entity.getContextJson());
    }

    public void putContext(WhatsappConversationStateEntity entity, String key, Object value) {
        Map<String, Object> ctx = parseContext(entity.getContextJson());
        ctx.put(key, value);
        entity.setContextJson(serialize(ctx));
    }

    public WhatsAppBotState currentState(WhatsappConversationStateEntity entity) {
        if (entity == null || entity.getCurrentState() == null) return WhatsAppBotState.UNRECOGNIZED;
        try {
            return WhatsAppBotState.valueOf(entity.getCurrentState());
        } catch (IllegalArgumentException ex) {
            return WhatsAppBotState.UNRECOGNIZED;
        }
    }

    /**
     * Cron cada 10 min: borra sesiones cuyo TTL expiró hace más de 1 día.
     * No eliminamos las recién expiradas para preservar evidencia diagnóstica.
     */
    @Scheduled(cron = "0 */10 * * * *")
    @Transactional
    public void cleanupExpired() {
        try {
            int removed = repo.deleteExpiredBefore(LocalDateTime.now().minusDays(1));
            if (removed > 0) {
                logger.info("[WA-SESSION] cleanup removed {} expired states", removed);
            }
        } catch (Exception ex) {
            logger.warn("[WA-SESSION] cleanup failed: {}", ex.getMessage());
        }
    }

    // ─── Internals ──────────────────────────────────────────────────────────

    private Map<String, Object> parseContext(String json) {
        if (json == null || json.isBlank()) return new HashMap<>();
        try {
            return MAPPER.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception ex) {
            logger.warn("[WA-SESSION] invalid context json, resetting: {}", ex.getMessage());
            return new HashMap<>();
        }
    }

    private String serialize(Map<String, Object> ctx) {
        try {
            return MAPPER.writeValueAsString(ctx);
        } catch (Exception ex) {
            logger.warn("[WA-SESSION] serialize failed, using empty: {}", ex.getMessage());
            return "{}";
        }
    }
}
