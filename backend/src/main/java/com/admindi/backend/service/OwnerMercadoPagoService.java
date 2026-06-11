package com.admindi.backend.service;

import com.admindi.backend.model.Role;
import com.admindi.backend.model.UserEntity;
import com.admindi.backend.repository.UserRepository;
import com.mercadopago.client.user.UserClient;
import com.mercadopago.core.MPRequestOptions;
import com.mercadopago.exceptions.MPApiException;
import com.mercadopago.exceptions.MPException;
import com.mercadopago.resources.user.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;

/**
 * Vinculación de cuenta Mercado Pago del dueño — flujo OAuth (iniciar sesión en MP).
 */
@Service
public class OwnerMercadoPagoService {

    private static final Logger logger = LoggerFactory.getLogger(OwnerMercadoPagoService.class);
    private static final long STATE_TTL_MS = 15 * 60 * 1000L;

    @Value("${mercadopago.client-id:}")
    private String clientId;

    @Value("${mercadopago.client-secret:}")
    private String clientSecret;

    @Value("${mercadopago.oauth-redirect-uri:}")
    private String oauthRedirectUri;

    @Value("${app.url:http://localhost:3000}")
    private String appUrl;

    @Value("${app.encryption.key:}")
    private String stateSigningKey;

    @Value("${mercadopago.allow-manual-token-link:false}")
    private boolean allowManualTokenLink;

    @Value("${mercadopago.oauth-use-test-token:false}")
    private boolean oauthUseTestToken;

    @Value("${mercadopago.oauth-skip-token-validation:false}")
    private boolean oauthSkipTokenValidation;

    /** Solo webhooks / API integrador — nunca se guarda como cuenta del dueño. */
    @Value("${mercadopago.access-token:}")
    private String integratorAccessToken;

    private final UserRepository userRepository;
    private final MercadoPagoOAuthApi oauthApi;

    public OwnerMercadoPagoService(UserRepository userRepository, MercadoPagoOAuthApi oauthApi) {
        this.userRepository = userRepository;
        this.oauthApi = oauthApi;
    }

    @PostConstruct
    void logOAuthStartup() {
        boolean creds = oauthApi.isOAuthConfigured(clientId, clientSecret);
        boolean redirect = oauthRedirectUri != null && !oauthRedirectUri.isBlank()
                && !oauthRedirectUri.contains("localhost");
        logger.info("[MercadoPago] OAuth al arranque: credentials={} redirect={} uri={}",
                creds, redirect, redirect ? oauthRedirectUri : "(sin URL pública)");
        if (creds && redirect) {
            boolean mpAccepts = oauthApi.validateClientCredentials(clientId, clientSecret);
            if (!mpAccepts) {
                logger.error(
                        "[MercadoPago] Client ID / Client Secret RECHAZADOS por Mercado Pago (invalid_client). "
                                + "En developers.mercadopago.com → app ADMINDI → PRODUCCIÓN → Credenciales de producción "
                                + "copia de nuevo Client ID y Client Secret (mismo bloque), pégalo en application-secrets.yml "
                                + "y ejecuta ./scripts/verificar-mp-oauth-credenciales.sh. "
                                + "No uses el Access Token de prueba como Client Secret.");
            }
        }
    }

    public Map<String, Object> getConnectionStatus(UserEntity owner) {
        boolean connected = owner.hasMercadoPagoConnected();
        boolean hasCredentials = oauthApi.isOAuthConfigured(clientId, clientSecret);
        boolean hasPublicRedirect = oauthRedirectUri != null && !oauthRedirectUri.isBlank()
                && !oauthRedirectUri.contains("localhost");
        boolean oauthReady = hasCredentials && hasPublicRedirect;
        return Map.of(
                "connected", connected,
                "mpUserId", owner.getMpUserId() != null ? owner.getMpUserId() : "",
                "connectedAt", owner.getMpConnectedAt() != null ? owner.getMpConnectedAt().toString() : "",
                "oauthReady", oauthReady,
                "oauthAvailable", oauthReady,
                "oauthCredentialsConfigured", hasCredentials,
                "oauthRedirectConfigured", hasPublicRedirect,
                "oauthRedirectUri", hasPublicRedirect ? oauthRedirectUri : ""
        );
    }

    public boolean isOwnerConnected(String ownerContextId) {
        return resolveSellerUser(ownerContextId)
                .map(UserEntity::hasMercadoPagoConnected)
                .orElse(false);
    }

    public String requireOwnerAccessToken(String ownerContextId) {
        UserEntity owner = resolveSellerUser(ownerContextId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Dueño no encontrado."));
        if (!owner.hasMercadoPagoConnected()) {
            throw new ResponseStatusException(HttpStatus.PRECONDITION_FAILED,
                    "El arrendador aún no ha vinculado su cuenta de Mercado Pago.");
        }
        return owner.getMpAccessToken().trim();
    }

    /**
     * Dueño vendedor de la organización: id de factura.ownerId o usuario OWNER con ese owner_id.
     */
    Optional<UserEntity> resolveSellerUser(String ownerContextId) {
        if (ownerContextId == null || ownerContextId.isBlank()) {
            return Optional.empty();
        }
        Optional<UserEntity> byId = userRepository.findById(ownerContextId);
        if (byId.isPresent() && (byId.get().getRole() == Role.OWNER || byId.get().getRole() == Role.SUPER_ADMIN)) {
            return byId;
        }
        Optional<UserEntity> ownerMember = userRepository.findByOwnerId(ownerContextId).stream()
                .filter(u -> u.getRole() == Role.OWNER)
                .findFirst();
        if (ownerMember.isPresent()) {
            return ownerMember;
        }
        return byId;
    }

    /**
     * URL de autorización: el dueño inicia sesión en Mercado Pago (como «Continuar con Google»).
     */
    public String buildAuthorizationUrl(UserEntity owner) {
        if (!oauthApi.isOAuthConfigured(clientId, clientSecret)) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Falta configurar Mercado Pago en el servidor (Client ID y Client Secret).");
        }
        if (oauthRedirectUri == null || oauthRedirectUri.isBlank() || oauthRedirectUri.contains("localhost")) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Mercado Pago requiere una URL pública HTTPS. En local, ejecuta "
                            + "./scripts/start-ngrok-tunnel.sh y arranca el backend con ./scripts/run-backend-with-ngrok.sh");
        }
        String state = signState(owner.getId());
        return oauthApi.buildAuthorizationUrl(clientId.trim(), oauthRedirectUri.trim(), state);
    }

    @Transactional
    public String handleOAuthCallback(String code, String state) {
        String base = appUrl.replaceAll("/$", "") + "/dashboard?tab=PROFILE";
        if (code == null || code.isBlank()) {
            return base + "&mpOwner=cancelled";
        }
        try {
            String ownerId = verifyState(state);
            UserEntity owner = userRepository.findById(ownerId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Dueño inválido."));
            if (owner.getRole() != Role.OWNER) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Solo dueños pueden vincular Mercado Pago.");
            }

            MercadoPagoOAuthApi.OAuthTokenResponse tokens = oauthApi.exchangeAuthorizationCode(
                    clientId.trim(), clientSecret.trim(), code.trim(), oauthRedirectUri.trim(), oauthUseTestToken);
            if (tokens.accessToken() == null || tokens.accessToken().isBlank()) {
                throw new IllegalStateException("Sin access_token");
            }
            persistCredentials(owner, tokens.accessToken(), tokens.refreshToken(), tokens.expiresIn());
            owner = userRepository.saveAndFlush(owner);
            if (!owner.hasMercadoPagoConnected()) {
                logger.error("[MercadoPago] OAuth guardó usuario pero sin token en BD ownerId={}", owner.getId());
                return base + "&mpOwner=error";
            }
            logger.info("[MercadoPago] OAuth OK ownerId={} mpUserId={} testToken={}",
                    owner.getId(), owner.getMpUserId(), oauthUseTestToken);
            return base + "&mpOwner=connected";
        } catch (ResponseStatusException e) {
            logger.warn("[MercadoPago] OAuth callback rejected: {}", e.getReason());
            return base + "&mpOwner=error";
        } catch (RestClientResponseException e) {
            logger.error("[MercadoPago] OAuth token exchange HTTP {}: {}", e.getStatusCode(), e.getResponseBodyAsString());
            return base + "&mpOwner=error";
        } catch (Exception e) {
            logger.error("[MercadoPago] OAuth callback failed: {}", e.getMessage());
            return base + "&mpOwner=error";
        }
    }

    @Transactional
    public Map<String, Object> linkWithAccessToken(UserEntity owner, String accessToken) {
        if (!allowManualTokenLink) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Vinculación manual deshabilitada. Usa «Continuar con Mercado Pago».");
        }
        if (accessToken == null || accessToken.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Access Token requerido.");
        }
        String token = accessToken.trim();
        validateTokenAndPersist(owner, token, null, null);
        userRepository.save(owner);
        logger.info("[MercadoPago] Manual link ownerId={} mpUserId={}", owner.getId(), owner.getMpUserId());
        return getConnectionStatus(owner);
    }

    @Transactional
    public void disconnect(UserEntity owner) {
        owner.setMpUserId(null);
        owner.setMpAccessToken(null);
        owner.setMpRefreshToken(null);
        owner.setMpTokenExpiresAt(null);
        owner.setMpConnectedAt(null);
        userRepository.save(owner);
        logger.info("[MercadoPago] Disconnected ownerId={}", owner.getId());
    }

    public UserEntity resolveOwnerFromSecurity() {
        String username = SecurityContextHolder.getContext().getAuthentication().getName();
        UserEntity u = userRepository.findByLoginIdentifier(username)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sesión inválida."));
        if (u.getRole() != Role.OWNER) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Solo dueños pueden gestionar Mercado Pago.");
        }
        return u;
    }

    private void validateTokenAndPersist(UserEntity owner, String accessToken, String refreshToken, Long expiresIn) {
        rejectIfPlatformIntegratorToken(accessToken);
        boolean validated = false;
        try {
            MPRequestOptions opts = MPRequestOptions.builder().accessToken(accessToken).build();
            User mpUser = new UserClient().get(opts);
            if (mpUser.getId() == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "No se pudo validar la cuenta de Mercado Pago.");
            }
            owner.setMpUserId(String.valueOf(mpUser.getId()));
            validated = true;
        } catch (MPApiException e) {
            logger.warn("[MercadoPago] Token validation MP API: {} — {}", e.getStatusCode(), e.getMessage());
            if (!oauthSkipTokenValidation || !accessToken.startsWith("APP_USR-")) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "No pudimos verificar tu cuenta de Mercado Pago. Intenta vincular de nuevo.");
            }
            logger.warn("[MercadoPago] Guardando token OAuth sin validación API (solo perfil local)");
        } catch (MPException e) {
            if (!oauthSkipTokenValidation || !accessToken.startsWith("APP_USR-")) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Mercado Pago no respondió. Intenta más tarde.");
            }
            logger.warn("[MercadoPago] Guardando token OAuth sin validación API (red/MP caído, local)");
        }
        if (!validated && owner.getMpUserId() == null) {
            owner.setMpUserId("pending-validation");
        }
        owner.setMpAccessToken(accessToken);
        owner.setMpRefreshToken(refreshToken);
        if (expiresIn != null && expiresIn > 0) {
            owner.setMpTokenExpiresAt(LocalDateTime.ofInstant(
                    Instant.now().plusSeconds(expiresIn), ZoneOffset.UTC));
        } else {
            owner.setMpTokenExpiresAt(null);
        }
        owner.setMpConnectedAt(LocalDateTime.now());
    }

    private void persistCredentials(UserEntity owner, String accessToken, String refreshToken, Long expiresIn) {
        validateTokenAndPersist(owner, accessToken, refreshToken, expiresIn);
    }

    /** El dueño debe vincular su propia cuenta MP (OAuth), no las credenciales de la app ADMINDI. */
    private void rejectIfPlatformIntegratorToken(String accessToken) {
        if (integratorAccessToken == null || integratorAccessToken.isBlank()) {
            return;
        }
        if (accessToken != null && accessToken.trim().equals(integratorAccessToken.trim())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Debes iniciar sesión con tu cuenta de Mercado Pago (Continuar con Mercado Pago). "
                            + "No uses el Access Token de la aplicación ADMINDI.");
        }
    }

    private String signState(String ownerId) {
        long ts = System.currentTimeMillis();
        String payload = ownerId + "|" + ts;
        String sig = hmacSha256(resolveStateKey(), payload);
        String raw = payload + "|" + sig;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    private String verifyState(String state) {
        if (state == null || state.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "State OAuth inválido.");
        }
        try {
            String raw = new String(Base64.getUrlDecoder().decode(state), StandardCharsets.UTF_8);
            String[] parts = raw.split("\\|");
            if (parts.length != 3) {
                throw new IllegalArgumentException("format");
            }
            String ownerId = parts[0];
            long ts = Long.parseLong(parts[1]);
            String sig = parts[2];
            String expected = hmacSha256(resolveStateKey(), ownerId + "|" + ts);
            if (!java.security.MessageDigest.isEqual(
                    expected.getBytes(StandardCharsets.UTF_8), sig.getBytes(StandardCharsets.UTF_8))) {
                throw new IllegalArgumentException("sig");
            }
            if (System.currentTimeMillis() - ts > STATE_TTL_MS) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "La sesión expiró. Intenta de nuevo.");
            }
            return ownerId;
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "State OAuth inválido.");
        }
    }

    private String resolveStateKey() {
        if (stateSigningKey != null && !stateSigningKey.isBlank()) {
            return stateSigningKey;
        }
        return "admindi-mp-oauth-state-dev-only";
    }

    private static String hmacSha256(String secret, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] raw = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(raw.length * 2);
            for (byte b : raw) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
