package com.admindi.backend.whatsapp;

import com.admindi.backend.dto.TransferProofDTO;
import com.admindi.backend.model.InvoiceEntity;
import com.admindi.backend.model.TenantProfileEntity;
import com.admindi.backend.repository.InvoiceRepository;
import com.admindi.backend.repository.TenantProfileRepository;
import com.admindi.backend.service.LedgerService;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Wrapper fino que encapsula la invocación del flujo de pagos para el chatbot.
 *
 * Separado en su propio bean para facilitar mock en tests del bot sin tener
 * que instanciar {@link LedgerService} completo (con todas sus dependencias).
 *
 * Validación cross-org: antes de invocar, confirmamos que el user autenticado
 * por el bot tiene un TenantProfile que coincide con la factura. Defensa en
 * profundidad frente a un eventual estado corrupto de la sesión.
 */
@Component
public class BotLedgerInvoker {

    private final LedgerService ledgerService;
    private final InvoiceRepository invoiceRepository;
    private final TenantProfileRepository tenantProfileRepository;

    public BotLedgerInvoker(LedgerService ledgerService,
                             InvoiceRepository invoiceRepository,
                             TenantProfileRepository tenantProfileRepository) {
        this.ledgerService = ledgerService;
        this.invoiceRepository = invoiceRepository;
        this.tenantProfileRepository = tenantProfileRepository;
    }

    /**
     * Invoca el flujo de pagos con un archivo ya persistido (path relativo).
     * Debe llamarse dentro de un {@code BotSecurityBridge.runAs(tenant, ...)}
     * para que {@code resolveActorEmail()} funcione desde el SecurityContext.
     *
     * @throws SecurityException si la factura no pertenece a un expediente
     *         activo del user autenticado en el SecurityContext.
     */
    public TransferProofDTO submitFromBot(String invoiceId,
                                           String claveRastreo,
                                           String bankEmitter,
                                           String accountReceiver /* ignorado V58 */,
                                           BigDecimal amount,
                                           LocalDate transferDate,
                                           String fileUrl) {
        enforceInvoiceOwnership(invoiceId);
        // V58 — el chatbot usa AI_OCR porque siempre procesa foto con Claude Vision.
        // La cuenta receptora se ignora (viene del owner.clabe).
        return submitFromBot(invoiceId, claveRastreo, bankEmitter, accountReceiver,
                amount, transferDate, fileUrl, "AI_OCR");
    }

    /**
     * Igual que {@link #submitFromBot} pero permite elegir {@code captureMethod}
     * (p. ej. {@code MANUAL} cuando el inquilino tecleó los datos sin foto).
     */
    public TransferProofDTO submitFromBot(String invoiceId,
                                           String claveRastreo,
                                           String bankEmitter,
                                           String accountReceiver,
                                           BigDecimal amount,
                                           LocalDate transferDate,
                                           String fileUrl,
                                           String captureMethod) {
        enforceInvoiceOwnership(invoiceId);
        String method = "AI_OCR".equalsIgnoreCase(captureMethod) ? "AI_OCR" : "MANUAL";
        return ledgerService.submitTransferProofWithFileUrl(invoiceId, claveRastreo,
                bankEmitter, amount, transferDate, fileUrl, method);
    }

    private void enforceInvoiceOwnership(String invoiceId) {
        InvoiceEntity invoice = invoiceRepository.findById(invoiceId)
                .orElseThrow(() -> new SecurityException("Factura no encontrada."));
        Object principal = SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        if (!(principal instanceof com.admindi.backend.model.UserEntity user)) {
            throw new SecurityException("Contexto de bot inválido.");
        }
        List<TenantProfileEntity> profiles = tenantProfileRepository
                .findByUserIdAndArchivedAtIsNull(user.getId());
        boolean match = profiles.stream()
                .anyMatch(p -> invoice.getTenantProfileId() != null
                        && invoice.getTenantProfileId().equals(p.getId()));
        if (!match) {
            throw new SecurityException("IDOR: factura no pertenece a este inquilino.");
        }
    }
}
