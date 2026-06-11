package com.admindi.backend.controller;

import com.admindi.backend.model.UserEntity;
import com.admindi.backend.service.OwnerMercadoPagoService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.Map;

/**
 * Vinculación de cuenta Mercado Pago del dueño (vendedor que recibe la renta).
 */
@RestController
@RequestMapping("/api/integrations/mercadopago/owner")
@PreAuthorize("hasRole('OWNER')")
public class OwnerMercadoPagoController {

    private final OwnerMercadoPagoService ownerMpService;

    public OwnerMercadoPagoController(OwnerMercadoPagoService ownerMpService) {
        this.ownerMpService = ownerMpService;
    }

    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> status() {
        UserEntity owner = ownerMpService.resolveOwnerFromSecurity();
        return ResponseEntity.ok(ownerMpService.getConnectionStatus(owner));
    }

    /** Devuelve la URL de OAuth para abrirla desde el frontend (con JWT). */
    @GetMapping("/oauth/authorize-url")
    public ResponseEntity<Map<String, String>> oauthAuthorizeUrl() {
        UserEntity owner = ownerMpService.resolveOwnerFromSecurity();
        return ResponseEntity.ok(Map.of("authorizationUrl", ownerMpService.buildAuthorizationUrl(owner)));
    }

    /**
     * Pruebas: pegar Access Token de prueba desde developers.mercadopago.com
     * (Credenciales de prueba → Access token).
     */
    @PostMapping("/link-token")
    public ResponseEntity<Map<String, Object>> linkToken(@RequestBody Map<String, String> body) {
        UserEntity owner = ownerMpService.resolveOwnerFromSecurity();
        String token = body != null ? body.get("accessToken") : null;
        return ResponseEntity.ok(ownerMpService.linkWithAccessToken(owner, token));
    }

    @DeleteMapping("/disconnect")
    public ResponseEntity<Map<String, Object>> disconnect() {
        UserEntity owner = ownerMpService.resolveOwnerFromSecurity();
        ownerMpService.disconnect(owner);
        return ResponseEntity.ok(ownerMpService.getConnectionStatus(owner));
    }
}
