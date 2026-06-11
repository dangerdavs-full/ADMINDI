package com.admindi.backend.whatsapp;

import com.admindi.backend.model.UserEntity;

/**
 * Puerto para enviar un comprobante SPEI ya extraído (OCR) al flujo de
 * validación existente ({@code LedgerService.submitTransferProof}).
 *
 * El bot no llama directo a {@code LedgerService} porque éste depende del
 * {@link org.springframework.security.core.context.SecurityContextHolder}.
 * Usamos {@link BotSecurityBridge#runAs} para simular el contexto del
 * TENANT y aquí encapsulamos esa invocación en un solo punto.
 */
public interface ProofSubmissionPort {

    enum ProofStatus {
        /** Banxico CEP confirmó el comprobante. */
        VALIDATED,
        /** Faltan datos — el bot pidió al user completar (Fase 3+). */
        INCOMPLETE,
        /** Banxico CEP rechazó los datos. */
        REJECTED_BY_CEP,
        /** Banxico no disponible; queda en RECEIVED para override manual del dueño. */
        TEMP_ERROR,
        /** Fallo inesperado. */
        FAILED
    }

    record SubmissionResult(ProofStatus status, String detail, String proofId) {}

    /**
     * Envía los datos al flujo de pagos. La imagen ya debe estar almacenada
     * (path obtenido con {@link #persistBotProofImage(byte[], String)}).
     *
     * @param amount   monto como String (fácil de serializar en context JSON)
     * @param isoDate  fecha formato yyyy-MM-dd
     * @param imagePath path relativo devuelto por {@link #persistBotProofImage}
     */
    SubmissionResult submit(UserEntity tenant,
                            String invoiceId,
                            String claveRastreo,
                            String bankEmitter,
                            String accountReceiver,
                            String amount,
                            String isoDate,
                            String imagePath);

    /**
     * Igual que {@link #submit} pero con {@code captureMethod} explícito
     * ({@code AI_OCR} o {@code MANUAL}). El bot usa MANUAL cuando el inquilino
     * tecleó los datos sin enviar foto.
     */
    default SubmissionResult submit(UserEntity tenant,
                                    String invoiceId,
                                    String claveRastreo,
                                    String bankEmitter,
                                    String accountReceiver,
                                    String amount,
                                    String isoDate,
                                    String imagePath,
                                    String captureMethod) {
        return submit(tenant, invoiceId, claveRastreo, bankEmitter, accountReceiver,
                amount, isoDate, imagePath);
    }

    /**
     * Persiste la imagen del comprobante en el storage y devuelve la ruta
     * para asociarla al proof cuando se cree.
     */
    String persistBotProofImage(byte[] bytes, String mimeType);
}
