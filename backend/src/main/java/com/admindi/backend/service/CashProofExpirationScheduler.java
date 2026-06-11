package com.admindi.backend.service;

import com.admindi.backend.model.InvoiceEntity;
import com.admindi.backend.model.TransferProofStatus;
import com.admindi.backend.model.TransferProofSubmission;
import com.admindi.backend.repository.InvoiceRepository;
import com.admindi.backend.repository.TenantProfileRepository;
import com.admindi.backend.repository.TransferProofSubmissionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Scheduler que marca los comprobantes en PENDING_OWNER_VALIDATION (CASH y
 * SPEI que cayeron a validación manual) como EXPIRED_AWAITING_OWNER cuando
 * pasaron 120 horas sin que el dueño decidiera.
 *
 * <p>V59 — antes solo cubría CASH; por diseño los SPEI que caen a validación
 * manual (Banxico caído o dueño sin CLABE) también nacen con expires_at=+120h
 * y deben expirar por el mismo camino.
 *
 * Corre cada hora. Es ligero: una query con índice parcial en
 * {@code idx_transfer_proof_pending_owner_validation} (V59). Si no hay
 * expirados, no hace nada (ni logs salvo debug).
 *
 * Después de marcar expirados, envía dos notificaciones:
 *   1. Al inquilino: "tu comprobante expiró sin validación del dueño"
 *   2. Al dueño: "se expiró un comprobante sin tu validación"
 *
 * Las notificaciones usan body libre (no requieren plantilla Meta porque
 * son eventos nuevos y van por email + in-app si el canal WhatsApp no
 * tiene plantilla registrada; eso lo decide DomainEventDispatcher).
 */
@Service
public class CashProofExpirationScheduler {

    private static final Logger logger = LoggerFactory.getLogger(CashProofExpirationScheduler.class);

    private final TransferProofSubmissionRepository proofRepo;
    private final InvoiceRepository invoiceRepo;
    private final TenantProfileRepository tenantProfileRepo;
    private final LedgerService ledgerService;
    private final DomainEventDispatcher dispatcher;

    public CashProofExpirationScheduler(TransferProofSubmissionRepository proofRepo,
                                          InvoiceRepository invoiceRepo,
                                          TenantProfileRepository tenantProfileRepo,
                                          LedgerService ledgerService,
                                          DomainEventDispatcher dispatcher) {
        this.proofRepo = proofRepo;
        this.invoiceRepo = invoiceRepo;
        this.tenantProfileRepo = tenantProfileRepo;
        this.ledgerService = ledgerService;
        this.dispatcher = dispatcher;
    }

    /**
     * Cron cada hora en punto. Si el dueño no validó en 120h, el comprobante
     * pasa a EXPIRED_AWAITING_OWNER y se notifica a las dos partes.
     *
     * <p>Override cron vía {@code admindi.payments.cash.expiration-cron} si
     * hace falta más frecuencia en QA.
     */
    @Scheduled(cron = "${admindi.payments.cash.expiration-cron:0 5 * * * *}",
               zone = "America/Mexico_City")
    public void expireStaleCashProofs() {
        // V58.1 — Capturamos la lista DESPUÉS de marcar expirados para evitar
        // notificar como "expirado" a un proof que el dueño alcanzó a aprobar
        // entre nuestra primera query y la mutación. Releemos por id.
        //
        // V59 — se mantiene el nombre del método público para back-compat pero
        // internamente usamos el generalizado que abarca SPEI+CASH.
        int marked = ledgerService.expirePendingOwnerValidationOlderThan(LocalDateTime.now());
        if (marked == 0) return;
        logger.warn("[PROOF-EXPIRE] marked {} proofs as EXPIRED_AWAITING_OWNER (cash + spei manual)", marked);

        // Re-consultar proofs que QUEDARON en EXPIRED_AWAITING_OWNER tras la
        // mutación. Solo a esos notificamos.
        List<TransferProofSubmission> expired =
                proofRepo.findByStatusAndReviewedAtAfter(
                        TransferProofStatus.EXPIRED_AWAITING_OWNER,
                        LocalDateTime.now().minusMinutes(10));

        for (TransferProofSubmission proof : expired) {
            try {
                InvoiceEntity invoice = invoiceRepo.findById(proof.getInvoiceId()).orElse(null);
                String month = invoice != null ? invoice.getMonthYear() : "(sin factura)";
                String amountStr = proof.getAmount() != null ? proof.getAmount().toPlainString() : "(sin monto)";
                String kind = proof.isCash() ? "de efectivo" : "SPEI";

                // Notifica al dueño
                dispatcher.dispatch(
                        "CASH_PAYMENT_EXPIRED",
                        "Comprobante " + kind + " expirado sin validación: " + month,
                        "El comprobante " + kind + " por $" + amountStr
                                + " MXN que el inquilino envió el " + proof.getSubmittedAt()
                                + " expiró sin tu validación. El inquilino deberá subir uno nuevo "
                                + "o coordinar directamente contigo.",
                        proof.getOwnerId(),
                        "SYSTEM",
                        null);

                // Notifica al inquilino (vía user_id del tenantProfile)
                if (proof.getTenantProfileId() != null) {
                    try {
                        String tenantUserId = tenantProfileRepo.findById(proof.getTenantProfileId())
                                .map(tp -> tp.getUserId()).orElse(null);
                        if (tenantUserId != null) {
                            List<String> recipients = java.util.List.of(tenantUserId);
                            dispatcher.dispatch(
                                    "CASH_PAYMENT_EXPIRED",
                                    "Tu comprobante " + kind + " expiró",
                                    "Tu arrendador no validó el comprobante " + kind
                                            + " por $" + amountStr
                                            + " MXN que enviaste el " + proof.getSubmittedAt()
                                            + " dentro de las 120 horas. Puedes subir otro o "
                                            + "contactar directamente a tu arrendador.",
                                    proof.getOwnerId(),
                                    "SYSTEM",
                                    recipients);
                        }
                    } catch (Exception notifyTenantEx) {
                        logger.debug("[PROOF-EXPIRE] failed to notify tenant for proof {}: {}",
                                proof.getId(), notifyTenantEx.getClass().getSimpleName());
                    }
                }
            } catch (Exception ex) {
                logger.warn("[PROOF-EXPIRE] failed to notify proof {}: {}",
                        proof.getId(), ex.getClass().getSimpleName());
            }
        }
    }
}
