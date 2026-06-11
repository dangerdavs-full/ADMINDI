package com.admindi.backend.controller;

import com.admindi.backend.service.OwnerMercadoPagoService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

/**
 * Callback OAuth Mercado Pago (sin JWT — MP redirige aquí tras autorizar).
 */
@RestController
@RequestMapping("/api/integrations/mercadopago/owner/oauth")
public class MercadoPagoOAuthCallbackController {

    private final OwnerMercadoPagoService ownerMpService;

    public MercadoPagoOAuthCallbackController(OwnerMercadoPagoService ownerMpService) {
        this.ownerMpService = ownerMpService;
    }

    @GetMapping("/callback")
    public ResponseEntity<Void> callback(
            @RequestParam(value = "code", required = false) String code,
            @RequestParam(value = "state", required = false) String state) {
        String redirect = ownerMpService.handleOAuthCallback(code, state);
        return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(redirect)).build();
    }
}
