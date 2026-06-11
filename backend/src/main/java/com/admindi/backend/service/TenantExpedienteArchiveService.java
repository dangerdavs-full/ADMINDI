package com.admindi.backend.service;

import com.admindi.backend.model.ActionTaskEntity;
import com.admindi.backend.model.InvoiceEntity;
import com.admindi.backend.model.LeaseEntity;
import com.admindi.backend.model.PaymentAgreementEntity;
import com.admindi.backend.model.PaymentEntity;
import com.admindi.backend.model.PropertyEntity;
import com.admindi.backend.model.LeaseStatus;
import com.admindi.backend.model.PropertyMovementEventType;
import com.admindi.backend.model.Role;
import com.admindi.backend.model.TenantProfileEntity;
import com.admindi.backend.model.TransferProofSubmission;
import com.admindi.backend.model.UserEntity;
import com.admindi.backend.repository.ActionTaskRepository;
import com.admindi.backend.repository.InvoiceRepository;
import com.admindi.backend.repository.LeaseRepository;
import com.admindi.backend.repository.OwnerMembershipRepository;
import com.admindi.backend.model.TenantArchiveSnapshotEntity;
import com.admindi.backend.repository.PaymentAgreementRepository;
import com.admindi.backend.repository.PaymentRepository;
import com.admindi.backend.repository.PropertyRepository;
import com.admindi.backend.repository.TenantProfileRepository;
import com.admindi.backend.repository.TransferProofSubmissionRepository;
import com.admindi.backend.repository.UserRepository;
import com.admindi.backend.security.TenantContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Operational close-out for one tenant expediente: terminate active lease, void current-period invoice,
 * set archivedAt, drop owner membership when no active expediente remains for that owner,
 * deactivate user and revoke refresh sessions when no active expediente remains at all.
 */
@Service
public class TenantExpedienteArchiveService {

    private final UserRepository userRepository;
    private final TenantProfileRepository tenantProfileRepository;
    private final LeaseRepository leaseRepository;
    private final LeaseService leaseService;
    private final LedgerService ledgerService;
    private final OwnerMembershipRepository ownerMembershipRepository;
    private final TenantService tenantService;
    private final PropertyMovementService propertyMovementService;
    private final DomainEventDispatcher dispatcher;
    private final RefreshTokenRevocationService refreshTokenRevocationService;
    private final VacancyService vacancyService;
    private final ActionTaskRepository actionTaskRepository;
    private final PropertyRepository propertyRepository;
    private final TenantArchiveSnapshotService snapshotService;
    private final ReauthService reauthService;
    private final InvoiceRepository invoiceRepository;
    private final PaymentRepository paymentRepository;
    private final PaymentAgreementRepository paymentAgreementRepository;
    private final TransferProofSubmissionRepository transferProofSubmissionRepository;
    private final AuditService auditService;
    private final UsernameService usernameService;

    @Autowired
    public TenantExpedienteArchiveService(
            UserRepository userRepository,
            TenantProfileRepository tenantProfileRepository,
            LeaseRepository leaseRepository,
            LeaseService leaseService,
            LedgerService ledgerService,
            OwnerMembershipRepository ownerMembershipRepository,
            TenantService tenantService,
            PropertyMovementService propertyMovementService,
            DomainEventDispatcher dispatcher,
            RefreshTokenRevocationService refreshTokenRevocationService,
            VacancyService vacancyService,
            ActionTaskRepository actionTaskRepository,
            PropertyRepository propertyRepository,
            TenantArchiveSnapshotService snapshotService,
            ReauthService reauthService,
            InvoiceRepository invoiceRepository,
            PaymentRepository paymentRepository,
            PaymentAgreementRepository paymentAgreementRepository,
            TransferProofSubmissionRepository transferProofSubmissionRepository,
            AuditService auditService,
            UsernameService usernameService) {
        this.userRepository = userRepository;
        this.tenantProfileRepository = tenantProfileRepository;
        this.leaseRepository = leaseRepository;
        this.leaseService = leaseService;
        this.ledgerService = ledgerService;
        this.ownerMembershipRepository = ownerMembershipRepository;
        this.tenantService = tenantService;
        this.propertyMovementService = propertyMovementService;
        this.dispatcher = dispatcher;
        this.refreshTokenRevocationService = refreshTokenRevocationService;
        this.vacancyService = vacancyService;
        this.actionTaskRepository = actionTaskRepository;
        this.propertyRepository = propertyRepository;
        this.snapshotService = snapshotService;
        this.reauthService = reauthService;
        this.invoiceRepository = invoiceRepository;
        this.paymentRepository = paymentRepository;
        this.paymentAgreementRepository = paymentAgreementRepository;
        this.transferProofSubmissionRepository = transferProofSubmissionRepository;
        this.auditService = auditService;
        this.usernameService = usernameService;
    }

    @Transactional
    public void archiveOperational(String tenantProfileId) {
        archiveOperational(tenantProfileId, null, null);
    }

    /**
     * Archiva expediente con reauth contextual por rol (Etapa 2):
     *  - SUPER_ADMIN: solo contraseña (MFA opcional si la tiene).
     *  - Todos los roles (incluyendo SUPER_ADMIN): MFA + contraseña. El SUPER_ADMIN
     *    también requiere MFA para evitar filtraciones/hackeos aunque no necesite
     *    autorización del dueño para ejecutar.
     *  - Se genera snapshot inmutable (adeudo, meses pagados, evidencias) antes de mutar.
     */
    @Transactional
    public void archiveOperational(String tenantProfileId, String reauthPassword, String reauthMfaCode) {
        String actor = SecurityContextHolder.getContext().getAuthentication().getName();
        UserEntity actorUser = userRepository.findByLoginIdentifier(actor).orElse(null);
        String actorUserId = actorUser != null ? actorUser.getId() : null;
        String actorRole = actorUser != null && actorUser.getRole() != null ? actorUser.getRole().name() : "OWNER";
        boolean isSuperAdmin = actorUser != null && actorUser.getRole() == Role.SUPER_ADMIN;

        // Reauth gate: MFA + contraseña para TODOS los roles (incluyendo SUPER_ADMIN).
        // La diferencia de SUPER_ADMIN es operativa (no necesita autorización del dueño),
        // pero la verificación de identidad es idéntica para prevenir uso indebido de la cuenta.
        String operation = isSuperAdmin ? "TENANT_ARCHIVE_SUPERADMIN" : "TENANT_ARCHIVE_PROPERTY_ADMIN";
        reauthService.verifyReauth(reauthPassword, reauthMfaCode, operation);

        TenantProfileEntity profile = tenantProfileRepository.findById(tenantProfileId)
                .orElseThrow(() -> new RuntimeException("Tenant profile not found."));

        // SUPER_ADMIN puede operar cross-owner; otros roles están restringidos al owner de su contexto.
        String ownerId = isSuperAdmin ? profile.getOwnerId() : TenantContext.resolveOwnerId(userRepository);
        if (!ownerId.equals(profile.getOwnerId())) {
            throw new RuntimeException("IDOR: profile belongs to another organization.");
        }
        if (profile.getArchivedAt() != null) {
            throw new RuntimeException("This expediente is already archived.");
        }

        // Política de retención: solo se conserva expediente histórico si el inquilino pagó
        // al menos 1 mes. Si nunca pagó (0 invoices en PAID y 0 payments registrados), se
        // purga por completo sin dejar rastro operativo (no snapshot, no movement, no task).
        // Se conserva solo una entrada de audit mínima para trazabilidad interna.
        if (!hasAnyPaidHistory(profile.getId())) {
            purgeWithoutTrace(profile, ownerId, actor, actorUserId, actorRole);
            return;
        }

        // Ruta con historial: se preserva expediente (snapshot inmutable + movimiento + task).
        TenantArchiveSnapshotEntity snapshot = snapshotService.buildAndPersist(profile, actorUserId, actorRole);

        // Barremos TODAS las facturas abiertas del inquilino (no solo el mes actual): evita que
        // facturas PENDING/LATE/PARTIALLY_PAID de meses previos sobrevivan e inflen la contabilidad.
        ledgerService.voidAllOpenInvoicesForTenant(profile.getId(), ownerId);

        String propertyId = profile.getPropertyId();
        if (propertyId != null && !propertyId.isBlank()) {
            leaseRepository
                    .findFirstByOwnerIdAndTenant_IdAndProperty_IdAndStatus(
                            ownerId, profile.getUserId(), propertyId, LeaseStatus.ACTIVE)
                    .ifPresent(lease -> leaseService.terminateLease(lease.getId()));
        }

        profile.setArchivedAt(LocalDateTime.now());
        tenantProfileRepository.save(profile);

        String tenantUserId = profile.getUserId();

        long remainingForOwner = tenantProfileRepository.countByUserIdAndOwnerIdAndArchivedAtIsNull(tenantUserId, ownerId);
        if (remainingForOwner == 0) {
            ownerMembershipRepository.findByUserIdAndOwnerId(tenantUserId, ownerId)
                    .ifPresent(m -> ownerMembershipRepository.delete(m));
        }

        long remainingGlobal = tenantProfileRepository.countByUserIdAndArchivedAtIsNull(tenantUserId);
        UserEntity tenantUser = userRepository.findById(tenantUserId).orElseThrow();
        if (remainingGlobal == 0 && tenantUser.getRole() == Role.TENANT) {
            tenantUser.setActive(false);
            userRepository.save(tenantUser);
            refreshTokenRevocationService.revokeAllRefreshSessionsForUser(tenantUserId);
            // Liberar username original: el expediente histórico del inmueble
            // conserva todo, pero el identificador queda disponible para reuso.
            usernameService.tombstoneUsername(tenantUser);
        }

        tenantService.resyncTenantOwnerPointer(tenantUserId);

        if (propertyId != null && !propertyId.isBlank()) {
            String meta = String.format(
                    "{\"tenantProfileId\":\"%s\",\"userId\":\"%s\",\"snapshotId\":\"%s\"}",
                    profile.getId(), tenantUserId, snapshot.getId());
            propertyMovementService.record(ownerId, propertyId, "TENANT_PROFILE", profile.getId(),
                    actorUserId, actorRole, PropertyMovementEventType.TENANT_EXPEDIENTE_ARCHIVED,
                    "Expediente archived",
                    "Operational close-out: lease terminated and profile archived.",
                    LocalDateTime.now(),
                    meta,
                    null);
        }

        dispatcher.dispatch(
                "TENANT_EXPEDIENTE_ARCHIVED",
                "Baja operativa de expediente",
                "Contrato terminado si aplicaba, factura del periodo anulada, vacancia abierta si el inmueble quedó libre.",
                ownerId,
                actor);

        ActionTaskEntity task = new ActionTaskEntity();
        task.setId(UUID.randomUUID().toString());
        task.setUserId(ownerId);
        task.setOwnerId(ownerId);
        task.setEventType("TENANT_EXPEDIENTE_ARCHIVED");
        task.setTitle("Post-baja: revisar inmueble y vacancia");
        String propLabel = propertyId != null && !propertyId.isBlank()
                ? propertyRepository.findById(propertyId).map(PropertyEntity::getName).orElse(propertyId)
                : "sin inmueble";
        task.setDescription("Expediente archivado para el inquilino. Revise vacancia/comercial y cobranza: " + propLabel + ".");
        task.setResourceType("TENANT_PROFILE");
        task.setResourceId(profile.getId());
        task.setStatus("OPEN");
        task.setCreatedAt(LocalDateTime.now());
        actionTaskRepository.save(task);

        if (propertyId != null && !propertyId.isBlank()) {
            leaseService.refreshPropertyOccupancyIncludingProfiles(ownerId, propertyId);
            if (tenantProfileRepository.countByOwnerIdAndPropertyIdAndArchivedAtIsNull(ownerId, propertyId) == 0) {
                vacancyService.ensureOpenVacancyAfterPropertyReleased(ownerId, propertyId,
                        "Tras archivo operativo del expediente; inmueble sin arrendatario activo.");
            }
        }
    }

    /** True si el expediente tuvo al menos un pago real (invoice PAID o payment persistido). */
    private boolean hasAnyPaidHistory(String tenantProfileId) {
        List<InvoiceEntity> invoices = invoiceRepository.findByTenantProfileId(tenantProfileId);
        for (InvoiceEntity inv : invoices) {
            if ("PAID".equalsIgnoreCase(inv.getStatus())) {
                return true;
            }
        }
        List<PaymentEntity> payments = paymentRepository.findByTenantProfileId(tenantProfileId);
        return !payments.isEmpty();
    }

    /**
     * Purga total del expediente cuando el inquilino nunca pagó: borra invoices, payments,
     * proofs, agreements, leases y el propio perfil. Si éste era el único perfil activo del
     * usuario y su rol es TENANT, también anonimiza y desactiva la cuenta del usuario para
     * no dejar PII. No se genera snapshot, movimiento de inmueble ni tarea al dueño — solo
     * un evento de auditoría mínimo para trazabilidad interna.
     */
    private void purgeWithoutTrace(TenantProfileEntity profile, String ownerId, String actor,
                                   String actorUserId, String actorRole) {
        String tenantProfileId = profile.getId();
        String tenantUserId = profile.getUserId();
        String propertyId = profile.getPropertyId();

        List<PaymentAgreementEntity> agreements = paymentAgreementRepository.findByTenantProfileId(tenantProfileId);
        if (!agreements.isEmpty()) paymentAgreementRepository.deleteAll(agreements);

        List<TransferProofSubmission> proofs = transferProofSubmissionRepository.findByTenantProfileId(tenantProfileId);
        if (!proofs.isEmpty()) transferProofSubmissionRepository.deleteAll(proofs);

        List<PaymentEntity> payments = paymentRepository.findByTenantProfileId(tenantProfileId);
        if (!payments.isEmpty()) paymentRepository.deleteAll(payments);

        List<InvoiceEntity> invoices = invoiceRepository.findByTenantProfileId(tenantProfileId);
        if (!invoices.isEmpty()) invoiceRepository.deleteAll(invoices);

        if (propertyId != null && !propertyId.isBlank()) {
            List<LeaseEntity> leases = leaseRepository.findByTenantId(tenantUserId);
            for (LeaseEntity lease : leases) {
                if (lease.getOwnerId() == null || !ownerId.equals(lease.getOwnerId())) continue;
                if (lease.getProperty() == null || !propertyId.equals(lease.getProperty().getId())) continue;
                leaseRepository.delete(lease);
            }
        }

        tenantProfileRepository.delete(profile);

        long remainingForOwner = tenantProfileRepository.countByUserIdAndOwnerIdAndArchivedAtIsNull(tenantUserId, ownerId);
        if (remainingForOwner == 0) {
            ownerMembershipRepository.findByUserIdAndOwnerId(tenantUserId, ownerId)
                    .ifPresent(ownerMembershipRepository::delete);
        }

        long remainingGlobal = tenantProfileRepository.countByUserIdAndArchivedAtIsNull(tenantUserId);
        UserEntity tenantUser = userRepository.findById(tenantUserId).orElse(null);
        if (tenantUser != null && remainingGlobal == 0 && tenantUser.getRole() == Role.TENANT) {
            refreshTokenRevocationService.revokeAllRefreshSessionsForUser(tenantUserId);
            // V54: anonimización — borrar PII y desactivar. Evita violar FKs
            // históricas (audit, etc.) pero elimina el rastro identificable
            // conforme al requisito. El nombre queda "[purged]" y el username
            // se tombstonea para liberar el identificador original.
            tenantUser.setActive(false);
            tenantUser.setName("[purged]");
            tenantUser.setPhone(null);
            tenantUser.setContactEmail(null);
            tenantUser.setContactPhone(null);
            tenantUser.setMfaEnabled(false);
            tenantUser.setMfaSecret(null);
            tenantUser.setDeletedAt(LocalDateTime.now());
            userRepository.save(tenantUser);
            // Liberar username para reuso (único campo único en V54).
            usernameService.tombstoneUsername(tenantUser);
        }

        tenantService.resyncTenantOwnerPointer(tenantUserId);

        if (propertyId != null && !propertyId.isBlank()) {
            leaseService.refreshPropertyOccupancyIncludingProfiles(ownerId, propertyId);
            if (tenantProfileRepository.countByOwnerIdAndPropertyIdAndArchivedAtIsNull(ownerId, propertyId) == 0) {
                vacancyService.ensureOpenVacancyAfterPropertyReleased(ownerId, propertyId,
                        "Inquilino dado de baja sin historial de pagos; inmueble liberado.");
            }
        }

        // Audit mínimo interno: sin PII del inquilino, solo ids operativos.
        auditService.logEvent(
                actor,
                actorRole,
                "TENANT_EXPEDIENTE_PURGED",
                "TenantProfile",
                tenantProfileId,
                ownerId,
                null,
                String.format("{\"propertyId\":\"%s\",\"reason\":\"NO_PAYMENT_HISTORY\",\"actorUserId\":\"%s\"}",
                        propertyId == null ? "" : propertyId,
                        actorUserId == null ? "" : actorUserId),
                null,
                null);
    }
}
