package com.admindi.backend.whatsapp;

import com.admindi.backend.model.UserEntity;
import com.admindi.backend.repository.UserActivationTokenRepository;
import com.admindi.backend.repository.UserRepository;
import com.admindi.backend.service.AccountActivationService;
import com.admindi.backend.service.TwilioWhatsAppService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Verifica que el usuario de WhatsApp sea una cuenta ADMINDI real y utilizable
 * antes de permitir el NIP y los flujos del bot.
 *
 * Si el user aún no activó su cuenta ({@code mustChangePassword=true}), emite
 * un link de activación por WhatsApp para que establezca su contraseña en el
 * portal (one-shot, TTL configurable).
 */
@Service
public class WhatsAppAccountGate {

    private static final Logger logger = LoggerFactory.getLogger(WhatsAppAccountGate.class);
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    /** No reemitir tokens más seguido que esto (evita spam si el user escribe HOLA 20 veces). */
    private static final int REISSUE_COOLDOWN_MINUTES = 15;

    private final UserRepository userRepo;
    private final UserActivationTokenRepository tokenRepo;
    private final AccountActivationService activationService;
    private final TwilioWhatsAppService twilio;

    public WhatsAppAccountGate(UserRepository userRepo,
                                UserActivationTokenRepository tokenRepo,
                                AccountActivationService activationService,
                                TwilioWhatsAppService twilio) {
        this.userRepo = userRepo;
        this.tokenRepo = tokenRepo;
        this.activationService = activationService;
        this.twilio = twilio;
    }

    public enum AccessStatus {
        /** Cuenta activa y con contraseña definitiva — puede usar el bot. */
        ALLOWED,
        /** Teléfono reconocido pero la cuenta aún no activó contraseña. */
        NEEDS_ACTIVATION,
        /** Cuenta desactivada o eliminada. */
        DENIED_INACTIVE
    }

    public record AccessResult(AccessStatus status, String detail) {}

    /**
     * Evalúa si el user puede operar el bot. Recarga el user desde DB para
     * detectar activación reciente (el user pudo haber consumido el link hace
     * un minuto y vuelve a escribir HOLA).
     */
    public AccessResult evaluate(UserEntity user) {
        if (user == null) {
            return new AccessResult(AccessStatus.DENIED_INACTIVE, "no_user");
        }
        UserEntity fresh = userRepo.findById(user.getId()).orElse(null);
        if (fresh == null || !fresh.isActive() || fresh.getDeletedAt() != null) {
            return new AccessResult(AccessStatus.DENIED_INACTIVE, "inactive_or_deleted");
        }
        if (fresh.isMustChangePassword()) {
            return new AccessResult(AccessStatus.NEEDS_ACTIVATION, "must_change_password");
        }
        return new AccessResult(AccessStatus.ALLOWED, "ok");
    }

    /**
     * Envía (o reenvía con throttle) el link de activación por WhatsApp.
     *
     * @return true si se envió mensaje al user.
     */
    public boolean offerActivation(String fromE164, UserEntity user) {
        return offerActivation(fromE164, user, false);
    }

    /**
     * @param forceReissue true cuando el user escribió ACTIVAR (ignora throttle de 15 min).
     */
    public boolean offerActivation(String fromE164, UserEntity user, boolean forceReissue) {
        UserEntity fresh = userRepo.findById(user.getId()).orElse(user);
        String name = fresh.getName() != null ? fresh.getName().split("\\s+")[0] : "usuario";

        if (!forceReissue && hasRecentPendingToken(fresh.getId())) {
            twilio.sendFreeformWhatsApp(fromE164,
                    "Hola " + name + ". Ya te enviamos un link para activar tu cuenta ADMINDI "
                            + "(válido " + activationTtlHours() + " h).\n\n"
                            + "Ábrelo en tu navegador, crea tu contraseña y luego escribe *HOLA* "
                            + "aquí para usar el bot.\n\n"
                            + "Si no lo encuentras, espera " + REISSUE_COOLDOWN_MINUTES
                            + " minutos y escribe *ACTIVAR* para recibir uno nuevo.");
            return true;
        }

        try {
            AccountActivationService.IssuedActivation issued = activationService.issue(
                    fresh.getId(),
                    AccountActivationService.CHANNEL_WHATSAPP,
                    "WHATSAPP_BOT",
                    null);
            twilio.sendFreeformWhatsApp(fromE164,
                    "Hola " + name + ". Confirmamos que eres usuario de ADMINDI, "
                            + "pero tu cuenta aún no está activa.\n\n"
                            + "Para proteger tu información, primero crea tu contraseña aquí:\n"
                            + issued.activationUrl() + "\n\n"
                            + "El enlace es de un solo uso y vence el "
                            + issued.expiresAt().format(FMT) + ".\n\n"
                            + "Cuando termines, escribe *HOLA* y configurarás tu NIP de WhatsApp.");
            logger.info("[BOT-GATE] activation link issued user={} tokenId={}",
                    fresh.getId(), issued.tokenId());
            return true;
        } catch (Exception ex) {
            logger.warn("[BOT-GATE] activation issue failed user={}: {}",
                    fresh.getId(), ex.getMessage());
            twilio.sendFreeformWhatsApp(fromE164,
                    "Hola " + name + ". Tu número está registrado en ADMINDI, pero necesitas "
                            + "activar tu cuenta en el portal web antes de usar este chat.\n\n"
                            + "Revisa el correo o WhatsApp de bienvenida que te envió tu arrendador, "
                            + "o pide que reenvíen tus credenciales. Luego escribe *HOLA* aquí.");
            return false;
        }
    }

    public void sendDeniedInactive(String fromE164) {
        twilio.sendFreeformWhatsApp(fromE164,
                "Esta cuenta de ADMINDI está desactivada. "
                        + "Contacta a tu arrendador o a soporte para reactivarla.");
    }

    public void sendUnknownPhone(String fromE164) {
        twilio.sendFreeformWhatsApp(fromE164,
                "Hola. Tu número no está vinculado a ninguna cuenta activa de ADMINDI.\n\n"
                        + "• Si eres *inquilino*, pide a tu arrendador que registre tu WhatsApp.\n"
                        + "• Si eres *dueño*, agrega tu número en Mi perfil del portal.\n"
                        + "• Si acabas de darte de alta, espera el mensaje de bienvenida.");
    }

    private boolean hasRecentPendingToken(String userId) {
        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(REISSUE_COOLDOWN_MINUTES);
        return tokenRepo.findByUserIdAndConsumedAtIsNullAndRevokedAtIsNull(userId).stream()
                .anyMatch(t -> t.getIssuedAt() != null && t.getIssuedAt().isAfter(cutoff));
    }

    private int activationTtlHours() {
        return 24; // mismo default que admindi.activation.ttl-hours
    }
}
