package com.admindi.backend.whatsapp;

/**
 * Puerto (interface) para extraer datos de un comprobante SPEI a partir de
 * su imagen.
 *
 * Fase 2 (bot): no hay implementación; el flow detecta ausencia y responde
 * con un mensaje degradado. Fase 3 conecta el OCR con Claude Vision
 * implementando este contrato (bean nombrado "receiptOcrPort").
 *
 * Al mantener el puerto como interface el chatbot no depende del SDK de IA
 * ni de detalles de implementación. Cambiar provider (Claude → alternativa)
 * no requiere tocar el bot.
 */
public interface ReceiptOcrPort {

    record ExtractedReceipt(
            boolean ok,
            String claveRastreo,
            String bankEmitter,
            String accountReceiver,
            String amount,            // String para evitar BigDecimal bugs en contexto JSON
            String transferDate,      // ISO yyyy-MM-dd
            String beneficiaryName,
            String rfcBeneficiary,
            double confidence,
            String errorMessage
    ) {
        public static ExtractedReceipt failed(String msg) {
            return new ExtractedReceipt(false, null, null, null, null, null, null, null, 0, msg);
        }
    }

    ExtractedReceipt extract(byte[] imageBytes, String mimeType, String userId, String ownerId);
}
