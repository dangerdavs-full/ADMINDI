package com.admindi.backend.service;

import com.admindi.backend.model.InvoiceEntity;
import com.admindi.backend.model.PaymentEntity;
import com.admindi.backend.model.PaymentMethod;
import com.admindi.backend.model.PaymentStatus;
import com.admindi.backend.model.Role;
import com.admindi.backend.model.TenantProfileEntity;
import com.admindi.backend.model.UserEntity;
import com.admindi.backend.repository.InvoiceRepository;
import com.admindi.backend.repository.PaymentRepository;
import com.admindi.backend.repository.TenantProfileRepository;
import com.admindi.backend.repository.UserRepository;
import com.admindi.backend.security.TenantContext;
import com.mercadopago.MercadoPagoConfig;
import com.mercadopago.client.payment.PaymentClient;
import com.mercadopago.client.preference.PreferenceBackUrlsRequest;
import com.mercadopago.client.preference.PreferenceClient;
import com.mercadopago.client.preference.PreferenceItemRequest;
import com.mercadopago.client.preference.PreferencePayerRequest;
import com.mercadopago.client.preference.PreferenceRequest;
import com.mercadopago.exceptions.MPApiException;
import com.mercadopago.exceptions.MPException;
import com.mercadopago.core.MPRequestOptions;
import com.mercadopago.resources.payment.Payment;
import com.mercadopago.resources.preference.Preference;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Integración Mercado Pago Checkout Pro para pago de renta por inquilinos.
 * Cobros con el access token del dueño (vendedor). El token de plataforma
 * ({@code MP_ACCESS_TOKEN}) solo se usa para webhooks / API integrador.
 */
@Service
public class MercadoPagoService {

    private static final Logger logger = LoggerFactory.getLogger(MercadoPagoService.class);

    /** Mínimo permitido para abono parcial vía Checkout Pro (MXN). */
    private static final BigDecimal MIN_TENANT_PAYMENT_AMOUNT = new BigDecimal("1.00");

    /** Frontend local: back_urls del checkout (ngrok solo webhook/OAuth). */
    private static final String LOCAL_FRONTEND_URL = "http://localhost:3000";

    /** Token de la aplicación ADMINDI (webhooks, OAuth). No es el del dueño. */
    @Value("${mercadopago.access-token:}")
    private String integratorAccessToken;

    @Value("${app.url:http://localhost:3000}")
    private String appUrl;

    @Value("${mercadopago.notification-url:}")
    private String notificationUrlOverride;

    private final InvoiceRepository invoiceRepository;
    private final PaymentRepository paymentRepository;
    private final DomainEventDispatcher dispatcher;
    private final PropertyMovementService propertyMovementService;
    private final UserRepository userRepository;
    private final TenantProfileRepository tenantProfileRepository;
    private final OwnerMercadoPagoService ownerMercadoPagoService;

    public MercadoPagoService(InvoiceRepository invoiceRepository,
                              PaymentRepository paymentRepository,
                              DomainEventDispatcher dispatcher,
                              PropertyMovementService propertyMovementService,
                              UserRepository userRepository,
                              TenantProfileRepository tenantProfileRepository,
                              OwnerMercadoPagoService ownerMercadoPagoService) {
        this.invoiceRepository = invoiceRepository;
        this.paymentRepository = paymentRepository;
        this.dispatcher = dispatcher;
        this.propertyMovementService = propertyMovementService;
        this.userRepository = userRepository;
        this.tenantProfileRepository = tenantProfileRepository;
        this.ownerMercadoPagoService = ownerMercadoPagoService;
    }

    @PostConstruct
    void initSdk() {
        if (isIntegratorConfigured()) {
            MercadoPagoConfig.setAccessToken(integratorAccessToken.trim());
            logger.info("[MercadoPago] SDK integrador inicializado (webhooks/OAuth)");
        } else {
            logger.warn("[MercadoPago] Sin MP_ACCESS_TOKEN de plataforma — webhooks pueden fallar");
        }
    }

    public boolean isIntegratorConfigured() {
        return integratorAccessToken != null
                && !integratorAccessToken.isBlank()
                && !integratorAccessToken.startsWith("TEST-mock");
    }

    public Map<String, Object> integrationStatus() {
        return Map.of(
                "integratorConfigured", isIntegratorConfigured(),
                "mode", isIntegratorConfigured() ? "live" : "mock"
        );
    }

    /** Para inquilino: ¿el dueño de esta factura ya vinculó MP? */
    public Map<String, Object> tenantCheckoutStatus(String invoiceId) {
        InvoiceEntity invoice = authorizeInvoiceForTenantPayment(invoiceId, null);
        boolean ownerConnected = ownerMercadoPagoService.isOwnerConnected(invoice.getOwnerId());
        if (!ownerConnected) {
            logger.info("[MercadoPago] Inquilino: dueño sin MP vinculado invoice={} ownerId={}",
                    invoiceId, invoice.getOwnerId());
        }
        return Map.of(
                "invoiceId", invoiceId,
                "ownerMpConnected", ownerConnected,
                "canPayWithMp", ownerConnected
        );
    }

    /**
     * Crea preferencia Checkout Pro para que el inquilino pague su factura.
     *
     * @param requestedAmount monto elegido por el inquilino; {@code null} = saldo pendiente completo
     */
    public Map<String, String> createPreference(String invoiceId, String tenantEmail,
                                                  String tenantProfileIdParam, BigDecimal requestedAmount) {
        InvoiceEntity invoice = authorizeInvoiceForTenantPayment(invoiceId, tenantProfileIdParam);

        if ("PAID".equals(invoice.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "La factura ya está pagada.");
        }

        if (!ownerMercadoPagoService.isOwnerConnected(invoice.getOwnerId())) {
            throw new ResponseStatusException(HttpStatus.PRECONDITION_FAILED,
                    "Tu arrendador aún no ha vinculado Mercado Pago. Pide que lo configure en Mi perfil.");
        }

        String ownerToken = ownerMercadoPagoService.requireOwnerAccessToken(invoice.getOwnerId());
        MPRequestOptions ownerOpts = MPRequestOptions.builder().accessToken(ownerToken).build();

        try {
            BigDecimal amount = resolvePreferenceAmount(invoice, requestedAmount);
            if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "No hay saldo pendiente para pagar en esta factura.");
            }
            String title = "Renta " + (invoice.getMonthYear() != null ? invoice.getMonthYear() : invoice.getId());

            PreferenceItemRequest item = PreferenceItemRequest.builder()
                    .id(invoice.getId())
                    .title(title)
                    .description("Pago de arrendamiento ADMINDI")
                    .quantity(1)
                    .currencyId("MXN")
                    .unitPrice(amount)
                    .build();

            String returnBase = resolveCheckoutReturnBase(ownerToken);
            PreferenceBackUrlsRequest backUrls = PreferenceBackUrlsRequest.builder()
                    .success(returnBase + "/dashboard?mp=success&invoiceId=" + invoice.getId())
                    .failure(returnBase + "/dashboard?mp=failure&invoiceId=" + invoice.getId())
                    .pending(returnBase + "/dashboard?mp=pending&invoiceId=" + invoice.getId())
                    .build();

            PreferencePayerRequest payer = (tenantEmail != null && !tenantEmail.isBlank())
                    ? PreferencePayerRequest.builder().email(tenantEmail.trim()).build()
                    : null;

            var preferenceBuilder = PreferenceRequest.builder()
                    .items(List.of(item))
                    .externalReference(invoice.getId())
                    .backUrls(backUrls);
            // auto_return + ngrok interstitial o sandbox TEST rompen Safari (bucle de redirecciones).
            if (shouldEnableAutoReturn(returnBase, ownerToken)) {
                preferenceBuilder.autoReturn("approved");
            }
            String notificationUrl = resolveNotificationUrl();
            if (notificationUrl != null && notificationUrl.startsWith("https://")) {
                preferenceBuilder.notificationUrl(notificationUrl);
            }
            if (payer != null) {
                preferenceBuilder.payer(payer);
            }

            Preference preference = new PreferenceClient().create(preferenceBuilder.build(), ownerOpts);
            String checkoutUrl = resolveCheckoutUrl(preference, ownerToken);
            if (checkoutUrl == null || checkoutUrl.isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                        "Mercado Pago no devolvió URL de checkout.");
            }

            boolean sandboxCheckout = checkoutUrl.contains("sandbox.mercadopago");
            logger.info("[MercadoPago] Preferencia {} factura {} monto={} sandbox={} returnBase={}",
                    preference.getId(), invoiceId, amount, sandboxCheckout, returnBase);

            return Map.of(
                    "preferenceId", preference.getId(),
                    "checkoutUrl", checkoutUrl,
                    // Legado: mismo URL que checkoutUrl (no forzar sandbox si el dueño es producción).
                    "sandboxUrl", checkoutUrl,
                    "invoiceId", invoiceId,
                    "amount", amount.toPlainString(),
                    "sandboxMode", String.valueOf(sandboxCheckout)
            );
        } catch (MPApiException e) {
            String mpBody = e.getApiResponse() != null ? e.getApiResponse().getContent() : "";
            logger.error("[MercadoPago] API error creando preferencia: {} {} body={}",
                    e.getStatusCode(), e.getMessage(), mpBody);
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    mapPreferenceError(e.getStatusCode(), mpBody));
        } catch (MPException e) {
            logger.error("[MercadoPago] Error SDK: {}", e.getMessage());
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Error de conexión con Mercado Pago.");
        }
    }

    /**
     * Webhook IPN / Webhooks v1. En modo live consulta el pago en la API de MP (no confía solo en el body).
     */
    @Transactional
    public void processWebhook(Map<String, Object> payload) {
        if (!isIntegratorConfigured()) {
            processMockWebhook(payload);
            return;
        }

        String type = payload.get("type") != null ? payload.get("type").toString() : "";
        if (!"payment".equalsIgnoreCase(type)) {
            logger.debug("[MercadoPago] Ignorando notificación type={}", type);
            return;
        }

        Object dataObj = payload.get("data");
        if (!(dataObj instanceof Map<?, ?> dataMap)) {
            logger.warn("[MercadoPago] Webhook payment sin data.id");
            return;
        }
        Object idObj = dataMap.get("id");
        if (idObj == null) {
            logger.warn("[MercadoPago] Webhook payment sin data.id");
            return;
        }

        try {
            Long paymentId = Long.parseLong(idObj.toString());
            fetchAndApplyPayment(paymentId);
        } catch (NumberFormatException e) {
            logger.warn("[MercadoPago] data.id inválido: {}", idObj);
        } catch (MPApiException e) {
            logger.error("[MercadoPago] No se pudo obtener pago {}: {}", idObj, e.getMessage());
        } catch (MPException e) {
            logger.error("[MercadoPago] Error SDK obteniendo pago {}: {}", idObj, e.getMessage());
        }
    }

    /** IPN legacy: GET ?topic=payment&id=123 */
    @Transactional
    public void processLegacyIpn(String topic, String resourceId) {
        if (!isIntegratorConfigured()) {
            return;
        }
        if (!"payment".equalsIgnoreCase(topic) || resourceId == null || resourceId.isBlank()) {
            return;
        }
        try {
            Long paymentId = Long.parseLong(resourceId.trim());
            fetchAndApplyPayment(paymentId);
        } catch (Exception e) {
            logger.warn("[MercadoPago] IPN legacy falló topic={} id={}: {}", topic, resourceId, e.getMessage());
        }
    }

    /**
     * Consulta el pago con el token del dueño de la factura (el cobro fue a su cuenta MP).
     */
    private void fetchAndApplyPayment(Long paymentId) throws MPException, MPApiException {
        Payment probe = new PaymentClient().get(paymentId);
        String invoiceId = probe.getExternalReference();
        if (invoiceId == null || invoiceId.isBlank()) {
            logger.warn("[MercadoPago] Pago {} sin external_reference", paymentId);
            return;
        }
        InvoiceEntity invoice = invoiceRepository.findById(invoiceId).orElse(null);
        if (invoice == null) {
            logger.warn("[MercadoPago] Factura no encontrada: {}", invoiceId);
            return;
        }
        if (!ownerMercadoPagoService.isOwnerConnected(invoice.getOwnerId())) {
            logger.warn("[MercadoPago] Dueño sin MP vinculado para factura {}", invoiceId);
            return;
        }
        String ownerToken = ownerMercadoPagoService.requireOwnerAccessToken(invoice.getOwnerId());
        MPRequestOptions ownerOpts = MPRequestOptions.builder().accessToken(ownerToken).build();
        Payment mpPayment = new PaymentClient().get(paymentId, ownerOpts);
        applyPaymentFromMercadoPago(mpPayment, invoice);
    }

    private void applyPaymentFromMercadoPago(Payment mpPayment, InvoiceEntity invoice) {
        if (mpPayment == null || invoice == null) {
            return;
        }
        String invoiceId = invoice.getId();
        if (mpPayment.getExternalReference() != null
                && !invoiceId.equals(mpPayment.getExternalReference())) {
            logger.warn("[MercadoPago] external_reference {} no coincide con factura {}",
                    mpPayment.getExternalReference(), invoiceId);
            return;
        }

        UserEntity owner = userRepository.findById(invoice.getOwnerId()).orElse(null);
        if (owner != null && owner.getMpUserId() != null && !owner.getMpUserId().startsWith("pending")
                && mpPayment.getCollectorId() != null) {
            String collector = String.valueOf(mpPayment.getCollectorId());
            if (!owner.getMpUserId().equals(collector)) {
                logger.warn("[MercadoPago] Pago {} collector={} no es el dueño mpUserId={} factura {}",
                        mpPayment.getId(), collector, owner.getMpUserId(), invoiceId);
                return;
            }
        }

        String status = mpPayment.getStatus();
        if (!"approved".equalsIgnoreCase(status)) {
            logger.info("[MercadoPago] Pago {} status={} — sin marcar factura {}", mpPayment.getId(), status, invoiceId);
            return;
        }

        BigDecimal paid = mpPayment.getTransactionAmount() != null
                ? mpPayment.getTransactionAmount().setScale(2, RoundingMode.HALF_UP)
                : null;
        if (paid == null || paid.compareTo(BigDecimal.ZERO) <= 0) {
            logger.warn("[MercadoPago] Pago {} sin monto válido factura {}", mpPayment.getId(), invoiceId);
            return;
        }

        applyMercadoPagoSettlement(invoice, String.valueOf(mpPayment.getId()), paid);
    }

    public Map<String, String> getPaymentStatus(String invoiceId) {
        InvoiceEntity invoice = authorizeInvoiceRead(invoiceId);
        return Map.of(
                "invoiceId", invoiceId,
                "invoiceStatus", invoice.getStatus(),
                "paymentGatewayRef", invoice.getPaymentGatewayRef() != null ? invoice.getPaymentGatewayRef() : "",
                "paid", String.valueOf("PAID".equals(invoice.getStatus()))
        );
    }

    // ─── Autorización ───────────────────────────────────────────────────

    private InvoiceEntity authorizeInvoiceForTenantPayment(String invoiceId, String tenantProfileIdParam) {
        UserEntity user = resolveCurrentUser();
        if (user.getRole() != Role.TENANT) {
            throw new AccessDeniedException("Solo inquilinos pueden iniciar pago Mercado Pago.");
        }
        String ownerCtx = TenantContext.getCurrentOwner();
        if (ownerCtx == null || ownerCtx.isBlank()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Sin contexto de organización.");
        }

        InvoiceEntity invoice = invoiceRepository.findById(invoiceId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Factura no encontrada."));

        if (!ownerCtx.equals(invoice.getOwnerId())) {
            throw new AccessDeniedException("Esta factura no pertenece a tu organización.");
        }

        List<TenantProfileEntity> profiles =
                tenantProfileRepository.findByUserIdAndOwnerIdAndArchivedAtIsNull(user.getId(), ownerCtx);

        if (tenantProfileIdParam != null && !tenantProfileIdParam.isBlank()) {
            TenantProfileEntity p = tenantProfileRepository.findByIdAndUserId(tenantProfileIdParam, user.getId())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "Expediente no válido."));
            if (!invoice.getTenantProfileId().equals(p.getId())) {
                throw new AccessDeniedException("La factura no corresponde a este expediente.");
            }
        } else {
            boolean owns = profiles.stream()
                    .anyMatch(p -> p.getId().equals(invoice.getTenantProfileId()));
            if (!owns) {
                throw new AccessDeniedException("No tienes permiso para pagar esta factura.");
            }
        }
        return invoice;
    }

    private InvoiceEntity authorizeInvoiceRead(String invoiceId) {
        UserEntity user = resolveCurrentUser();
        InvoiceEntity invoice = invoiceRepository.findById(invoiceId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Factura no encontrada."));

        if (user.getRole() == Role.TENANT) {
            String ownerCtx = TenantContext.getCurrentOwner();
            if (ownerCtx == null || !ownerCtx.equals(invoice.getOwnerId())) {
                throw new AccessDeniedException("Factura no accesible.");
            }
            boolean owns = tenantProfileRepository.findByUserIdAndOwnerIdAndArchivedAtIsNull(user.getId(), ownerCtx)
                    .stream()
                    .anyMatch(p -> p.getId().equals(invoice.getTenantProfileId()));
            if (!owns) {
                throw new AccessDeniedException("Factura no accesible.");
            }
            return invoice;
        }

        String ownerId = TenantContext.resolveOwnerId(userRepository);
        if (!ownerId.equals(invoice.getOwnerId())) {
            throw new AccessDeniedException("Factura no accesible.");
        }
        return invoice;
    }

    private UserEntity resolveCurrentUser() {
        String username = SecurityContextHolder.getContext().getAuthentication().getName();
        return userRepository.findByUsername(username)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Usuario no encontrado."));
    }

    // ─── Confirmación de pago ───────────────────────────────────────────

    /**
     * Aplica un pago MP a la factura (total, parcial o con excedente), alineado con el motor SPEI.
     */
    @Transactional
    protected void applyMercadoPagoSettlement(InvoiceEntity invoice, String mpPaymentId, BigDecimal transferAmount) {
        String invoiceId = invoice.getId();

        if (paymentRepository.existsByGatewayReferenceAndStatus(mpPaymentId, PaymentStatus.CONFIRMED)) {
            logger.info("[MercadoPago] Pago MP {} ya registrado", mpPaymentId);
            return;
        }

        BigDecimal currentPaid = invoice.getPaidAmount() != null ? invoice.getPaidAmount() : BigDecimal.ZERO;
        BigDecimal totalOwed = invoice.getTotalAmount() != null ? invoice.getTotalAmount() : invoice.getBaseAmount();
        BigDecimal outstanding = totalOwed.subtract(currentPaid);
        if (outstanding.compareTo(BigDecimal.ZERO) < 0) {
            outstanding = BigDecimal.ZERO;
        }

        BigDecimal applied;
        BigDecimal unapplied;
        if (transferAmount.compareTo(outstanding) >= 0) {
            applied = outstanding;
            unapplied = transferAmount.subtract(outstanding);
        } else {
            applied = transferAmount;
            unapplied = BigDecimal.ZERO;
        }

        PaymentEntity payment = new PaymentEntity();
        payment.setInvoiceId(invoiceId);
        payment.setOwnerId(invoice.getOwnerId());
        payment.setTenantProfileId(invoice.getTenantProfileId());
        payment.setAmount(transferAmount);
        payment.setAppliedAmount(applied);
        payment.setUnappliedAmount(unapplied);
        payment.setPaymentMethod(PaymentMethod.MERCADO_PAGO);
        payment.setGatewayReference(mpPaymentId);
        payment.setStatus(PaymentStatus.CONFIRMED);
        payment.setPaidAt(LocalDateTime.now());
        payment.setConfirmedBy("MERCADO_PAGO");
        payment.setConfirmedAt(LocalDateTime.now());

        BigDecimal newPaidAmount = currentPaid.add(applied);
        BigDecimal newOutstanding = totalOwed.subtract(newPaidAmount);
        BigDecimal newCredit = (invoice.getCreditBalance() != null ? invoice.getCreditBalance() : BigDecimal.ZERO)
                .add(unapplied);

        invoice.setPaidAmount(newPaidAmount);
        invoice.setOutstandingAmount(newOutstanding.compareTo(BigDecimal.ZERO) < 0 ? BigDecimal.ZERO : newOutstanding);
        invoice.setCreditBalance(newCredit);
        invoice.setPaymentMethod(PaymentMethod.MERCADO_PAGO);
        invoice.setPaymentGatewayRef(mpPaymentId);

        boolean fullyPaid = newPaidAmount.compareTo(totalOwed) >= 0;
        if (fullyPaid) {
            invoice.setStatus("PAID");
            invoice.setPaidDate(LocalDate.now());
            invoice.setOutstandingAmount(BigDecimal.ZERO);
            if (unapplied.compareTo(BigDecimal.ZERO) > 0) {
                invoice.setSettlementStatus("OVERPAID");
                payment.setNotes("Mercado Pago. Excedente: $" + unapplied.toPlainString() + " registrado como saldo a favor.");
            } else {
                invoice.setSettlementStatus("PAID");
                payment.setNotes("Mercado Pago. Pago completo.");
            }
        } else {
            invoice.setStatus("PARTIALLY_PAID");
            invoice.setSettlementStatus("PARTIALLY_PAID");
            payment.setNotes("Mercado Pago. Abono parcial: $" + applied.toPlainString()
                    + " de $" + totalOwed.toPlainString() + ". Pendiente: $" + invoice.getOutstandingAmount().toPlainString());
        }

        paymentRepository.save(payment);
        invoiceRepository.save(invoice);

        try {
            propertyMovementService.recordPaymentMovement(invoice, payment);
        } catch (Exception ex) {
            logger.warn("[Timeline] MP payment movement skipped: {}", ex.getMessage());
        }

        String eventTitle = fullyPaid
                ? "Pago Mercado Pago confirmado: " + invoice.getMonthYear()
                : "Abono Mercado Pago registrado: " + invoice.getMonthYear();
        String eventBody = "ID MP: " + mpPaymentId + " | Monto: $" + transferAmount.toPlainString()
                + (fullyPaid ? "" : " | Pendiente: $" + invoice.getOutstandingAmount().toPlainString());
        dispatcher.dispatch("PAYMENT_MP_CONFIRMED", eventTitle, eventBody,
                invoice.getOwnerId(), "MERCADO_PAGO", null);

        logger.info("[MercadoPago] Pago aplicado factura {} (MP ID: {}) applied={} outstanding={}",
                invoiceId, mpPaymentId, applied, invoice.getOutstandingAmount());
    }

    private void processMockWebhook(Map<String, Object> payload) {
        String type = payload.get("type") != null ? payload.get("type").toString() : "";
        if (!"payment".equals(type)) {
            return;
        }
        String invoiceId = payload.get("external_reference") != null ? payload.get("external_reference").toString() : null;
        String mpStatus = payload.get("status") != null ? payload.get("status").toString() : "approved";
        String mpPaymentId = payload.get("payment_id") != null
                ? payload.get("payment_id").toString()
                : "MOCK-PAY-" + UUID.randomUUID().toString().substring(0, 8);

        if (invoiceId == null) {
            return;
        }
        InvoiceEntity invoice = invoiceRepository.findById(invoiceId).orElse(null);
        if (invoice == null || !"approved".equals(mpStatus)) {
            return;
        }
        BigDecimal mockAmount = resolveOutstandingBalance(invoice);
        applyMercadoPagoSettlement(invoice, mpPaymentId, mockAmount);
    }

    private Map<String, String> createMockPreference(InvoiceEntity invoice) {
        String invoiceId = invoice.getId();
        String mockPreferenceId = "MOCK-PREF-" + UUID.randomUUID().toString().substring(0, 8);
        String mockCheckoutUrl = "https://www.mercadopago.com.mx/checkout/v1/redirect?pref_id=" + mockPreferenceId;
        logger.info("[MercadoPago] Mock preference para factura {}", invoiceId);
        return Map.of(
                "preferenceId", mockPreferenceId,
                "checkoutUrl", mockCheckoutUrl,
                "sandboxUrl", "https://sandbox.mercadopago.com.mx/checkout/v1/redirect?pref_id=" + mockPreferenceId,
                "invoiceId", invoiceId,
                "amount", invoice.getTotalAmount().toPlainString()
        );
    }

    private String resolveNotificationUrl() {
        if (notificationUrlOverride != null && !notificationUrlOverride.isBlank()) {
            return notificationUrlOverride.trim();
        }
        return null;
    }

    private static BigDecimal resolveOutstandingBalance(InvoiceEntity invoice) {
        BigDecimal currentPaid = invoice.getPaidAmount() != null ? invoice.getPaidAmount() : BigDecimal.ZERO;
        BigDecimal totalOwed = invoice.getTotalAmount() != null ? invoice.getTotalAmount() : invoice.getBaseAmount();
        BigDecimal computed = totalOwed.subtract(currentPaid).setScale(2, RoundingMode.HALF_UP);
        if (computed.compareTo(BigDecimal.ZERO) > 0) {
            return computed;
        }
        if (invoice.getOutstandingAmount() != null
                && invoice.getOutstandingAmount().compareTo(BigDecimal.ZERO) > 0) {
            return invoice.getOutstandingAmount().setScale(2, RoundingMode.HALF_UP);
        }
        if (invoice.getTotalAmount() != null) {
            return invoice.getTotalAmount().setScale(2, RoundingMode.HALF_UP);
        }
        return invoice.getBaseAmount() != null
                ? invoice.getBaseAmount().setScale(2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;
    }

    private static BigDecimal resolvePreferenceAmount(InvoiceEntity invoice, BigDecimal requestedAmount) {
        BigDecimal outstanding = resolveOutstandingBalance(invoice);
        if (outstanding.compareTo(BigDecimal.ZERO) <= 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "No hay saldo pendiente para pagar en esta factura.");
        }

        if (requestedAmount == null) {
            return outstanding;
        }

        BigDecimal amount = requestedAmount.setScale(2, RoundingMode.HALF_UP);
        if (amount.compareTo(MIN_TENANT_PAYMENT_AMOUNT) < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "El monto mínimo a abonar es $" + MIN_TENANT_PAYMENT_AMOUNT.toPlainString() + " MXN.");
        }
        if (amount.compareTo(outstanding) > 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "El monto no puede superar el saldo pendiente ($" + outstanding.toPlainString() + " MXN).");
        }
        return amount;
    }

    private String normalizeAppUrl() {
        if (appUrl == null || appUrl.isBlank()) {
            return LOCAL_FRONTEND_URL;
        }
        return appUrl.endsWith("/") ? appUrl.substring(0, appUrl.length() - 1) : appUrl;
    }

    /**
     * URLs de retorno del checkout: frontend local en dev. Ngrok solo para webhook y OAuth.
     */
    private String resolveCheckoutReturnBase(String ownerToken) {
        String base = normalizeAppUrl();
        if (base.contains("localhost") || base.contains("127.0.0.1")) {
            return base;
        }
        if (base.contains("ngrok")) {
            logger.info("[MercadoPago] back_urls → localhost (evita bucle Safari/ngrok interstitial)");
            return LOCAL_FRONTEND_URL;
        }
        if (ownerToken != null && ownerToken.startsWith("TEST-")) {
            return LOCAL_FRONTEND_URL;
        }
        return base;
    }

    private static boolean shouldEnableAutoReturn(String returnBase, String ownerToken) {
        if (returnBase == null || !returnBase.startsWith("https://")) {
            return false;
        }
        if (returnBase.contains("ngrok") || returnBase.contains("localhost")) {
            return false;
        }
        return ownerToken == null || !ownerToken.startsWith("TEST-");
    }

    /**
     * Token TEST- → sandbox_init_point; APP_USR producción → init_point (evita bucle sandbox/prod).
     */
    private static String resolveCheckoutUrl(Preference preference, String ownerToken) {
        String sandbox = preference.getSandboxInitPoint();
        String production = preference.getInitPoint();
        boolean testSellerToken = ownerToken != null && ownerToken.startsWith("TEST-");
        if (testSellerToken && sandbox != null && !sandbox.isBlank()) {
            return sandbox;
        }
        if (production != null && !production.isBlank()) {
            return production;
        }
        return sandbox;
    }

    private static String mapPreferenceError(int statusCode, String mpBody) {
        if (mpBody != null) {
            String lower = mpBody.toLowerCase();
            if (lower.contains("auto_return") || lower.contains("back_url")) {
                return "Mercado Pago no aceptó las URLs de retorno. En producción usa HTTPS en app.url.";
            }
            if (lower.contains("unauthorized") || statusCode == 401) {
                return "El token de Mercado Pago del arrendador expiró. Pide que vuelva a vincular su cuenta en Mi perfil.";
            }
        }
        if (statusCode == 401) {
            return "El token de Mercado Pago del arrendador expiró. Pide que vuelva a vincular su cuenta en Mi perfil.";
        }
        return "No se pudo crear el checkout en Mercado Pago. "
                + "En pruebas, el arrendador debe pagar con cuenta de prueba vendedora y el inquilino con usuario de prueba comprador.";
    }
}
