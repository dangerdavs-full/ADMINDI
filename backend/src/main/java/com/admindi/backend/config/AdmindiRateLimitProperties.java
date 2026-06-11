package com.admindi.backend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Limites HTTP anti fuerza bruta / abuso. Login parametrizado por entorno (prod mas estricto).
 */
@ConfigurationProperties(prefix = "admindi.rate-limit")
public class AdmindiRateLimitProperties {

    /** Maximo POST /auth/login por ventana (por IP). */
    private int loginMaxRequests = 10;

    /** Ventana login en minutos. */
    private int loginWindowMinutes = 15;

    /** Max peticiones por ruta para select/switch/refresh (misma ventana 1 min que el resto de API no-login). */
    private int authContextRefreshMaxRequests = 10;

    /** Ventana en ms para rutas que no son login (bucket por IP+ruta completa). */
    private long defaultWindowMs = 60_000L;

    private int defaultMaxRequests = 200;

    private int webhookMaxRequests = 100;

    private int postMutateMaxRequests = 60;

    private int deleteMaxRequests = 10;

    private int paymentPostMaxRequests = 30;

    public int getLoginMaxRequests() {
        return loginMaxRequests;
    }

    public void setLoginMaxRequests(int loginMaxRequests) {
        this.loginMaxRequests = loginMaxRequests;
    }

    public int getLoginWindowMinutes() {
        return loginWindowMinutes;
    }

    public void setLoginWindowMinutes(int loginWindowMinutes) {
        this.loginWindowMinutes = loginWindowMinutes;
    }

    public int getAuthContextRefreshMaxRequests() {
        return authContextRefreshMaxRequests;
    }

    public void setAuthContextRefreshMaxRequests(int authContextRefreshMaxRequests) {
        this.authContextRefreshMaxRequests = authContextRefreshMaxRequests;
    }

    public long getDefaultWindowMs() {
        return defaultWindowMs;
    }

    public void setDefaultWindowMs(long defaultWindowMs) {
        this.defaultWindowMs = defaultWindowMs;
    }

    public int getDefaultMaxRequests() {
        return defaultMaxRequests;
    }

    public void setDefaultMaxRequests(int defaultMaxRequests) {
        this.defaultMaxRequests = defaultMaxRequests;
    }

    public int getWebhookMaxRequests() {
        return webhookMaxRequests;
    }

    public void setWebhookMaxRequests(int webhookMaxRequests) {
        this.webhookMaxRequests = webhookMaxRequests;
    }

    public int getPostMutateMaxRequests() {
        return postMutateMaxRequests;
    }

    public void setPostMutateMaxRequests(int postMutateMaxRequests) {
        this.postMutateMaxRequests = postMutateMaxRequests;
    }

    public int getDeleteMaxRequests() {
        return deleteMaxRequests;
    }

    public void setDeleteMaxRequests(int deleteMaxRequests) {
        this.deleteMaxRequests = deleteMaxRequests;
    }

    public int getPaymentPostMaxRequests() {
        return paymentPostMaxRequests;
    }

    public void setPaymentPostMaxRequests(int paymentPostMaxRequests) {
        this.paymentPostMaxRequests = paymentPostMaxRequests;
    }
}
