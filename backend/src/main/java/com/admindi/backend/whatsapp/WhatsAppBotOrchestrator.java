package com.admindi.backend.whatsapp;

import com.admindi.backend.ai.PromptGuardrails;
import com.admindi.backend.model.AuditEventEntity;
import com.admindi.backend.model.TenantProfileEntity;
import com.admindi.backend.model.UserEntity;
import com.admindi.backend.model.WhatsappConversationStateEntity;
import com.admindi.backend.repository.AuditEventRepository;
import com.admindi.backend.repository.TenantProfileRepository;
import com.admindi.backend.service.TwilioWhatsAppService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;

/**
 * Orquestador del chatbot de WhatsApp.
 *
 * Entrada: mapa de parámetros del webhook de Twilio (ya validado con HMAC-SHA1
 * por {@code TwilioWebhookController}). Resuelve el user por teléfono, carga
 * sesión conversacional, despacha al handler del estado actual, persiste la
 * transición y responde al usuario con {@link TwilioWhatsAppService#sendFreeformWhatsApp}.
 *
 * Diseño por capas:
 *  - Este orquestador se ocupa del ruteo, la identidad, el NIP y las respuestas.
 *  - Los flujos de dominio (crear ticket, subir comprobante, consultar saldo)
 *    delegan a {@code WhatsAppFlowHandlers} (Fase 2 core) y {@code WhatsAppOcrFlow}
 *    (Fase 3 OCR — inyectado como Optional para no acoplar).
 *
 * Nota: inyectamos {@code WhatsAppFlowHandlers} y {@code WhatsAppOcrFlow} como
 * colaboradores para mantener este archivo enfocado en la state machine.
 */
@Service
public class WhatsAppBotOrchestrator {

    private static final Logger logger = LoggerFactory.getLogger(WhatsAppBotOrchestrator.class);

    /** Palabras clave que el usuario puede escribir para volver al menú desde cualquier estado. */
    private static final Set<String> MENU_KEYWORDS = Set.of(
            "menu", "menú", "inicio", "hola", "hi", "hello", "start");

    private final PhoneIdentityResolver phoneResolver;
    private final WhatsAppSessionService sessions;
    private final WhatsAppPinService pinService;
    private final TwilioWhatsAppService twilio;
    private final TenantProfileRepository tenantProfileRepo;
    private final AuditEventRepository auditRepo;
    private final WhatsAppFlowHandlers flows;
    private final WhatsAppOwnerBotOrchestrator ownerOrchestrator;
    private final WhatsAppAccountGate accountGate;
    private final WhatsAppPropertyContext propertyContext;

    @Value("${whatsapp.bot.enabled:false}")
    private boolean enabled;

    public WhatsAppBotOrchestrator(PhoneIdentityResolver phoneResolver,
                                    WhatsAppSessionService sessions,
                                    WhatsAppPinService pinService,
                                    TwilioWhatsAppService twilio,
                                    TenantProfileRepository tenantProfileRepo,
                                    AuditEventRepository auditRepo,
                                    WhatsAppFlowHandlers flows,
                                    WhatsAppOwnerBotOrchestrator ownerOrchestrator,
                                    WhatsAppAccountGate accountGate,
                                    WhatsAppPropertyContext propertyContext) {
        this.phoneResolver = phoneResolver;
        this.sessions = sessions;
        this.pinService = pinService;
        this.twilio = twilio;
        this.tenantProfileRepo = tenantProfileRepo;
        this.auditRepo = auditRepo;
        this.flows = flows;
        this.ownerOrchestrator = ownerOrchestrator;
        this.accountGate = accountGate;
        this.propertyContext = propertyContext;
    }

    /**
     * Punto de entrada llamado por {@code TwilioWebhookController} tras validar firma.
     *
     * La respuesta al usuario NO se incluye en el TwiML (se responde con Response
     * vacío), sino que se envía por la API de Twilio con {@code sendFreeformWhatsApp}.
     * Esto desacopla la HTTP response de Twilio del mensaje que el usuario ve.
     */
    public void handleInbound(Map<String, String> params) {
        if (!enabled) {
            auditOutcome("BOT_DISABLED", params.get("From"), "whatsapp.bot.enabled=false");
            return;
        }

        String rawFrom = params.get("From");
        String fromE164 = PhoneIdentityResolver.toE164(rawFrom);
        if (fromE164.isBlank()) {
            auditOutcome("BOT_REJECTED", rawFrom, "invalid_from");
            return;
        }

        String body = safeBody(params.get("Body"));
        int numMedia = parseInt(params.getOrDefault("NumMedia", "0"));
        List<IncomingMedia> media = collectMedia(params, numMedia);

        Optional<PhoneIdentityResolver.ResolvedPhoneUser> resolved =
                phoneResolver.resolveActiveUser(fromE164);
        if (resolved.isEmpty()) {
            if (phoneResolver.resolveInactiveByPhone(rawFrom).isPresent()) {
                accountGate.sendDeniedInactive(fromE164);
                auditOutcome("BOT_INACTIVE_ACCOUNT", fromE164, "phone matched inactive user");
            } else {
                accountGate.sendUnknownPhone(fromE164);
                auditOutcome("BOT_UNKNOWN_PHONE", fromE164, "no matching user");
            }
            return;
        }

        if (resolved.get().role() == com.admindi.backend.model.Role.OWNER) {
            if (ownerOrchestrator.isOwnerBotEnabled()) {
                ownerOrchestrator.handleInbound(params, resolved.get().user());
            } else {
                twilio.sendFreeformWhatsApp(fromE164,
                        "El bot de validación para dueños aún no está activo en tu cuenta. " +
                        "Usa el portal web o contacta soporte ADMINDI.");
                auditOutcome("OWNER_BOT_DISABLED", fromE164, "owner matched but flag off");
            }
            return;
        }

        UserEntity user = resolved.get().user();

        // Lower case para facilitar keywords y NIP casos obvios.
        String bodyLower = body == null ? "" : body.trim().toLowerCase(Locale.ROOT);

        // Gate de cuenta: debe estar activa y con contraseña definitiva (no temporal).
        WhatsAppAccountGate.AccessResult access = accountGate.evaluate(user);
        if (access.status() != WhatsAppAccountGate.AccessStatus.ALLOWED) {
            if (access.status() == WhatsAppAccountGate.AccessStatus.NEEDS_ACTIVATION) {
                accountGate.offerActivation(fromE164, user, isActivationKeyword(bodyLower));
                auditOutcome("BOT_NEEDS_ACTIVATION", fromE164, access.detail());
            } else {
                accountGate.sendDeniedInactive(fromE164);
                auditOutcome("BOT_DENIED_INACTIVE", fromE164, access.detail());
            }
            return;
        }

        // Sesión activa o fresca.
        Optional<WhatsappConversationStateEntity> maybeSession = sessions.load(fromE164);

        WhatsappConversationStateEntity session = maybeSession.orElseGet(() -> {
            // Primera interacción o sesión expirada. Decidimos estado inicial según
            // si tiene NIP configurado o no.
            WhatsAppBotState initial = pinService.hasPinConfigured(user.getId())
                    ? WhatsAppBotState.ASKING_PIN
                    : WhatsAppBotState.ASKING_PIN_SETUP;
            return sessions.reset(fromE164, user.getId(), initial);
        });

        // Shortcut: si el user escribe "menu" estando en MENU o en QUERY_VIEWING,
        // lo atajamos para que no se filtre al handler.
        WhatsAppBotState currentState = sessions.currentState(session);
        if (isMenuKeyword(bodyLower) && (currentState == WhatsAppBotState.MENU
                || currentState == WhatsAppBotState.QUERY_VIEWING)) {
            showMenu(fromE164, user, session);
            return;
        }

        try {
            switch (currentState) {
                case ASKING_PIN_SETUP -> handleAskingPinSetup(fromE164, user, session, body);
                case ASKING_PIN      -> handleAskingPin(fromE164, user, session, body);
                case LOCKED          -> handleLocked(fromE164, user);
                case MENU            -> handleMenu(fromE164, user, session, body);
                case SELECT_PROPERTY -> {
                    if (propertyContext.handleSelection(fromE164, user, session, body)) {
                        showMenu(fromE164, user, session);
                    }
                }

                case PROOF_WAITING_IMAGE     -> flows.handleProofWaitingImage(fromE164, user, session, body, media);
                case PROOF_MANUAL_ENTRY      -> flows.handleProofManualEntry(fromE164, user, session, body);
                case PROOF_CONFIRMING_DATA   -> flows.handleProofConfirmingData(fromE164, user, session, body);

                case TICKET_WAITING_DESC     -> flows.handleTicketWaitingDesc(fromE164, user, session, body);
                case TICKET_WAITING_URGENCY  -> flows.handleTicketWaitingUrgency(fromE164, user, session, body);
                case TICKET_WAITING_PROPERTY -> flows.handleTicketWaitingProperty(fromE164, user, session, body);
                case TICKET_WAITING_PHOTOS   -> flows.handleTicketWaitingPhotos(fromE164, user, session, body, media);
                case TICKET_CONFIRMING       -> flows.handleTicketConfirming(fromE164, user, session, body);

                case QUERY_VIEWING -> showMenu(fromE164, user, session);

                default -> {
                    logger.warn("[BOT] unhandled state={} for user={}", currentState, user.getId());
                    showMenu(fromE164, user, session);
                }
            }
        } catch (PromptGuardrails.InjectionAttemptException injEx) {
            auditOutcome("BOT_INJECTION_DETECTED", fromE164, injEx.getMessage());
            twilio.sendFreeformWhatsApp(fromE164,
                    "No pude procesar tu mensaje. Escribe MENU para volver al inicio.");
        } catch (Exception ex) {
            logger.error("[BOT] unexpected error handling state={} user={}: {}",
                    currentState, user.getId(), ex.getClass().getSimpleName(), ex);
            auditOutcome("BOT_ERROR", fromE164,
                    ex.getClass().getSimpleName() + ": " + safeTruncate(ex.getMessage()));
            twilio.sendFreeformWhatsApp(fromE164,
                    "Tuvimos un problema temporal. Escribe MENU para volver al inicio.");
        }
    }

    // ─── Handlers inline para el estado de autenticación ────────────────────

    private void handleAskingPinSetup(String fromE164, UserEntity user,
                                       WhatsappConversationStateEntity session, String body) {
        String pin = digitsOnly(body);
        if (pin.length() < pinService.getMinLength() || pin.length() > pinService.getMaxLength()) {
            twilio.sendFreeformWhatsApp(fromE164,
                    "Hola " + firstName(user) + ". Para proteger tus operaciones por WhatsApp, " +
                    "necesito que configures un NIP entre " + pinService.getMinLength() +
                    " y " + pinService.getMaxLength() + " dígitos. Respóndeme solo con los números, por favor.");
            return;
        }
        try {
            pinService.setPin(user.getId(), pin);
        } catch (IllegalArgumentException ex) {
            twilio.sendFreeformWhatsApp(fromE164, "NIP inválido: " + ex.getMessage());
            return;
        }
        sessions.transition(session, WhatsAppBotState.MENU, Map.of("pinSetAt", LocalDateTime.now().toString()));
        twilio.sendFreeformWhatsApp(fromE164,
                "Perfecto, tu NIP quedó registrado. NO se lo compartas a nadie; " +
                "ADMINDI nunca te lo pedirá por correo ni en llamada.");
        propertyContext.autoSelectIfSingle(user, session);
        if (propertyContext.needsSelection(user, session)) {
            propertyContext.promptSelection(fromE164, user, session);
        } else {
            showMenu(fromE164, user, session);
        }
    }

    private void handleAskingPin(String fromE164, UserEntity user,
                                  WhatsappConversationStateEntity session, String body) {
        String pin = digitsOnly(body);
        if (pin.isBlank()) {
            twilio.sendFreeformWhatsApp(fromE164,
                    "Para continuar, por favor ingresa tu NIP (solo números).");
            return;
        }
        WhatsAppPinService.VerifyResult res = pinService.verify(user.getId(), pin);
        switch (res) {
            case OK -> {
                propertyContext.autoSelectIfSingle(user, session);
                if (propertyContext.needsSelection(user, session)) {
                    propertyContext.promptSelection(fromE164, user, session);
                } else {
                    sessions.transition(session, WhatsAppBotState.MENU, Map.of());
                    showMenu(fromE164, user, session);
                }
            }
            case MISMATCH -> twilio.sendFreeformWhatsApp(fromE164,
                    "NIP incorrecto. Vuelve a intentarlo (cuidado: tras varios fallos se bloquea temporalmente).");
            case LOCKED -> {
                sessions.transition(session, WhatsAppBotState.LOCKED, Map.of());
                Optional<LocalDateTime> until = pinService.getLockedUntil(user.getId());
                String untilStr = until.map(LocalDateTime::toString).orElse("más tarde");
                twilio.sendFreeformWhatsApp(fromE164,
                        "Tu NIP quedó bloqueado por demasiados intentos fallidos. " +
                        "Inténtalo de nuevo después de " + untilStr + " o pide un reset a tu arrendador.");
            }
            case NOT_CONFIGURED -> {
                sessions.transition(session, WhatsAppBotState.ASKING_PIN_SETUP, Map.of());
                twilio.sendFreeformWhatsApp(fromE164,
                        "Parece que no tienes NIP configurado. Elige uno de " +
                        pinService.getMinLength() + " a " + pinService.getMaxLength() + " dígitos.");
            }
            case ERROR -> twilio.sendFreeformWhatsApp(fromE164,
                    "Tuvimos un problema técnico validando tu NIP. Intenta de nuevo en unos minutos.");
        }
    }

    private void handleLocked(String fromE164, UserEntity user) {
        Optional<LocalDateTime> until = pinService.getLockedUntil(user.getId());
        String untilStr = until.map(LocalDateTime::toString).orElse("más tarde");
        twilio.sendFreeformWhatsApp(fromE164,
                "Tu cuenta de WhatsApp está bloqueada hasta " + untilStr + ". " +
                "Escribe a tu arrendador si necesitas desbloquearla de inmediato.");
    }

    private void handleMenu(String fromE164, UserEntity user,
                             WhatsappConversationStateEntity session, String body) {
        if (propertyContext.needsSelection(user, session)) {
            propertyContext.promptSelection(fromE164, user, session);
            return;
        }
        String opt = digitsOnly(body);
        switch (opt) {
            case "1" -> flows.startProofFlow(fromE164, user, session);
            case "2" -> flows.startTicketFlow(fromE164, user, session);
            case "3" -> flows.showBalance(fromE164, user, session);
            case "4" -> {
                if (propertyContext.activeProfiles(user).size() > 1) {
                    propertyContext.promptSelection(fromE164, user, session);
                } else {
                    showMenu(fromE164, user, session);
                }
            }
            default -> showMenu(fromE164, user, session);
        }
    }

    private void showMenu(String fromE164, UserEntity user,
                           WhatsappConversationStateEntity session) {
        sessions.transition(session, WhatsAppBotState.MENU, Map.of());
        int properties = propertyContext.activeProfiles(user).size();
        String activeProperty = propertyContext.selectedPropertyLabel(session);
        StringBuilder sb = new StringBuilder();
        sb.append("Hola ").append(firstName(user)).append(". ¿Qué necesitas hacer?\n\n");
        if (activeProperty != null) {
            sb.append("📍 Inmueble activo: *").append(activeProperty).append("*\n\n");
        }
        sb.append("1) Registrar pago SPEI (foto o datos escritos)\n");
        sb.append("2) Reportar mantenimiento\n");
        sb.append("3) Mi saldo y últimos pagos\n");
        if (properties > 1) {
            sb.append("4) Cambiar inmueble activo\n");
        }
        sb.append("\nResponde con el número. Escribe MENU para volver aquí.");
        twilio.sendFreeformWhatsApp(fromE164, sb.toString());
    }

    private boolean isActivationKeyword(String bodyLower) {
        return bodyLower.contains("activar") || bodyLower.contains("contraseña")
                || bodyLower.contains("password") || bodyLower.contains("clave");
    }

    // ─── Helpers ────────────────────────────────────────────────────────────

    public record IncomingMedia(String url, String contentType) {}

    private List<IncomingMedia> collectMedia(Map<String, String> params, int n) {
        if (n <= 0) return List.of();
        List<IncomingMedia> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            String url = params.get("MediaUrl" + i);
            String type = params.get("MediaContentType" + i);
            if (url != null && !url.isBlank()) {
                out.add(new IncomingMedia(url, type == null ? "" : type));
            }
        }
        return out;
    }

    private boolean isMenuKeyword(String txt) {
        return MENU_KEYWORDS.contains(txt);
    }

    private String safeBody(String raw) {
        // Sanitiza para prevenir inyección contra el modelo y contra nuestros
        // propios prompts de sistema. Si viene claramente malicioso, lo logueamos
        // y devolvemos "" para que el handler reaccione como input inválido.
        try {
            return PromptGuardrails.sanitize(raw);
        } catch (PromptGuardrails.InjectionAttemptException ex) {
            logger.warn("[BOT] injection attempt in body: {}", ex.getMessage());
            return "";
        }
    }

    private String digitsOnly(String s) {
        if (s == null) return "";
        return s.replaceAll("\\D", "");
    }

    private int parseInt(String s) {
        if (s == null) return 0;
        try { return Integer.parseInt(s.trim()); }
        catch (Exception ex) { return 0; }
    }

    private String firstName(UserEntity user) {
        if (user == null || user.getName() == null) return "";
        String first = user.getName().trim().split("\\s+")[0];
        return first.isEmpty() ? "" : first;
    }

    private String safeTruncate(String s) {
        if (s == null) return "";
        return s.length() > 240 ? s.substring(0, 240) : s;
    }

    private void auditOutcome(String event, String phone, String detail) {
        try {
            AuditEventEntity a = new AuditEventEntity();
            a.setId(UUID.randomUUID().toString());
            a.setTimestamp(LocalDateTime.now());
            a.setActorId("TWILIO_BOT");
            a.setActorRole("SYSTEM");
            a.setEventType(event);
            a.setResourceType("WHATSAPP_BOT");
            a.setResourceId(redact(phone));
            a.setNewValues("{\"detail\":\""
                    + (detail == null ? "" : detail.replace("\"", "'")) + "\"}");
            auditRepo.save(a);
        } catch (Exception ex) {
            logger.warn("[BOT] audit save failed: {}", ex.getMessage());
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
