package com.admindi.backend.service;

import com.admindi.backend.ai.AiAccountingService;
import com.admindi.backend.dto.InvoiceDTO;
import com.admindi.backend.dto.PaymentDTO;
import com.admindi.backend.dto.ShortfallSubmitResultDTO;
import com.admindi.backend.dto.TransferProofDTO;
import com.admindi.backend.model.*;
import com.admindi.backend.repository.*;
import com.admindi.backend.security.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
public class LedgerService {

    private static final Logger logger = LoggerFactory.getLogger(LedgerService.class);

    private final InvoiceRepository invoiceRepository;
    private final TenantProfileRepository profileRepository;
    private final UserRepository userRepository;
    private final FileStorageService fileStorageService;
    private final PaymentRepository paymentRepository;
    private final TransferProofSubmissionRepository proofRepository;
    private final DomainEventDispatcher dispatcher;
    private final BanxicoCepAdapter cepAdapter;
    private final PropertyMovementService propertyMovementService;
    private final LeaseRepository leaseRepository;
    private final PaymentAgreementRepository paymentAgreementRepository;
    private final ReportingPeriodService reportingPeriodService;
    private final PropertyRepository propertyRepository;
    private final AiAccountingService aiAccountingService;
    private final PaymentProofArchiver proofArchiver;
    private final BanxicoInstitutionCatalogService banxicoInstitutionCatalogService;

    @Value("${app.url:http://localhost:3000}")
    private String appUrl;

    @Autowired
    public LedgerService(InvoiceRepository invoiceRepository,
                         TenantProfileRepository profileRepository,
                         UserRepository userRepository,
                         FileStorageService fileStorageService,
                         PaymentRepository paymentRepository,
                         TransferProofSubmissionRepository proofRepository,
                         DomainEventDispatcher dispatcher,
                         BanxicoCepAdapter cepAdapter,
                         PropertyMovementService propertyMovementService,
                         LeaseRepository leaseRepository,
                         PaymentAgreementRepository paymentAgreementRepository,
                         ReportingPeriodService reportingPeriodService,
                         PropertyRepository propertyRepository,
                         AiAccountingService aiAccountingService,
                         PaymentProofArchiver proofArchiver,
                         BanxicoInstitutionCatalogService banxicoInstitutionCatalogService) {
        this.invoiceRepository = invoiceRepository;
        this.profileRepository = profileRepository;
        this.userRepository = userRepository;
        this.fileStorageService = fileStorageService;
        this.paymentRepository = paymentRepository;
        this.proofRepository = proofRepository;
        this.dispatcher = dispatcher;
        this.cepAdapter = cepAdapter;
        this.propertyMovementService = propertyMovementService;
        this.leaseRepository = leaseRepository;
        this.paymentAgreementRepository = paymentAgreementRepository;
        this.reportingPeriodService = reportingPeriodService;
        this.propertyRepository = propertyRepository;
        this.aiAccountingService = aiAccountingService;
        this.proofArchiver = proofArchiver;
        this.banxicoInstitutionCatalogService = banxicoInstitutionCatalogService;
    }

    // ─── Context Resolvers ──────────────────────────────────────────────

    private String resolveOwnerId() {
        return TenantContext.resolveOwnerId(userRepository);
    }

    private String resolveActorEmail() {
        return SecurityContextHolder.getContext().getAuthentication().getName();
    }

    // ─── V58.1 — Authorization Guards (IDOR prevention) ─────────────────

    /**
     * Garantiza que el {@code tenant} autenticado es dueño del TenantProfile
     * asociado a la {@code invoice}. Lanza si no — previene IDOR donde un
     * inquilino con un invoiceId ajeno sube comprobante o toca proofs de otro.
     *
     * <p>Aplicable a: submitTransferProof, submitCashPaymentProof, completeProofData.
     */
    private void enforceTenantOwnsInvoice(UserEntity tenant, InvoiceEntity invoice) {
        if (tenant == null || invoice == null) {
            throw new SecurityException("Contexto de autenticación inválido.");
        }
        if (invoice.getTenantProfileId() == null) {
            throw new SecurityException("Factura sin expediente asociado.");
        }
        TenantProfileEntity profile = profileRepository.findById(invoice.getTenantProfileId())
                .orElseThrow(() -> new SecurityException("Expediente no encontrado."));
        if (profile.getArchivedAt() != null) {
            throw new SecurityException("Expediente archivado; no se pueden registrar pagos.");
        }
        if (!tenant.getId().equals(profile.getUserId())) {
            logger.warn("[IDOR-BLOCK] tenant={} intentó operar sobre factura {} del tenantProfile {} (owner real user={})",
                    tenant.getId(), invoice.getId(), profile.getId(), profile.getUserId());
            throw new SecurityException("IDOR: esta factura no te pertenece.");
        }
    }

    /**
     * Garantiza que el {@code actor} autenticado es el OWNER (o PROPERTY_ADMIN
     * del mismo owner) dueño del {@code proof}. Usado por overrideTransferProof.
     */
    private void enforceOwnerControlsProof(UserEntity actor, TransferProofSubmission proof) {
        if (actor == null || proof == null) {
            throw new SecurityException("Contexto de autenticación inválido.");
        }
        String actorOwnerId = actor.getOwnerId() != null ? actor.getOwnerId() : actor.getId();
        if (!actorOwnerId.equals(proof.getOwnerId())) {
            logger.warn("[IDOR-BLOCK] actor={} (owner={}) intentó operar sobre proof {} del owner {}",
                    actor.getId(), actorOwnerId, proof.getId(), proof.getOwnerId());
            throw new SecurityException("IDOR: este comprobante no pertenece a tu organización.");
        }
    }

    // ─── Invoice Queries ────────────────────────────────────────────────

    public List<InvoiceDTO> getInvoicesForMyOrganization() {
        String ownerId = resolveOwnerId();
        return invoiceRepository.findByOwnerId(ownerId).stream()
                .map(this::mapInvoiceToDTO)
                .collect(Collectors.toList());
    }

    public List<InvoiceDTO> getMyInvoicesAsTenant(String tenantProfileIdParam) {
        String email = SecurityContextHolder.getContext().getAuthentication().getName();
        UserEntity tenant = userRepository.findByLoginIdentifier(email).orElseThrow();

        String ctxOwner = TenantContext.getCurrentOwner();
        if (ctxOwner == null || ctxOwner.isBlank()) {
            throw new RuntimeException("Seleccione la organización (contexto) antes de ver facturas.");
        }
        List<TenantProfileEntity> profiles = resolveTenantProfilesForContext(tenant, ctxOwner, tenantProfileIdParam);
        return profiles.stream()
                .flatMap(profile -> invoiceRepository.findByTenantProfileId(profile.getId()).stream())
                .map(this::mapInvoiceToDTO)
                .collect(Collectors.toList());
    }

    /**
     * Baja operativa: anula la factura abierta del periodo calendario actual para el expediente (no ingreso esperado).
     * @deprecated Usar {@link #voidAllOpenInvoicesForTenant(String, String)} que barre todo el expediente y cierra
     *             fugas cuando existen facturas de meses anteriores en estado PENDING/LATE/PARTIALLY_PAID.
     */
    @Deprecated
    @Transactional
    public void voidOpenInvoiceForCurrentPeriod(String tenantProfileId, String ownerId) {
        voidAllOpenInvoicesForTenant(tenantProfileId, ownerId);
    }

    /**
     * Al dar de baja un expediente: anula TODAS las facturas abiertas (no pagadas) del inquilino —
     * periodo actual, periodos anteriores o generadas previamente por el cron — para que la contabilidad
     * no arrastre "rentas ligadas" fantasma a un expediente archivado. Solo se conservan las facturas
     * en estado PAID porque representan ingresos reales ya cobrados (historial financiero inmutable).
     */
    @Transactional
    public void voidAllOpenInvoicesForTenant(String tenantProfileId, String ownerId) {
        List<InvoiceEntity> all = invoiceRepository.findByTenantProfileId(tenantProfileId);
        for (InvoiceEntity inv : all) {
            if (!ownerId.equals(inv.getOwnerId())) {
                continue;
            }
            String s = inv.getStatus() == null ? "" : inv.getStatus().toUpperCase();
            if ("PAID".equals(s) || "VOID".equals(s) || "VOIDED".equals(s) || "CANCELLED".equals(s) || "CANCELED".equals(s)) {
                continue;
            }
            inv.setStatus("VOID");
            inv.setSettlementStatus("CANCELLED");
            inv.setOutstandingAmount(BigDecimal.ZERO);
            invoiceRepository.save(inv);
        }
    }

    private List<TenantProfileEntity> resolveTenantProfilesForContext(UserEntity tenant, String ctxOwner,
                                                                      String tenantProfileIdParam) {
        List<TenantProfileEntity> inOwner = profileRepository.findByUserIdAndOwnerIdAndArchivedAtIsNull(
                tenant.getId(), ctxOwner);
        if (tenantProfileIdParam == null || tenantProfileIdParam.isBlank()) {
            if (inOwner.size() > 1) {
                throw new RuntimeException("Indique el expediente (tenantProfileId): hay más de uno en esta organización.");
            }
            return new ArrayList<>(inOwner);
        }
        TenantProfileEntity p = profileRepository.findByIdAndUserId(tenantProfileIdParam, tenant.getId())
                .orElseThrow(() -> new RuntimeException("Expediente no válido."));
        if (p.getArchivedAt() != null || !ctxOwner.equals(p.getOwnerId())) {
            throw new RuntimeException("Expediente no válido para este contexto.");
        }
        return List.of(p);
    }

    // ─── Manual Payment (SUPER_ADMIN override — exceptional) ────────────

    /**
     * Manual payment override for exceptional cases (cash collection, etc.).
     * This is NOT the normal SPEI flow — it's reserved for SUPER_ADMIN or
     * explicit PAYMENT_APPLY authority.
     */
    @Transactional
    public void markAsPaidManual(String invoiceId, String paymentReference, String paymentNotes, String paymentMethodStr) {
        markAsPaidManual(invoiceId, paymentReference, paymentNotes, paymentMethodStr, null);
    }

    /**
     * Bloque 4 / Gap C: override manual de pago + referencia a comprobante. Se
     * preserva la firma sin paymentProofFileId para back-compat (controllers
     * antiguos siguen funcionando) y se añade una variante que publica el
     * archivo en el movement del inmueble para que aparezca en el expediente.
     */
    @Transactional
    public void markAsPaidManual(String invoiceId, String paymentReference, String paymentNotes,
                                 String paymentMethodStr, String paymentProofFileId) {
        String ownerId = resolveOwnerId();
        String actor = resolveActorEmail();

        InvoiceEntity invoice = invoiceRepository.findById(invoiceId)
                .orElseThrow(() -> new RuntimeException("Factura no encontrada."));

        if (!invoice.getOwnerId().equals(ownerId)) {
            throw new RuntimeException("IDOR: Factura no pertenece a esta organización.");
        }

        PaymentMethod method = PaymentMethod.CASH;
        if (paymentMethodStr != null) {
            try { method = PaymentMethod.valueOf(paymentMethodStr); } catch (Exception ignored) {}
        }

        PaymentEntity payment = new PaymentEntity();
        payment.setInvoiceId(invoiceId);
        payment.setOwnerId(ownerId);
        payment.setTenantProfileId(invoice.getTenantProfileId());
        payment.setAmount(invoice.getTotalAmount());
        payment.setAppliedAmount(invoice.getTotalAmount());
        payment.setUnappliedAmount(BigDecimal.ZERO);
        payment.setPaymentMethod(method);
        payment.setGatewayReference(paymentReference);
        payment.setStatus(PaymentStatus.CONFIRMED);
        payment.setPaidAt(LocalDateTime.now());
        payment.setConfirmedBy(actor);
        payment.setConfirmedAt(LocalDateTime.now());
        payment.setNotes(paymentNotes != null ? "[OVERRIDE MANUAL] " + paymentNotes : "[OVERRIDE MANUAL]");
        paymentRepository.save(payment);

        invoice.setStatus("PAID");
        invoice.setPaidDate(LocalDate.now());
        invoice.setSettlementStatus("PAID");
        invoice.setOutstandingAmount(BigDecimal.ZERO);
        invoice.setPaidAmount(invoice.getTotalAmount());
        invoice.setPaymentMethod(method);
        if (paymentReference != null) invoice.setPaymentReference(paymentReference);
        if (paymentNotes != null) invoice.setPaymentNotes(paymentNotes);
        invoiceRepository.save(invoice);

        // Gap C fix: antes del override manual no generábamos movement en el
        // inmueble; el expediente no reflejaba el pago cuando venía por esta vía.
        propertyMovementService.recordPaymentMovement(invoice, payment, paymentProofFileId);

        dispatcher.dispatch("PAYMENT_MANUAL_OVERRIDE",
                "Pago manual (override): " + invoice.getMonthYear(),
                "Método: " + method + " | Actor: " + actor,
                ownerId, actor, null);

        logger.warn("[Ledger] MANUAL OVERRIDE payment for invoice {} by {} proofFile={}",
                invoiceId, actor, paymentProofFileId);
    }

    // ─── Transfer Proof: Automatic CEP Flow ─────────────────────────────

    /**
     * Core SPEI flow: tenant submits proof → system validates via CEP automatically.
     * 
     * If CEP validates: payment is auto-confirmed, invoice is auto-paid.
     * If data is missing: proof is marked INCOMPLETE_DATA → tenant must complete.
     * If CEP rejects: proof is marked REJECTED_BY_CEP.
     * 
     * Owner/Admin is NOTIFIED of the outcome, never asked to manually approve.
     */
    @Transactional
    public TransferProofDTO submitTransferProof(String invoiceId, String claveRastreo, String bankEmitter,
                                                 BigDecimal amount, LocalDate transferDate,
                                                 String captureMethod, MultipartFile file) {
        String fileUrl = (file != null && !file.isEmpty())
                ? fileStorageService.storeFile(file) : null;
        return submitTransferProofWithFileUrl(invoiceId, claveRastreo, bankEmitter,
                amount, transferDate, fileUrl, captureMethod);
    }

    /**
     * V58 — Flujo SPEI. Diseño refinado:
     * <ul>
     *   <li>La cuenta receptora (CLABE) <b>nunca</b> la captura el inquilino.
     *       Se toma de {@code owner.clabe} (cifrada en users.clabe).</li>
     *   <li>Si el dueño no tiene CLABE configurada → el comprobante queda
     *       {@code PENDING_OWNER_VALIDATION} (120h para que el dueño valide
     *       manualmente). Mensaje claro al inquilino.</li>
     *   <li>Si la CLABE que Banxico devuelve para el comprobante NO coincide
     *       con la del dueño → rechazo inmediato con motivo
     *       "account_mismatch" y mensaje claro.</li>
     *   <li>Límite mensual AI_OCR: 6/mes por inquilino. MANUAL: ilimitado.</li>
     * </ul>
     *
     * @param captureMethod "AI_OCR" (el user subió foto y la IA extrajo datos)
     *                      o "MANUAL" (el user tecleó los datos). Si es null
     *                      asumimos MANUAL para no bloquear al inquilino.
     */
    @Transactional
    public TransferProofDTO submitTransferProofWithFileUrl(String invoiceId, String claveRastreo,
                                                             String bankEmitter,
                                                             BigDecimal amount, LocalDate transferDate,
                                                             String fileUrl, String captureMethod) {
        String email = resolveActorEmail();
        UserEntity user = userRepository.findByLoginIdentifier(email).orElseThrow();

        InvoiceEntity invoice = invoiceRepository.findById(invoiceId)
                .orElseThrow(() -> new RuntimeException("Factura no encontrada."));

        // V58.1 — GUARD IDOR CRÍTICO: el tenant autenticado debe ser dueño del
        // tenantProfile asociado a la factura. Sin esto cualquier TENANT con
        // un invoiceId ajeno podría subir comprobante contra esa factura.
        enforceTenantOwnsInvoice(user, invoice);

        // V58 — Normalizar captureMethod. Si el frontend no mandó nada asumimos
        // MANUAL (lo más permisivo) para no rechazar por falta de metadata.
        String normalizedMethod = "AI_OCR".equalsIgnoreCase(captureMethod) ? "AI_OCR" : "MANUAL";

        // V58.1 — Detección de duplicados por claveRastreo dentro de la misma
        // organización. Protege contra doble aplicación del mismo SPEI real.
        if (claveRastreo != null && !claveRastreo.isBlank()) {
            boolean duplicate = proofRepository
                    .existsValidatedDuplicateClaveRastreo(invoice.getOwnerId(), claveRastreo);
            if (duplicate) {
                throw new RuntimeException(
                        "Esta clave de rastreo ya fue aplicada a un pago. "
                        + "Cada transferencia SPEI solo se puede registrar una vez.");
            }
        }

        // V58 — Verificar límite mensual SOLO para AI_OCR. MANUAL es ilimitado.
        if ("AI_OCR".equals(normalizedMethod)) {
            java.time.LocalDateTime monthStart = java.time.LocalDate.now()
                    .withDayOfMonth(1).atStartOfDay();
            java.time.LocalDateTime nextMonthStart = monthStart.plusMonths(1);
            long aiOcrThisMonth = proofRepository.countSpeiSubmissionsInWindow(
                    invoice.getTenantProfileId(), "AI_OCR", monthStart, nextMonthStart);
            PaymentProofAttemptPolicy.Decision monthlyDecision =
                    PaymentProofAttemptPolicy.evaluateSpeiAiOcrMonthly(aiOcrThisMonth);
            if (!monthlyDecision.allowed()) {
                throw new RuntimeException(
                        PaymentProofAttemptPolicy.userFriendlyDenyMessage(monthlyDecision.denyReason()));
            }
        }

        // V58 — Obtener CLABE del dueño. Si no tiene, NO podemos validar con
        // CEP (Banxico exige cuenta receptora) — caemos a flujo manual.
        UserEntity owner = userRepository.findById(invoice.getOwnerId())
                .orElseThrow(() -> new RuntimeException("Dueño de la factura no encontrado."));
        String ownerClabe = owner.getClabe();
        boolean ownerHasClabe = ownerClabe != null && !ownerClabe.isBlank();

        // Calcular attempt_number a partir del histórico (para trazabilidad, no limita)
        long priorSubmissions = proofRepository.countByInvoiceIdAndPaymentType(invoiceId, "SPEI");
        int attemptNumber = (int) priorSubmissions + 1;

        TransferProofSubmission proof = new TransferProofSubmission();
        proof.setInvoiceId(invoiceId);
        proof.setOwnerId(invoice.getOwnerId());
        proof.setTenantProfileId(invoice.getTenantProfileId());
        proof.setFileUrl(fileUrl);
        proof.setClaveRastreo(claveRastreo);
        proof.setBankEmitter(normalizeBankEmitterOrThrow(bankEmitter));
        // V58 — accountReceiver viene SIEMPRE del dueño, no del inquilino
        proof.setAccountReceiver(ownerClabe);
        proof.setAmount(amount);
        proof.setTransferDate(transferDate);
        proof.setPaymentType("SPEI");
        proof.setAttemptNumber(attemptNumber);
        proof.setCaptureMethod(normalizedMethod);

        // V58 — Si el dueño no tiene CLABE, no podemos llamar a Banxico. El
        // comprobante queda PENDING_OWNER_VALIDATION por 120h igual que CASH.
        if (!ownerHasClabe) {
            proof.setStatus(TransferProofStatus.PENDING_OWNER_VALIDATION);
            proof.setExpiresAt(java.time.LocalDateTime.now().plusHours(120));
            proof.setOwnerValidationNotes("[AUTO] Dueño sin CLABE configurada — validación manual obligatoria");
            proofRepository.save(proof);

            notifyOwnerPaymentPendingValidation(invoice, proof, email, 120,
                    "SPEI sin CLABE del dueño — validación manual obligatoria.");

            invoice.setProofOfPaymentUrl(fileUrl);
            invoice.setPaymentReference(claveRastreo);
            invoice.setTenantUploadDate(LocalDateTime.now());
            invoiceRepository.save(invoice);

            logger.info("[SPEI-NO-CLABE] Proof {} queued for manual owner validation (invoice {})",
                    proof.getId(), invoiceId);
            return mapProofToDTO(proof, invoice);
        }

        proof.setStatus(TransferProofStatus.RECEIVED);

        // ─── Persistir ANTES de llamar al CEP ────────────────────────────
        // Bug V58: cepAdapter crea CepValidationAttempt con FK a este proof.
        // Si no lo guardamos primero, PostgreSQL rechaza el INSERT del attempt
        // con violación de FK y aborta la transacción entera (400 al user).
        proofRepository.save(proof);

        // ─── AUTOMATIC CEP VALIDATION ───
        BanxicoCepAdapter.CepValidationResult cepResult = cepAdapter.validate(proof);

        if (cepResult.isValid()) {
            // CEP validated → auto-confirm payment
            hydrateProofFromCepValidation(proof, cepResult);
            storeCepArtifacts(proof, invoice, cepResult);
            proof.setStatus(TransferProofStatus.VALIDATED);
            proof.setReviewedAt(LocalDateTime.now());
            proof.setReviewedBy("SISTEMA_CEP");
            proofRepository.save(proof);

            autoConfirmPayment(invoice, proof);

            notifyOwnerPaymentAutoValidated(invoice, proof);

            logger.info("[CEP-AUTO] Payment auto-confirmed for invoice {} via CEP", invoiceId);

        } else if (!cepResult.getMissingFields().isEmpty()) {
            // Missing data → tenant must complete
            proof.setStatus(TransferProofStatus.INCOMPLETE_DATA);
            proof.setMissingFields("[\"" + String.join("\",\"", cepResult.getMissingFields()) + "\"]");
            proofRepository.save(proof);

            dispatcher.dispatch("TRANSFER_PROOF_INCOMPLETE",
                    "Comprobante SPEI con datos faltantes: " + invoice.getMonthYear(),
                    "Campos faltantes: " + cepResult.getMissingFields(),
                    invoice.getOwnerId(), email, null);

            logger.info("[CEP-AUTO] Incomplete data for invoice {}: {}", invoiceId, cepResult.getMissingFields());

        } else if ("__SERVICE_UNAVAILABLE__".equals(cepResult.getMessage())) {
            // V58 fix — Banxico no respondió (HTTP error / red / timeout).
            // NO es rechazo del pago, es problema temporal nuestro → el proof
            // queda PENDING_OWNER_VALIDATION con 120h para que el dueño valide
            // manualmente. NO consume intento mensual AI del inquilino.
            proof.setStatus(TransferProofStatus.PENDING_OWNER_VALIDATION);
            proof.setExpiresAt(java.time.LocalDateTime.now().plusHours(120));
            proof.setOwnerValidationNotes(
                    "[AUTO] Banxico no disponible al momento de validar — revisión manual obligatoria");
            proofRepository.save(proof);

            notifyOwnerPaymentPendingValidation(invoice, proof, email, 120,
                    "Banxico no disponible — validación manual.");

            logger.warn("[CEP-UNAVAILABLE] Proof {} queued for manual owner validation (Banxico down)",
                    proof.getId());

        } else {
            // CEP rejected (all data present but validation failed)
            proof.setStatus(TransferProofStatus.REJECTED_BY_CEP);
            proof.setRejectionReason(cepResult.getMessage());
            proof.setReviewedAt(LocalDateTime.now());
            proof.setReviewedBy("SISTEMA_CEP");
            proofRepository.save(proof);

            dispatcher.dispatch("PAYMENT_CEP_REJECTED",
                    "CEP rechazó comprobante SPEI: " + invoice.getMonthYear(),
                    "Motivo: " + cepResult.getMessage(),
                    invoice.getOwnerId(), email, null);

            logger.warn("[CEP-AUTO] CEP rejected proof for invoice {}: {}", invoiceId, cepResult.getMessage());
        }

        // Update invoice proof reference (for traceability, not for manual review)
        invoice.setProofOfPaymentUrl(fileUrl);
        invoice.setPaymentReference(claveRastreo);
        invoice.setTenantUploadDate(LocalDateTime.now());
        invoiceRepository.save(invoice);

        return mapProofToDTO(proof, invoice);
    }

    // ─── CASH Payment Flow (V57) ───────────────────────────────────────
    //
    // El inquilino sube foto del recibo/comprobante de pago en efectivo.
    // OCR extrae solo el monto. El sistema NO valida contra Banxico (no aplica).
    // El comprobante queda PENDING_OWNER_VALIDATION con 120h para que el dueño
    // decida. Si aprueba, se aplica como pago. Si rechaza o expira, cuenta
    // como "fallo" según PaymentProofAttemptPolicy.
    //
    // Duración 120h: configurable vía admindi.payments.cash.owner-validation-hours.
    // ───────────────────────────────────────────────────────────────────────

    /**
     * Flujo CASH (efectivo): el inquilino sube comprobante, el dueño decide
     * en las siguientes 120 horas. Solo requiere monto; no hay validación
     * bancaria automática.
     */
    @Transactional
    public TransferProofDTO submitCashPaymentProof(String invoiceId, BigDecimal amount,
                                                     String fileUrl, String tenantNote) {
        String email = resolveActorEmail();
        UserEntity user = userRepository.findByLoginIdentifier(email).orElseThrow();

        InvoiceEntity invoice = invoiceRepository.findById(invoiceId)
                .orElseThrow(() -> new RuntimeException("Factura no encontrada."));

        // V58.1 — GUARD IDOR: mismo patrón que SPEI
        enforceTenantOwnsInvoice(user, invoice);

        // Validaciones de entrada
        if (amount == null || amount.signum() <= 0) {
            throw new RuntimeException("El monto del comprobante en efectivo es obligatorio y debe ser mayor a 0.");
        }
        if (fileUrl == null || fileUrl.isBlank()) {
            throw new RuntimeException("Debes adjuntar la foto del comprobante de pago en efectivo.");
        }

        // V57 — Control de intentos CASH.
        List<TransferProofSubmission> cashHistory =
                proofRepository.findByInvoiceIdAndPaymentType(invoiceId, "CASH");
        PaymentProofAttemptPolicy.Decision attempt =
                PaymentProofAttemptPolicy.evaluateCash(cashHistory);
        if (!attempt.allowed()) {
            throw new RuntimeException(
                    PaymentProofAttemptPolicy.userFriendlyDenyMessage(attempt.denyReason()));
        }

        int validationHours = 120; // 5 días para que el dueño valide

        TransferProofSubmission proof = new TransferProofSubmission();
        proof.setInvoiceId(invoiceId);
        proof.setOwnerId(invoice.getOwnerId());
        proof.setTenantProfileId(invoice.getTenantProfileId());
        proof.setFileUrl(fileUrl);
        proof.setAmount(amount);
        proof.setTransferDate(LocalDate.now());
        proof.setStatus(TransferProofStatus.PENDING_OWNER_VALIDATION);
        proof.setPaymentType("CASH");
        proof.setAttemptNumber(attempt.attemptNumber());
        proof.setExpiresAt(LocalDateTime.now().plusHours(validationHours));
        if (tenantNote != null && !tenantNote.isBlank()) {
            proof.setOwnerValidationNotes("[NOTA INQUILINO] " + tenantNote.trim());
        }
        proofRepository.save(proof);

        notifyOwnerPaymentPendingValidation(invoice, proof, email, validationHours,
                "Comprobante en efectivo pendiente de tu validación.");

        logger.info("[CASH] Tenant submitted cash proof for invoice {} amount {} (attempt {}/3)",
                invoiceId, amount, attempt.attemptNumber());

        return mapProofToDTO(proof, invoice);
    }

    /**
     * Tenant completes missing data → system retries CEP automatically.
     *
     * <p>V58.1 — El parámetro {@code accountReceiver} se IGNORA. La CLABE solo
     * puede venir del dueño (users.clabe). Permitir al inquilino cambiarla aquí
     * anularía la validación CEP vs owner.clabe del primer intento.
     */
    @Transactional
    public TransferProofDTO completeProofData(String proofId, String claveRastreo, String bankEmitter,
                                               String accountReceiver /* ignorado V58.1 */,
                                               BigDecimal amount, LocalDate transferDate) {
        String email = resolveActorEmail();
        UserEntity user = userRepository.findByLoginIdentifier(email).orElseThrow();

        TransferProofSubmission proof = proofRepository.findById(proofId)
                .orElseThrow(() -> new RuntimeException("Comprobante no encontrado."));

        // V58.1 — IDOR guard: resolver la factura y verificar que el inquilino
        // autenticado es dueño del expediente asociado.
        InvoiceEntity invoiceForGuard = invoiceRepository.findById(proof.getInvoiceId())
                .orElseThrow(() -> new RuntimeException("Factura del comprobante no encontrada."));
        enforceTenantOwnsInvoice(user, invoiceForGuard);

        if (proof.getStatus() != TransferProofStatus.INCOMPLETE_DATA) {
            throw new RuntimeException("El comprobante no requiere datos adicionales.");
        }

        if (claveRastreo != null) proof.setClaveRastreo(claveRastreo);
        if (bankEmitter != null) proof.setBankEmitter(normalizeBankEmitterOrThrow(bankEmitter));
        // V58.1 — accountReceiver NO se acepta del body. Si por alguna razón el
        // proof original no tiene accountReceiver (ej. creado cuando el owner no
        // tenía CLABE), lo re-resolvemos del owner actual para no quedar stale.
        if (proof.getAccountReceiver() == null || proof.getAccountReceiver().isBlank()) {
            userRepository.findById(proof.getOwnerId()).ifPresent(owner -> {
                if (owner.getClabe() != null && !owner.getClabe().isBlank()) {
                    proof.setAccountReceiver(owner.getClabe());
                }
            });
        }
        if (amount != null) proof.setAmount(amount);
        if (transferDate != null) proof.setTransferDate(transferDate);

        // ─── RETRY CEP AUTOMATICALLY ───
        BanxicoCepAdapter.CepValidationResult retry = cepAdapter.validate(proof);

        InvoiceEntity invoice = invoiceRepository.findById(proof.getInvoiceId()).orElse(null);

        if (retry.isValid()) {
            hydrateProofFromCepValidation(proof, retry);
            storeCepArtifacts(proof, invoice, retry);
            proof.setStatus(TransferProofStatus.VALIDATED);
            proof.setMissingFields(null);
            proof.setReviewedAt(LocalDateTime.now());
            proof.setReviewedBy("SISTEMA_CEP");
            proofRepository.save(proof);

            if (invoice != null) {
                autoConfirmPayment(invoice, proof);
                notifyOwnerPaymentAutoValidated(invoice, proof);
            }

            logger.info("[CEP-AUTO] Retry succeeded — auto-confirmed payment for proof {}", proofId);

        } else if (!retry.getMissingFields().isEmpty()) {
            // Still missing data
            proof.setMissingFields("[\"" + String.join("\",\"", retry.getMissingFields()) + "\"]");
            proofRepository.save(proof);
        } else {
            // CEP rejected
            proof.setStatus(TransferProofStatus.REJECTED_BY_CEP);
            proof.setRejectionReason(retry.getMessage());
            proof.setReviewedAt(LocalDateTime.now());
            proof.setReviewedBy("SISTEMA_CEP");
            proofRepository.save(proof);
        }

        return mapProofToDTO(proof, invoice);
    }

    private String normalizeBankEmitterOrThrow(String bankEmitter) {
        if (bankEmitter == null || bankEmitter.isBlank()) {
            return bankEmitter;
        }
        return banxicoInstitutionCatalogService.resolveEmitter(bankEmitter)
                .map(BanxicoInstitutionCatalogService.ResolvedInstitution::name)
                .orElseThrow(() -> new RuntimeException(
                        "Selecciona un banco emisor válido del catálogo Banxico."));
    }

    private void hydrateProofFromCepValidation(TransferProofSubmission proof,
                                               BanxicoCepAdapter.CepValidationResult cepResult) {
        if (proof == null || cepResult == null) {
            return;
        }
        Map<String, String> fields = cepResult.getCepFields();
        if (fields == null || fields.isEmpty()) {
            return;
        }
        if (fields.get("bancoEmisor") != null && !fields.get("bancoEmisor").isBlank()) {
            proof.setBankEmitter(fields.get("bancoEmisor"));
        }
        if (fields.get("cuentaBeneficiario") != null && !fields.get("cuentaBeneficiario").isBlank()) {
            proof.setAccountReceiver(fields.get("cuentaBeneficiario").replaceAll("\\D", ""));
        }
        if (fields.get("monto") != null && !fields.get("monto").isBlank()) {
            try {
                proof.setAmount(new BigDecimal(fields.get("monto").replaceAll("[,$\\s]", "")));
            } catch (Exception ignore) {
                // Si Banxico regresó un formato no parseable, conservamos el declarado.
            }
        }
        if (fields.get("fechaOperacion") != null && !fields.get("fechaOperacion").isBlank()) {
            try {
                proof.setTransferDate(LocalDate.parse(fields.get("fechaOperacion")));
            } catch (Exception ignore) {
                // Conservamos la fecha original si Banxico no viene en ISO.
            }
        }
    }

    private void storeCepArtifacts(TransferProofSubmission proof, InvoiceEntity invoice,
                                   BanxicoCepAdapter.CepValidationResult cepResult) {
        if (proof == null || cepResult == null) {
            return;
        }
        if ((proof.getCepXmlUrl() == null || proof.getCepXmlUrl().isBlank())
                && cepResult.getCepXml() != null && !cepResult.getCepXml().isBlank()) {
            String xmlName = buildCepArtifactName(invoice, proof, "xml");
            String xmlUrl = fileStorageService.storeBytes(
                    cepResult.getCepXml().getBytes(StandardCharsets.UTF_8),
                    xmlName,
                    "application/xml",
                    "cep");
            proof.setCepXmlUrl(xmlUrl);
        }
        if ((proof.getCepPdfUrl() == null || proof.getCepPdfUrl().isBlank())
                && cepResult.getCepPdfBytes() != null && cepResult.getCepPdfBytes().length > 0) {
            String pdfName = buildCepArtifactName(invoice, proof, "pdf");
            String pdfUrl = fileStorageService.storeBytes(
                    cepResult.getCepPdfBytes(),
                    pdfName,
                    "application/pdf",
                    "cep");
            proof.setCepPdfUrl(pdfUrl);
        }
    }

    private String buildCepArtifactName(InvoiceEntity invoice, TransferProofSubmission proof, String extension) {
        String month = invoice != null && invoice.getMonthYear() != null ? invoice.getMonthYear() : "sin-mes";
        return "cep-banxico-" + month + "-" + proof.getId().substring(0, 8) + "." + extension;
    }

    /**
     * Settlement engine: applies a CEP-validated payment to an invoice.
     *
     * Handles three scenarios:
     * - EXACT:    transferAmount == outstanding → PAID, outstandingAmount = 0
     * - PARTIAL:  transferAmount < outstanding  → PARTIALLY_PAID, records remainder
     * - OVERPAID: transferAmount > outstanding  → PAID + creditBalance for surplus
     *
     * <p>V58.1 — Idempotente: si ya existe un PaymentEntity con el mismo
     * {@code gatewayReference} (clave de rastreo) y {@code invoiceId} en estado
     * CONFIRMED, no se vuelve a aplicar. Previene race conditions y double-click
     * en override del dueño.
     */
    private void autoConfirmPayment(InvoiceEntity invoice, TransferProofSubmission proof) {
        // V58.1 — Guard de idempotencia
        String gatewayRef = proof.getClaveRastreo();
        if (gatewayRef != null && !gatewayRef.isBlank()) {
            boolean alreadyApplied = paymentRepository.findByInvoiceId(invoice.getId()).stream()
                    .filter(p -> gatewayRef.equalsIgnoreCase(p.getGatewayReference()))
                    .anyMatch(p -> p.getStatus() == PaymentStatus.CONFIRMED);
            if (alreadyApplied) {
                logger.warn("[Settlement] SKIP idempotent — proof {} ya tiene PaymentEntity CONFIRMED " +
                        "con gateway_reference={} en invoice {}. No se duplica.",
                        proof.getId(), gatewayRef, invoice.getId());
                return;
            }
        }

        BigDecimal transferAmount = proof.getAmount() != null ? proof.getAmount() : invoice.getTotalAmount();

        // Calculate current outstanding (supports multiple partial payments)
        BigDecimal currentPaid = invoice.getPaidAmount() != null ? invoice.getPaidAmount() : BigDecimal.ZERO;
        BigDecimal totalOwed = invoice.getTotalAmount();
        BigDecimal outstanding = totalOwed.subtract(currentPaid);

        // How much of this payment applies to the invoice
        BigDecimal applied;
        BigDecimal unapplied;

        if (transferAmount.compareTo(outstanding) >= 0) {
            // Covers full outstanding (exact or overpaid)
            applied = outstanding;
            unapplied = transferAmount.subtract(outstanding);
        } else {
            // Partial payment
            applied = transferAmount;
            unapplied = BigDecimal.ZERO;
        }

        // V58.1 — Distinguir CASH aprobado por dueño de SPEI validado por CEP
        // para que la contabilidad y los reportes reflejen el método real.
        PaymentMethod paymentMethod = proof.isCash()
                ? PaymentMethod.CASH
                : PaymentMethod.TRANSFER_SPEI;

        // Create payment record
        PaymentEntity payment = new PaymentEntity();
        payment.setInvoiceId(invoice.getId());
        payment.setOwnerId(invoice.getOwnerId());
        payment.setTenantProfileId(invoice.getTenantProfileId());
        payment.setAmount(transferAmount);
        payment.setAppliedAmount(applied);
        payment.setUnappliedAmount(unapplied);
        payment.setPaymentMethod(paymentMethod);
        payment.setGatewayReference(gatewayRef);
        payment.setStatus(PaymentStatus.CONFIRMED);
        payment.setPaidAt(proof.getTransferDate() != null ? proof.getTransferDate().atStartOfDay() : LocalDateTime.now());
        payment.setConfirmedBy(proof.isCash() ? "OWNER_CASH_APPROVAL" : "SISTEMA_CEP");
        payment.setConfirmedAt(LocalDateTime.now());

        // Update invoice accounting
        BigDecimal newPaidAmount = currentPaid.add(applied);
        BigDecimal newOutstanding = totalOwed.subtract(newPaidAmount);
        BigDecimal newCredit = (invoice.getCreditBalance() != null ? invoice.getCreditBalance() : BigDecimal.ZERO).add(unapplied);

        invoice.setPaidAmount(newPaidAmount);
        invoice.setOutstandingAmount(newOutstanding.compareTo(BigDecimal.ZERO) < 0 ? BigDecimal.ZERO : newOutstanding);
        invoice.setCreditBalance(newCredit);
        invoice.setPaymentMethod(paymentMethod);

        // Determine settlement status
        int cmp = newPaidAmount.compareTo(totalOwed);
        if (cmp >= 0) {
            // Fully paid or overpaid
            invoice.setStatus("PAID");
            invoice.setPaidDate(LocalDate.now());
            invoice.setOutstandingAmount(BigDecimal.ZERO);

            if (unapplied.compareTo(BigDecimal.ZERO) > 0) {
                invoice.setSettlementStatus("OVERPAID");
                payment.setNotes("CEP auto-validado. Excedente: $" + unapplied.toPlainString() + " registrado como saldo a favor.");
            } else {
                invoice.setSettlementStatus("PAID");
                payment.setNotes("CEP auto-validado. Pago exacto.");
            }
        } else {
            // Partial
            invoice.setStatus("PARTIALLY_PAID");
            invoice.setSettlementStatus("PARTIALLY_PAID");
            payment.setNotes("CEP auto-validado. Pago parcial: $" + applied.toPlainString()
                    + " de $" + totalOwed.toPlainString() + ". Pendiente: $" + newOutstanding.toPlainString());
        }

        paymentRepository.save(payment);
        invoiceRepository.save(invoice);

        try {
            propertyMovementService.recordPaymentMovement(invoice, payment);
        } catch (Exception ex) {
            logger.warn("[Timeline] Could not record payment movement for invoice {}: {}", invoice.getId(), ex.getMessage());
        }

        // V56: categorización asíncrona con IA. No bloquea ni altera el flujo
        // si Claude falla — los campos quedan null y el contador puede clasificar
        // manualmente desde el panel.
        try {
            aiAccountingService.categorizePaymentAsync(payment.getId());
        } catch (Exception ex) {
            logger.debug("[AI-ACC] categorize trigger failed for payment {}: {}",
                    payment.getId(), ex.getClass().getSimpleName());
        }

        // V57: archivar comprobante al expediente del inmueble para que el dueño
        // lo vea en "Archivos del inmueble" sin tener que ir al libro mayor.
        // Idempotente — si ya existe el file en property_files no duplica.
        try {
            proofArchiver.archiveValidatedProof(proof, invoice);
        } catch (Exception ex) {
            logger.debug("[ARCHIVE] archive trigger failed for proof {}: {}",
                    proof.getId(), ex.getClass().getSimpleName());
        }

        logger.info("[Settlement] Invoice {}: paid={}, outstanding={}, credit={}, settlement={}",
                invoice.getId(), newPaidAmount, invoice.getOutstandingAmount(), newCredit, invoice.getSettlementStatus());
    }

    /**
     * Notifica al DUEÑO que le acaba de caer un SPEI confirmado contra uno de sus inmuebles.
     *
     * Se llama DESPUÉS de que {@link #autoConfirmPayment(InvoiceEntity, TransferProofSubmission)}
     * marca la factura como pagada. A diferencia de {@code PAYMENT_AUTO_VALIDATED} (que le llega al
     * arrendatario como recibo), este evento está dirigido explícitamente al dueño para que en el
     * momento sepa:
     *   - qué inmueble cobró,
     *   - qué arrendatario pagó,
     *   - monto aplicado,
     *   - clave de rastreo SPEI para cruzar con su estado de cuenta.
     *
     * Cualquier fallo al componer el mensaje (datos faltantes, lookup nulo) se degrada con log
     * y NO rompe el flujo principal de cobro.
     */
    private void notifyOwnerPaymentPendingValidation(InvoiceEntity invoice,
                                                      TransferProofSubmission proof,
                                                      String actorEmail,
                                                      int validationHours,
                                                      String detailNote) {
        try {
            if (invoice == null || invoice.getOwnerId() == null || invoice.getOwnerId().isBlank()) {
                return;
            }
            UserEntity owner = userRepository.findById(invoice.getOwnerId()).orElse(null);
            String ownerName = owner != null && owner.getName() != null ? owner.getName() : "";
            String tenantName = resolveTenantNameForInvoice(invoice);
            String propertyName = resolvePropertyNameForInvoice(invoice);
            BigDecimal amount = proof != null && proof.getAmount() != null
                    ? proof.getAmount()
                    : (invoice.getOutstandingAmount() != null ? invoice.getOutstandingAmount()
                    : invoice.getTotalAmount());
            String typeLabel = proof != null && proof.isCash() ? "EFECTIVO" : "SPEI";

            String title = "Comprobante " + typeLabel + " por validar: " + invoice.getMonthYear();
            String body = detailNote + "\n\nInquilino: " + tenantName
                    + "\nInmueble: " + propertyName
                    + "\nMonto: $" + (amount != null ? amount.toPlainString() : "0")
                    + "\nTienes " + validationHours + " horas. Valida por WhatsApp (escribe VALIDAR) o en el portal.";

            Map<String, String> tplVars = Map.of(
                    "1", ownerName,
                    "2", tenantName,
                    "3", propertyName,
                    "4", invoice.getMonthYear() != null ? invoice.getMonthYear() : "",
                    "5", typeLabel,
                    "6", amount != null ? amount.toPlainString() : "0",
                    "7", String.valueOf(validationHours)
            );

            dispatcher.dispatch(
                    "CASH_PAYMENT_PENDING_OWNER",
                    title,
                    body,
                    invoice.getOwnerId(),
                    actorEmail,
                    List.of(invoice.getOwnerId()),
                    tplVars
            );
        } catch (Exception ex) {
            logger.warn("[CASH_PAYMENT_PENDING_OWNER] notify failed invoice={}: {}",
                    invoice != null ? invoice.getId() : "null", ex.getMessage());
        }
    }

    /**
     * SPEI validado por CEP — plantilla {@code admindi_payment_auto_validated_v1} (6 slots).
     */
    private void notifyOwnerPaymentAutoValidated(InvoiceEntity invoice, TransferProofSubmission proof) {
        try {
            if (invoice == null || invoice.getOwnerId() == null || invoice.getOwnerId().isBlank()) {
                return;
            }
            UserEntity owner = userRepository.findById(invoice.getOwnerId()).orElse(null);
            String ownerName = owner != null && owner.getName() != null ? owner.getName() : "";
            String propertyName = resolvePropertyNameForInvoice(invoice);
            String tenantName = resolveTenantNameForInvoice(invoice);
            BigDecimal amount = proof != null && proof.getAmount() != null
                    ? proof.getAmount()
                    : (invoice.getPaidAmount() != null ? invoice.getPaidAmount() : BigDecimal.ZERO);
            String clave = proof != null && proof.getClaveRastreo() != null
                    ? proof.getClaveRastreo()
                    : "(sin clave)";

            String title = "Pago SPEI validado automáticamente: " + invoice.getMonthYear();
            String body = "Inmueble: " + propertyName + "\nArrendatario: " + tenantName
                    + "\nMonto: $" + amount.toPlainString() + "\nClave: " + clave;

            Map<String, String> tplVars = Map.of(
                    "1", ownerName,
                    "2", propertyName,
                    "3", tenantName,
                    "4", invoice.getMonthYear() != null ? invoice.getMonthYear() : "",
                    "5", amount.toPlainString(),
                    "6", clave
            );

            dispatcher.dispatch(
                    "PAYMENT_AUTO_VALIDATED",
                    title,
                    body,
                    invoice.getOwnerId(),
                    "SYSTEM",
                    List.of(invoice.getOwnerId()),
                    tplVars
            );
        } catch (Exception ex) {
            logger.warn("[PAYMENT_AUTO_VALIDATED] notify failed invoice={}: {}",
                    invoice != null ? invoice.getId() : "null", ex.getMessage());
        }
    }

    /** Plantilla {@code admindi_cash_payment_approved_v1} al inquilino (4 slots). */
    private void notifyTenantCashPaymentApproved(InvoiceEntity invoice, TransferProofSubmission proof) {
        try {
            String tenantUserId = resolveTenantUserIdForInvoice(invoice);
            if (tenantUserId == null) return;

            String tenantName = resolveTenantNameForInvoice(invoice);
            String propertyName = resolvePropertyNameForInvoice(invoice);
            BigDecimal amount = proof != null && proof.getAmount() != null
                    ? proof.getAmount()
                    : (invoice.getPaidAmount() != null ? invoice.getPaidAmount() : BigDecimal.ZERO);

            String title = "Pago en efectivo confirmado: " + invoice.getMonthYear();
            String body = "Tu arrendador confirmó tu pago en " + propertyName
                    + " por $" + amount.toPlainString() + ".";

            Map<String, String> tplVars = Map.of(
                    "1", tenantName,
                    "2", propertyName,
                    "3", invoice.getMonthYear() != null ? invoice.getMonthYear() : "",
                    "4", amount.toPlainString()
            );

            dispatcher.dispatch(
                    "CASH_PAYMENT_APPROVED",
                    title,
                    body,
                    invoice.getOwnerId(),
                    "SYSTEM",
                    List.of(tenantUserId),
                    tplVars
            );
        } catch (Exception ex) {
            logger.warn("[CASH_PAYMENT_APPROVED] notify failed invoice={}: {}",
                    invoice != null ? invoice.getId() : "null", ex.getMessage());
        }
    }

    /** Plantilla {@code admindi_cash_payment_rejected_v1} al inquilino (4 slots). */
    private void notifyTenantCashPaymentRejected(InvoiceEntity invoice, String reason) {
        try {
            String tenantUserId = resolveTenantUserIdForInvoice(invoice);
            if (tenantUserId == null) return;

            String tenantName = resolveTenantNameForInvoice(invoice);
            String propertyName = resolvePropertyNameForInvoice(invoice);
            String safeReason = reason != null && !reason.isBlank() ? reason : "Rechazado por el arrendador";

            String title = "Comprobante en efectivo no confirmado: " + invoice.getMonthYear();
            String body = "Motivo: " + safeReason;

            Map<String, String> tplVars = Map.of(
                    "1", tenantName,
                    "2", propertyName,
                    "3", invoice.getMonthYear() != null ? invoice.getMonthYear() : "",
                    "4", safeReason
            );

            dispatcher.dispatch(
                    "CASH_PAYMENT_REJECTED",
                    title,
                    body,
                    invoice.getOwnerId(),
                    "SYSTEM",
                    List.of(tenantUserId),
                    tplVars
            );
        } catch (Exception ex) {
            logger.warn("[CASH_PAYMENT_REJECTED] notify failed invoice={}: {}",
                    invoice != null ? invoice.getId() : "null", ex.getMessage());
        }
    }

    private String resolveTenantUserIdForInvoice(InvoiceEntity invoice) {
        if (invoice == null || invoice.getTenantProfileId() == null) return null;
        return profileRepository.findById(invoice.getTenantProfileId())
                .map(TenantProfileEntity::getUserId)
                .orElse(null);
    }

    private void notifyOwnerOfTransferConfirmation(InvoiceEntity invoice, TransferProofSubmission proof) {
        try {
            if (invoice == null || invoice.getOwnerId() == null || invoice.getOwnerId().isBlank()) {
                return;
            }

            String propertyName = resolvePropertyNameForInvoice(invoice);
            String tenantName = resolveTenantNameForInvoice(invoice);

            BigDecimal amount = proof != null && proof.getAmount() != null
                    ? proof.getAmount()
                    : (invoice.getPaidAmount() != null ? invoice.getPaidAmount() : BigDecimal.ZERO);
            String clave = proof != null && proof.getClaveRastreo() != null
                    ? proof.getClaveRastreo()
                    : "(sin clave)";

            String whenStr = LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"));

            String title = "Pago SPEI confirmado: " + propertyName;
            StringBuilder body = new StringBuilder();
            body.append("Se validó un pago SPEI a tu cuenta.\n\n");
            body.append("Inmueble: ").append(propertyName).append("\n");
            body.append("Arrendatario: ").append(tenantName).append("\n");
            body.append("Periodo: ").append(invoice.getMonthYear() != null ? invoice.getMonthYear() : "(n/d)").append("\n");
            body.append("Monto aplicado: $").append(amount.toPlainString()).append("\n");
            body.append("Clave de rastreo: ").append(clave).append("\n");
            body.append("Validado el ").append(whenStr).append(".");

            // Resolver nombre del dueño y URL del portal para la plantilla.
            UserEntity owner = userRepository.findById(invoice.getOwnerId()).orElse(null);
            String ownerName = owner != null && owner.getName() != null ? owner.getName() : "";

            // Variables WhatsApp — plantilla admindi_transfer_confirmed_owner_v2 (7 slots).
            Map<String, String> tplVars = Map.of(
                    "1", ownerName,
                    "2", whenStr,
                    "3", propertyName,
                    "4", tenantName,
                    "5", amount.toPlainString(),
                    "6", clave,
                    "7", appUrl != null ? appUrl : ""
            );

            dispatcher.dispatch(
                    "TRANSFER_CONFIRMED",
                    title,
                    body.toString(),
                    invoice.getOwnerId(),
                    "SYSTEM",
                    List.of(invoice.getOwnerId()),
                    tplVars
            );
        } catch (Exception ex) {
            logger.warn("[TRANSFER_CONFIRMED] Could not notify owner for invoice {}: {}",
                    invoice != null ? invoice.getId() : "null", ex.getMessage());
        }
    }

    private String resolvePropertyNameForInvoice(InvoiceEntity invoice) {
        try {
            // 1) Vía lease (canónico)
            if (invoice.getLeaseId() != null) {
                LeaseEntity lease = leaseRepository.findById(invoice.getLeaseId()).orElse(null);
                if (lease != null && lease.getProperty() != null && lease.getProperty().getName() != null) {
                    return lease.getProperty().getName();
                }
            }
            // 2) Vía tenant profile → propertyId
            if (invoice.getTenantProfileId() != null) {
                TenantProfileEntity profile = profileRepository.findById(invoice.getTenantProfileId()).orElse(null);
                if (profile != null && profile.getPropertyId() != null) {
                    PropertyEntity prop = propertyRepository.findById(profile.getPropertyId()).orElse(null);
                    if (prop != null && prop.getName() != null) return prop.getName();
                }
            }
        } catch (Exception ignored) { /* fallback abajo */ }
        return "(inmueble)";
    }

    private String resolveTenantNameForInvoice(InvoiceEntity invoice) {
        try {
            if (invoice.getTenantProfileId() != null) {
                TenantProfileEntity profile = profileRepository.findById(invoice.getTenantProfileId()).orElse(null);
                if (profile != null && profile.getUserId() != null) {
                    UserEntity tenant = userRepository.findById(profile.getUserId()).orElse(null);
                    if (tenant != null && tenant.getName() != null) return tenant.getName();
                }
            }
        } catch (Exception ignored) { /* fallback abajo */ }
        return "(arrendatario)";
    }

    // ─── OWNER / SUPER_ADMIN Override: manual review of a proof ───────────
    //
    // V57: se extiende para soportar DOS casos:
    //   1. SPEI (legacy): override excepcional cuando CEP falló pero el dueño
    //      sabe que sí recibió el dinero.
    //   2. CASH (nuevo): flujo NORMAL para efectivo. El dueño revisa en su
    //      bandeja y aprueba/rechaza comprobantes PENDING_OWNER_VALIDATION.

    /**
     * Override / validación manual de un comprobante.
     *
     * <p>Para CASH es el flujo normal: el dueño aprueba o rechaza y la factura
     * se actualiza en consecuencia. Para SPEI es excepcional (el flujo normal
     * es automático vía CEP).
     */
    @Transactional
    public void overrideTransferProof(String proofId, boolean approve, String reason) {
        String actor = resolveActorEmail();
        UserEntity actorUser = userRepository.findByLoginIdentifier(actor)
                .orElseThrow(() -> new SecurityException("Usuario autenticado no encontrado."));

        TransferProofSubmission proof = proofRepository.findById(proofId)
                .orElseThrow(() -> new RuntimeException("Comprobante no encontrado."));

        // V58.1 — GUARD IDOR CRÍTICO: el OWNER (o staff de esa org) solo puede
        // operar proofs de su propia organización. Sin esto un OWNER con un
        // proofId filtrado aprobaría/rechazaría comprobantes de otra org.
        enforceOwnerControlsProof(actorUser, proof);

        // V57 — Guard contra decisiones duplicadas: si ya tiene reviewed_at
        // y está en estado terminal, no reprocesar.
        if (proof.getStatus() == TransferProofStatus.VALIDATED
                || proof.getStatus() == TransferProofStatus.VALIDATED_BY_OWNER
                || proof.getStatus() == TransferProofStatus.REJECTED_BY_OWNER
                || proof.getStatus() == TransferProofStatus.EXPIRED_AWAITING_OWNER) {
            throw new RuntimeException("Este comprobante ya fue procesado (estado=" + proof.getStatus() + ").");
        }

        InvoiceEntity invoice = invoiceRepository.findById(proof.getInvoiceId()).orElseThrow();
        boolean isCash = proof.isCash();

        proof.setReviewedAt(LocalDateTime.now());
        proof.setReviewedBy(actor + (isCash ? " [CASH-VALIDATION]" : " [OVERRIDE]"));

        if (approve) {
            // Estados distintos según tipo para que el reporte y la auditoría
            // distingan "validado por CEP" de "validado por dueño".
            proof.setStatus(isCash
                    ? TransferProofStatus.VALIDATED_BY_OWNER
                    : TransferProofStatus.VALIDATED);
            proofRepository.save(proof);
            autoConfirmPayment(invoice, proof);

            if (isCash) {
                notifyTenantCashPaymentApproved(invoice, proof);
            } else {
                String title = "Override manual: comprobante aprobado para " + invoice.getMonthYear();
                dispatcher.dispatch("PAYMENT_MANUAL_OVERRIDE", title, "Actor: " + actor,
                        proof.getOwnerId(), actor, null);
                notifyOwnerOfTransferConfirmation(invoice, proof);
            }

        } else {
            // Rechazo manual. Para CASH deja consumido el intento y registra
            // el motivo en owner_validation_notes para que el inquilino lo vea.
            proof.setStatus(isCash
                    ? TransferProofStatus.REJECTED_BY_OWNER
                    : TransferProofStatus.REJECTED);
            String safeReason = reason != null && !reason.isBlank() ? reason : "Rechazado manualmente";
            proof.setRejectionReason("[" + (isCash ? "CASH-REJECT" : "OVERRIDE") + "] " + safeReason);
            if (isCash) {
                proof.setOwnerValidationNotes(safeReason);
            }
            proofRepository.save(proof);

            // La factura vuelve a PENDING (si no lo estaba).
            if (!"PAID".equalsIgnoreCase(invoice.getStatus())
                    && !"PARTIALLY_PAID".equalsIgnoreCase(invoice.getStatus())) {
                invoice.setStatus("PENDING");
                invoiceRepository.save(invoice);
            }

            if (isCash) {
                notifyTenantCashPaymentRejected(invoice, safeReason);
            } else {
                String title = "Override manual: comprobante rechazado para " + invoice.getMonthYear();
                dispatcher.dispatch("PAYMENT_MANUAL_OVERRIDE", title, "Motivo: " + safeReason,
                        proof.getOwnerId(), actor, null);
            }
        }

        logger.warn("[Ledger] {} proof {} by {} — approved={} type={}",
                isCash ? "CASH-VALIDATION" : "MANUAL OVERRIDE",
                proofId, actor, approve, proof.getPaymentType());
    }

    /**
     * Scheduler callback: marca EXPIRED_AWAITING_OWNER los proofs con expires_at
     * vencido (tanto CASH como SPEI que cayeron a validación manual). No envía
     * notificaciones aquí; el scheduler lo hace después del commit.
     *
     * <p>V59 — antes solo expiraba CASH. Ahora cualquier proof en
     * PENDING_OWNER_VALIDATION con {@code expires_at < cutoff} es marcado,
     * incluyendo SPEI que cayó a validación manual porque Banxico estaba caído
     * o el dueño no tenía CLABE registrada.
     */
    @Transactional
    public int expirePendingOwnerValidationOlderThan(LocalDateTime cutoff) {
        List<TransferProofSubmission> expiring = proofRepository.findExpiredPendingOwnerValidation(cutoff);
        int count = 0;
        for (TransferProofSubmission proof : expiring) {
            proof.setStatus(TransferProofStatus.EXPIRED_AWAITING_OWNER);
            proof.setReviewedAt(LocalDateTime.now());
            proof.setReviewedBy("SYSTEM_EXPIRATION");
            proofRepository.save(proof);
            count++;
        }
        return count;
    }

    /**
     * Alias back-compat. Prefiere {@link #expirePendingOwnerValidationOlderThan}.
     */
    @Deprecated
    @Transactional
    public int expireCashProofsOlderThan(LocalDateTime cutoff) {
        return expirePendingOwnerValidationOlderThan(cutoff);
    }

    /**
     * Carga los proofs PENDING_OWNER_VALIDATION del dueño actual (CASH + SPEI
     * que cayeron a validación manual), ordenados por expires_at ascendente
     * (los más urgentes primero).
     *
     * <p>V59 — antes solo devolvía CASH; por eso los dueños nunca veían los
     * SPEI encolados en validación manual aunque les llegara el email.
     */
    public List<TransferProofDTO> getPendingProofsForOwner() {
        String ownerId = resolveOwnerId();
        return proofRepository.findPendingOwnerValidationForOwner(ownerId).stream()
                .map(p -> {
                    InvoiceEntity inv = invoiceRepository.findById(p.getInvoiceId()).orElse(null);
                    return mapProofToDTO(p, inv);
                })
                .collect(Collectors.toList());
    }

    /**
     * Alias back-compat. Prefiere {@link #getPendingProofsForOwner}.
     */
    @Deprecated
    public List<TransferProofDTO> getPendingCashProofsForOwner() {
        return getPendingProofsForOwner();
    }

    // ─── Proof Queries (read-only trazabilidad) ─────────────────────────

    /**
     * Get all proofs for an owner — grouped by status for trazabilidad.
     * This is READ-ONLY — owner sees the automated results.
     */
    public List<TransferProofDTO> getAllProofs() {
        String ownerId = resolveOwnerId();
        return proofRepository.findByOwnerId(ownerId).stream()
                .map(proof -> {
                    InvoiceEntity invoice = invoiceRepository.findById(proof.getInvoiceId()).orElse(null);
                    return mapProofToDTO(proof, invoice);
                })
                .collect(Collectors.toList());
    }

    /**
     * V65 — Histórico de comprobantes para el expediente. Devuelve sólo los
     * estados que el dueño YA decidió manualmente o que expiraron (no los que
     * están pendientes ni incomplete_data). Soporta filtro por:
     *   - propertyId: histórico del expediente del inmueble.
     *   - tenantProfileId: histórico de un inquilino específico.
     *
     * <p>Si ambos filtros vienen, intersecta. Si ninguno, devuelve todo el
     * histórico del owner.</p>
     */
    public List<TransferProofDTO> getProofsHistory(String propertyIdFilter, String tenantProfileIdFilter) {
        java.util.Set<TransferProofStatus> terminalStates = java.util.EnumSet.of(
                TransferProofStatus.VALIDATED,
                TransferProofStatus.VALIDATED_BY_OWNER,
                TransferProofStatus.REJECTED,
                TransferProofStatus.REJECTED_BY_CEP,
                TransferProofStatus.REJECTED_BY_OWNER,
                TransferProofStatus.EXPIRED_AWAITING_OWNER);

        UserEntity actor = userRepository.findByLoginIdentifier(resolveActorEmail()).orElseThrow();
        if (actor.getRole() == Role.TENANT) {
            String ctxOwner = TenantContext.getCurrentOwner();
            if (ctxOwner == null || ctxOwner.isBlank()) {
                throw new RuntimeException("Seleccione la organización (contexto) antes de ver el historial de comprobantes.");
            }
            List<TenantProfileEntity> profiles = resolveTenantProfilesForContext(actor, ctxOwner, tenantProfileIdFilter);
            return profiles.stream()
                    .filter(profile -> propertyIdFilter == null || propertyIdFilter.isBlank()
                            || propertyIdFilter.equals(profile.getPropertyId()))
                    .flatMap(profile -> proofRepository.findByTenantProfileId(profile.getId()).stream())
                    .filter(p -> terminalStates.contains(p.getStatus()))
                    .map(proof -> {
                        InvoiceEntity invoice = invoiceRepository.findById(proof.getInvoiceId()).orElse(null);
                        return mapProofToDTO(proof, invoice);
                    })
                    .sorted((a, b) -> {
                        String la = a.getReviewedAt() != null ? a.getReviewedAt() : a.getSubmittedAt();
                        String lb = b.getReviewedAt() != null ? b.getReviewedAt() : b.getSubmittedAt();
                        if (la == null && lb == null) return 0;
                        if (la == null) return 1;
                        if (lb == null) return -1;
                        return lb.compareTo(la);
                    })
                    .collect(Collectors.toList());
        }

        String ownerId = resolveOwnerId();
        return proofRepository.findByOwnerId(ownerId).stream()
                .filter(p -> terminalStates.contains(p.getStatus()))
                .filter(p -> {
                    if (tenantProfileIdFilter == null || tenantProfileIdFilter.isBlank()) return true;
                    return tenantProfileIdFilter.equals(p.getTenantProfileId());
                })
                .filter(p -> {
                    if (propertyIdFilter == null || propertyIdFilter.isBlank()) return true;
                    // Resolvemos property vía tenantProfile (cache en memoria del lookup
                    // para no martillar el repo — dataset histórico no es enorme).
                    if (p.getTenantProfileId() == null) return false;
                    return profileRepository.findById(p.getTenantProfileId())
                            .map(tp -> propertyIdFilter.equals(tp.getPropertyId()))
                            .orElse(false);
                })
                .map(proof -> {
                    InvoiceEntity invoice = invoiceRepository.findById(proof.getInvoiceId()).orElse(null);
                    return mapProofToDTO(proof, invoice);
                })
                .sorted((a, b) -> {
                    String la = a.getReviewedAt() != null ? a.getReviewedAt() : a.getSubmittedAt();
                    String lb = b.getReviewedAt() != null ? b.getReviewedAt() : b.getSubmittedAt();
                    if (la == null && lb == null) return 0;
                    if (la == null) return 1;
                    if (lb == null) return -1;
                    return lb.compareTo(la); // más recientes primero
                })
                .collect(Collectors.toList());
    }

    /**
     * Get payment history.
     */
    public List<PaymentDTO> getPayments(String monthYear) {
        if (monthYear != null && !monthYear.isBlank()) {
            reportingPeriodService.validateOwnerMonthYear(monthYear);
        }
        String ownerId = resolveOwnerId();
        List<PaymentEntity> payments = paymentRepository.findByOwnerId(ownerId);

        return payments.stream()
                .filter(p -> {
                    if (monthYear == null || monthYear.isBlank()) return true;
                    InvoiceEntity inv = invoiceRepository.findById(p.getInvoiceId()).orElse(null);
                    return inv != null && monthYear.equals(inv.getMonthYear());
                })
                .map(this::mapPaymentToDTO)
                .collect(Collectors.toList());
    }

    // ─── Autonomous Ledger Engine (Cron) ────────────────────────────────

    @Scheduled(cron = "0 0 0 * * ?")
    public void cronProcessLedger() {
        processLedgerForDate(LocalDate.now());
    }

    public void processLedgerForDate(LocalDate currentDate) {
        // Solo generamos facturas para expedientes vigentes. Antes el cron tomaba findAll() y
        // creaba una factura PENDING para cada perfil — incluso los archivados — lo que producía
        // "rentas ligadas" fantasma en la contabilidad cada vez que el scheduler corría.
        List<TenantProfileEntity> activeProfiles = profileRepository.findByArchivedAtIsNull();

        for (TenantProfileEntity profile : activeProfiles) {
            generateCurrentMonthInvoiceIfMissing(profile, currentDate);
        }

        List<InvoiceEntity> pendingInvoices = invoiceRepository.findByStatus("PENDING");
        for (InvoiceEntity inv : pendingInvoices) {
            TenantProfileEntity profile = profileRepository.findById(inv.getTenantProfileId()).orElse(null);
            if (profile != null) {
                // No aplicamos mora ni cobros a expedientes archivados; además auto-sanamos la
                // factura dejándola VOID para que deje de inflar "rentas ligadas" retroactivamente
                // (defensa para DBs con datos sucios de versiones anteriores del cron).
                if (profile.getArchivedAt() != null) {
                    inv.setStatus("VOID");
                    inv.setSettlementStatus("CANCELLED");
                    inv.setOutstandingAmount(BigDecimal.ZERO);
                    invoiceRepository.save(inv);
                    continue;
                }
                LocalDate graceLimit = inv.getDueDate().plusDays(profile.getGracePeriodDays());
                if (currentDate.isAfter(graceLimit)) {
                    // Etapa 2: si hay convenio protegiendo la factura (REQUESTED/APPROVED/ACTIVE),
                    // se suspende el cobro de mora hasta su resolución.
                    if (hasProtectingAgreement(inv.getId())) {
                        continue;
                    }
                    applyLateFeeIfEligible(inv, profile, currentDate);
                }
            }
        }

        // Partial payments: if promised completion passed and no active/approved agreement, apply late rules
        for (InvoiceEntity inv : invoiceRepository.findBySettlementStatus("PARTIALLY_PAID")) {
            if (inv.getOutstandingAmount() == null || inv.getOutstandingAmount().compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }
            if ("REQUESTING_AGREEMENT".equals(inv.getShortfallReason())) {
                continue;
            }
            if (hasProtectingAgreement(inv.getId())) {
                continue;
            }
            if (inv.getPromisedCompletionDate() == null || !currentDate.isAfter(inv.getPromisedCompletionDate())) {
                continue;
            }
            TenantProfileEntity profile = profileRepository.findById(inv.getTenantProfileId()).orElse(null);
            if (profile == null) {
                continue;
            }
            if (!"LATE".equals(inv.getStatus())) {
                applyLateFeeIfEligible(inv, profile, currentDate);
            }
        }
    }

    /**
     * Genera de forma idempotente la factura del mes correspondiente a {@code currentDate}
     * para el expediente indicado. Si ya existe factura para ese par (tenantProfileId, monthYear)
     * NO se crea otra: esto permite invocarlo tanto desde el cron diario como desde
     * {@link com.admindi.backend.service.TenantService#createTenant} sin duplicar.
     *
     * <p>No valida si el expediente está archivado — ese filtro vive en {@link #processLedgerForDate}
     * porque ahí es donde aplica. Los callers directos (ej. creación de expediente) ya decidieron
     * que quieren factura para ese tenant.
     *
     * <p>Al participar en la transacción del caller (sin {@code @Transactional} propio),
     * cualquier fallo posterior en el mismo flujo hace rollback también de esta inserción.
     *
     * @return {@code Optional} con la factura recién creada, o vacío si ya existía.
     */
    public Optional<InvoiceEntity> generateCurrentMonthInvoiceIfMissing(
            TenantProfileEntity profile, LocalDate currentDate) {
        String monthYear = currentDate.format(DateTimeFormatter.ofPattern("yyyy-MM"));
        Optional<InvoiceEntity> existing = invoiceRepository.findByTenantProfileIdAndMonthYear(
                profile.getId(), monthYear);
        if (existing.isPresent()) {
            return Optional.empty();
        }
        LocalDate dueDate = currentDate.withDayOfMonth(
                Math.min(profile.getPaymentDay(), currentDate.lengthOfMonth()));
        InvoiceEntity inv = new InvoiceEntity();
        inv.setTenantProfileId(profile.getId());
        inv.setOwnerId(profile.getOwnerId());
        inv.setMonthYear(monthYear);
        inv.setIssueDate(currentDate.withDayOfMonth(1));
        inv.setDueDate(dueDate);
        inv.setBaseAmount(profile.getRentAmount());
        inv.setTotalAmount(profile.getRentAmount());
        inv.setConcept("RENTA_MENSUAL");
        inv.setStatus("PENDING");
        inv.setPaidAmount(BigDecimal.ZERO);
        inv.setOutstandingAmount(profile.getRentAmount());
        inv.setCreditBalance(BigDecimal.ZERO);
        inv.setSettlementStatus("UNPAID");
        // Bloque 4 / Gap B: amarrar la factura al contrato activo del expediente.
        // Sin esto, el frontend del dueño no podía resolver "factura → contrato"
        // y aparecían facturas huérfanas tras renovaciones o firmas nuevas.
        if (profile.getPropertyId() != null) {
            leaseRepository.findFirstByOwnerIdAndTenant_IdAndProperty_IdAndStatus(
                    profile.getOwnerId(), profile.getUserId(), profile.getPropertyId(),
                    LeaseStatus.ACTIVE)
                .ifPresent(lease -> inv.setLeaseId(lease.getId()));
        }
        InvoiceEntity saved = invoiceRepository.save(inv);
        logger.info("[LEDGER] invoice generada tenantProfile={} monthYear={} invoiceId={} due={}",
                profile.getId(), monthYear, saved.getId(), dueDate);
        return Optional.of(saved);
    }

    private void applyLateFeeIfEligible(InvoiceEntity inv, TenantProfileEntity profile, LocalDate currentDate) {
        inv.setStatus("LATE");
        if (profile.isHasLateFee()) {
            BigDecimal lateFee = BigDecimal.ZERO;
            if ("FIXED_AMOUNT".equals(profile.getLateFeeType())) {
                lateFee = profile.getLateFeeValue();
            } else if ("PERCENTAGE".equals(profile.getLateFeeType())) {
                BigDecimal percent = profile.getLateFeeValue().divide(new BigDecimal("100"));
                lateFee = inv.getBaseAmount().multiply(percent);
            }
            inv.setAppliedLateFee(lateFee);
            inv.setTotalAmount(inv.getBaseAmount().add(lateFee));
            if (inv.getPaidAmount() != null) {
                BigDecimal owed = inv.getTotalAmount().subtract(inv.getPaidAmount());
                inv.setOutstandingAmount(owed.compareTo(BigDecimal.ZERO) < 0 ? BigDecimal.ZERO : owed);
            } else {
                inv.setOutstandingAmount(inv.getTotalAmount());
            }
        }
        invoiceRepository.save(inv);
    }

    private boolean hasProtectingAgreement(String invoiceId) {
        return paymentAgreementRepository.findByInvoiceId(invoiceId).stream()
                .anyMatch(a -> a.getStatus() == PaymentAgreementStatus.ACTIVE
                        || a.getStatus() == PaymentAgreementStatus.APPROVED);
    }

    @Transactional
    public ShortfallSubmitResultDTO submitShortfallReason(String invoiceId, String shortfallReason,
                                                          String shortfallDescription, LocalDate promisedCompletionDate) {
        String email = resolveActorEmail();
        UserEntity user = userRepository.findByLoginIdentifier(email).orElseThrow();

        InvoiceEntity invoice = invoiceRepository.findById(invoiceId)
                .orElseThrow(() -> new RuntimeException("Invoice not found."));
        TenantProfileEntity profile = profileRepository.findById(invoice.getTenantProfileId())
                .orElseThrow(() -> new RuntimeException("Tenant profile not found."));
        if (!profile.getUserId().equals(user.getId())) {
            throw new RuntimeException("Invoice does not belong to tenant.");
        }
        String ctx = TenantContext.getCurrentOwner();
        if (ctx != null && !invoice.getOwnerId().equals(ctx)) {
            throw new RuntimeException("Factura no pertenece al contexto seleccionado.");
        }
        if (!"PARTIALLY_PAID".equals(invoice.getSettlementStatus())) {
            throw new RuntimeException("Shortfall reason only applies to partially paid invoices.");
        }
        if (invoice.getOutstandingAmount() == null || invoice.getOutstandingAmount().compareTo(BigDecimal.ZERO) <= 0) {
            throw new RuntimeException("No outstanding balance on invoice.");
        }

        java.util.Set<String> allowed = java.util.Set.of(
                "PARTIAL_SAME_MONTH", "PARTIAL_NEXT_MONTH", "REQUESTING_AGREEMENT", "BANK_ISSUE", "OTHER");
        if (shortfallReason == null || !allowed.contains(shortfallReason)) {
            throw new RuntimeException("Invalid shortfallReason.");
        }

        invoice.setShortfallReason(shortfallReason);
        invoice.setShortfallDescription(shortfallDescription);
        invoice.setPromisedCompletionDate(promisedCompletionDate);
        invoiceRepository.save(invoice);

        ShortfallSubmitResultDTO out = new ShortfallSubmitResultDTO();
        out.setInvoice(mapInvoiceToDTO(invoice));
        boolean needAgreement = "PARTIAL_NEXT_MONTH".equals(shortfallReason);
        out.setAgreementRequired(needAgreement);
        out.setMessage(needAgreement
                ? "You must request a payment agreement for the remaining balance."
                : "Shortfall reason recorded.");
        return out;
    }

    public List<InvoiceDTO> getInvoicesForProperty(String propertyId) {
        String ownerId = resolveOwnerId();
        return invoiceRepository.findByOwnerId(ownerId).stream()
                .map(this::mapInvoiceToDTO)
                .filter(d -> propertyId.equals(d.getPropertyId()))
                .collect(Collectors.toList());
    }

    // ─── Legacy compatibility ───────────────────────────────────────────

    @Transactional
    public void uploadProof(String invoiceId, String paymentReference, MultipartFile file) {
        submitTransferProof(invoiceId, paymentReference, null, null, null, null, file);
    }

    // ─── Mappers ────────────────────────────────────────────────────────

    private InvoiceDTO mapInvoiceToDTO(InvoiceEntity entity) {
        InvoiceDTO dto = new InvoiceDTO();
        dto.setId(entity.getId());
        dto.setMonthYear(entity.getMonthYear());
        dto.setIssueDate(entity.getIssueDate().toString());
        dto.setDueDate(entity.getDueDate().toString());
        dto.setBaseAmount(entity.getBaseAmount());
        dto.setAppliedLateFee(entity.getAppliedLateFee());
        dto.setTotalAmount(entity.getTotalAmount());
        dto.setStatus(entity.getStatus());
        dto.setPaidDate(entity.getPaidDate() != null ? entity.getPaidDate().toString() : null);
        dto.setPaymentReference(entity.getPaymentReference());
        dto.setProofOfPaymentUrl(entity.getProofOfPaymentUrl());
        dto.setTenantUploadDate(entity.getTenantUploadDate() != null ? entity.getTenantUploadDate().toString() : null);

        // Settlement accounting
        dto.setPaidAmount(entity.getPaidAmount() != null ? entity.getPaidAmount() : BigDecimal.ZERO);
        dto.setOutstandingAmount(entity.getOutstandingAmount() != null ? entity.getOutstandingAmount() : entity.getTotalAmount());
        dto.setCreditBalance(entity.getCreditBalance() != null ? entity.getCreditBalance() : BigDecimal.ZERO);
        dto.setSettlementStatus(entity.getSettlementStatus() != null ? entity.getSettlementStatus() : "UNPAID");

        profileRepository.findById(entity.getTenantProfileId()).ifPresent(profile -> {
            userRepository.findById(profile.getUserId()).ifPresent(user -> {
                dto.setTenantName(user.getName());
                dto.setTenantEmail(user.getContactEmail());
            });
        });

        dto.setLeaseId(entity.getLeaseId());
        dto.setShortfallReason(entity.getShortfallReason());
        dto.setShortfallDescription(entity.getShortfallDescription());
        if (entity.getPromisedCompletionDate() != null) {
            dto.setPromisedCompletionDate(entity.getPromisedCompletionDate().toString());
        }
        if (entity.getLeaseId() != null) {
            leaseRepository.findById(entity.getLeaseId()).ifPresent(lease -> {
                PropertyEntity p = lease.resolvePropertyEntity();
                if (p != null) {
                    dto.setPropertyId(p.getId());
                }
            });
        }
        paymentAgreementRepository.findByInvoiceId(entity.getId()).stream()
                .filter(a -> a.getStatus() == PaymentAgreementStatus.ACTIVE
                        || a.getStatus() == PaymentAgreementStatus.APPROVED
                        || a.getStatus() == PaymentAgreementStatus.REQUESTED
                        || a.getStatus() == PaymentAgreementStatus.BREACHED)
                .findFirst()
                .ifPresent(a -> dto.setAgreementSummaryStatus(a.getStatus().name()));

        return dto;
    }

    private PaymentDTO mapPaymentToDTO(PaymentEntity entity) {
        PaymentDTO dto = new PaymentDTO();
        dto.setId(entity.getId());
        dto.setInvoiceId(entity.getInvoiceId());
        dto.setAmount(entity.getAmount());
        dto.setPaymentMethod(entity.getPaymentMethod().name());
        dto.setGatewayReference(entity.getGatewayReference());
        dto.setStatus(entity.getStatus().name());
        dto.setPaidAt(entity.getPaidAt() != null ? entity.getPaidAt().toString() : null);
        dto.setConfirmedBy(entity.getConfirmedBy());
        dto.setConfirmedAt(entity.getConfirmedAt() != null ? entity.getConfirmedAt().toString() : null);
        dto.setNotes(entity.getNotes());
        dto.setCreatedAt(entity.getCreatedAt() != null ? entity.getCreatedAt().toString() : null);
        dto.setAppliedAmount(entity.getAppliedAmount());
        dto.setUnappliedAmount(entity.getUnappliedAmount() != null ? entity.getUnappliedAmount() : BigDecimal.ZERO);

        // V56 — campos de clasificación IA (pueden ser null mientras Claude
        // termina de procesar de forma asíncrona).
        dto.setAiCategory(entity.getAiCategory());
        dto.setAiCfdiUse(entity.getAiCfdiUse());
        dto.setAiTaxDeductible(entity.getAiTaxDeductible());
        dto.setAiConfidence(entity.getAiConfidence());
        dto.setAiReviewedByUser(entity.isAiReviewedByUser());

        invoiceRepository.findById(entity.getInvoiceId()).ifPresent(inv -> {
            dto.setMonthYear(inv.getMonthYear());
            profileRepository.findById(inv.getTenantProfileId()).ifPresent(profile -> {
                userRepository.findById(profile.getUserId()).ifPresent(user -> {
                    dto.setTenantName(user.getName());
                    dto.setTenantEmail(user.getContactEmail());
                });
            });
        });

        return dto;
    }

    private TransferProofDTO mapProofToDTO(TransferProofSubmission proof, InvoiceEntity invoice) {
        TransferProofDTO dto = new TransferProofDTO();
        dto.setId(proof.getId());
        dto.setInvoiceId(proof.getInvoiceId());
        dto.setFileUrl(proof.getFileUrl());
        dto.setCepXmlAvailable(proof.getCepXmlUrl() != null && !proof.getCepXmlUrl().isBlank());
        dto.setCepPdfAvailable(proof.getCepPdfUrl() != null && !proof.getCepPdfUrl().isBlank());
        dto.setClaveRastreo(proof.getClaveRastreo());
        dto.setBankEmitter(proof.getBankEmitter());
        dto.setAccountReceiver(proof.getAccountReceiver());
        dto.setAmount(proof.getAmount());
        dto.setTransferDate(proof.getTransferDate() != null ? proof.getTransferDate().toString() : null);
        dto.setStatus(proof.getStatus().name());
        dto.setRejectionReason(proof.getRejectionReason());
        dto.setMissingFields(proof.getMissingFields());
        dto.setSubmittedAt(proof.getSubmittedAt() != null ? proof.getSubmittedAt().toString() : null);
        dto.setReviewedAt(proof.getReviewedAt() != null ? proof.getReviewedAt().toString() : null);
        dto.setReviewedBy(proof.getReviewedBy());

        // V57 — campos de tipo e intentos
        dto.setPaymentType(proof.getPaymentType());
        dto.setAttemptNumber(proof.getAttemptNumber());
        dto.setOwnerValidationNotes(proof.getOwnerValidationNotes());
        if (proof.getExpiresAt() != null) {
            dto.setExpiresAt(proof.getExpiresAt().toString());
            long hoursLeft = java.time.Duration.between(LocalDateTime.now(), proof.getExpiresAt()).toHours();
            dto.setHoursRemaining(Math.max(0, hoursLeft));
        }
        // V58 — Calcular attemptsRemaining según tipo + método de captura:
        //  - CASH: reglas V57 (3 pendientes + 3 fallos por factura)
        //  - SPEI AI_OCR: 6 por mes calendario por inquilino
        //  - SPEI MANUAL: ilimitado (MAX_VALUE como señal; el frontend lo oculta)
        try {
            PaymentProofAttemptPolicy.Decision d;
            if (proof.isCash()) {
                List<TransferProofSubmission> cashHistory =
                        proofRepository.findByInvoiceIdAndPaymentType(proof.getInvoiceId(), "CASH");
                d = PaymentProofAttemptPolicy.evaluateCash(cashHistory);
            } else if (proof.isAiOcr()) {
                java.time.LocalDateTime monthStart = java.time.LocalDate.now()
                        .withDayOfMonth(1).atStartOfDay();
                java.time.LocalDateTime nextMonthStart = monthStart.plusMonths(1);
                long aiOcrThisMonth = proofRepository.countSpeiSubmissionsInWindow(
                        proof.getTenantProfileId(), "AI_OCR", monthStart, nextMonthStart);
                d = PaymentProofAttemptPolicy.evaluateSpeiAiOcrMonthly(aiOcrThisMonth);
            } else {
                // SPEI manual → ilimitado. Usamos 0 prior para no bloquear.
                d = PaymentProofAttemptPolicy.evaluateSpeiManualMonthly(0);
            }
            dto.setAttemptsRemaining(d.allowed() ? d.attemptsRemaining() : 0);
        } catch (Exception ignore) {
            dto.setAttemptsRemaining(null);
        }

        if (invoice != null) {
            dto.setMonthYear(invoice.getMonthYear());
            profileRepository.findById(invoice.getTenantProfileId()).ifPresent(profile -> {
                userRepository.findById(profile.getUserId()).ifPresent(user -> {
                    dto.setTenantName(user.getName());
                    dto.setTenantEmail(user.getContactEmail());
                });
            });
        }

        return dto;
    }
}
