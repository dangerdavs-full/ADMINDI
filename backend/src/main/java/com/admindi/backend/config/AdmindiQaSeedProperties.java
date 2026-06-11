package com.admindi.backend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "admindi.qa-seed")
public class AdmindiQaSeedProperties {

    /**
     * Si true, al arranque se ejecuta semilla idempotente Etapa 0 (usuarios QA).
     * Desactivar en prod ({@code application-prod.yml}).
     */
    private boolean enabled = false;

    /**
     * Si true, un email QA existente debe tener exactamente el UUID fijo de Etapa 0 (falla si no).
     * Si false (tipico local/qa con BD ya poblada), se adopta el id existente y solo se valida rol.
     */
    private boolean strictUserIds = true;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isStrictUserIds() {
        return strictUserIds;
    }

    public void setStrictUserIds(boolean strictUserIds) {
        this.strictUserIds = strictUserIds;
    }
}
