package com.admindi.backend.ai;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.math.BigDecimal;

/**
 * Configuración del cliente Anthropic Claude.
 *
 * Mapea el bloque {@code anthropic:} de {@code application.yml}. Todas las
 * propiedades tienen default seguro: si {@code enabled=false} o falta
 * {@code api-key}, {@link ClaudeService} responde {@code ClaudeResponse.disabled()}
 * y cada consumidor debe manejar el fallback documentado (no romper flujos).
 */
@Configuration
@ConfigurationProperties(prefix = "anthropic")
public class AnthropicProperties {

    private boolean enabled = false;
    private String apiKey = "";
    private String apiUrl = "https://api.anthropic.com/v1/messages";
    private String apiVersion = "2023-06-01";
    private String model = "claude-sonnet-4-5";
    private String visionModel = "claude-sonnet-4-5";
    private int timeoutMs = 30000;
    private int maxTokens = 2048;
    private BigDecimal dailyBudgetUsdPerUser = new BigDecimal("2.00");
    private BigDecimal inputCostPerMtok = new BigDecimal("3.00");
    private BigDecimal outputCostPerMtok = new BigDecimal("15.00");

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }
    public String getApiUrl() { return apiUrl; }
    public void setApiUrl(String apiUrl) { this.apiUrl = apiUrl; }
    public String getApiVersion() { return apiVersion; }
    public void setApiVersion(String apiVersion) { this.apiVersion = apiVersion; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public String getVisionModel() { return visionModel; }
    public void setVisionModel(String visionModel) { this.visionModel = visionModel; }
    public int getTimeoutMs() { return timeoutMs; }
    public void setTimeoutMs(int timeoutMs) { this.timeoutMs = timeoutMs; }
    public int getMaxTokens() { return maxTokens; }
    public void setMaxTokens(int maxTokens) { this.maxTokens = maxTokens; }
    public BigDecimal getDailyBudgetUsdPerUser() { return dailyBudgetUsdPerUser; }
    public void setDailyBudgetUsdPerUser(BigDecimal dailyBudgetUsdPerUser) {
        this.dailyBudgetUsdPerUser = dailyBudgetUsdPerUser;
    }
    public BigDecimal getInputCostPerMtok() { return inputCostPerMtok; }
    public void setInputCostPerMtok(BigDecimal inputCostPerMtok) {
        this.inputCostPerMtok = inputCostPerMtok;
    }
    public BigDecimal getOutputCostPerMtok() { return outputCostPerMtok; }
    public void setOutputCostPerMtok(BigDecimal outputCostPerMtok) {
        this.outputCostPerMtok = outputCostPerMtok;
    }

    /**
     * {@code true} si el servicio está habilitado Y hay api-key.
     * Los consumidores llaman a esto antes de invocar el modelo para caer al
     * fallback si la config no está completa.
     */
    public boolean isOperational() {
        return enabled && apiKey != null && !apiKey.isBlank();
    }
}
