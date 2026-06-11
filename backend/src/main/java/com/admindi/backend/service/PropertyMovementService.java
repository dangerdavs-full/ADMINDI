package com.admindi.backend.service;

import com.admindi.backend.dto.PropertyMovementDTO;
import com.admindi.backend.model.LeaseEntity;
import com.admindi.backend.model.InvoiceEntity;
import com.admindi.backend.model.PaymentEntity;
import com.admindi.backend.model.PropertyEntity;
import com.admindi.backend.model.PropertyMovementEntity;
import com.admindi.backend.model.PropertyMovementEventType;
import com.admindi.backend.model.Role;
import com.admindi.backend.model.TenantProfileEntity;
import com.admindi.backend.model.UserEntity;
import com.admindi.backend.repository.LeaseRepository;
import com.admindi.backend.repository.PropertyMovementRepository;
import com.admindi.backend.repository.PropertyRepository;
import com.admindi.backend.repository.TenantProfileRepository;
import com.admindi.backend.repository.UserRepository;
import com.admindi.backend.security.TenantContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class PropertyMovementService {

    private final PropertyMovementRepository movementRepository;
    private final PropertyRepository propertyRepository;
    private final LeaseRepository leaseRepository;
    private final TenantProfileRepository tenantProfileRepository;
    private final UserRepository userRepository;

    @Autowired
    public PropertyMovementService(PropertyMovementRepository movementRepository,
                                   PropertyRepository propertyRepository,
                                   LeaseRepository leaseRepository,
                                   TenantProfileRepository tenantProfileRepository,
                                   UserRepository userRepository) {
        this.movementRepository = movementRepository;
        this.propertyRepository = propertyRepository;
        this.leaseRepository = leaseRepository;
        this.tenantProfileRepository = tenantProfileRepository;
        this.userRepository = userRepository;
    }

    private String resolveOwnerIdForAccess() {
        return TenantContext.resolveOwnerId(userRepository);
    }

    private void assertCanViewProperty(String propertyId) {
        String ownerId = resolveOwnerIdForAccess();
        PropertyEntity prop = propertyRepository.findById(propertyId)
                .orElseThrow(() -> new RuntimeException("Inmueble no encontrado."));
        if (!prop.getOwnerId().equals(ownerId)) {
            throw new RuntimeException("Aislamiento IDOR: propiedad de otra organizacion.");
        }
    }

    public List<PropertyMovementDTO> getTimelineForProperty(String propertyId) {
        assertCanViewProperty(propertyId);
        return movementRepository.findByPropertyIdOrderByOccurredAtDesc(propertyId).stream()
                .map(this::toDto)
                .collect(Collectors.toList());
    }

    public Optional<String> resolvePropertyIdForInvoice(InvoiceEntity invoice) {
        if (invoice == null || invoice.getLeaseId() == null || invoice.getLeaseId().isBlank()) {
            return Optional.empty();
        }
        return resolvePropertyIdForLeaseId(invoice.getLeaseId());
    }

    public Optional<String> resolvePropertyIdForLeaseId(String leaseId) {
        if (leaseId == null || leaseId.isBlank()) {
            return Optional.empty();
        }
        return leaseRepository.findById(leaseId)
                .flatMap(lease -> Optional.ofNullable(lease.resolvePropertyEntity()).map(PropertyEntity::getId));
    }

    @Transactional
    public void record(String ownerId, String propertyId, String resourceType, String resourceId,
                       String actorUserId, String actorRole, String eventType, String title, String description,
                       LocalDateTime occurredAt, String metadataJson, String attachmentFileId) {
        if (propertyId == null || propertyId.isBlank()) {
            return;
        }
        PropertyMovementEntity e = new PropertyMovementEntity();
        e.setId(UUID.randomUUID().toString());
        e.setOwnerId(ownerId);
        e.setPropertyId(propertyId);
        e.setResourceType(resourceType);
        e.setResourceId(resourceId);
        e.setActorUserId(actorUserId);
        e.setActorRole(actorRole);
        e.setEventType(eventType);
        e.setTitle(title);
        e.setDescription(description);
        e.setOccurredAt(occurredAt != null ? occurredAt : LocalDateTime.now());
        e.setMetadataJson(metadataJson);
        e.setAttachmentFileId(attachmentFileId);
        movementRepository.save(e);
    }

    @Transactional
    public void recordPaymentMovement(InvoiceEntity invoice, PaymentEntity payment) {
        recordPaymentMovement(invoice, payment, null);
    }

    /**
     * Bloque 4 / Gap C: variante con comprobante adjunto. Permite que el override
     * manual del SUPERADMIN, el flujo CEP y la integración de pasarela (Mercado
     * Pago) archiven el recibo/constancia directamente en el movement del
     * inmueble. El {@code attachmentFileId} apunta a {@code file_upload_claims}
     * y queda accesible desde el expediente del inmueble sin joins indirectos.
     */
    @Transactional
    public void recordPaymentMovement(InvoiceEntity invoice, PaymentEntity payment, String attachmentFileId) {
        Optional<String> propId = resolvePropertyIdForInvoice(invoice);
        if (propId.isEmpty()) {
            return;
        }
        String settlement = invoice.getSettlementStatus();
        String eventType;
        if ("OVERPAID".equals(settlement)) {
            eventType = PropertyMovementEventType.PAYMENT_OVERPAY;
        } else if ("PARTIALLY_PAID".equals(settlement)) {
            eventType = PropertyMovementEventType.PAYMENT_PARTIAL;
        } else {
            eventType = PropertyMovementEventType.PAYMENT_EXACT;
        }
        String actorUserId = null;
        String actorRole = Role.TENANT.name();
        Optional<TenantProfileEntity> tp = tenantProfileRepository.findById(invoice.getTenantProfileId());
        if (tp.isPresent()) {
            actorUserId = tp.get().getUserId();
        }
        String applied = payment.getAppliedAmount() != null ? payment.getAppliedAmount().toPlainString() : "0";
        String unapplied = payment.getUnappliedAmount() != null ? payment.getUnappliedAmount().toPlainString() : "0";
        String meta = String.format(
                "{\"invoiceId\":\"%s\",\"paymentId\":\"%s\",\"monthYear\":\"%s\",\"appliedAmount\":\"%s\",\"unappliedAmount\":\"%s\"}",
                invoice.getId(),
                payment.getId(),
                invoice.getMonthYear() != null ? invoice.getMonthYear() : "",
                applied,
                unapplied);
        LocalDateTime when = payment.getPaidAt() != null ? payment.getPaidAt() : LocalDateTime.now();
        String notes = payment.getNotes() != null ? payment.getNotes() : "";
        record(invoice.getOwnerId(), propId.get(), "PAYMENT", payment.getId(),
                actorUserId, actorRole, eventType,
                "Pago registrado", notes,
                when, meta, attachmentFileId);
    }

    private PropertyMovementDTO toDto(PropertyMovementEntity e) {
        PropertyMovementDTO dto = new PropertyMovementDTO();
        dto.setId(e.getId());
        dto.setPropertyId(e.getPropertyId());
        dto.setResourceType(e.getResourceType());
        dto.setResourceId(e.getResourceId());
        dto.setActorUserId(e.getActorUserId());
        dto.setActorRole(e.getActorRole());
        dto.setEventType(e.getEventType());
        dto.setTitle(e.getTitle());
        dto.setDescription(e.getDescription());
        dto.setOccurredAt(e.getOccurredAt() != null ? e.getOccurredAt().toString() : null);
        dto.setMetadataJson(e.getMetadataJson());
        dto.setAttachmentFileId(e.getAttachmentFileId());
        return dto;
    }
}