package com.admindi.backend.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * OAuth Mercado Pago (authorization code) sin depender del flujo incompleto del SDK.
 */
@Component
public class MercadoPagoOAuthApi {

    private static final Logger log = LoggerFactory.getLogger(MercadoPagoOAuthApi.class);
    private static final String AUTH_BASE_MX = "https://auth.mercadopago.com.mx/authorization";
    private static final String TOKEN_URL = "https://api.mercadopago.com/oauth/token";

    private final RestClient restClient = RestClient.create();

    public boolean isOAuthConfigured(String clientId, String clientSecret) {
        return clientId != null && !clientId.isBlank()
                && clientSecret != null && !clientSecret.isBlank();
    }

    /**
     * Comprueba con MP que client_id y client_secret forman un par válido (sin código OAuth).
     */
    public boolean validateClientCredentials(String clientId, String clientSecret) {
        if (!isOAuthConfigured(clientId, clientSecret)) {
            return false;
        }
        StringBuilder form = new StringBuilder();
        appendFormParam(form, "grant_type", "client_credentials");
        appendFormParam(form, "client_id", clientId.trim());
        appendFormParam(form, "client_secret", clientSecret.trim());
        try {
            restClient.post()
                    .uri(TOKEN_URL)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form.toString())
                    .retrieve()
                    .toBodilessEntity();
            return true;
        } catch (RestClientResponseException e) {
            String body = e.getResponseBodyAsString();
            if (body != null && body.contains("invalid_client")) {
                return false;
            }
            log.warn("[MercadoPago] Validación credenciales HTTP {}: {}", e.getStatusCode(), body);
            return false;
        }
    }

    public String buildAuthorizationUrl(String clientId, String redirectUri, String state) {
        return AUTH_BASE_MX
                + "?client_id=" + encode(clientId)
                + "&response_type=code"
                + "&platform_id=mp"
                + "&state=" + encode(state)
                + "&redirect_uri=" + encode(redirectUri);
    }

    @SuppressWarnings("unchecked")
    public OAuthTokenResponse exchangeAuthorizationCode(
            String clientId, String clientSecret, String code, String redirectUri, boolean useTestToken) {
        // MP exige form-urlencoded en el body (JSON provoca invalid_client).
        StringBuilder form = new StringBuilder();
        appendFormParam(form, "grant_type", "authorization_code");
        appendFormParam(form, "client_id", clientId);
        appendFormParam(form, "client_secret", clientSecret);
        appendFormParam(form, "code", code);
        appendFormParam(form, "redirect_uri", redirectUri);
        if (useTestToken) {
            appendFormParam(form, "test_token", "true");
        }

        try {
            Map<String, Object> json = restClient.post()
                    .uri(TOKEN_URL)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form.toString())
                    .retrieve()
                    .body(Map.class);
            if (json == null) {
                throw new IllegalStateException("Respuesta vacía de Mercado Pago");
            }
            return new OAuthTokenResponse(
                    stringVal(json.get("access_token")),
                    stringVal(json.get("refresh_token")),
                    longVal(json.get("expires_in")));
        } catch (RestClientResponseException e) {
            log.warn("[MercadoPago] OAuth token HTTP {}: {}", e.getStatusCode(), e.getResponseBodyAsString());
            throw e;
        }
    }

    private static void appendFormParam(StringBuilder form, String name, String value) {
        if (form.length() > 0) {
            form.append('&');
        }
        form.append(encode(name)).append('=').append(encode(value));
    }

    private static String encode(String v) {
        return URLEncoder.encode(v, StandardCharsets.UTF_8);
    }

    private static String stringVal(Object o) {
        return o == null ? null : o.toString();
    }

    private static Long longVal(Object o) {
        if (o == null) return null;
        if (o instanceof Number n) return n.longValue();
        return Long.parseLong(o.toString());
    }

    public record OAuthTokenResponse(String accessToken, String refreshToken, Long expiresIn) {}
}
