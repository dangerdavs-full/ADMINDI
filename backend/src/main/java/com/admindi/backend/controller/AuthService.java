package com.admindi.backend.controller;

import com.admindi.backend.dto.AuthRequest;
import com.admindi.backend.dto.AuthResponse;
import com.admindi.backend.dto.ChangePasswordRequest;
import com.admindi.backend.model.OwnerMembershipEntity;
import com.admindi.backend.model.PlatformProviderAssignmentEntity;
import com.admindi.backend.model.RefreshTokenSessionEntity;
import com.admindi.backend.model.Role;
import com.admindi.backend.model.TenantProfileEntity;
import com.admindi.backend.model.UserEntity;
import com.admindi.backend.repository.OwnerMembershipRepository;
import com.admindi.backend.repository.PlatformProviderAssignmentRepository;
import com.admindi.backend.repository.TenantProfileRepository;
import com.admindi.backend.repository.RefreshTokenSessionRepository;
import com.admindi.backend.repository.UserRepository;
import com.admindi.backend.security.JwtService;
import com.admindi.backend.security.TokenBlacklistService;
import com.admindi.backend.model.AuditEventEntity;
import com.admindi.backend.repository.AuditEventRepository;
import com.admindi.backend.service.PermissionService;
import dev.samstevens.totp.code.CodeVerifier;
import dev.samstevens.totp.exceptions.QrGenerationException;
import dev.samstevens.totp.qr.QrData;
import dev.samstevens.totp.qr.QrGenerator;
import dev.samstevens.totp.qr.ZxingPngQrGenerator;
import dev.samstevens.totp.secret.DefaultSecretGenerator;
import dev.samstevens.totp.secret.SecretGenerator;
import dev.samstevens.totp.util.Utils;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.LocalDateTime;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class AuthService {

    private final UserRepository userRepository;
    private final RefreshTokenSessionRepository refreshRepo;
    private final OwnerMembershipRepository membershipRepository;
    private final TenantProfileRepository tenantProfileRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final AuthenticationManager authenticationManager;
    private final AuditEventRepository auditEventRepository;
    private final PermissionService permissionService;
    private final TokenBlacklistService tokenBlacklistService;
    private final PlatformProviderAssignmentRepository providerAssignmentRepository;
    private final SecretGenerator secretGenerator = new DefaultSecretGenerator();
    private final QrGenerator qrGenerator = new ZxingPngQrGenerator();
    @Autowired
    private CodeVerifier codeVerifier;

    @Autowired
    public AuthService(UserRepository userRepository,
                       RefreshTokenSessionRepository refreshRepo,
                       OwnerMembershipRepository membershipRepository,
                       TenantProfileRepository tenantProfileRepository,
                       PasswordEncoder passwordEncoder,
                       JwtService jwtService,
                       AuthenticationManager authenticationManager,
                       AuditEventRepository auditEventRepository,
                       PermissionService permissionService,
                       TokenBlacklistService tokenBlacklistService,
                       PlatformProviderAssignmentRepository providerAssignmentRepository) {
        this.userRepository = userRepository;
        this.refreshRepo = refreshRepo;
        this.membershipRepository = membershipRepository;
        this.tenantProfileRepository = tenantProfileRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.authenticationManager = authenticationManager;
        this.auditEventRepository = auditEventRepository;
        this.permissionService = permissionService;
        this.tokenBlacklistService = tokenBlacklistService;
        this.providerAssignmentRepository = providerAssignmentRepository;
    }

    private void trackSession(String jti, String userId) {
        HttpServletRequest request = ((ServletRequestAttributes) RequestContextHolder.currentRequestAttributes()).getRequest();
        RefreshTokenSessionEntity session = new RefreshTokenSessionEntity();
        session.setId(jti);
        session.setUserId(userId);
        session.setIssuedAt(LocalDateTime.now());
        session.setExpiresAt(LocalDateTime.now().plusHours(24));
        session.setRevoked(false);
        session.setIpAddress(request.getRemoteAddr());
        session.setUserAgent(request.getHeader("User-Agent"));
        
        // Pseudo hashing token (Not strictly necessary if JTI is strong and DB sealed)
        session.setTokenHash("track_" + jti); 
        refreshRepo.save(session);
    }

    private static final java.util.Set<Role> MFA_REQUIRED_ROLES = java.util.Set.of(
        Role.SUPER_ADMIN, Role.OWNER, Role.PROPERTY_ADMIN, Role.ACCOUNTANT
    );

    public AuthResponse authenticate(AuthRequest request) {
        // V48: el login es por username (con email como alias transicional durante
        // la migración; ver AuthRequest.getLoginIdentifier). El DaoAuthenticationProvider
        // llama a UserDetailsService.loadByUsername, que a su vez hace findByUsername.
        String loginId = request.getLoginIdentifier();
        if (loginId == null) {
            throw new RuntimeException("Usuario/Email requerido.");
        }
        authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(loginId, request.getPassword())
        );
        UserEntity user = userRepository.findByUsername(loginId).orElseThrow();
        
        // Block soft-deleted users
        if (!user.isActive()) {
            throw new RuntimeException("Cuenta desactivada. Contacte al administrador.");
        }
        
        boolean mfaRequired = MFA_REQUIRED_ROLES.contains(user.getRole());
        
        if (user.isMfaEnabled()) {
            // MFA configured: issue a short-lived challenge token that proves first factor passed
            String challengeToken = jwtService.generateMfaChallengeToken(user);
            AuthResponse resp = new AuthResponse(challengeToken, "null", user.isMustChangePassword(), user.getRole().name(), user.getName(), user.isOnboardingCompleted());
            resp.setMfaChallengeToken(challengeToken);
            return resp;
        }
        
        if (mfaRequired && !user.isMfaEnabled()) {
            // MFA mandatory but not yet configured: issue an MFA_CHALLENGE token
            // This allows the user to call /mfa/setup and then /mfa/verify
            String challengeToken = jwtService.generateMfaChallengeToken(user);
            AuthResponse resp = new AuthResponse(challengeToken, "null", user.isMustChangePassword(), user.getRole().name(), user.getName(), user.isOnboardingCompleted());
            resp.setMfaSetupRequired(true);
            resp.setMfaChallengeToken(challengeToken);
            return resp;
        }

        return finalizeLogin(user);
    }

    private AuthResponse finalizeLogin(UserEntity user) {
        return issueAccessAfterVerification(user, null);
    }

    /**
     * Emite access + refresh (rotación) tras login/MFA/refresh. {@code requestedContextId} se usa en refresh
     * para conservar el owner activo (inquilino multi-dueño y staff multi-org).
     */
    private AuthResponse issueAccessAfterVerification(UserEntity user, String requestedContextId) {
        String refreshJwt = jwtService.generateRefreshToken(user);
        String refreshJti = jwtService.extractClaim(refreshJwt, claims -> claims.getId());
        trackSession(refreshJti, user.getId());

        String ctxParam = requestedContextId != null ? requestedContextId.trim() : "";

        if (user.getRole() == Role.SUPER_ADMIN) {
            String accessToken = jwtService.generateFullToken(user, null, user);
            AuthResponse resp = new AuthResponse(accessToken, "null", user.isMustChangePassword(), user.getRole().name(), user.getName(), user.isOnboardingCompleted());
            resp.setRefreshToken(refreshJwt);
            return resp;
        }

        if (user.getRole() == Role.OWNER) {
            String accessToken = jwtService.generateFullToken(user, user.getId(), user);
            AuthResponse resp = new AuthResponse(accessToken, user.getId(), user.isMustChangePassword(), user.getRole().name(), user.getName(), user.isOnboardingCompleted());
            resp.setRefreshToken(refreshJwt);
            return resp;
        }

        if (user.getRole() == Role.TENANT) {
            LinkedHashMap<String, String> orgs = buildTenantOwnerContexts(user);
            if (orgs.isEmpty()) {
                throw new RuntimeException(
                        "Arrendatario sin expedientes u organización asignada. Contacte a su administrador.");
            }
            if (orgs.size() > 1) {
                if (!ctxParam.isEmpty() && orgs.containsKey(ctxParam) && tenantHasAccessToOwner(user, ctxParam)) {
                    AuthResponse resp = buildFullAuthResponseForOwner(user, ctxParam);
                    resp.setRefreshToken(refreshJwt);
                    return resp;
                }
                String baseToken = jwtService.generateBaseToken(user, user);
                AuthResponse resp = new AuthResponse(baseToken, orgs, user.getName());
                enrichOrgSelectionResponse(resp, user, refreshJwt);
                return resp;
            }
            String onlyOwner = orgs.keySet().iterator().next();
            AuthResponse resp = buildFullAuthResponseForOwner(user, onlyOwner);
            resp.setRefreshToken(refreshJwt);
            return resp;
        }

        // PROPERTY_ADMIN, ACCOUNTANT, REAL_ESTATE_AGENT, MAINTENANCE_PROVIDER — sin contexto en body: BASE (comportamiento histórico)
        LinkedHashMap<String, String> orgs = buildStaffOwnerContexts(user);
        if (orgs.isEmpty()) {
            throw new RuntimeException("Usuario sin organizaciones asignadas.");
        }
        if (ctxParam.isEmpty()) {
            String baseToken = jwtService.generateBaseToken(user, user);
            AuthResponse resp = new AuthResponse(baseToken, orgs, user.getName());
            enrichOrgSelectionResponse(resp, user, refreshJwt);
            return resp;
        }
        if (orgs.containsKey(ctxParam) && staffHasAccessToOwner(user, ctxParam)) {
            AuthResponse resp = buildFullAuthResponseForOwner(user, ctxParam);
            resp.setRefreshToken(refreshJwt);
            return resp;
        }
        String baseToken = jwtService.generateBaseToken(user, user);
        AuthResponse resp = new AuthResponse(baseToken, orgs, user.getName());
        enrichOrgSelectionResponse(resp, user, refreshJwt);
        return resp;
    }

    private AuthResponse buildFullAuthResponseForOwner(UserEntity user, String ownerId) {
        List<String> resolvedPerms = permissionService.resolvePermissions(user.getId(), ownerId);
        String accessJwt = jwtService.generateFullToken(user, ownerId, user, resolvedPerms);
        return new AuthResponse(accessJwt, ownerId, user.isMustChangePassword(), user.getRole().name(), user.getName(), user.isOnboardingCompleted());
    }

    private boolean tenantHasAccessToOwner(UserEntity user, String targetOwnerId) {
        if (targetOwnerId == null || targetOwnerId.isBlank()) {
            return false;
        }
        if (membershipRepository.findByUserIdAndOwnerId(user.getId(), targetOwnerId).isPresent()) {
            return true;
        }
        if (targetOwnerId.equals(user.getOwnerId())) {
            return true;
        }
        return tenantProfileRepository.existsByUserIdAndOwnerIdAndArchivedAtIsNull(user.getId(), targetOwnerId);
    }

    /**
     * ¿El staff tiene acceso a ese dueño?
     *
     * <p>Fuentes reconocidas (cualquiera basta para autorizar):</p>
     * <ol>
     *   <li>Legacy {@code users.owner_id} igual al objetivo, o el usuario ES el owner
     *       raíz (SUPER_ADMIN actuando sobre su propio id).</li>
     *   <li>Fila activa en {@code owner_memberships} (PROPERTY_ADMIN y ACCOUNTANT
     *       creados vía alta de staff — {@code StaffService.ensureOwnerMembership}).</li>
     *   <li>Fila ACTIVA en {@code platform_provider_assignments} (REAL_ESTATE_AGENT
     *       y MAINTENANCE_PROVIDER vinculados desde el panel del dueño — el flujo
     *       de proveedores NUNCA toca {@code owner_memberships}, por eso este tercer
     *       caso es indispensable para que los proveedores y agentes puedan iniciar
     *       sesión después de ser vinculados).</li>
     * </ol>
     */
    private boolean staffHasAccessToOwner(UserEntity user, String targetOwnerId) {
        if (targetOwnerId == null || targetOwnerId.isBlank()) {
            return false;
        }
        if (targetOwnerId.equals(user.getOwnerId()) || targetOwnerId.equals(user.getId())) {
            return true;
        }
        if (membershipRepository.findByUserId(user.getId()).stream()
                .anyMatch(m -> m.getOwnerId().equals(targetOwnerId))) {
            return true;
        }
        if (isProviderRole(user.getRole())) {
            return providerAssignmentRepository
                    .findByProviderIdAndOwnerId(user.getId(), targetOwnerId)
                    .filter(PlatformProviderAssignmentEntity::isActive)
                    .isPresent();
        }
        return false;
    }

    /**
     * Construye el mapa de contextos (ownerId → nombre) que el staff puede elegir al
     * iniciar sesión.
     *
     * <p><b>Bug histórico corregido aquí</b>: la versión previa solo leía
     * {@code owner_memberships}, por lo que los agentes inmobiliarios y proveedores
     * de mantenimiento — que se vinculan al dueño únicamente vía
     * {@code platform_provider_assignments} (con {@code source = PLATFORM | PRIVATE})
     * — aparecían sin contextos al loguearse y obtenían "Usuario sin organizaciones
     * asignadas", pese a estar correctamente vinculados.</p>
     *
     * <p>Hoy fusiona ambas fuentes: memberships (staff admin/contador) + provider
     * assignments activos (agentes/proveedores). Si un mismo owner aparece en las
     * dos tablas, el nombre se conserva una sola vez (es un {@link LinkedHashMap}).</p>
     */
    private LinkedHashMap<String, String> buildStaffOwnerContexts(UserEntity user) {
        LinkedHashMap<String, String> orgs = new LinkedHashMap<>();
        for (OwnerMembershipEntity m : membershipRepository.findByUserId(user.getId())) {
            userRepository.findById(m.getOwnerId()).ifPresent(o -> orgs.putIfAbsent(o.getId(), o.getName()));
        }
        if (isProviderRole(user.getRole())) {
            for (PlatformProviderAssignmentEntity a
                    : providerAssignmentRepository.findByProviderIdAndActiveTrue(user.getId())) {
                userRepository.findById(a.getOwnerId())
                        .filter(UserEntity::isActive)
                        .ifPresent(o -> orgs.putIfAbsent(o.getId(), o.getName()));
            }
        }
        if (user.getOwnerId() != null && !user.getOwnerId().isBlank()) {
            userRepository.findById(user.getOwnerId()).ifPresent(o -> orgs.putIfAbsent(o.getId(), o.getName()));
            if (!orgs.containsKey(user.getOwnerId())) {
                orgs.put(user.getOwnerId(), "Contexto predeterminado");
            }
        }
        return orgs;
    }

    /**
     * Proveedores de plataforma y agentes inmobiliarios son los únicos roles cuyo
     * vínculo con dueños vive en {@code platform_provider_assignments}.
     */
    private static boolean isProviderRole(Role role) {
        return role == Role.MAINTENANCE_PROVIDER || role == Role.REAL_ESTATE_AGENT;
    }

    /**
     * Owners available to a tenant: {@link OwnerMembershipEntity} + {@link TenantProfileEntity} + legacy {@link UserEntity#getOwnerId()}.
     */
    private LinkedHashMap<String, String> buildTenantOwnerContexts(UserEntity user) {
        LinkedHashMap<String, String> orgs = new LinkedHashMap<>();
        for (OwnerMembershipEntity m : membershipRepository.findByUserId(user.getId())) {
            userRepository.findById(m.getOwnerId()).ifPresent(o -> orgs.putIfAbsent(o.getId(), o.getName()));
        }
        for (TenantProfileEntity p : tenantProfileRepository.findByUserId(user.getId())) {
            if (p.getArchivedAt() != null) {
                continue;
            }
            userRepository.findById(p.getOwnerId()).ifPresent(o -> orgs.putIfAbsent(o.getId(), o.getName()));
        }
        if (user.getOwnerId() != null && !user.getOwnerId().isBlank()) {
            userRepository.findById(user.getOwnerId()).ifPresent(o -> orgs.putIfAbsent(o.getId(), o.getName()));
        }
        return orgs;
    }

    private void enrichOrgSelectionResponse(AuthResponse resp, UserEntity user, String refreshJwt) {
        resp.setRole(user.getRole().name());
        resp.setMustChangePassword(user.isMustChangePassword());
        resp.setOnboardingCompleted(user.isOnboardingCompleted());
        resp.setRefreshToken(refreshJwt);
    }

    public void changePassword(ChangePasswordRequest request) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        UserEntity user = userRepository.findByUsername(auth.getName()).orElseThrow();
        user.setPassword(passwordEncoder.encode(request.getNewPassword()));
        user.setMustChangePassword(false);
        userRepository.save(user);
    }

    /**
     * V54 — el parámetro {@code identifier} es exclusivamente el username del
     * usuario a resetear. El fallback por email ya no existe (el campo
     * users.email fue eliminado en V54). SUPER_ADMIN dispara este flujo desde
     * el panel.
     */
    public String forceReset(String identifier) {
        UserEntity user = userRepository.findByLoginIdentifier(identifier)
                .orElseThrow(() -> new RuntimeException("User not found"));
        
        byte[] randomBytes = new byte[8];
        new SecureRandom().nextBytes(randomBytes);
        String tempPass = Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);
        
        user.setPassword(passwordEncoder.encode(tempPass));
        user.setMustChangePassword(true);
        userRepository.save(user);
        
        // Mock $500 MXN Penalty creation for future Invoice coupling
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        String adminMail = (auth != null && auth.getName() != null) ? auth.getName() : "SYSTEM";
        AuditEventEntity audit = new AuditEventEntity();
        audit.setId(UUID.randomUUID().toString());
        audit.setTimestamp(LocalDateTime.now());
        audit.setActorId(adminMail);
        audit.setEventType("FEE_ADMIN_RESET_PASSWORD_CHARGED");
        audit.setResourceType("Invoices");
        audit.setResourceId(user.getId()); // Target owner to charge
        audit.setNewValues("{\"amount\": 500.00, \"currency\": \"MXN\", \"reason\": \"MFA / Password administrative bypass\"}");
        auditEventRepository.save(audit);
        
        return tempPass;
    }

    public void completeOnboarding(OnboardingRequest request) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        UserEntity user = userRepository.findByUsername(auth.getName()).orElseThrow();
        user.setOnboardingCompleted(true);
        if (request != null) {
            if (request.getUsePlatformMaintenance() != null) {
                user.setUsePlatformMaintenance(request.getUsePlatformMaintenance());
            }
            if (request.getUsePlatformAgents() != null) {
                user.setUsePlatformAgents(request.getUsePlatformAgents());
            }
        }
        userRepository.save(user);
    }

    /**
     * Alta administrativa de un nuevo SUPER_ADMIN.
     *
     * <p>V50 — la identidad primaria es {@code username}. El parámetro
     * {@code username} se normaliza (lowercase, trim) y queda sujeto a las mismas
     * reglas que el resto de cuentas. El SUPER_ADMIN no tiene datos de contacto
     * (invariante de dominio: no recibe notificaciones); se fuerzan email,
     * contactEmail y contactPhone a {@code null}.</p>
     *
     * @return la contraseña tal cual fue enviada (el caller controla la política
     *         de entrega al humano; nunca se devuelve en logs).
     */
    public String createSuperAdmin(String username, String password, String name) {
        // V51 — case-sensitive: sólo trim, no lowercase.
        String loginId = username == null ? null : username.trim();
        if (loginId == null || loginId.isBlank()) {
            throw new RuntimeException("Username del super admin requerido.");
        }
        if (password == null || password.isBlank()) {
            throw new RuntimeException("Password del super admin requerido.");
        }
        if (userRepository.findByUsername(loginId).isPresent()) {
            throw new RuntimeException("Usuario ya existe");
        }
        UserEntity admin = new UserEntity();
        admin.setId(UUID.randomUUID().toString());
        admin.setLoginUsername(loginId);
        // Invariante: SUPER_ADMIN no recibe notificaciones. V54: el campo
        // users.email ya no existe; contactEmail/contactPhone quedan null.
        admin.setContactEmail(null);
        admin.setContactPhone(null);
        admin.setPassword(passwordEncoder.encode(password));
        admin.setName(name != null ? name : "Super Admin");
        admin.setRole(Role.SUPER_ADMIN);
        admin.setMustChangePassword(true);
        admin.setOnboardingCompleted(true);
        admin.setActive(true);
        userRepository.save(admin);
        return password;
    }
    
    public MfaResponse setupMfa() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        UserEntity user = userRepository.findByUsername(auth.getName()).orElseThrow();
        
        String secret = secretGenerator.generate();
        user.setMfaSecret(secret);
        // Ensure mfa is not activated until they verify it!
        userRepository.save(user);
        
        // V50: etiqueta del authenticator = username (identidad primaria). Antes se
        // usaba email, que podía ser NULL (SA) o no único, y algunas apps TOTP
        // muestran labels duplicados entre cuentas con el mismo correo.
        QrData data = new QrData.Builder()
            .label(user.getUsername())
            .secret(secret)
            .issuer("ADMINDI")
            .build();
            
        try {
            byte[] qrBytes = qrGenerator.generate(data);
            String mimeType = qrGenerator.getImageMimeType();
            String uri = Utils.getDataUriForImage(qrBytes, mimeType);
            return new MfaResponse(uri, secret);
        } catch (QrGenerationException e) {
            throw new RuntimeException("Error generating QR code", e);
        }
    }
    
    public AuthResponse verifyMfa(MfaRequest request) {
        // Validate the challenge token to prove first factor was passed
        String challengeToken = request.getChallengeToken();
        if (challengeToken == null || challengeToken.isEmpty()) {
            throw new RuntimeException("Missing MFA challenge token. Login again.");
        }

        String tokenType;
        String tokenSubject;
        try {
            tokenType = jwtService.extractType(challengeToken);
            tokenSubject = jwtService.extractUsername(challengeToken); // post V48 = username
        } catch (Exception e) {
            throw new RuntimeException("Invalid or expired MFA challenge token.");
        }

        // V50: el token MFA_CHALLENGE fue emitido con subject=username. El cliente
        // debe re-enviar el MISMO username en {@code MfaRequest.username}; si lo
        // envió bajo la clave legacy {@code "email"}, el setter @Deprecated lo
        // normaliza a {@code username} antes de llegar aquí.
        if (!"MFA_CHALLENGE".equals(tokenType)) {
            throw new RuntimeException("Invalid token type for MFA verification.");
        }
        String claimedId = request.getUsername();
        if (claimedId == null || claimedId.isBlank()) {
            throw new RuntimeException("Username requerido para verificar MFA.");
        }
        // V51 — case-sensitive: sólo trim.
        String normalizedClaimed = claimedId.trim();
        if (!normalizedClaimed.equals(tokenSubject)) {
            throw new RuntimeException("Token/usuario mismatch.");
        }

        UserEntity user = userRepository.findByUsername(normalizedClaimed)
            .orElseThrow(() -> new RuntimeException("User not found"));
            
        if (user.getMfaSecret() == null) {
            throw new RuntimeException("MFA not configured");
        }
        
        if (codeVerifier.isValidCode(user.getMfaSecret(), request.getCode())) {
            if (!user.isMfaEnabled()) {
                user.setMfaEnabled(true);
                userRepository.save(user);
            }
            return finalizeLogin(user);
        } else {
            throw new RuntimeException("Invalid MFA code");
        }
    }

    public Map<String, Object> getContexts() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        UserEntity user = userRepository.findByUsername(auth.getName()).orElseThrow();

        if (user.getRole() == Role.SUPER_ADMIN) {
             return Map.of("contexts", List.of(Map.of("id", "null", "name", "Administración Global")));
        }

        if (user.getRole() == Role.TENANT) {
            LinkedHashMap<String, String> orgs = buildTenantOwnerContexts(user);
            List<Map<String, Object>> contexts = orgs.entrySet().stream()
                    .map(e -> Map.<String, Object>of("id", e.getKey(), "name", e.getValue()))
                    .toList();
            return Map.of("contexts", contexts);
        }

        // Staff, providers y owners — reutilizamos buildStaffOwnerContexts para que la
        // lista refleje tanto owner_memberships (admin/contador) como
        // platform_provider_assignments (agente/proveedor). Antes este endpoint solo
        // leía memberships y dejaba a los providers sin contexto pese a estar vinculados.
        LinkedHashMap<String, String> orgs = buildStaffOwnerContexts(user);
        List<Map<String, Object>> contexts = orgs.entrySet().stream()
                .map(e -> Map.<String, Object>of("id", e.getKey(), "name", e.getValue()))
                .toList();
        return Map.of("contexts", contexts);
    }

    public AuthResponse selectContext(String targetOwnerId) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        UserEntity user = userRepository.findByUsername(auth.getName()).orElseThrow();

        final String effectiveTargetOwnerId;
        if (user.getRole() != Role.SUPER_ADMIN) {
            // Delegamos la verificación a los métodos específicos por rol para
            // mantener una sola fuente de verdad sobre qué tablas autorizan el
            // contexto (owner_memberships + tenant_profiles para TENANT;
            // owner_memberships + platform_provider_assignments para staff).
            boolean hasAccess = (user.getRole() == Role.TENANT)
                    ? tenantHasAccessToOwner(user, targetOwnerId)
                    : staffHasAccessToOwner(user, targetOwnerId);
            if (!hasAccess) {
                throw new RuntimeException("Acceso denegado a este contexto.");
            }
            effectiveTargetOwnerId = targetOwnerId;
        } else {
            effectiveTargetOwnerId = "null".equals(targetOwnerId) ? null : targetOwnerId;
        }

        // Resolve fine-grained permissions from grants for this user+owner pair
        List<String> resolvedPerms = effectiveTargetOwnerId != null
            ? permissionService.resolvePermissions(user.getId(), effectiveTargetOwnerId)
            : List.of();
        String accessJwt = jwtService.generateFullToken(user, effectiveTargetOwnerId, user, resolvedPerms);
        String refreshJwt = jwtService.generateRefreshToken(user);
        trackSession(jwtService.extractClaim(refreshJwt, claims -> claims.getId()), user.getId());
        AuthResponse resp = new AuthResponse(accessJwt, effectiveTargetOwnerId == null ? "null" : effectiveTargetOwnerId, user.isMustChangePassword(), user.getRole().name(), user.getName(), user.isOnboardingCompleted());
        resp.setRefreshToken(refreshJwt);
        return resp;
    }

    /**
     * Cambio de contexto con sesión ya contextualizada: exige token FULL (BASE lo bloquea {@code JwtAuthenticationFilter}).
     */
    public AuthResponse switchContext(String targetOwnerId) {
        HttpServletRequest req = ((ServletRequestAttributes) RequestContextHolder.currentRequestAttributes()).getRequest();
        String authHeader = req.getHeader("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            throw new RuntimeException("Missing bearer token");
        }
        String jwt = authHeader.substring(7);
        String tokenType;
        try {
            tokenType = jwtService.extractType(jwt);
        } catch (Exception e) {
            throw new RuntimeException("Invalid access token");
        }
        if (!"FULL".equals(tokenType)) {
            throw new RuntimeException("switch-context requiere token FULL");
        }
        return selectContext(targetOwnerId);
    }

    /**
     * Logout: blacklist del JTI del access Bearer actual y revocación de todas las sesiones refresh del usuario
     * (BD + Redis JTI por sesión, alineado a recuperación de cuenta).
     */
    public void logout() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            throw new RuntimeException("Not authenticated");
        }
        UserEntity user = userRepository.findByUsername(auth.getName()).orElseThrow();

        HttpServletRequest request = ((ServletRequestAttributes) RequestContextHolder.currentRequestAttributes()).getRequest();
        String authHeader = request.getHeader("Authorization");
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            String jwt = authHeader.substring(7);
            try {
                String type = jwtService.extractType(jwt);
                if (!"REFRESH".equals(type) && !"MFA_CHALLENGE".equals(type)) {
                    String jti = jwtService.extractClaim(jwt, claims -> claims.getId());
                    tokenBlacklistService.revokeToken(jti);
                }
            } catch (Exception ignored) {
                // continuar con revocación refresh
            }
        }

        for (RefreshTokenSessionEntity s : refreshRepo.findByUserId(user.getId())) {
            if (!s.isRevoked()) {
                s.setRevoked(true);
                refreshRepo.save(s);
                tokenBlacklistService.revokeToken(s.getId());
            }
        }
    }

    public AuthResponse refresh(String refreshToken, String requestedContextId) {
        // Validate it's actually a REFRESH token
        String tokenType;
        try {
            tokenType = jwtService.extractType(refreshToken);
        } catch (Exception e) {
            throw new RuntimeException("Invalid refresh token");
        }
        if (!"REFRESH".equals(tokenType)) {
            throw new RuntimeException("Token is not a refresh token");
        }

        String subjectUsername = jwtService.extractUsername(refreshToken);
        UserEntity user = userRepository.findByUsername(subjectUsername).orElseThrow();
        
        if (!jwtService.isTokenValid(refreshToken, user)) {
            throw new RuntimeException("Invalid or expired refresh token");
        }

        if (!user.isActive()) {
            throw new RuntimeException("Account deactivated");
        }
        
        String jti = jwtService.extractClaim(refreshToken, claims -> claims.getId());
        if (jti != null) {
            refreshRepo.findById(jti).ifPresent(session -> {
                 if (session.isRevoked()) {
                     // Token Reuse Detection — revoke ALL sessions for this user
                     List<RefreshTokenSessionEntity> allSessions = refreshRepo.findByUserId(user.getId());
                     for(RefreshTokenSessionEntity s : allSessions) {
                         s.setRevoked(true);
                         refreshRepo.save(s);
                     }
                     throw new RuntimeException("Token reuse detected. All sessions revoked.");
                 }
                 // Revoke the old refresh token
                 session.setRevoked(true);
                 refreshRepo.save(session);
            });
        }
        
        // Issue new access + refresh pair (rotation), preserving owner context when válido
        return issueAccessAfterVerification(user, requestedContextId);
    }
}
