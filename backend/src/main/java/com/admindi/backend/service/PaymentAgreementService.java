package com.admindi.backend.service;

import com.admindi.backend.dto.AgreementInstallmentDTO;
import com.admindi.backend.dto.PaymentAgreementDTO;
import com.admindi.backend.model.*;
import com.admindi.backend.repository.*;
import com.admindi.backend.security.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class PaymentAgreementService {

    private static final Logger logger = LoggerFactory.getLogger(PaymentAgreementService.class);

    private final PaymentAgreementRepository agreementRepository;
    private final AgreementInstallmentRepository installmentRepository;
    private final InvoiceRepository invoiceRepository;
    private final TenantProfileRepository profileRepository;
    private final UserRepository userRepository;
    private final PropertyMovementService propertyMovementService;
    private final FileStorageService fileStorageService;

    @Autowired
    public PaymentAgreementService(PaymentAgreementRepository agreementRepository,
                                    AgreementInstallmentRepository installmentRepository,
                                    InvoiceRepository invoiceRepository,
                                    TenantProfileRepository profileRepository,
                                    UserRepository userRepository,
                                    PropertyMovementService propertyMovementService,
                                    FileStorageService fileStorageService) {
        this.agreementRepository = agreementRepository;
        this.installmentRepository = installmentRepository;
        this.invoiceRepository = invoiceRepository;
        this.profileRepository = profileRepository;
        this.userRepository = userRepository;
        this.propertyMovementService = propertyMovementService;
        this.fileStorageService = fileStorageService;
    }

    private String resolveActorEmail() {
        return SecurityContextHolder.getContext().getAuthentication().getName();
    }

    private UserEntity resolveCurrentUser() {
        return userRepository.findByLoginIdentifier(resolveActorEmail()).orElseThrow();
    }

    private String resolveOwnerId() {
        return TenantContext.resolveOwnerId(userRepository);
    }

    // ─── Tenant: request an agreement ───────────────────────────────────

    @Transactional
    public PaymentAgreementDTO requestAgreement(String invoiceId, BigDecimal requestedAmount,
                                                 String reason, String description) {
        UserEntity user = resolveCurrentUser();
        String ctx = TenantContext.getCurrentOwner();
        if (ctx == null || ctx.isBlank()) {
            throw new RuntimeException("Seleccione la organización antes de solicitar un convenio.");
        }

        InvoiceEntity invoice = invoiceRepository.findById(invoiceId)
                .orElseThrow(() -> new RuntimeException("Factura no encontrada."));

        if (!ctx.equals(invoice.getOwnerId())) {
            throw new RuntimeException("La factura no pertenece al contexto actual.");
        }

        TenantProfileEntity profile = profileRepository.findById(invoice.getTenantProfileId())
                .orElseThrow(() -> new RuntimeException("Perfil de inquilino no encontrado."));
        if (!profile.getUserId().equals(user.getId())) {
            throw new RuntimeException("La factura no corresponde a su cuenta.");
        }

        if ("PAID".equals(invoice.getStatus())) {
            throw new RuntimeException("La factura ya está pagada.");
        }

        PaymentAgreementEntity agreement = new PaymentAgreementEntity();
        agreement.setOwnerId(invoice.getOwnerId());
        agreement.setTenantProfileId(profile.getId());
        agreement.setLeaseId(invoice.getLeaseId());
        agreement.setInvoiceId(invoiceId);
        agreement.setRequestedAmount(requestedAmount);
        agreement.setReason(reason);
        agreement.setDescription(description);
        agreement.setStatus(PaymentAgreementStatus.REQUESTED);

        agreementRepository.save(agreement);
        logger.info("[CONVENIO] Tenant {} requested agreement for invoice {} — amount: {}",
                user.getLoginUsername(), invoiceId, requestedAmount);

        recordAgreementMovement(agreement, invoice, PropertyMovementEventType.AGREEMENT_REQUESTED,
                "Convenio solicitado", "Monto solicitado: " + requestedAmount, user.getId(), user.getRole().name(), null);

        return mapToDTO(agreement);
    }

    // ─── Tenant: get my agreements ──────────────────────────────────────

    public List<PaymentAgreementDTO> getMyAgreements(String tenantProfileIdParam) {
        UserEntity user = resolveCurrentUser();
        String ctx = TenantContext.getCurrentOwner();
        if (ctx == null || ctx.isBlank()) {
            return List.of();
        }
        List<TenantProfileEntity> profiles = profileRepository.findByUserIdAndOwnerIdAndArchivedAtIsNull(user.getId(), ctx);
        if (tenantProfileIdParam == null || tenantProfileIdParam.isBlank()) {
            if (profiles.size() > 1) {
                throw new RuntimeException("Indique el expediente (tenantProfileId): hay más de uno en esta organización.");
            }
        } else {
            TenantProfileEntity p = profileRepository.findByIdAndUserId(tenantProfileIdParam, user.getId())
                    .orElseThrow(() -> new RuntimeException("Expediente no válido."));
            if (p.getArchivedAt() != null || !ctx.equals(p.getOwnerId())) {
                throw new RuntimeException("Expediente no válido para este contexto.");
            }
            profiles = List.of(p);
        }
        return profiles.stream()
                .flatMap(p -> agreementRepository.findByTenantProfileId(p.getId()).stream())
                .map(this::mapToDTO)
                .collect(Collectors.toList());
    }

    // ─── Owner: get pending agreements ──────────────────────────────────

    public List<PaymentAgreementDTO> getPendingAgreements() {
        String ownerId = resolveOwnerId();
        return agreementRepository.findByOwnerIdAndStatus(ownerId, PaymentAgreementStatus.REQUESTED).stream()
                .map(this::mapToDTO)
                .collect(Collectors.toList());
    }

    /**
     * V67 — Convenios de un tenant_profile específico, para el expediente del
     * inquilino. Sólo owners/staff de la organización del tenant pueden ver.
     */
    public List<PaymentAgreementDTO> getAgreementsByTenantProfile(String tenantProfileId) {
        if (tenantProfileId == null || tenantProfileId.isBlank()) {
            throw new IllegalArgumentException("tenantProfileId requerido.");
        }
        String ownerId = resolveOwnerId();
        TenantProfileEntity profile = profileRepository.findById(tenantProfileId)
                .orElseThrow(() -> new RuntimeException("Expediente no encontrado."));
        if (!ownerId.equals(profile.getOwnerId())) {
            throw new SecurityException("Este expediente no pertenece a tu organización.");
        }
        return agreementRepository.findByTenantProfileId(tenantProfileId).stream()
                .map(this::mapToDTO)
                .collect(Collectors.toList());
    }

    // ─── Owner/Admin: get all agreements ────────────────────────────────

    /**
     * Lista todos los convenios del owner con auto-sanación:
     * <p>
     * Si un convenio está ligado a una factura ahora en estado VOID/CANCELLED (por ejemplo porque
     * el arrendatario fue dado de baja y se barrieron sus invoices abiertas), o si el expediente
     * asociado fue archivado o su usuario desactivado, el convenio deja de tener sustento económico
     * y se marca CANCELLED en línea. Esto evita que la pestaña "Convenios" de un inmueble muestre
     * un convenio ACTIVE cuando la cobranza del mismo periodo ya fue anulada.
     * <p>
     * Regla reiterada: la validación humana (dueño) define si un convenio pasa de REQUESTED a
     * APPROVED/ACTIVE; si el dueño nunca lo valida, se queda en REQUESTED (nunca ACTIVE). Esta
     * rutina solo cierra convenios cuyo soporte (invoice/tenant) ya no existe; nunca promueve
     * estados ni toma decisiones de aprobación.
     */
    @Transactional
    public List<PaymentAgreementDTO> getAllAgreements() {
        String ownerId = resolveOwnerId();
        List<PaymentAgreementEntity> ags = agreementRepository.findByOwnerId(ownerId);
        for (PaymentAgreementEntity ag : ags) {
            if (isTerminalStatus(ag.getStatus())) {
                continue;
            }
            boolean orphan = false;
            if (ag.getInvoiceId() != null) {
                InvoiceEntity inv = invoiceRepository.findById(ag.getInvoiceId()).orElse(null);
                if (inv == null) {
                    orphan = true;
                } else {
                    String s = inv.getStatus() == null ? "" : inv.getStatus().toUpperCase();
                    if ("VOID".equals(s) || "VOIDED".equals(s) || "CANCELLED".equals(s) || "CANCELED".equals(s)) {
                        orphan = true;
                    }
                }
            }
            if (!orphan && ag.getTenantProfileId() != null) {
                TenantProfileEntity tp = profileRepository.findById(ag.getTenantProfileId()).orElse(null);
                if (tp == null || tp.getArchivedAt() != null) {
                    orphan = true;
                } else if (tp.getUserId() != null) {
                    UserEntity u = userRepository.findById(tp.getUserId()).orElse(null);
                    if (u == null || !u.isActive()) {
                        orphan = true;
                    }
                }
            }
            if (orphan) {
                ag.setStatus(PaymentAgreementStatus.CANCELLED);
                ag.setRejectionReason(ag.getRejectionReason() != null
                        ? ag.getRejectionReason()
                        : "Cancelado automáticamente: la factura o el expediente asociado ya no existe.");
                agreementRepository.save(ag);
                logger.info("[CONVENIO] self-heal: agreement {} cancelled (invoice void or tenant archived/inactive)",
                        ag.getId());
            }
        }
        return ags.stream()
                .map(this::mapToDTO)
                .collect(Collectors.toList());
    }

    private static boolean isTerminalStatus(PaymentAgreementStatus s) {
        return s == PaymentAgreementStatus.CANCELLED
                || s == PaymentAgreementStatus.REJECTED
                || s == PaymentAgreementStatus.COMPLETED;
    }

    // ─── Owner: approve agreement ───────────────────────────────────────

    @Transactional
    public PaymentAgreementDTO approveAgreement(String agreementId, BigDecimal approvedAmount,
                                                 List<InstallmentInput> installments) {
        String ownerId = resolveOwnerId();
        PaymentAgreementEntity ag = agreementRepository.findById(agreementId)
                .orElseThrow(() -> new RuntimeException("Convenio no encontrado."));

        if (!ag.getOwnerId().equals(ownerId)) {
            throw new RuntimeException("No tiene permisos para aprobar este convenio.");
        }
        if (ag.getStatus() != PaymentAgreementStatus.REQUESTED) {
            throw new RuntimeException("Este convenio ya fue procesado.");
        }

        // Calculate deferred
        InvoiceEntity invoice = invoiceRepository.findById(ag.getInvoiceId()).orElse(null);
        BigDecimal totalOwed = invoice != null
                ? (invoice.getOutstandingAmount() != null ? invoice.getOutstandingAmount() : invoice.getTotalAmount())
                : ag.getRequestedAmount();
        BigDecimal deferred = totalOwed.subtract(approvedAmount);

        ag.setApprovedAmount(approvedAmount);
        ag.setDeferredAmount(deferred.compareTo(BigDecimal.ZERO) > 0 ? deferred : BigDecimal.ZERO);
        ag.setStatus(PaymentAgreementStatus.APPROVED);
        ag.setApprovedAt(LocalDateTime.now());
        ag.setApprovedBy(resolveActorEmail());
        agreementRepository.save(ag);

        // Create installments if provided
        if (installments != null && !installments.isEmpty()) {
            for (InstallmentInput input : installments) {
                AgreementInstallmentEntity inst = new AgreementInstallmentEntity();
                inst.setAgreementId(agreementId);
                inst.setDueDate(input.dueDate());
                inst.setAmount(input.amount());
                inst.setStatus(InstallmentStatus.PENDING);
                installmentRepository.save(inst);
            }
            // Mark agreement as ACTIVE once installments are created
            ag.setStatus(PaymentAgreementStatus.ACTIVE);
            agreementRepository.save(ag);
        }

        logger.info("[CONVENIO] Owner {} approved agreement {} — approved: {}, deferred: {}",
                resolveActorEmail(), agreementId, approvedAmount, ag.getDeferredAmount());

        UserEntity ownerUser = resolveCurrentUser();
        InvoiceEntity invAfter = invoiceRepository.findById(ag.getInvoiceId()).orElse(null);
        recordAgreementMovement(ag, invAfter, PropertyMovementEventType.AGREEMENT_APPROVED,
                "Convenio aprobado", "Aprobado: " + approvedAmount + " | Diferido: " + ag.getDeferredAmount(),
                ownerUser.getId(), ownerUser.getRole().name(), null);

        return mapToDTO(ag);
    }

    // ─── Owner: reject agreement ────────────────────────────────────────

    @Transactional
    public PaymentAgreementDTO rejectAgreement(String agreementId, String rejectionReason) {
        String ownerId = resolveOwnerId();
        PaymentAgreementEntity ag = agreementRepository.findById(agreementId)
                .orElseThrow(() -> new RuntimeException("Convenio no encontrado."));

        if (!ag.getOwnerId().equals(ownerId)) {
            throw new RuntimeException("No tiene permisos para rechazar este convenio.");
        }

        ag.setStatus(PaymentAgreementStatus.REJECTED);
        ag.setRejectedAt(LocalDateTime.now());
        ag.setRejectedBy(resolveActorEmail());
        ag.setRejectionReason(rejectionReason);
        agreementRepository.save(ag);

        logger.info("[CONVENIO] Owner {} rejected agreement {}: {}", resolveActorEmail(), agreementId, rejectionReason);

        UserEntity ownerUser = resolveCurrentUser();
        InvoiceEntity invAfter = invoiceRepository.findById(ag.getInvoiceId()).orElse(null);
        recordAgreementMovement(ag, invAfter, PropertyMovementEventType.AGREEMENT_REJECTED,
                "Convenio rechazado", rejectionReason, ownerUser.getId(), ownerUser.getRole().name(), null);

        return mapToDTO(ag);
    }

    private void recordAgreementMovement(PaymentAgreementEntity agreement, InvoiceEntity invoice, String eventType,
                                         String title, String description, String actorUserId, String actorRole,
                                         String attachmentFileId) {
        if (invoice == null) {
            invoice = agreement.getInvoiceId() != null
                    ? invoiceRepository.findById(agreement.getInvoiceId()).orElse(null)
                    : null;
        }
        if (invoice == null) {
            return;
        }
        propertyMovementService.resolvePropertyIdForInvoice(invoice).ifPresent(propertyId ->
                propertyMovementService.record(
                        agreement.getOwnerId(),
                        propertyId,
                        "AGREEMENT",
                        agreement.getId(),
                        actorUserId,
                        actorRole,
                        eventType,
                        title,
                        description,
                        java.time.LocalDateTime.now(),
                        "{\"agreementId\":\"" + agreement.getId() + "\",\"invoiceId\":\"" + agreement.getInvoiceId() + "\"}",
                        attachmentFileId));
    }

    /** Called when agreement is breached (e.g. from scheduled job). */
    public void recordAgreementBreached(PaymentAgreementEntity agreement, InvoiceEntity invoice) {
        recordAgreementMovement(agreement, invoice, PropertyMovementEventType.AGREEMENT_BREACHED,
                "Convenio incumplido", "El convenio pasó a estado BREACHED.", null, "SYSTEM", null);
    }

    @Transactional
    public PaymentAgreementDTO attachEvidence(String agreementId, MultipartFile file) {
        String ownerId = resolveOwnerId();
        PaymentAgreementEntity ag = agreementRepository.findById(agreementId)
                .orElseThrow(() -> new RuntimeException("Convenio no encontrado."));
        if (!ag.getOwnerId().equals(ownerId)) {
            throw new RuntimeException("No tiene permisos.");
        }
        if (file != null && !file.isEmpty()) {
            String path = fileStorageService.store(file, "agreements/" + agreementId);
            ag.setEvidenceFileUrl(path);
        } else {
            ag.setEvidenceFileUrl(null);
        }
        agreementRepository.save(ag);
        UserEntity ownerUser = resolveCurrentUser();
        InvoiceEntity inv = invoiceRepository.findById(ag.getInvoiceId()).orElse(null);
        recordAgreementMovement(ag, inv, PropertyMovementEventType.AGREEMENT_EVIDENCE,
                "Evidencia de convenio actualizada",
                ag.getEvidenceFileUrl() != null ? ag.getEvidenceFileUrl() : "Sin archivo",
                ownerUser.getId(), ownerUser.getRole().name(), null);
        return mapToDTO(ag);
    }

    // ─── Mapper ─────────────────────────────────────────────────────────

    private PaymentAgreementDTO mapToDTO(PaymentAgreementEntity entity) {
        PaymentAgreementDTO dto = new PaymentAgreementDTO();
        dto.setId(entity.getId());
        dto.setOwnerId(entity.getOwnerId());
        dto.setTenantProfileId(entity.getTenantProfileId());
        dto.setLeaseId(entity.getLeaseId());
        dto.setInvoiceId(entity.getInvoiceId());
        dto.setRequestedAmount(entity.getRequestedAmount());
        dto.setApprovedAmount(entity.getApprovedAmount());
        dto.setDeferredAmount(entity.getDeferredAmount());
        dto.setReason(entity.getReason());
        dto.setDescription(entity.getDescription());
        dto.setEvidenceFileUrl(entity.getEvidenceFileUrl());
        dto.setStatus(entity.getStatus().name());
        dto.setCreatedAt(entity.getCreatedAt() != null ? entity.getCreatedAt().toString() : null);
        dto.setApprovedAt(entity.getApprovedAt() != null ? entity.getApprovedAt().toString() : null);
        dto.setApprovedBy(entity.getApprovedBy());
        dto.setRejectedAt(entity.getRejectedAt() != null ? entity.getRejectedAt().toString() : null);
        dto.setRejectedBy(entity.getRejectedBy());
        dto.setRejectionReason(entity.getRejectionReason());

        // Enrich tenant info
        profileRepository.findById(entity.getTenantProfileId()).ifPresent(profile -> {
            userRepository.findById(profile.getUserId()).ifPresent(user -> {
                dto.setTenantName(user.getName());
                dto.setTenantEmail(user.getContactEmail());
            });
        });

        // Enrich month from invoice
        if (entity.getInvoiceId() != null) {
            invoiceRepository.findById(entity.getInvoiceId()).ifPresent(inv -> {
                dto.setMonthYear(inv.getMonthYear());
            });
        }

        // Installments
        List<AgreementInstallmentEntity> installments = installmentRepository.findByAgreementId(entity.getId());
        dto.setInstallments(installments.stream().map(inst -> {
            AgreementInstallmentDTO idto = new AgreementInstallmentDTO();
            idto.setId(inst.getId());
            idto.setAgreementId(inst.getAgreementId());
            idto.setDueDate(inst.getDueDate().toString());
            idto.setAmount(inst.getAmount());
            idto.setStatus(inst.getStatus().name());
            idto.setPaidAt(inst.getPaidAt() != null ? inst.getPaidAt().toString() : null);
            idto.setPaymentId(inst.getPaymentId());
            return idto;
        }).collect(Collectors.toList()));

        return dto;
    }

    // ─── Input record ───────────────────────────────────────────────────

    public record InstallmentInput(LocalDate dueDate, BigDecimal amount) {}

    /**
     * Cron diario (02:00 CDMX): marca cuotas vencidas como LATE y convenios como BREACHED
     * cuando hay cuotas con más de 14 días de mora.
     * <p>
     * Se usa {@code LocalDate.now(ZoneId.of("America/Mexico_City"))} para que el "hoy" sea el
     * día calendario de CDMX — jamás el del contenedor en UTC, ni el del reloj del SO del
     * desarrollador. Así, por mucho que alguien adelante el reloj del servidor, el cron no
     * adelanta arbitrariamente el vencimiento de cuotas ni marca BREACHED antes de tiempo.
     */
    @Scheduled(cron = "0 0 2 * * ?", zone = "America/Mexico_City")
    @Transactional
    public void installmentAndBreachCron() {
        LocalDate today = LocalDate.now(java.time.ZoneId.of("America/Mexico_City"));
        for (AgreementInstallmentEntity inst : installmentRepository.findByStatusAndDueDateBefore(InstallmentStatus.PENDING, today)) {
            inst.setStatus(InstallmentStatus.LATE);
            installmentRepository.save(inst);
        }
        for (PaymentAgreementEntity ag : agreementRepository.findByStatus(PaymentAgreementStatus.ACTIVE)) {
            List<AgreementInstallmentEntity> insts = installmentRepository.findByAgreementId(ag.getId());
            boolean breach = insts.stream().anyMatch(i ->
                    (i.getStatus() == InstallmentStatus.PENDING || i.getStatus() == InstallmentStatus.LATE)
                            && i.getDueDate() != null
                            && i.getDueDate().plusDays(14).isBefore(today));
            if (breach) {
                ag.setStatus(PaymentAgreementStatus.BREACHED);
                agreementRepository.save(ag);
                InvoiceEntity inv = ag.getInvoiceId() != null ? invoiceRepository.findById(ag.getInvoiceId()).orElse(null) : null;
                recordAgreementBreached(ag, inv);
                logger.warn("[CONVENIO] Agreement {} marked BREACHED by cron", ag.getId());
            }
        }
    }
}
