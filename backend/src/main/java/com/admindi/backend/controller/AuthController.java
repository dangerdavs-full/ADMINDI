package com.admindi.backend.controller;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import com.admindi.backend.dto.AuthRequest;
import com.admindi.backend.dto.AuthResponse;
import com.admindi.backend.dto.ChangePasswordRequest;
import com.admindi.backend.service.AccountActivationService;
import com.admindi.backend.service.UsernameService;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService service;
    private final AccountActivationService activationService;
    private final UsernameService usernameService;

    @Autowired
    public AuthController(AuthService service, AccountActivationService activationService,
                          UsernameService usernameService) {
        this.service = service;
        this.activationService = activationService;
        this.usernameService = usernameService;
    }

    /**
     * V48 / Bloque 2: utilidad anónima para validar disponibilidad de un username
     * antes de enviar el formulario. Devuelve sugerencia si está ocupado.
     * No expone si el conflicto viene de una cuenta activa o tombstone — sólo
     * responde el estado funcional (disponible / no disponible).
     */
    @GetMapping("/check-username")
    public ResponseEntity<Map<String, Object>> checkUsername(@RequestParam("username") String username) {
        String normalized = usernameService.normalize(username);
        boolean available;
        try {
            usernameService.ensureAvailable(normalized);
            available = true;
        } catch (com.admindi.backend.exception.UsernameTakenException ex) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("available", false);
            body.put("normalized", normalized);
            body.put("suggestion", ex.getSuggestion());
            return ResponseEntity.ok(body);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("available", available);
        body.put("normalized", normalized);
        return ResponseEntity.ok(body);
    }

    @PostMapping("/login")
    public ResponseEntity<AuthResponse> authenticate(@RequestBody AuthRequest request) {
        return ResponseEntity.ok(service.authenticate(request));
    }


    @PostMapping("/change-password")
    @PreAuthorize("isAuthenticated()") // Cualquiera autenticado puede cambiar su clave logueado
    public ResponseEntity<Void> changeMyPassword(@RequestBody ChangePasswordRequest request) {
        service.changePassword(request);
        return ResponseEntity.ok().build();
    }

    /**
     * V54: la URL sigue usando la clave legacy {@code {email}} para compat con
     * clientes antiguos, pero el valor efectivo es siempre el {@code username}
     * del target (ver {@link AuthService#forceReset}).
     */
    @PostMapping("/force-reset/{email}")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ResponseEntity<Map<String, String>> forceReset(@PathVariable String email) {
        String tempPass = service.forceReset(email);
        return ResponseEntity.ok(Map.of("tempPassword", tempPass));
    }

    @PostMapping("/complete-onboarding")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Void> completeOnboarding(@RequestBody OnboardingRequest request) {
        service.completeOnboarding(request);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/mfa/setup")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<MfaResponse> setupMfa() {
        return ResponseEntity.ok(service.setupMfa());
    }

    @PostMapping("/mfa/verify")
    public ResponseEntity<AuthResponse> verifyMfa(@RequestBody MfaRequest request) {
        return ResponseEntity.ok(service.verifyMfa(request));
    }

    @PostMapping("/super-admin")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ResponseEntity<java.util.Map<String, String>> createSuperAdmin(@RequestBody AuthRequest request) {
        String tempPass = service.createSuperAdmin(request.getUsername(), request.getPassword(), request.getName());
        return ResponseEntity.ok(java.util.Map.of("tempPassword", tempPass));
    }

    @GetMapping("/contexts")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<java.util.Map<String, Object>> getContexts() {
        return ResponseEntity.ok(service.getContexts());
    }

    public static class SelectContextRequest {
        public String contextId;
    }

    @PostMapping("/select-context")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<AuthResponse> selectContext(@RequestBody SelectContextRequest request) {
        return ResponseEntity.ok(service.selectContext(request.contextId));
    }

    /** Cambio de contexto con token FULL; BASE debe usar solo {@code /select-context}. */
    @PostMapping("/switch-context")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<AuthResponse> switchContext(@RequestBody SelectContextRequest request) {
        return ResponseEntity.ok(service.switchContext(request.contextId));
    }

    @PostMapping("/logout")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Void> logout() {
        service.logout();
        return ResponseEntity.noContent().build();
    }

    public static class RefreshRequest {
        public String refreshToken;
        /** Owner/contexto activo del cliente; el refresh valida y emite FULL scoped si sigue vigente. */
        public String contextId;
    }

    @PostMapping("/refresh")
    public ResponseEntity<AuthResponse> refresh(@RequestBody RefreshRequest request) {
        return ResponseEntity.ok(service.refresh(request.refreshToken, request.contextId));
    }

    // ─── Activación de cuenta (staff / agente / provider) ──────────────────
    //
    // Flujo: el creador (SUPER_ADMIN u OWNER) da de alta la cuenta; el backend
    // emite un token one-shot de 24h y lo envía por email + WhatsApp al user.
    // El user abre {APP_URL}/activate?token=xxx, esta pantalla llama:
    //   · GET  /api/auth/activation/info?token=xxx   → ver si el link es válido
    //   · POST /api/auth/activate                     → establecer contraseña
    //
    // Ambos endpoints son públicos (permitAll via /api/auth/**). El GET nunca
    // expone userId ni hash; solo nombre y email para que el user confirme
    // que el link es suyo.

    @GetMapping("/activation/info")
    public ResponseEntity<Map<String, Object>> getActivationInfo(@RequestParam("token") String token) {
        AccountActivationService.ActivationInfo info = activationService.inspect(token);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("usable", info.usable());
        body.put("userName", info.userName());
        body.put("userEmail", info.userEmail() == null ? null : maskEmail(info.userEmail()));
        body.put("expiresAt", info.expiresAt());
        return ResponseEntity.ok(body);
    }

    public static class ActivateRequest {
        public String token;
        public String newPassword;
    }

    @PostMapping("/activate")
    public ResponseEntity<Map<String, Object>> activate(@RequestBody ActivateRequest request,
                                                        HttpServletRequest http) {
        String ip = clientIp(http);
        try {
            activationService.consume(request.token, request.newPassword, ip);
            return ResponseEntity.ok(Map.of(
                    "ok", true,
                    "message", "Cuenta activada. Ya puedes iniciar sesión.",
                    "at", LocalDateTime.now()));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Map.of(
                    "ok", false,
                    "error", ex.getMessage()));
        }
    }

    private static String clientIp(HttpServletRequest req) {
        String fwd = req.getHeader("X-Forwarded-For");
        if (fwd != null && !fwd.isBlank()) {
            int comma = fwd.indexOf(',');
            return comma > 0 ? fwd.substring(0, comma).trim() : fwd.trim();
        }
        return req.getRemoteAddr();
    }

    private static String maskEmail(String e) {
        if (e == null) return null;
        int at = e.indexOf('@');
        if (at <= 1) return "***" + (at >= 0 ? e.substring(at) : "");
        return e.charAt(0) + "***" + e.substring(at);
    }
}
