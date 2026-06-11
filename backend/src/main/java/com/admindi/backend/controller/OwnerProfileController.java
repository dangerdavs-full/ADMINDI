package com.admindi.backend.controller;

import com.admindi.backend.model.Role;
import com.admindi.backend.model.UserEntity;
import com.admindi.backend.repository.UserRepository;
import com.admindi.backend.service.BanxicoInstitutionCatalogService;
import com.admindi.backend.service.ClabeValidator;
import com.admindi.backend.service.DomainEventDispatcher;
import com.admindi.backend.service.ReauthService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Perfil del dueño (Fase 1 notificaciones).
 *
 * Endpoints self-service para que el OWNER consulte y actualice:
 *  - Datos de contacto (email, país+teléfono) — independientes del email de login.
 *  - Datos bancarios (CLABE, banco, titular) para que los arrendatarios reciban
 *    recordatorios de pago con la cuenta correcta.
 *
 * Seguridad:
 *  - Solo rol OWNER, y solo sobre su propio registro.
 *  - GET enmascara la CLABE (3 primeros + *** + 3 últimos). El valor completo se
 *    guarda cifrado en DB y nunca se entrega en claro a ningún cliente.
 *  - PUT exige reauth (password + MFA) porque cambiar CLABE o email equivale a
 *    redirigir transferencias o notificaciones — mismo criterio que
 *    {@link com.admindi.backend.controller.SuperAdminUserController#updateContact}.
 *  - Se dispara OWNER_PROFILE_UPDATED para que el dueño y otros stakeholders
 *    sean notificados del cambio (pista temprana de compromiso de cuenta).
 */
@RestController
@RequestMapping("/api/owner/profile")
@PreAuthorize("hasRole('OWNER')")
public class OwnerProfileController {

    private static final Logger logger = LoggerFactory.getLogger(OwnerProfileController.class);

    private final UserRepository userRepository;
    private final BanxicoInstitutionCatalogService banxicoInstitutionCatalogService;
    private final ReauthService reauthService;
    private final DomainEventDispatcher dispatcher;

    @Value("${app.url:http://localhost:3000}")
    private String appUrl;

    public OwnerProfileController(UserRepository userRepository,
                                  BanxicoInstitutionCatalogService banxicoInstitutionCatalogService,
                                  ReauthService reauthService,
                                  DomainEventDispatcher dispatcher) {
        this.userRepository = userRepository;
        this.banxicoInstitutionCatalogService = banxicoInstitutionCatalogService;
        this.reauthService = reauthService;
        this.dispatcher = dispatcher;
    }

    @GetMapping
    public ResponseEntity<Map<String, Object>> getMyProfile() {
        UserEntity me = resolveSelf();
        return ResponseEntity.ok(toResponse(me));
    }

    @PutMapping
    public ResponseEntity<Map<String, Object>> updateMyProfile(@RequestBody Map<String, String> body) {
        if (body == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Body requerido.");
        }
        String password = body.get("password");
        String mfaCode = body.get("mfaCode");
        reauthService.verifyReauth(password, mfaCode, "OWNER_PROFILE_UPDATE");

        UserEntity me = resolveSelf();

        boolean changedContact = false;
        boolean changedBank = false;
        List<String> changes = new java.util.ArrayList<>();

        // Contacto
        if (body.containsKey("contactEmail")) {
            String v = body.get("contactEmail");
            if (v != null && !v.isBlank() && !looksLikeEmail(v)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Email de contacto inválido.");
            }
            // V54 — contactEmail NO único, compartible. Normalizar (trim + lower)
            // y permitir null/blank para borrar.
            if (v == null || v.isBlank()) {
                me.setContactEmail(null);
            } else {
                me.setContactEmail(v.trim().toLowerCase());
            }
            changedContact = true;
            changes.add("contactEmail");
        }
        if (body.containsKey("contactPhone") || body.containsKey("contactCountryCode")) {
            String cc = body.getOrDefault("contactCountryCode", me.getContactCountryCode());
            String raw = body.getOrDefault("contactPhone", me.getContactPhone());
            if (raw != null && !raw.isBlank()) {
                String digits = raw.replaceAll("[^0-9]", "");
                if (digits.length() < 7) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "Teléfono inválido. Mínimo 7 dígitos.");
                }
                String code = cc == null || cc.isBlank() ? "+52"
                        : (cc.startsWith("+") ? cc : "+" + cc.replaceAll("[^0-9]", ""));
                me.setContactPhone(code + digits);
                me.setContactCountryCode(code);
                changedContact = true;
                changes.add("contactPhone");
            } else {
                me.setContactPhone(null);
                changedContact = true;
                changes.add("contactPhone");
            }
        }

        // Bancarios
        if (body.containsKey("clabe")) {
            String raw = body.get("clabe");
            if (raw == null || raw.isBlank()) {
                me.setClabe(null);
            } else {
                String digits = raw.replaceAll("\\s", "");
                if (!ClabeValidator.isValid(digits)) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "CLABE inválida. Debe tener 18 dígitos y dígito verificador correcto.");
                }
                me.setClabe(digits);
            }
            changedBank = true;
            changes.add("clabe");
        }
        if (body.containsKey("bankName")) {
            String v = body.get("bankName");
            me.setBankName(v == null || v.isBlank() ? null : v.trim());
            changedBank = true;
            changes.add("bankName");
        }
        if (body.containsKey("accountHolderName")) {
            String v = body.get("accountHolderName");
            me.setAccountHolderName(v == null || v.isBlank() ? null : v.trim());
            changedBank = true;
            changes.add("accountHolderName");
        }

        boolean shouldNormalizeBank = changedBank
                && (body.containsKey("clabe") || body.containsKey("bankName")
                || me.getBankName() == null || me.getBankName().isBlank());
        if (shouldNormalizeBank) {
            if (me.getClabe() == null || me.getClabe().isBlank()) {
                me.setBankName(null);
            } else {
                String canonicalBank = banxicoInstitutionCatalogService
                        .resolveReceiver(me.getClabe(), me.getBankName())
                        .map(BanxicoInstitutionCatalogService.ResolvedInstitution::name)
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                                "No pude identificar un banco Banxico válido para esa CLABE."));
                me.setBankName(canonicalBank);
                if (!changes.contains("bankName")) {
                    changes.add("bankName");
                }
            }
        }

        if (!changedContact && !changedBank) {
            return ResponseEntity.ok(toResponse(me));
        }

        userRepository.save(me);

        // Dispatch OWNER_PROFILE_UPDATED — recipient = el propio dueño (alerta defensiva).
        // El body NO incluye CLABE completa: solo los campos que cambiaron.
        String body1 = "Se actualizó tu perfil. Campos: " + String.join(", ", changes)
                + ". Si no fuiste tú, contacta al administrador de inmediato.";

        // Variables WhatsApp — plantilla admindi_owner_profile_updated_v3 (4 slots).
        // Si el body final aprobado por Meta tiene otra cantidad/orden de variables,
        // ajustar este mapa. NO se incluyen valores viejos ni CLABE completa.
        String when = LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"));
        Map<String, String> tplVars = Map.of(
                "1", me.getName() != null ? me.getName() : "",
                "2", when,
                "3", String.join(", ", changes),
                "4", appUrl != null ? appUrl : ""
        );

        try {
            dispatcher.dispatch(
                    "OWNER_PROFILE_UPDATED",
                    "Perfil actualizado",
                    body1,
                    me.getId(),
                    me.getUsername(),
                    List.of(me.getId()),
                    tplVars,
                    null
            );
        } catch (Exception e) {
            logger.warn("[OWNER_PROFILE] dispatch failed userId={} err={}", me.getId(), e.getMessage());
        }

        logger.info("[OWNER_PROFILE] updated ownerId={} fields={}", me.getId(), changes);
        return ResponseEntity.ok(toResponse(me));
    }

    private UserEntity resolveSelf() {
        String email = SecurityContextHolder.getContext().getAuthentication().getName();
        UserEntity u = userRepository.findByLoginIdentifier(email)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                        "Sesión inválida."));
        if (u.getRole() != Role.OWNER) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Este endpoint es solo para OWNER.");
        }
        return u;
    }

    private Map<String, Object> toResponse(UserEntity u) {
        Map<String, Object> out = new HashMap<>();
        out.put("id", u.getId());
        out.put("name", u.getName());
        // V54: un único email por user (contactEmail). Se emite también como
        // `email` para compat del frontend — ambos apuntan al mismo valor.
        String effectiveEmail = u.getContactEmail() != null ? u.getContactEmail() : "";
        out.put("email", effectiveEmail);
        out.put("contactEmail", effectiveEmail);
        out.put("contactPhone", u.getContactPhone() != null ? u.getContactPhone() : "");
        out.put("contactCountryCode", u.getContactCountryCode() != null ? u.getContactCountryCode() : "");
        out.put("clabeMasked", u.getClabe() != null ? ClabeValidator.mask(u.getClabe()) : "");
        out.put("hasClabe", u.getClabe() != null && !u.getClabe().isBlank());
        out.put("bankName", u.getBankName() != null ? u.getBankName() : "");
        out.put("accountHolderName", u.getAccountHolderName() != null ? u.getAccountHolderName() : "");
        return out;
    }

    private static boolean looksLikeEmail(String s) {
        if (s == null) return false;
        int at = s.indexOf('@');
        int dot = s.lastIndexOf('.');
        return at > 0 && dot > at + 1 && dot < s.length() - 1;
    }
}
