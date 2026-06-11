package com.admindi.backend.whatsapp;

import com.admindi.backend.model.PropertyEntity;
import com.admindi.backend.model.TenantProfileEntity;
import com.admindi.backend.model.UserEntity;
import com.admindi.backend.model.WhatsappConversationStateEntity;
import com.admindi.backend.repository.PropertyRepository;
import com.admindi.backend.repository.TenantProfileRepository;
import com.admindi.backend.service.TwilioWhatsAppService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Contexto de inmueble activo para inquilinos con varios expedientes.
 *
 * Tras autenticar con NIP, si el tenant tiene más de un inmueble vigente se le
 * pide elegir cuál usar en esta sesión. El {@code tenantProfileId} queda en el
 * contexto de la sesión y filtra comprobantes, tickets y consultas de saldo.
 */
@Service
public class WhatsAppPropertyContext {

    private final WhatsAppSessionService sessions;
    private final TwilioWhatsAppService twilio;
    private final TenantProfileRepository tenantProfileRepo;
    private final PropertyRepository propertyRepo;

    public WhatsAppPropertyContext(WhatsAppSessionService sessions,
                                    TwilioWhatsAppService twilio,
                                    TenantProfileRepository tenantProfileRepo,
                                    PropertyRepository propertyRepo) {
        this.sessions = sessions;
        this.twilio = twilio;
        this.tenantProfileRepo = tenantProfileRepo;
        this.propertyRepo = propertyRepo;
    }

    public List<TenantProfileEntity> activeProfiles(UserEntity user) {
        return tenantProfileRepo.findByUserIdAndArchivedAtIsNull(user.getId()).stream()
                .filter(p -> p.getPropertyId() != null)
                .toList();
    }

    /**
     * ¿Hace falta que el user elija inmueble en esta sesión?
     */
    public boolean needsSelection(UserEntity user, WhatsappConversationStateEntity session) {
        List<TenantProfileEntity> profiles = activeProfiles(user);
        if (profiles.size() <= 1) return false;
        Map<String, Object> ctx = sessions.getContext(session);
        String selected = (String) ctx.get("tenantProfileId");
        if (selected == null || selected.isBlank()) return true;
        return profiles.stream().noneMatch(p -> p.getId().equals(selected));
    }

    /**
     * Si hay un solo inmueble, lo selecciona automáticamente en la sesión.
     */
    public void autoSelectIfSingle(UserEntity user, WhatsappConversationStateEntity session) {
        List<TenantProfileEntity> profiles = activeProfiles(user);
        if (profiles.size() == 1) {
            bindProfile(session, profiles.get(0));
        }
    }

    public void promptSelection(String fromE164, UserEntity user,
                                 WhatsappConversationStateEntity session) {
        List<TenantProfileEntity> profiles = activeProfiles(user);
        if (profiles.isEmpty()) {
            twilio.sendFreeformWhatsApp(fromE164,
                    "No tienes inmuebles vigentes. Contacta a tu arrendador.");
            return;
        }
        if (profiles.size() == 1) {
            bindProfile(session, profiles.get(0));
            return;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Tienes varios inmuebles registrados. ¿Sobre cuál quieres operar?\n\n");
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < profiles.size(); i++) {
            TenantProfileEntity p = profiles.get(i);
            ids.add(p.getId());
            PropertyEntity prop = propertyRepo.findById(p.getPropertyId()).orElse(null);
            String label = prop != null && prop.getName() != null ? prop.getName()
                    : "Inmueble " + (i + 1);
            sb.append(i + 1).append(") ").append(label).append("\n");
        }
        sb.append("\nResponde con el número del inmueble.");
        sessions.transition(session, WhatsAppBotState.SELECT_PROPERTY, Map.of(
                "propertyChoiceProfileIds", ids));
        twilio.sendFreeformWhatsApp(fromE164, sb.toString());
    }

    /** @return true si el inmueble quedó seleccionado correctamente. */
    public boolean handleSelection(String fromE164, UserEntity user,
                                    WhatsappConversationStateEntity session, String body) {
        Map<String, Object> ctx = sessions.getContext(session);
        Object raw = ctx.get("propertyChoiceProfileIds");
        List<?> choices = raw instanceof List ? (List<?>) raw : List.of();
        int idx = parseOption(body) - 1;
        if (idx < 0 || idx >= choices.size()) {
            twilio.sendFreeformWhatsApp(fromE164,
                    "No reconozco esa opción. Responde con el número del inmueble (1, 2, 3…).");
            return false;
        }
        String profileId = String.valueOf(choices.get(idx));
        TenantProfileEntity profile = tenantProfileRepo.findById(profileId).orElse(null);
        if (profile == null || !user.getId().equals(profile.getUserId())) {
            twilio.sendFreeformWhatsApp(fromE164, "Inmueble no válido. Intenta de nuevo.");
            return false;
        }
        bindProfile(session, profile);
        sessions.transition(session, WhatsAppBotState.MENU, Map.of());
        PropertyEntity prop = propertyRepo.findById(profile.getPropertyId()).orElse(null);
        String name = prop != null && prop.getName() != null ? prop.getName() : "tu inmueble";
        twilio.sendFreeformWhatsApp(fromE164,
                "Perfecto, trabajaremos con *" + name + "*.");
        return true;
    }

    public Optional<TenantProfileEntity> selectedProfile(WhatsappConversationStateEntity session,
                                                          UserEntity user) {
        Map<String, Object> ctx = sessions.getContext(session);
        String profileId = (String) ctx.get("tenantProfileId");
        if (profileId != null) {
            return tenantProfileRepo.findById(profileId)
                    .filter(p -> user.getId().equals(p.getUserId()))
                    .filter(p -> p.getArchivedAt() == null);
        }
        List<TenantProfileEntity> profiles = activeProfiles(user);
        if (profiles.size() == 1) {
            return Optional.of(profiles.get(0));
        }
        return Optional.empty();
    }

    public String selectedPropertyLabel(WhatsappConversationStateEntity session) {
        Map<String, Object> ctx = sessions.getContext(session);
        String name = (String) ctx.get("propertyName");
        return name != null && !name.isBlank() ? name : null;
    }

    private void bindProfile(WhatsappConversationStateEntity session, TenantProfileEntity profile) {
        PropertyEntity prop = propertyRepo.findById(profile.getPropertyId()).orElse(null);
        String name = prop != null && prop.getName() != null ? prop.getName() : "Inmueble";
        sessions.putContext(session, "tenantProfileId", profile.getId());
        sessions.putContext(session, "propertyId", profile.getPropertyId());
        sessions.putContext(session, "ownerId", profile.getOwnerId());
        sessions.putContext(session, "propertyName", name);
        sessions.save(session);
    }

    private int parseOption(String s) {
        if (s == null) return -1;
        String d = s.replaceAll("\\D", "");
        if (d.isBlank()) return -1;
        try { return Integer.parseInt(d); }
        catch (Exception ex) { return -1; }
    }
}
