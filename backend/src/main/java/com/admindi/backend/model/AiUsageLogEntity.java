package com.admindi.backend.model;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Traza de cada llamada a Anthropic Claude.
 *
 * Usos:
 *  - Enforce del presupuesto diario por usuario ({@code anthropic.daily-budget-usd-per-user}).
 *    {@code ClaudeService} suma {@code cost_usd} de las últimas 24h del user antes
 *    de hacer una nueva llamada.
 *  - Auditoría agregada por organización (panel superadmin).
 *  - Detección de abuso (un user con consumo anómalo).
 */
@Entity
@Table(name = "ai_usage_log")
public class AiUsageLogEntity {

    @Id
    private String id;

    @Column(name = "user_id")
    private String userId;

    @Column(name = "owner_id")
    private String ownerId;

    @Column(nullable = false)
    private String model;

    /**
     * Flujo de negocio: OCR_RECEIPT, BOT_CHAT, BANXICO_ADAPTIVE,
     * ACCOUNTING_CATEGORIZE, REPORT_NARRATIVE, etc.
     */
    @Column(nullable = false)
    private String purpose;

    @Column(name = "input_tokens", nullable = false)
    private int inputTokens;

    @Column(name = "output_tokens", nullable = false)
    private int outputTokens;

    @Column(name = "cost_usd", nullable = false, precision = 10, scale = 6)
    private BigDecimal costUsd = BigDecimal.ZERO;

    @Column(name = "http_status")
    private Integer httpStatus;

    @Column(nullable = false)
    private boolean success = true;

    @Column(name = "error_message", length = 1024)
    private String errorMessage;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    public AiUsageLogEntity() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public String getOwnerId() { return ownerId; }
    public void setOwnerId(String ownerId) { this.ownerId = ownerId; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public String getPurpose() { return purpose; }
    public void setPurpose(String purpose) { this.purpose = purpose; }
    public int getInputTokens() { return inputTokens; }
    public void setInputTokens(int inputTokens) { this.inputTokens = inputTokens; }
    public int getOutputTokens() { return outputTokens; }
    public void setOutputTokens(int outputTokens) { this.outputTokens = outputTokens; }
    public BigDecimal getCostUsd() { return costUsd; }
    public void setCostUsd(BigDecimal costUsd) { this.costUsd = costUsd; }
    public Integer getHttpStatus() { return httpStatus; }
    public void setHttpStatus(Integer httpStatus) { this.httpStatus = httpStatus; }
    public boolean isSuccess() { return success; }
    public void setSuccess(boolean success) { this.success = success; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
