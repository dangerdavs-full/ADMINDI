package com.admindi.backend.controller;

import com.admindi.backend.service.MercadoPagoService;
import com.admindi.backend.service.MercadoPagoWebhookValidator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.Map;

@RestController
@RequestMapping("/api/integrations/mercadopago")
public class MercadoPagoController {

    private final MercadoPagoService mpService;
    private final MercadoPagoWebhookValidator webhookValidator;

    @Autowired
    public MercadoPagoController(MercadoPagoService mpService,
                                   MercadoPagoWebhookValidator webhookValidator) {
        this.mpService = mpService;
        this.webhookValidator = webhookValidator;
    }

    /** Estado de configuración (sin exponer secretos). */
    @GetMapping("/status")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Map<String, Object>> status() {
        return ResponseEntity.ok(mpService.integrationStatus());
    }

    /** Inquilino: ¿el dueño ya vinculó MP para esta factura? */
    @GetMapping("/checkout-status/{invoiceId}")
    @PreAuthorize("hasRole('TENANT')")
    public ResponseEntity<Map<String, Object>> checkoutStatus(@PathVariable String invoiceId) {
        return ResponseEntity.ok(mpService.tenantCheckoutStatus(invoiceId));
    }

    /** Inquilino: crea preferencia Checkout Pro para pagar su factura de renta. */
    @PostMapping("/create-preference")
    @PreAuthorize("hasRole('TENANT')")
    public ResponseEntity<Map<String, String>> createPreference(@RequestBody Map<String, String> payload) {
        String invoiceId = payload.get("invoiceId");
        String tenantEmail = payload.get("tenantEmail");
        String tenantProfileId = payload.get("tenantProfileId");
        if (invoiceId == null || invoiceId.isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        BigDecimal amount = null;
        String amountStr = payload.get("amount");
        if (amountStr != null && !amountStr.isBlank()) {
            try {
                amount = new BigDecimal(amountStr.trim());
            } catch (NumberFormatException e) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Monto inválido.");
            }
        }
        return ResponseEntity.ok(mpService.createPreference(invoiceId, tenantEmail, tenantProfileId, amount));
    }

    /**
     * Webhook Mercado Pago — sin JWT. Valida {@code x-signature} si hay secreto configurado
     * y siempre consulta el pago en la API de MP antes de marcar la factura como pagada.
     */
    @PostMapping("/webhook")
    public ResponseEntity<Void> webhook(
            @RequestBody Map<String, Object> payload,
            @RequestHeader(value = "x-signature", required = false) String xSignature,
            @RequestHeader(value = "x-request-id", required = false) String xRequestId) {

        if (webhookValidator.isSignatureValidationEnabled()) {
            String dataId = webhookValidator.extractDataId(payload);
            if (!webhookValidator.isValid(dataId, xSignature, xRequestId)) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
            }
        }

        mpService.processWebhook(payload);
        return ResponseEntity.ok().build();
    }

    /** IPN legacy (GET) — algunas integraciones antiguas de MP. */
    @GetMapping("/webhook")
    public ResponseEntity<Void> webhookLegacy(
            @RequestParam(value = "topic", required = false) String topic,
            @RequestParam(value = "id", required = false) String id) {
        mpService.processLegacyIpn(topic, id);
        return ResponseEntity.ok().build();
    }

    /** Consulta estado de factura tras volver del checkout (inquilino o dueño con permiso). */
    @GetMapping("/payment-status/{invoiceId}")
    @PreAuthorize("hasAnyRole('TENANT','OWNER') or hasAuthority('PAYMENT_VIEW')")
    public ResponseEntity<Map<String, String>> paymentStatus(@PathVariable String invoiceId) {
        return ResponseEntity.ok(mpService.getPaymentStatus(invoiceId));
    }
}
