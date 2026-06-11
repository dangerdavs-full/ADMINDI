package com.admindi.backend.ai;

import com.admindi.backend.model.AiUsageLogEntity;
import com.admindi.backend.repository.AiUsageLogRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;

/**
 * Cliente HTTP para Anthropic Claude (text + vision).
 *
 * Diseño: en vez de traer un SDK beta, usamos {@link java.net.http.HttpClient}
 * nativo para controlar timeouts, reintentos y presupuesto desde nuestra capa.
 * La API de Anthropic es simple (POST JSON a /v1/messages) y este servicio la
 * envuelve con cuatro garantías:
 *
 *  1. <b>Disabled fallback</b>: si {@code anthropic.enabled=false} o falta
 *     {@code api-key}, devuelve {@link ClaudeResponse#disabled()} sin tocar la
 *     red. Los consumidores tienen fallback documentado.
 *  2. <b>Budget enforcement</b>: antes de cada llamada verifica el gasto del
 *     user en las últimas 24h contra {@code daily-budget-usd-per-user}. Si se
 *     excede, responde {@link ClaudeResponse#budgetExceeded()} sin llamar.
 *  3. <b>Usage logging</b>: cada invocación (éxito o fallo) registra tokens +
 *     costo en {@code ai_usage_log} para trazabilidad y abuso.
 *  4. <b>JSON schema enforcement</b> (opcional): si el caller pide respuesta
 *     estructurada, el prompt instruye a Claude a devolver JSON puro y se
 *     valida/parsea antes de devolver.
 */
@Service
public class ClaudeService {

    private static final Logger logger = LoggerFactory.getLogger(ClaudeService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final AnthropicProperties props;
    private final AiUsageLogRepository usageRepo;
    private final HttpClient httpClient;

    public ClaudeService(AnthropicProperties props, AiUsageLogRepository usageRepo) {
        this.props = props;
        this.usageRepo = usageRepo;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    public record ClaudeResponse(
            boolean ok,
            String text,
            Map<String, Object> structured,
            int inputTokens,
            int outputTokens,
            String errorMessage) {

        public static ClaudeResponse disabled() {
            return new ClaudeResponse(false, "", Map.of(), 0, 0, "anthropic_disabled");
        }

        public static ClaudeResponse budgetExceeded() {
            return new ClaudeResponse(false, "", Map.of(), 0, 0, "daily_budget_exceeded");
        }

        public static ClaudeResponse error(String msg) {
            return new ClaudeResponse(false, "", Map.of(), 0, 0, msg);
        }
    }

    /**
     * Llamada de texto simple.
     *
     * @param systemPrompt    instrucciones de sistema (persona, reglas, formato esperado)
     * @param userMessage     mensaje del usuario — ya debería venir sanitizado
     *                        por {@link PromptGuardrails#sanitize(String)}
     * @param expectJson      si true, instruye a Claude a devolver JSON puro y
     *                        se parsea a {@code structured}
     * @param userId          user que origina la llamada (para budget + audit)
     * @param ownerId         organización (puede ser null)
     * @param purpose         identificador del flujo de negocio (BOT_CHAT, OCR_RECEIPT, etc.)
     */
    public ClaudeResponse chat(String systemPrompt,
                                String userMessage,
                                boolean expectJson,
                                String userId,
                                String ownerId,
                                String purpose) {
        if (!props.isOperational()) return ClaudeResponse.disabled();
        if (exceedsDailyBudget(userId)) return ClaudeResponse.budgetExceeded();

        String fullSystem = systemPrompt == null ? "" : systemPrompt;
        if (expectJson) {
            fullSystem += "\n\nIMPORTANT: Respond ONLY with a single JSON object. " +
                    "Do NOT include markdown fences, explanations, or prose. " +
                    "The first character of your response must be '{' and the last must be '}'.";
        }

        Map<String, Object> requestBody = new LinkedHashMap<>();
        requestBody.put("model", props.getModel());
        requestBody.put("max_tokens", props.getMaxTokens());
        requestBody.put("system", fullSystem);
        requestBody.put("messages", List.of(Map.of(
                "role", "user",
                "content", userMessage == null ? "" : userMessage
        )));

        return executeCall(requestBody, expectJson, userId, ownerId, purpose);
    }

    /**
     * Llamada con imagen. {@code imageBytes} se transmite en base64 inline.
     *
     * @param mimeType  "image/jpeg", "image/png", "image/webp" o "image/gif" (soportados por Claude)
     */
    public ClaudeResponse analyzeImage(byte[] imageBytes,
                                        String mimeType,
                                        String systemPrompt,
                                        String instruction,
                                        boolean expectJson,
                                        String userId,
                                        String ownerId,
                                        String purpose) {
        if (!props.isOperational()) return ClaudeResponse.disabled();
        if (exceedsDailyBudget(userId)) return ClaudeResponse.budgetExceeded();
        if (imageBytes == null || imageBytes.length == 0) {
            return ClaudeResponse.error("empty_image");
        }

        String b64 = Base64.getEncoder().encodeToString(imageBytes);
        String fullSystem = systemPrompt == null ? "" : systemPrompt;
        if (expectJson) {
            fullSystem += "\n\nIMPORTANT: Respond ONLY with a single JSON object. " +
                    "Do NOT include markdown fences, explanations, or prose.";
        }

        Map<String, Object> imgBlock = new LinkedHashMap<>();
        imgBlock.put("type", "image");
        imgBlock.put("source", Map.of(
                "type", "base64",
                "media_type", mimeType == null ? "image/jpeg" : mimeType,
                "data", b64
        ));

        Map<String, Object> textBlock = Map.of(
                "type", "text",
                "text", instruction == null ? "" : instruction
        );

        Map<String, Object> requestBody = new LinkedHashMap<>();
        requestBody.put("model", props.getVisionModel());
        requestBody.put("max_tokens", props.getMaxTokens());
        requestBody.put("system", fullSystem);
        requestBody.put("messages", List.of(Map.of(
                "role", "user",
                "content", List.of(imgBlock, textBlock)
        )));

        return executeCall(requestBody, expectJson, userId, ownerId, purpose);
    }

    // ─── Internals ──────────────────────────────────────────────────────────

    private ClaudeResponse executeCall(Map<String, Object> body,
                                        boolean expectJson,
                                        String userId,
                                        String ownerId,
                                        String purpose) {
        String logId = UUID.randomUUID().toString();
        AiUsageLogEntity log = new AiUsageLogEntity();
        log.setId(logId);
        log.setUserId(userId);
        log.setOwnerId(ownerId);
        log.setPurpose(purpose == null ? "UNKNOWN" : purpose);
        log.setModel(String.valueOf(body.getOrDefault("model", props.getModel())));

        try {
            String bodyJson = MAPPER.writeValueAsString(body);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(props.getApiUrl()))
                    .timeout(Duration.ofMillis(props.getTimeoutMs()))
                    .header("Content-Type", "application/json")
                    .header("x-api-key", props.getApiKey())
                    .header("anthropic-version", props.getApiVersion())
                    .POST(HttpRequest.BodyPublishers.ofString(bodyJson, StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            log.setHttpStatus(response.statusCode());

            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                log.setSuccess(false);
                log.setErrorMessage("http_" + response.statusCode() + ": " +
                        truncate(response.body(), 500));
                usageRepo.save(log);
                logger.warn("[CLAUDE] HTTP {} on purpose={}: {}",
                        response.statusCode(), purpose, truncate(response.body(), 200));
                return ClaudeResponse.error("http_" + response.statusCode());
            }

            Map<String, Object> parsed = MAPPER.readValue(response.body(),
                    new TypeReference<Map<String, Object>>() {});

            // Usage tokens
            Map<String, Object> usage = castMap(parsed.get("usage"));
            int inTokens = asInt(usage.get("input_tokens"));
            int outTokens = asInt(usage.get("output_tokens"));
            log.setInputTokens(inTokens);
            log.setOutputTokens(outTokens);
            log.setCostUsd(computeCost(inTokens, outTokens));

            // Content: array of blocks; primer bloque tipo "text" es la respuesta
            String text = extractText(parsed);

            Map<String, Object> structured = Map.of();
            if (expectJson) {
                try {
                    structured = MAPPER.readValue(stripJsonFences(text),
                            new TypeReference<Map<String, Object>>() {});
                } catch (Exception parseEx) {
                    log.setSuccess(false);
                    log.setErrorMessage("json_parse_error: " + parseEx.getMessage());
                    usageRepo.save(log);
                    logger.warn("[CLAUDE] JSON parse failed for purpose={}: {}",
                            purpose, truncate(text, 200));
                    return ClaudeResponse.error("invalid_json_response");
                }
            }

            log.setSuccess(true);
            usageRepo.save(log);

            return new ClaudeResponse(true, text, structured, inTokens, outTokens, null);

        } catch (Exception ex) {
            log.setSuccess(false);
            log.setErrorMessage(ex.getClass().getSimpleName() + ": " +
                    truncate(ex.getMessage(), 500));
            try {
                usageRepo.save(log);
            } catch (Exception ignore) { /* best-effort */ }
            logger.warn("[CLAUDE] Call failed for purpose={}: {}", purpose, ex.getMessage());
            return ClaudeResponse.error(ex.getClass().getSimpleName());
        }
    }

    private BigDecimal computeCost(int inputTokens, int outputTokens) {
        BigDecimal inCost = props.getInputCostPerMtok()
                .multiply(BigDecimal.valueOf(inputTokens))
                .divide(BigDecimal.valueOf(1_000_000L), 6, RoundingMode.HALF_UP);
        BigDecimal outCost = props.getOutputCostPerMtok()
                .multiply(BigDecimal.valueOf(outputTokens))
                .divide(BigDecimal.valueOf(1_000_000L), 6, RoundingMode.HALF_UP);
        return inCost.add(outCost);
    }

    /**
     * Verifica si el user excedió el presupuesto diario. Si {@code userId} es
     * null (llamadas de sistema), no se aplica presupuesto por user pero SÍ se
     * registran en el log (con user_id NULL) para seguimiento agregado.
     */
    private boolean exceedsDailyBudget(String userId) {
        if (userId == null) return false;
        try {
            BigDecimal budget = props.getDailyBudgetUsdPerUser();
            if (budget == null || budget.signum() <= 0) return false;
            BigDecimal spent = usageRepo.sumCostByUserSince(userId,
                    LocalDateTime.now().minusHours(24));
            if (spent == null) spent = BigDecimal.ZERO;
            return spent.compareTo(budget) >= 0;
        } catch (Exception ex) {
            logger.warn("[CLAUDE] Budget check failed, allowing call: {}", ex.getMessage());
            return false;
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> castMap(Object o) {
        if (o instanceof Map) return (Map<String, Object>) o;
        return Map.of();
    }

    private int asInt(Object o) {
        if (o == null) return 0;
        if (o instanceof Number n) return n.intValue();
        try { return Integer.parseInt(o.toString()); }
        catch (Exception ex) { return 0; }
    }

    @SuppressWarnings("unchecked")
    private String extractText(Map<String, Object> parsed) {
        Object contentObj = parsed.get("content");
        if (!(contentObj instanceof List)) return "";
        List<Object> content = (List<Object>) contentObj;
        for (Object block : content) {
            if (block instanceof Map blockMap) {
                if ("text".equals(blockMap.get("type"))) {
                    Object t = blockMap.get("text");
                    if (t != null) return t.toString();
                }
            }
        }
        return "";
    }

    private String stripJsonFences(String s) {
        if (s == null) return "{}";
        String t = s.trim();
        if (t.startsWith("```")) {
            int firstNl = t.indexOf('\n');
            if (firstNl > 0) t = t.substring(firstNl + 1);
            if (t.endsWith("```")) t = t.substring(0, t.length() - 3);
        }
        return t.trim();
    }

    private String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max);
    }
}
