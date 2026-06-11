package com.admindi.backend.whatsapp;

import com.admindi.backend.dto.TransferProofDTO;
import com.admindi.backend.service.LedgerService;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Invocaciones de {@link LedgerService} para el bot del dueño (validación de
 * comprobantes CASH y SPEI en cola manual).
 */
@Component
public class BotOwnerLedgerInvoker {

    private final LedgerService ledgerService;

    public BotOwnerLedgerInvoker(LedgerService ledgerService) {
        this.ledgerService = ledgerService;
    }

    public List<TransferProofDTO> listPendingProofs() {
        return ledgerService.getPendingProofsForOwner();
    }

    public void approveProof(String proofId, String reviewedVia) {
        ledgerService.overrideTransferProof(proofId, true,
                "Aprobado por WhatsApp (" + reviewedVia + ")");
    }

    public void rejectProof(String proofId, String reason, String reviewedVia) {
        String safeReason = reason != null && !reason.isBlank()
                ? reason.trim()
                : "Rechazado por WhatsApp";
        ledgerService.overrideTransferProof(proofId, false,
                safeReason + " (" + reviewedVia + ")");
    }
}
