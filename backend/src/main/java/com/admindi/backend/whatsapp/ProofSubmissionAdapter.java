package com.admindi.backend.whatsapp;

import com.admindi.backend.dto.TransferProofDTO;
import com.admindi.backend.model.UserEntity;
import com.admindi.backend.service.FileStorageService;
import com.admindi.backend.service.LedgerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Adapter que conecta el bot de WhatsApp con el flujo de pagos existente.
 *
 * Responsabilidades:
 *  - Persistir la imagen del comprobante usando {@link FileStorageService#storeBytes}.
 *  - Invocar {@code LedgerService.submitTransferProof} dentro del contexto
 *    de seguridad del TENANT via {@link BotSecurityBridge}.
 *  - Traducir el resultado (DTO con status RECEIVED/VALIDATED/INCOMPLETE_DATA/
 *    REJECTED_BY_CEP) al enum del puerto para que el bot no dependa del
 *    modelo interno de {@code LedgerService}.
 */
@Service("proofSubmissionPort")
public class ProofSubmissionAdapter implements ProofSubmissionPort {

    private static final Logger logger = LoggerFactory.getLogger(ProofSubmissionAdapter.class);

    private final FileStorageService storageService;
    private final BotSecurityBridge securityBridge;
    private final BotLedgerInvoker ledgerInvoker;

    public ProofSubmissionAdapter(FileStorageService storageService,
                                   BotSecurityBridge securityBridge,
                                   BotLedgerInvoker ledgerInvoker) {
        this.storageService = storageService;
        this.securityBridge = securityBridge;
        this.ledgerInvoker = ledgerInvoker;
    }

    @Override
    public SubmissionResult submit(UserEntity tenant,
                                    String invoiceId,
                                    String claveRastreo,
                                    String bankEmitter,
                                    String accountReceiver,
                                    String amountStr,
                                    String isoDate,
                                    String imagePath) {
        return submit(tenant, invoiceId, claveRastreo, bankEmitter, accountReceiver,
                amountStr, isoDate, imagePath, "AI_OCR");
    }

    @Override
    public SubmissionResult submit(UserEntity tenant,
                                    String invoiceId,
                                    String claveRastreo,
                                    String bankEmitter,
                                    String accountReceiver,
                                    String amountStr,
                                    String isoDate,
                                    String imagePath,
                                    String captureMethod) {
        if (tenant == null || invoiceId == null) {
            return new SubmissionResult(ProofStatus.FAILED, "missing_input", null);
        }

        BigDecimal amount = parseAmount(amountStr);
        LocalDate date = parseDate(isoDate);

        try {
            TransferProofDTO proof = securityBridge.runAs(tenant, () ->
                    ledgerInvoker.submitFromBot(invoiceId, claveRastreo, bankEmitter,
                            accountReceiver, amount, date, imagePath, captureMethod));

            String status = proof.getStatus();
            if (status == null) status = "";
            return switch (status.toUpperCase()) {
                case "VALIDATED" -> new SubmissionResult(ProofStatus.VALIDATED,
                        "Pago confirmado", proof.getId());
                case "INCOMPLETE_DATA" -> new SubmissionResult(ProofStatus.INCOMPLETE,
                        missingFields(proof), proof.getId());
                case "REJECTED_BY_CEP" -> new SubmissionResult(ProofStatus.REJECTED_BY_CEP,
                        nz(proof.getRejectionReason()), proof.getId());
                case "RECEIVED" -> new SubmissionResult(ProofStatus.TEMP_ERROR,
                        "queued_for_manual_review", proof.getId());
                default -> new SubmissionResult(ProofStatus.TEMP_ERROR,
                        "unexpected_status=" + status, proof.getId());
            };
        } catch (Exception ex) {
            logger.warn("[BOT-PROOF] submit failed for user={} invoice={}: {}",
                    tenant.getId(), invoiceId, ex.getClass().getSimpleName());
            return new SubmissionResult(ProofStatus.FAILED,
                    ex.getClass().getSimpleName(), null);
        }
    }

    @Override
    public String persistBotProofImage(byte[] bytes, String mimeType) {
        try {
            return storageService.storeBytes(bytes, null, mimeType, "bot-proofs");
        } catch (Exception ex) {
            logger.warn("[BOT-PROOF] storeBytes failed: {}", ex.getMessage());
            return null;
        }
    }

    private BigDecimal parseAmount(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            // Eliminamos comas de miles y $ si viniera del OCR mal formateado.
            String clean = s.replaceAll("[,$\\s]", "");
            return new BigDecimal(clean);
        } catch (Exception ex) {
            return null;
        }
    }

    private LocalDate parseDate(String s) {
        if (s == null || s.isBlank()) return null;
        try { return LocalDate.parse(s); }
        catch (Exception ex) { return null; }
    }

    private String missingFields(TransferProofDTO proof) {
        if (proof == null || proof.getMissingFields() == null
                || proof.getMissingFields().isBlank()) {
            return "campos incompletos";
        }
        // El DTO devuelve un JSON string tipo ["field1","field2"]. Lo limpiamos
        // para mostrarle al usuario un texto amable sin caracteres JSON.
        String clean = proof.getMissingFields()
                .replaceAll("[\\[\\]\"]", "")
                .replace(",", ", ");
        return "faltan: " + clean;
    }

    private String nz(String s) { return s == null ? "sin detalle" : s; }
}
