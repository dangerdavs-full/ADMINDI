package com.admindi.backend.service;

import com.admindi.backend.dto.OwnerDTO;
import com.admindi.backend.model.Role;
import com.admindi.backend.model.UserEntity;
import com.admindi.backend.repository.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class OwnerService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final DomainEventDispatcher dispatcher;
    private final MaintenanceProviderService providerService;
    private final PlatformProviderAssignmentRepository assignmentRepo;
    private final NotificationRepository notifRepo;
    private final ActionTaskRepository taskRepo;
    private final NotificationPreferenceRepository prefRepo;
    private final PermissionGrantRepository grantRepo;
    private final AuditEventRepository auditRepo;
    private final ReauthService reauthService;
    private final UsernameService usernameService;

    @Value("${app.url:http://localhost:3000}")
    private String appUrl;

    @Autowired
    public OwnerService(UserRepository userRepository, PasswordEncoder passwordEncoder,
                        DomainEventDispatcher dispatcher,
                        MaintenanceProviderService providerService,
                        PlatformProviderAssignmentRepository assignmentRepo,
                        NotificationRepository notifRepo,
                        ActionTaskRepository taskRepo,
                        NotificationPreferenceRepository prefRepo,
                        PermissionGrantRepository grantRepo,
                        AuditEventRepository auditRepo,
                        ReauthService reauthService,
                        UsernameService usernameService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.dispatcher = dispatcher;
        this.providerService = providerService;
        this.assignmentRepo = assignmentRepo;
        this.notifRepo = notifRepo;
        this.taskRepo = taskRepo;
        this.prefRepo = prefRepo;
        this.grantRepo = grantRepo;
        this.auditRepo = auditRepo;
        this.reauthService = reauthService;
        this.usernameService = usernameService;
    }

    public List<OwnerDTO> getAllOwners() {
        return userRepository.findAll().stream()
                .filter(u -> u.getRole() == Role.OWNER && u.isActive())
                .map(this::mapToDTO)
                .collect(Collectors.toList());
    }

    public OwnerDTO getOwner(String id) {
        UserEntity owner = userRepository.findById(id)
            .filter(u -> u.getRole() == Role.OWNER && u.isActive())
            .orElseThrow(() -> new RuntimeException("Owner not found"));
        return mapToDTO(owner);
    }

    @Transactional
    public OwnerDTO createOwner(OwnerDTO dto) {
        // V54 — username + email + teléfono son OBLIGATORIOS.
        //   · username: identificador de login canónico (case-sensitive, único).
        //   · email: contactEmail del user — único email real, NO único global
        //     (varias cuentas pueden compartirlo). El DTO expone `email` como
        //     alias de `contactEmail`.
        //   · teléfono: canal operativo WhatsApp (Twilio), NO único.
        if (dto.getUsername() == null || dto.getUsername().isBlank()) {
            throw new RuntimeException("Usuario para iniciar sesión es obligatorio.");
        }
        if (dto.getContactEmail() == null || dto.getContactEmail().isBlank()) {
            throw new RuntimeException("Email es obligatorio.");
        }
        if (dto.getName() == null || dto.getName().isBlank()) {
            throw new RuntimeException("Nombre es obligatorio.");
        }
        if ((dto.getCountryCode() == null || dto.getRawPhone() == null ||
             dto.getRawPhone().replaceAll("[^0-9]", "").length() < 7)) {
            throw new RuntimeException("Teléfono de contacto con lada es obligatorio (mínimo 7 dígitos).");
        }

        // V54 — sink único: `contactEmail` (normalizado trim + lower). No hay
        // checks de colisión porque el campo ya no es único.
        final String normalizedEmail = dto.getContactEmail().trim().toLowerCase();

        // Username: explícito desde el form. UsernameService valida unicidad
        // global y lanza UsernameTakenException con suggestion si colisiona.
        String username = usernameService.resolveOrDerive(dto.getUsername(), normalizedEmail);

        // Normalize phone
        dto.normalizePhone();

        UserEntity owner = new UserEntity();
        owner.setLoginUsername(username);
        owner.setName(dto.getName().trim());
        owner.setPhone(dto.getPhone());
        owner.setRole(Role.OWNER);
        owner.setMustChangePassword(true);

        owner.setContactEmail(normalizedEmail);
        owner.setContactPhone(dto.getPhone());
        owner.setContactCountryCode(dto.getCountryCode());

        // Generate temp password
        byte[] randomBytes = new byte[8];
        new SecureRandom().nextBytes(randomBytes);
        String tempPass = Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);
        owner.setPassword(passwordEncoder.encode(tempPass));

        // First save to get generated ID
        owner = userRepository.saveAndFlush(owner);
        String actualOwnerId = owner.getId();
        // Set ownerId = id (self-referencing for owner context)
        owner.setOwnerId(actualOwnerId);
        owner = userRepository.saveAndFlush(owner);

        // Auto-assign all platform providers to this new owner
        providerService.assignAllProvidersToOwner(actualOwnerId);

        // OWNER_WELCOME — evento visible al usuario (antes era OWNER_CREATED interno).
        // El cuerpo incluye datos que el dueño necesita para su primer login: email,
        // contraseña temporal (obligatorio cambiarla) y URL del portal. Es seguro incluir
        // la contraseña temporal aquí porque `mustChangePassword=true` la invalida al
        // primer login. El canal EMAIL y WhatsApp (Twilio) entregan el mismo cuerpo;
        // IN_APP queda como fallback si no hay email/WhatsApp configurado.
        String createdBy = resolveActorEmail();
        final UserEntity savedOwner = owner;
        String welcomeBody = buildOwnerWelcomeBody(savedOwner, tempPass);

        // Variables WhatsApp — plantilla admindi_owner_welcome_v2 (3 slots).
        // V54: el único email del user es contactEmail. El texto Meta-aprobado
        // dice "Correo registrado: {{2}}" y así queda: WhatsApp es "teaser"
        // amistoso para avisar que ya hay cuenta; las credenciales reales
        // (username + temp password) viajan por email al contactEmail. El login
        // sigue siendo username case-sensitive — no se muestra aquí porque el
        // email es autoritativo y ya incluye el username + temp password.
        // NO incluir temp password en WhatsApp (solo email/IN_APP) por política
        // de seguridad: WhatsApp no es canal de credenciales.
        String contactEmailForTpl = savedOwner.getContactEmail() != null
                ? savedOwner.getContactEmail() : "";
        Map<String, String> tplVars = Map.of(
                "1", savedOwner.getName() != null ? savedOwner.getName() : "",
                "2", contactEmailForTpl,
                "3", appUrl != null ? appUrl : ""
        );

        dispatcher.dispatch(
            "OWNER_WELCOME",
            "Bienvenido a ADMINDI",
            welcomeBody,
            actualOwnerId,
            createdBy,
            List.of(actualOwnerId),
            tplVars
        );

        // Build response
        OwnerDTO savedDto = mapToDTO(owner);
        savedDto.setTempPassword(tempPass);
        return savedDto;
    }

    /**
     * Update contact information only (not login credentials).
     */
    public OwnerDTO updateOwnerContact(String id, OwnerDTO dto) {
        UserEntity owner = userRepository.findById(id)
            .filter(u -> u.getRole() == Role.OWNER && u.isActive())
            .orElseThrow(() -> new RuntimeException("Owner not found"));

        boolean changed = false;

        if (dto.getContactEmail() != null && !dto.getContactEmail().isBlank()) {
            // V54 — contactEmail normalizado (trim + lower). NO único, compartible.
            owner.setContactEmail(dto.getContactEmail().trim().toLowerCase());
            changed = true;
        }

        if (dto.getContactCountryCode() != null && dto.getRawPhone() != null) {
            String digits = dto.getRawPhone().replaceAll("[^0-9]", "");
            if (digits.length() < 7) {
                throw new RuntimeException("Teléfono inválido. Mínimo 7 dígitos.");
            }
            String code = dto.getContactCountryCode().startsWith("+") ? dto.getContactCountryCode() : "+" + dto.getContactCountryCode();
            String normalized = code + digits;
            owner.setContactPhone(normalized);
            owner.setContactCountryCode(code);
            owner.setPhone(normalized);
            changed = true;
        }

        if (dto.getName() != null && !dto.getName().isBlank()) {
            owner.setName(dto.getName().trim());
            changed = true;
        }

        if (changed) {
            userRepository.save(owner);
            String updatedBy = resolveActorEmail();
            dispatcher.dispatch(
                "OWNER_CONTACT_UPDATED",
                "Datos de contacto actualizados: " + owner.getName(),
                "Se actualizaron los datos de contacto del dueño " + owner.getName(),
                owner.getOwnerId(),
                updatedBy
            );
        }

        return mapToDTO(owner);
    }

    public OwnerDTO updateOwner(String id, OwnerDTO dto) {
        return updateOwnerContact(id, dto);
    }

    public void deleteOwner(String id) {
        UserEntity owner = userRepository.findById(id)
            .filter(u -> u.getRole() == Role.OWNER)
            .orElseThrow(() -> new RuntimeException("Owner not found"));

        // Soft delete
        owner.setActive(false);
        owner.setDeletedAt(LocalDateTime.now());
        userRepository.save(owner);

        // Dispatch OWNER_DEACTIVATED
        String deletedBy = resolveActorEmail();
        dispatcher.dispatch(
            "OWNER_DEACTIVATED",
            "Dueño desactivado: " + owner.getName(),
            "El dueño " + owner.getName() + " ha sido desactivado. Se suspenden automatizaciones.",
            owner.getOwnerId(),
            deletedBy
        );
    }

    /**
     * HARD DELETE: Purge owner and entire universe.
     * Deletes EVERYTHING including audit_events of this owner.
     * Requires reauth (password + MFA) from SUPER_ADMIN.
     */
    @Transactional
    public void purgeOwner(String id, boolean confirmPurge, String password, String mfaCode) {
        if (!confirmPurge) {
            throw new RuntimeException("Se requiere confirmPurge=true para ejecutar el purge total.");
        }

        // Reauth SUPER_ADMIN before destructive operation
        reauthService.verifyReauth(password, mfaCode, "PURGE_OWNER");

        UserEntity owner = userRepository.findById(id)
            .filter(u -> u.getRole() == Role.OWNER)
            .orElseThrow(() -> new RuntimeException("Owner not found"));

        String ownerId = owner.getId();
        String ownerName = owner.getName();
        String actorEmail = resolveActorEmail();
        int userCount;

        // 1. Delete provider assignments
        assignmentRepo.deleteByOwnerId(ownerId);

        // 2. Find all users in this owner context
        List<UserEntity> contextUsers = userRepository.findAll().stream()
            .filter(u -> ownerId.equals(u.getOwnerId()))
            .collect(Collectors.toList());

        userCount = contextUsers.size();

        List<String> contextUserIds = contextUsers.stream()
            .map(UserEntity::getId).collect(Collectors.toList());

        // 3. Delete notifications, tasks, preferences for context users
        for (String uid : contextUserIds) {
            notifRepo.deleteAll(notifRepo.findByUserIdOrderByCreatedAtDesc(uid));
            taskRepo.deleteAll(taskRepo.findByUserIdOrderByCreatedAtDesc(uid));
            prefRepo.deleteAll(prefRepo.findByUserId(uid));
        }

        // 4. Delete permission grants in this owner context
        grantRepo.findByOwnerId(ownerId).forEach(g -> grantRepo.delete(g));

        // 5. Delete context users (except the owner itself, delete last)
        contextUsers.stream()
            .filter(u -> !u.getId().equals(ownerId))
            .forEach(userRepository::delete);

        // 6. Delete the owner
        userRepository.delete(owner);

        // 7. Dispatch OWNER_PURGED — notification goes to SUPER_ADMIN only, no owner context
        dispatcher.dispatch(
            "OWNER_PURGED",
            "PURGE TOTAL: " + ownerName,
            "Se eliminó permanentemente al dueño " + ownerName + " y todo su universo (" + userCount + " usuarios, audit borrado)",
            null, // ownerId is null — owner no longer exists
            actorEmail
        );

        // 8. Delete ALL audit_events of this owner AFTER dispatch
        auditRepo.deleteByOwnerId(ownerId);
    }

    public java.util.Map<String, String> getRoutingPreferencesForOwner(String ownerUserId) {
        UserEntity u = userRepository.findById(ownerUserId)
                .orElseThrow(() -> new RuntimeException("Owner not found"));
        if (u.getRole() != Role.OWNER) {
            throw new RuntimeException("Routing preferences apply only to OWNER accounts.");
        }
        Boolean m = u.getUsePlatformMaintenance();
        Boolean a = u.getUsePlatformAgents();
        String maintenance = m == null ? "MIXED" : (Boolean.TRUE.equals(m) ? "PLATFORM" : "PRIVATE");
        String vacancy = a == null ? "MIXED" : (Boolean.TRUE.equals(a) ? "PLATFORM" : "PRIVATE");
        return Map.of(
                "maintenanceRoutingMode", maintenance,
                "vacancyRoutingMode", vacancy
        );
    }

    @Transactional
    public java.util.Map<String, String> updateRoutingPreferencesForOwner(String ownerUserId,
                                                                          String maintenanceRoutingMode,
                                                                          String vacancyRoutingMode) {
        UserEntity u = userRepository.findById(ownerUserId)
                .orElseThrow(() -> new RuntimeException("Owner not found"));
        if (u.getRole() != Role.OWNER) {
            throw new RuntimeException("Routing preferences apply only to OWNER accounts.");
        }
        u.setUsePlatformMaintenance(parseRoutingTriState(maintenanceRoutingMode, "maintenanceRoutingMode"));
        u.setUsePlatformAgents(parseRoutingTriState(vacancyRoutingMode, "vacancyRoutingMode"));
        userRepository.save(u);
        dispatcher.dispatch(
                "OWNER_ROUTING_UPDATED",
                "Preferencias de enrutado actualizadas",
                "Mantenimiento y vacancia/comercial",
                ownerUserId,
                resolveActorEmail(),
                null,
                null);
        return getRoutingPreferencesForOwner(ownerUserId);
    }

    private Boolean parseRoutingTriState(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new RuntimeException(fieldName + " es obligatorio (PLATFORM, PRIVATE o MIXED).");
        }
        String v = value.trim().toUpperCase();
        if ("MIXED".equals(v)) {
            return null;
        }
        if ("PLATFORM".equals(v)) {
            return true;
        }
        if ("PRIVATE".equals(v)) {
            return false;
        }
        throw new RuntimeException(fieldName + " invalido: use PLATFORM, PRIVATE o MIXED.");
    }

    private OwnerDTO mapToDTO(UserEntity entity) {
        OwnerDTO dto = new OwnerDTO();
        dto.setId(entity.getId());
        dto.setUsername(entity.getLoginUsername());
        dto.setName(entity.getName());
        dto.setPhone(entity.getPhone());
        // V54: contactEmail es el único email. Se expone vía getContactEmail();
        // el alias `email` del DTO lee el mismo backing field.
        dto.setContactEmail(entity.getContactEmail());
        dto.setContactPhone(entity.getContactPhone());
        dto.setContactCountryCode(entity.getContactCountryCode());
        return dto;
    }

    /**
     * Cuerpo humano-legible del mensaje de bienvenida para un nuevo dueño.
     *
     * Se reutiliza para EMAIL, WhatsApp y IN_APP (el dispatcher lo recibe tal cual).
     * Evita incluir URLs con query strings sensibles o tokens: el dueño entra con
     * USERNAME + tempPass y el sistema lo fuerza a cambiar contraseña inmediatamente
     * (UserEntity.mustChangePassword=true).
     *
     * V50 — el login ahora es por username; el email queda sólo como metadato
     * opcional de contacto. Exponemos "Usuario" como credencial y mencionamos el
     * correo de contacto únicamente si está presente para contexto.
     */
    private String buildOwnerWelcomeBody(UserEntity owner, String tempPassword) {
        StringBuilder sb = new StringBuilder();
        sb.append("Hola ").append(owner.getName() != null ? owner.getName() : "").append(",\n\n");
        sb.append("Tu cuenta en ADMINDI ha sido creada por el administrador.\n\n");
        sb.append("Datos de acceso:\n");
        sb.append("  • Portal: ").append(appUrl).append("\n");
        sb.append("  • Usuario: ").append(owner.getUsername() != null ? owner.getUsername() : "").append("\n");
        if (tempPassword != null && !tempPassword.isBlank()) {
            sb.append("  • Contraseña temporal: ").append(tempPassword).append("\n");
        }
        if (owner.getContactEmail() != null && !owner.getContactEmail().isBlank()) {
            sb.append("  • Correo de contacto registrado: ").append(owner.getContactEmail()).append("\n");
        }
        sb.append("\nPor seguridad, el sistema te pedirá cambiar la contraseña en tu primer acceso.\n");
        sb.append("Recuerda: tu identificador de login es el USUARIO, no el correo.\n\n");
        sb.append("Desde tu portal podrás:\n");
        sb.append("  • Registrar tus inmuebles y arrendatarios.\n");
        sb.append("  • Capturar tu CLABE para recibir transferencias SPEI.\n");
        sb.append("  • Configurar cómo quieres recibir notificaciones (email y WhatsApp).");
        return sb.toString();
    }

    private String resolveActorEmail() {
        try {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            return auth != null ? auth.getName() : "SYSTEM";
        } catch (Exception e) {
            return "SYSTEM";
        }
    }
}
