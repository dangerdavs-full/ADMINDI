package com.admindi.backend.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.Map;

/**
 * Valida la firma {@code x-signature} de notificaciones Mercado Pago (Webhooks v1).
 * Si {@code mercadopago.webhook-secret} está vacío, no valida firma (solo dev);
 * en producción debe configurarse {@code MP_WEBHOOK_SECRET}.
 */
@Component
public class MercadoPagoWebhookValidator {

    @Value("${mercadopago.webhook-secret:}")
    private String webhookSecret;

    public boolean isSignatureValidationEnabled() {
        return webhookSecret != null && !webhookSecret.isBlank();
    }

    /**
     * @param dataId      ID del recurso (p. ej. payment id) del body {@code data.id}
     * @param xSignature  header x-signature (ts=...,v1=...)
     * @param xRequestId  header x-request-id
     */
    public boolean isValid(String dataId, String xSignature, String xRequestId) {
        if (!isSignatureValidationEnabled()) {
            return true;
        }
        if (xSignature == null || xSignature.isBlank() || dataId == null || dataId.isBlank()) {
            return false;
        }
        String ts = null;
        String v1 = null;
        for (String part : xSignature.split(",")) {
            String[] kv = part.trim().split("=", 2);
            if (kv.length == 2) {
                if ("ts".equalsIgnoreCase(kv[0].trim())) {
                    ts = kv[1].trim();
                } else if ("v1".equalsIgnoreCase(kv[0].trim())) {
                    v1 = kv[1].trim();
                }
            }
        }
        if (ts == null || v1 == null) {
            return false;
        }
        String requestId = xRequestId != null ? xRequestId : "";
        String manifest = "id:" + dataId + ";request-id:" + requestId + ";ts:" + ts + ";";
        String expected = hmacSha256Hex(webhookSecret, manifest);
        return MessageDigest.isEqual(
                expected.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8),
                v1.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8));
    }

    /** Extrae {@code data.id} de un body JSON plano (notificación payment). */
    @SuppressWarnings("unchecked")
    public String extractDataId(Map<String, Object> payload) {
        if (payload == null) {
            return null;
        }
        Object data = payload.get("data");
        if (data instanceof Map<?, ?> dataMap) {
            Object id = dataMap.get("id");
            if (id != null) {
                return id.toString();
            }
        }
        Object id = payload.get("id");
        return id != null ? id.toString() : null;
    }

    private static String hmacSha256Hex(String secret, String manifest) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] raw = mac.doFinal(manifest.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(raw.length * 2);
            for (byte b : raw) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("HMAC error", e);
        }
    }
}
