package com.admindi.backend.whatsapp;

import com.admindi.backend.ai.PromptGuardrails;
import com.admindi.backend.model.AuditEventEntity;
import com.admindi.backend.model.UserEntity;
import com.admindi.backend.model.WhatsappConversationStateEntity;
import com.admindi.backend.repository.AuditEventRepository;
import com.admindi.backend.service.TwilioWhatsAppService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;

/**
 * Chatbot WhatsApp para el dueño: validar comprobantes CASH y SPEI en cola manual.
 */
@Service
public class WhatsAppOwnerBotOrchestrator {

    private static final Logger logger = LoggerFactory.getLogger(WhatsAppOwnerBotOrchestrator.class);

    private static final Set<String> MENU_KEYWORDS = Set.of(
            "menu", "menú", "inicio", "hola", "hi", "hello", "start", "validar", "pagos");

    private final WhatsAppSessionService sessions;
    private final WhatsAppPinService pinService;
    private final TwilioWhatsAppService twilio;
    private final AuditEventRepository auditRepo;
    private final WhatsAppOwnerFlowHandlers flows;
    private final WhatsAppOwnerReportFlow reportFlow;
    private final WhatsAppAccountGate accountGate;

    @Value("${whatsapp.bot.owner-enabled:false}")
    private boolean ownerBotEnabled;

    public WhatsAppOwnerBotOrchestrator(WhatsAppSessionService sessions,
                                         WhatsAppPinService pinService,
                                         TwilioWhatsAppService twilio,
                                         AuditEventRepository auditRepo,
                                         WhatsAppOwnerFlowHandlers flows,
                                         WhatsAppOwnerReportFlow reportFlow,
                                         WhatsAppAccountGate accountGate) {
        this.sessions = sessions;
        this.pinService = pinService;
        this.twilio = twilio;
        this.auditRepo = auditRepo;
        this.flows = flows;
        this.reportFlow = reportFlow;
        this.accountGate = accountGate;
    }

    public boolean isOwnerBotEnabled() {
        return ownerBotEnabled;
    }

    public void handleInbound(Map<String, String> params, UserEntity owner) {
        if (!ownerBotEnabled) {
            auditOutcome("OWNER_BOT_DISABLED", params.get("From"), "whatsapp.bot.owner-enabled=false");
            return;
        }

        String rawFrom = params.get("From");
        String fromE164 = PhoneIdentityResolver.toE164(rawFrom);
        String body = safeBody(params.get("Body"));
        String bodyLower = body == null ? "" : body.trim().toLowerCase(Locale.ROOT);

        WhatsAppAccountGate.AccessResult access = accountGate.evaluate(owner);
        if (access.status() != WhatsAppAccountGate.AccessStatus.ALLOWED) {
            if (access.status() == WhatsAppAccountGate.AccessStatus.NEEDS_ACTIVATION) {
                accountGate.offerActivation(fromE164, owner, isActivationKeyword(bodyLower));
                auditOutcome("OWNER_BOT_NEEDS_ACTIVATION", fromE164, access.detail());
            } else {
                accountGate.sendDeniedInactive(fromE164);
                auditOutcome("OWNER_BOT_DENIED_INACTIVE", fromE164, access.detail());
            }
            return;
        }

        Optional<WhatsappConversationStateEntity> maybeSession = sessions.load(fromE164);
        WhatsappConversationStateEntity session = maybeSession.orElseGet(() -> {
            WhatsAppBotState initial = pinService.hasPinConfigured(owner.getId())
                    ? WhatsAppBotState.ASKING_PIN
                    : WhatsAppBotState.ASKING_PIN_SETUP;
            return sessions.reset(fromE164, owner.getId(), initial);
        });

        WhatsAppBotState currentState = sessions.currentState(session);
        if (isMenuKeyword(bodyLower) && (currentState == WhatsAppBotState.OWNER_MENU
                || currentState == WhatsAppBotState.OWNER_PROOF_PICK
                || currentState == WhatsAppBotState.OWNER_REPORT_MONTH
                || currentState == WhatsAppBotState.OWNER_REPORT_SCOPE
                || currentState == WhatsAppBotState.OWNER_REPORT_TENANT_PICK)) {
            flows.showOwnerMenu(fromE164, owner, session);
            return;
        }

        try {
            switch (currentState) {
                case ASKING_PIN_SETUP -> handleAskingPinSetup(fromE164, owner, session, body);
                case ASKING_PIN -> handleAskingPin(fromE164, owner, session, body);
                case LOCKED -> handleLocked(fromE164, owner);
                case OWNER_MENU -> flows.handleOwnerMenu(fromE164, owner, session, body);
                case OWNER_PROOF_PICK -> flows.handleProofPick(fromE164, owner, session, body);
                case OWNER_PROOF_DECIDE -> flows.handleProofDecide(fromE164, owner, session, body);
                case OWNER_PROOF_REJECT_REASON -> flows.handleRejectReason(fromE164, owner, session, body);
                case OWNER_REPORT_MONTH -> reportFlow.handleMonthChoice(fromE164, owner, session, body);
                case OWNER_REPORT_SCOPE -> reportFlow.handleScopeChoice(fromE164, owner, session, body);
                case OWNER_REPORT_TENANT_PICK -> reportFlow.handleTenantPick(fromE164, owner, session, body);
                case MENU -> flows.showOwnerMenu(fromE164, owner, session);
                default -> flows.showOwnerMenu(fromE164, owner, session);
            }
        } catch (PromptGuardrails.InjectionAttemptException injEx) {
            auditOutcome("OWNER_BOT_INJECTION", fromE164, injEx.getMessage());
            twilio.sendFreeformWhatsApp(fromE164,
                    "No pude procesar tu mensaje. Escribe MENU para volver.");
        } catch (Exception ex) {
            logger.error("[OWNER-BOT] error user={}: {}", owner.getId(), ex.getMessage(), ex);
            auditOutcome("OWNER_BOT_ERROR", fromE164, ex.getClass().getSimpleName());
            twilio.sendFreeformWhatsApp(fromE164,
                    "Problema temporal. Escribe MENU para reintentar.");
        }
    }

    private void handleAskingPinSetup(String fromE164, UserEntity owner,
                                       WhatsappConversationStateEntity session, String body) {
        String pin = digitsOnly(body);
        if (pin.length() < pinService.getMinLength() || pin.length() > pinService.getMaxLength()) {
            twilio.sendFreeformWhatsApp(fromE164,
                    "Configura un NIP de " + pinService.getMinLength() + " a "
                            + pinService.getMaxLength() + " dígitos para autorizar pagos por WhatsApp.");
            return;
        }
        try {
            pinService.setPin(owner.getId(), pin);
        } catch (IllegalArgumentException ex) {
            twilio.sendFreeformWhatsApp(fromE164, "NIP inválido: " + ex.getMessage());
            return;
        }
        sessions.transition(session, WhatsAppBotState.OWNER_MENU, Map.of());
        twilio.sendFreeformWhatsApp(fromE164,
                "NIP registrado. Úsalo cada vez que entres; ADMINDI nunca te lo pedirá por otro canal.");
        flows.showOwnerMenu(fromE164, owner, session);
    }

    private void handleAskingPin(String fromE164, UserEntity owner,
                                  WhatsappConversationStateEntity session, String body) {
        String pin = digitsOnly(body);
        if (pin.isBlank()) {
            twilio.sendFreeformWhatsApp(fromE164, "Ingresa tu NIP (solo números).");
            return;
        }
        WhatsAppPinService.VerifyResult res = pinService.verify(owner.getId(), pin);
        switch (res) {
            case OK -> {
                sessions.transition(session, WhatsAppBotState.OWNER_MENU, Map.of());
                flows.showOwnerMenu(fromE164, owner, session);
            }
            case MISMATCH -> twilio.sendFreeformWhatsApp(fromE164, "NIP incorrecto.");
            case LOCKED -> {
                sessions.transition(session, WhatsAppBotState.LOCKED, Map.of());
                twilio.sendFreeformWhatsApp(fromE164,
                        "NIP bloqueado por intentos fallidos. Intenta más tarde o desde el portal.");
            }
            case NOT_CONFIGURED -> {
                sessions.transition(session, WhatsAppBotState.ASKING_PIN_SETUP, Map.of());
                twilio.sendFreeformWhatsApp(fromE164, "Primero configura tu NIP.");
            }
            case ERROR -> twilio.sendFreeformWhatsApp(fromE164, "Error validando NIP. Intenta en unos minutos.");
        }
    }

    private void handleLocked(String fromE164, UserEntity owner) {
        twilio.sendFreeformWhatsApp(fromE164,
                "Sesión bloqueada. Espera el tiempo de enfriamiento o contacta soporte ADMINDI.");
    }

    private boolean isMenuKeyword(String txt) {
        return MENU_KEYWORDS.contains(txt);
    }

    private boolean isActivationKeyword(String bodyLower) {
        return bodyLower.contains("activar") || bodyLower.contains("contraseña")
                || bodyLower.contains("password") || bodyLower.contains("clave");
    }

    private String safeBody(String raw) {
        try {
            return PromptGuardrails.sanitize(raw);
        } catch (PromptGuardrails.InjectionAttemptException ex) {
            return "";
        }
    }

    private String digitsOnly(String s) {
        if (s == null) return "";
        return s.replaceAll("\\D", "");
    }

    private void auditOutcome(String event, String phone, String detail) {
        try {
            AuditEventEntity a = new AuditEventEntity();
            a.setId(UUID.randomUUID().toString());
            a.setTimestamp(LocalDateTime.now());
            a.setActorId("TWILIO_OWNER_BOT");
            a.setActorRole("SYSTEM");
            a.setEventType(event);
            a.setResourceType("WHATSAPP_OWNER_BOT");
            a.setResourceId(redact(phone));
            a.setNewValues("{\"detail\":\""
                    + (detail == null ? "" : detail.replace("\"", "'")) + "\"}");
            auditRepo.save(a);
        } catch (Exception ex) {
            logger.warn("[OWNER-BOT] audit failed: {}", ex.getMessage());
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
