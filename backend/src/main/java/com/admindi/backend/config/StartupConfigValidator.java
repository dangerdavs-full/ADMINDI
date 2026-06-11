package com.admindi.backend.config;

import com.admindi.backend.ai.AnthropicProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Validador de configuración al arranque (V55/V56).
 *
 * Emite WARN / ERROR en logs si la combinación de flags es inconsistente, para
 * que el operador se entere sin tener que descubrirlo cuando un usuario reporta
 * un fallo. No aborta el arranque — la app sigue levantando con la config que
 * tenga, pero los mensajes dejan rastro claro en los logs.
 *
 * Se ejecuta una sola vez al {@link ApplicationReadyEvent}, después de que
 * Spring haya resuelto todos los properties y beans.
 */
@Component
public class StartupConfigValidator implements ApplicationListener<ApplicationReadyEvent> {

    private static final Logger logger = LoggerFactory.getLogger(StartupConfigValidator.class);

    private final AnthropicProperties anthropic;
    private final Environment env;

    @Value("${whatsapp.bot.enabled:false}")
    private boolean botEnabled;

    @Value("${twilio.enabled:false}")
    private boolean twilioEnabled;

    @Value("${twilio.auth-token:}")
    private String twilioAuthToken;

    @Value("${banxico.cep.enabled:false}")
    private boolean banxicoEnabled;

    @Value("${banxico.cep.adaptive-ai:false}")
    private boolean banxicoAdaptiveAi;

    @Value("${app.encryption.key:}")
    private String encryptionKey;

    public StartupConfigValidator(AnthropicProperties anthropic, Environment env) {
        this.anthropic = anthropic;
        this.env = env;
    }

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        List<String> activeProfiles = Arrays.asList(env.getActiveProfiles());
        boolean isProd = activeProfiles.contains("prod");

        List<String> warnings = new ArrayList<>();
        List<String> errors = new ArrayList<>();

        // ── Encryption key — crítico en prod ──
        if (isProd && (encryptionKey == null || encryptionKey.isBlank())) {
            errors.add("ENCRYPTION_KEY está vacía en perfil prod. Los campos cifrados " +
                    "(phone, clabe, contact_email) FALLARÁN al leer/escribir. " +
                    "Setea una base64(32 bytes) en la env var ENCRYPTION_KEY.");
        }

        // ── WhatsApp bot requires Twilio + Anthropic ──
        if (botEnabled) {
            if (!twilioEnabled) {
                errors.add("WHATSAPP_BOT_ENABLED=true pero TWILIO_ENABLED=false. " +
                        "El bot no puede responder mensajes sin Twilio. " +
                        "Deshabilita el bot o configura Twilio.");
            }
            if (twilioAuthToken == null || twilioAuthToken.isBlank()) {
                errors.add("WHATSAPP_BOT_ENABLED=true pero TWILIO_AUTH_TOKEN vacío. " +
                        "El webhook no podrá validar firmas HMAC-SHA1 y rechazará todo inbound.");
            }
            if (!anthropic.isOperational()) {
                warnings.add("WHATSAPP_BOT_ENABLED=true pero ANTHROPIC no operacional " +
                        "(enabled=" + anthropic.isEnabled() + ", key="
                        + (anthropic.getApiKey() == null || anthropic.getApiKey().isBlank() ? "empty" : "set")
                        + "). El flujo OCR responderá 'no disponible' al inquilino.");
            }
        }

        // ── Banxico adaptative requires Anthropic ──
        if (banxicoEnabled && banxicoAdaptiveAi && !anthropic.isOperational()) {
            warnings.add("BANXICO_ADAPTIVE_AI=true pero Anthropic no operacional. " +
                    "Si Banxico cambia su HTML, el scraper fallará sin poder auto-adaptarse. " +
                    "Habilita Anthropic o desactiva adaptive-ai.");
        }

        // ── Anthropic key format check (sin loguearla completa) ──
        if (anthropic.isEnabled() && anthropic.getApiKey() != null
                && !anthropic.getApiKey().isBlank()) {
            String key = anthropic.getApiKey().trim();
            if (!key.startsWith("sk-ant-")) {
                errors.add("ANTHROPIC_API_KEY no parece una key válida de Anthropic " +
                        "(debe empezar con 'sk-ant-'). Verifica la variable de entorno.");
            }
            if (key.length() < 50) {
                errors.add("ANTHROPIC_API_KEY parece truncada (longitud=" + key.length()
                        + "). Las keys reales tienen > 90 caracteres.");
            }
        }

        // ── Budget sanity ──
        if (anthropic.isOperational() && anthropic.getDailyBudgetUsdPerUser() != null
                && anthropic.getDailyBudgetUsdPerUser().signum() <= 0) {
            warnings.add("ANTHROPIC_DAILY_BUDGET=" + anthropic.getDailyBudgetUsdPerUser()
                    + " ≤ 0: NO se aplicará límite por usuario. "
                    + "Recomendado: 1.00 USD en producción.");
        }

        // ── Prod-specific ──
        if (isProd) {
            if (anthropic.isEnabled() && anthropic.getDailyBudgetUsdPerUser() != null
                    && anthropic.getDailyBudgetUsdPerUser().doubleValue() > 5.0) {
                warnings.add("ANTHROPIC_DAILY_BUDGET="
                        + anthropic.getDailyBudgetUsdPerUser()
                        + " USD parece alto para producción. "
                        + "Recomendado empezar con 1.00 USD y subir con evidencia.");
            }
        }

        // ── Emit results ──
        if (errors.isEmpty() && warnings.isEmpty()) {
            logger.info("[STARTUP-VALIDATOR] configuración OK (profiles={}, twilio={}, anthropic={}, bot={}, banxico={})",
                    activeProfiles, twilioEnabled, anthropic.isOperational(), botEnabled, banxicoEnabled);
        } else {
            for (String w : warnings) {
                logger.warn("[STARTUP-VALIDATOR] WARN: {}", w);
            }
            for (String e : errors) {
                logger.error("[STARTUP-VALIDATOR] ERROR: {}", e);
            }
            logger.warn("[STARTUP-VALIDATOR] total issues — warnings={} errors={}. " +
                    "Revísalos antes de abrir tráfico al servicio.",
                    warnings.size(), errors.size());
        }
    }
}
