package com.admindi.backend.ai;

import com.admindi.backend.whatsapp.ReceiptOcrPort;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * Extracción OCR de comprobantes SPEI con Claude Vision.
 *
 * Contrato: implementa {@link ReceiptOcrPort} para que el chatbot WhatsApp lo
 * use sin acoplarse a Anthropic. Si {@link ClaudeService} está deshabilitado
 * o excede presupuesto, devuelve un {@link ReceiptOcrPort.ExtractedReceipt}
 * en estado fallido — el caller (bot o controller) decide el fallback.
 *
 * Prompt engineering:
 *  - System prompt acota el dominio a comprobantes SPEI mexicanos y fuerza
 *    JSON estricto con los 7 campos esperados.
 *  - Instruction incluye ejemplos de forma para claveRastreo (suele ser
 *    alfanumérico de ~20-30 chars) y formato fecha ISO.
 *  - El response JSON se valida con jackson antes de usarse; si no parsea,
 *    devolvemos "failed" con mensaje claro.
 *
 * Seguridad:
 *  - No exponemos la key de API en ningún log.
 *  - Guardrails: Claude no recibe texto de usuario; sólo imagen + instruction
 *    fija. El único input no confiable es la imagen, que fue pre-validada por
 *    {@code WhatsAppMediaDownloader} (MIME + tamaño).
 *  - Budget: {@code ClaudeService} aplica límite diario por user; aquí no
 *    duplicamos la verificación.
 */
@Service("receiptOcrPort")
public class ReceiptOcrService implements ReceiptOcrPort {

    private static final Logger logger = LoggerFactory.getLogger(ReceiptOcrService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String SYSTEM_PROMPT = """
            Eres un extractor de datos de comprobantes SPEI mexicanos (Sistema de Pagos \
            Electrónicos Interbancarios).
            
            Reglas ESTRICTAS:
            1) Devuelve SOLO JSON válido, sin markdown ni explicación.
            2) Si un campo no es legible en la imagen, usa null (JSON null), NUNCA inventes.
            3) 'amount' en MXN como número (sin símbolos ni separadores de miles). Usa punto decimal.
            4) 'transferDate' en formato ISO yyyy-MM-dd. Si solo hay fecha sin año, usa el año actual.
            5) 'claveRastreo' es un identificador alfanumérico único (20-40 chars) que el banco \
               pone en el comprobante. Puede llamarse "Clave de rastreo", "CLAVE DE RASTREO" o \
               "Folio SPEI". Nunca es el número de referencia interno del banco.
            6) 'bankEmitter' es el banco que ENVÍA el dinero (ej "BBVA", "Santander", "Banorte").
            7) 'accountReceiver' es CLABE (18 dígitos) o cuenta del beneficiario.
            8) 'beneficiaryName' es el nombre del dueño de la cuenta destino (como aparece).
            9) 'rfcBeneficiary' es el RFC mexicano si aparece (13 chars persona física, 12 moral).
            10) 'confidence' es un número 0.0-1.0 indicando qué tan seguro estás. Usa 0.3 si hay \
                campos dudosos, 0.7+ si todo es claro.
            
            Schema de respuesta:
            {
              "ok": true|false,
              "claveRastreo": string|null,
              "amount": number|null,
              "transferDate": string|null,
              "bankEmitter": string|null,
              "accountReceiver": string|null,
              "beneficiaryName": string|null,
              "rfcBeneficiary": string|null,
              "confidence": number,
              "errorMessage": string|null
            }
            
            Si la imagen NO es un comprobante SPEI (es otra cosa: foto de una mascota, \
            screenshot arbitrario, etc.), responde con ok=false y errorMessage apropiado.
            """;

    private static final String INSTRUCTION = """
            Analiza este comprobante SPEI y extrae los campos en JSON según el schema \
            del sistema. Si no es un comprobante, indícalo con ok=false.
            """;

    private final ClaudeService claude;

    public ReceiptOcrService(ClaudeService claude) {
        this.claude = claude;
    }

    @Override
    public ExtractedReceipt extract(byte[] imageBytes, String mimeType, String userId, String ownerId) {
        if (imageBytes == null || imageBytes.length == 0) {
            return ExtractedReceipt.failed("empty_image");
        }

        ClaudeService.ClaudeResponse resp = claude.analyzeImage(
                imageBytes,
                mimeType,
                SYSTEM_PROMPT,
                INSTRUCTION,
                true,
                userId,
                ownerId,
                "OCR_RECEIPT");

        if (!resp.ok()) {
            return ExtractedReceipt.failed(resp.errorMessage() == null ? "ocr_unavailable" : resp.errorMessage());
        }

        Map<String, Object> json = resp.structured();
        if (json.isEmpty()) {
            return ExtractedReceipt.failed("empty_response");
        }

        boolean ok = Boolean.TRUE.equals(json.get("ok"));
        if (!ok) {
            String err = asString(json.get("errorMessage"));
            return ExtractedReceipt.failed(err == null ? "not_a_receipt" : err);
        }

        double confidence = asDouble(json.get("confidence"));

        return new ExtractedReceipt(
                true,
                asString(json.get("claveRastreo")),
                asString(json.get("bankEmitter")),
                asString(json.get("accountReceiver")),
                asString(json.get("amount")),
                asString(json.get("transferDate")),
                asString(json.get("beneficiaryName")),
                asString(json.get("rfcBeneficiary")),
                confidence,
                null
        );
    }

    private String asString(Object o) {
        if (o == null) return null;
        String s = o.toString().trim();
        return s.isEmpty() || "null".equalsIgnoreCase(s) ? null : s;
    }

    private double asDouble(Object o) {
        if (o == null) return 0.0;
        if (o instanceof Number n) return n.doubleValue();
        try { return Double.parseDouble(o.toString()); }
        catch (Exception ex) { return 0.0; }
    }
}
