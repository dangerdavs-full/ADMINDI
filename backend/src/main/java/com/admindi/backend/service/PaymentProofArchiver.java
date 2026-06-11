package com.admindi.backend.service;

import com.admindi.backend.model.InvoiceEntity;
import com.admindi.backend.model.PropertyFileEntity;
import com.admindi.backend.model.TenantProfileEntity;
import com.admindi.backend.model.TransferProofSubmission;
import com.admindi.backend.repository.PropertyFileRepository;
import com.admindi.backend.repository.TenantProfileRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Archivador de comprobantes de pago validados al expediente del inmueble.
 *
 * Cuando un pago se confirma (SPEI vía CEP o CASH aprobado por el dueño),
 * creamos un {@link PropertyFileEntity} para que aparezca en "Archivos del
 * inmueble" del panel del dueño. Así el dueño tiene un expediente completo
 * por inmueble sin tener que ir a buscar en el libro mayor.
 *
 * <p>Convención: {@code category="OTHER"}, {@code label="PAYMENT_PROOF"}
 * (agregado a ALLOWED_LABELS en V57), {@code note} con mes y monto.
 *
 * <p>Idempotente: si ya existe un PropertyFileEntity con el mismo filePath,
 * no se duplica (consulta previa). Esto evita ruido si el flujo corre 2
 * veces por un reintento.
 */
@Service
public class PaymentProofArchiver {

    private static final Logger logger = LoggerFactory.getLogger(PaymentProofArchiver.class);

    private final PropertyFileRepository propertyFileRepository;
    private final TenantProfileRepository tenantProfileRepository;

    public PaymentProofArchiver(PropertyFileRepository propertyFileRepository,
                                  TenantProfileRepository tenantProfileRepository) {
        this.propertyFileRepository = propertyFileRepository;
        this.tenantProfileRepository = tenantProfileRepository;
    }

    /**
     * Archiva el comprobante al expediente del inmueble. Falla silenciosamente
     * si falta información (no rompe el flujo de pago principal).
     */
    @Transactional
    public void archiveValidatedProof(TransferProofSubmission proof, InvoiceEntity invoice) {
        try {
            if (proof == null || invoice == null) return;
            String propertyId = resolvePropertyId(invoice);
            if (propertyId == null) {
                logger.debug("[ARCHIVE] no pude resolver propertyId para invoice {}", invoice.getId());
                return;
            }

            archiveIfPresent(propertyId, proof.getFileUrl(), buildProofFileName(proof, invoice),
                    guessContentType(proof.getFileUrl()), buildProofNote(proof, invoice), proof);
            archiveIfPresent(propertyId, proof.getCepXmlUrl(), buildCepFileName(proof, invoice, "xml"),
                    "application/xml", buildCepNote(proof, invoice, "XML"), proof);
            archiveIfPresent(propertyId, proof.getCepPdfUrl(), buildCepFileName(proof, invoice, "pdf"),
                    "application/pdf", buildCepNote(proof, invoice, "PDF"), proof);

            logger.info("[ARCHIVE] comprobante {} ({}) archivado en expediente de property={} invoice={} cepXml={} cepPdf={}",
                    proof.getId(), proof.getPaymentType(), propertyId, invoice.getId(),
                    notBlank(proof.getCepXmlUrl()), notBlank(proof.getCepPdfUrl()));
        } catch (Exception ex) {
            // Nunca propagamos — el archivado es "nice to have", el pago ya se confirmó.
            logger.warn("[ARCHIVE] fallo archivando proof {}: {}",
                    proof != null ? proof.getId() : "(null)",
                    ex.getClass().getSimpleName());
        }
    }

    private void archiveIfPresent(String propertyId, String filePath, String fileName,
                                  String contentType, String note, TransferProofSubmission proof) {
        if (!notBlank(filePath)) {
            return;
        }
        if (propertyFileRepository.existsByFilePath(filePath)) {
            return;
        }

        PropertyFileEntity entity = new PropertyFileEntity();
        entity.setId(UUID.randomUUID().toString());
        entity.setPropertyId(propertyId);
        entity.setCategory("OTHER");
        entity.setFileName(fileName);
        entity.setFilePath(filePath);
        entity.setContentType(contentType);
        entity.setSizeBytes(null);
        entity.setUploadedBy(proof.getReviewedBy() != null ? proof.getReviewedBy() : "SISTEMA_PAGOS");
        entity.setUploaderRole("SYSTEM");
        entity.setLabel("PAYMENT_PROOF");
        entity.setNote(note);
        entity.setUploadedAt(LocalDateTime.now());
        propertyFileRepository.save(entity);
    }

    private String resolvePropertyId(InvoiceEntity invoice) {
        if (invoice.getTenantProfileId() == null) return null;
        TenantProfileEntity profile = tenantProfileRepository.findById(invoice.getTenantProfileId())
                .orElse(null);
        return profile != null ? profile.getPropertyId() : null;
    }

    private String buildProofFileName(TransferProofSubmission proof, InvoiceEntity invoice) {
        String type = proof.isCash() ? "efectivo" : "spei";
        String month = invoice.getMonthYear() != null ? invoice.getMonthYear() : "sin-mes";
        return "comprobante-" + type + "-" + month + "-" + proof.getId().substring(0, 8) + extFromUrl(proof.getFileUrl());
    }

    private String buildCepFileName(TransferProofSubmission proof, InvoiceEntity invoice, String ext) {
        String month = invoice.getMonthYear() != null ? invoice.getMonthYear() : "sin-mes";
        return "cep-banxico-" + month + "-" + proof.getId().substring(0, 8) + "." + ext;
    }

    private String buildProofNote(TransferProofSubmission proof, InvoiceEntity invoice) {
        StringBuilder sb = new StringBuilder();
        sb.append("Comprobante ");
        sb.append(proof.isCash() ? "de pago en efectivo" : "SPEI");
        sb.append(" renta ").append(invoice.getMonthYear());
        if (proof.getAmount() != null) {
            sb.append(" — monto: $").append(proof.getAmount().toPlainString()).append(" MXN");
        }
        if (proof.getClaveRastreo() != null) {
            sb.append(" — clave: ").append(proof.getClaveRastreo());
        }
        return sb.toString();
    }

    private String buildCepNote(TransferProofSubmission proof, InvoiceEntity invoice, String format) {
        StringBuilder sb = new StringBuilder();
        sb.append("CEP oficial Banxico ").append(format);
        sb.append(" renta ").append(invoice.getMonthYear());
        if (proof.getAmount() != null) {
            sb.append(" — monto validado: $").append(proof.getAmount().toPlainString()).append(" MXN");
        }
        if (proof.getClaveRastreo() != null) {
            sb.append(" — clave: ").append(proof.getClaveRastreo());
        }
        return sb.toString();
    }

    private String guessContentType(String url) {
        if (url == null) return "application/octet-stream";
        String lower = url.toLowerCase();
        if (lower.endsWith(".pdf")) return "application/pdf";
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".webp")) return "image/webp";
        if (lower.endsWith(".gif")) return "image/gif";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".xml")) return "application/xml";
        return "application/octet-stream";
    }

    private String extFromUrl(String url) {
        if (url == null) return "";
        int dot = url.lastIndexOf('.');
        return dot > 0 ? url.substring(dot) : "";
    }

    private boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }
}
