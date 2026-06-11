package com.admindi.backend.service;

import com.admindi.backend.dto.StaffDTO;
import com.admindi.backend.model.*;
import com.admindi.backend.repository.*;
import com.admindi.backend.security.TenantContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class StaffService {

    /** Roles that count as "staff" — excludes TENANT, OWNER, and SUPER_ADMIN */
    private static final Set<Role> STAFF_ROLES = Set.of(
            Role.PROPERTY_ADMIN, Role.ACCOUNTANT, Role.REAL_ESTATE_AGENT, Role.MAINTENANCE_PROVIDER
    );

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final DomainEventDispatcher dispatcher;
    private final OwnerMembershipRepository membershipRepository;
    private final PermissionGrantRepository grantRepository;
    private final PermissionTemplateRepository templateRepository;
    private final OwnerCascadeDeletionService cascadeService;
    private final AccountActivationService activationService;
    private final UsernameService usernameService;

    @Value("${app.url:http://localhost:3000}")
    private String appUrl;

    @Autowired
    public StaffService(UserRepository userRepository, PasswordEncoder passwordEncoder,
                        DomainEventDispatcher dispatcher,
                        OwnerMembershipRepository membershipRepository,
                        PermissionGrantRepository grantRepository,
                        PermissionTemplateRepository templateRepository,
                        OwnerCascadeDeletionService cascadeService,
                        AccountActivationService activationService,
                        UsernameService usernameService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.dispatcher = dispatcher;
        this.membershipRepository = membershipRepository;
        this.grantRepository = grantRepository;
        this.templateRepository = templateRepository;
        this.cascadeService = cascadeService;
        this.activationService = activationService;
        this.usernameService = usernameService;
    }

    private String resolveOwnerId() {
        return TenantContext.resolveOwnerId(userRepository);
    }

    private String resolveActorEmail() {
        return SecurityContextHolder.getContext().getAuthentication().getName();
    }

    // ─── GET (via OwnerMembership) ──────────────────────────────────────

    public List<StaffDTO> getMyStaff() {
        String ownerId = resolveOwnerId();

        // Primary: query via owner_memberships to include multi-owner staff
        List<OwnerMembershipEntity> memberships = membershipRepository.findByOwnerId(ownerId);
        Set<String> memberUserIds = memberships.stream()
                .map(OwnerMembershipEntity::getUserId)
                .collect(Collectors.toSet());

        // Fallback: also include users with direct ownerId (legacy data)
        List<UserEntity> directUsers = userRepository.findByOwnerId(ownerId).stream()
                .filter(u -> u.isActive() && STAFF_ROLES.contains(u.getRole()))
                .toList();
        for (UserEntity u : directUsers) {
            memberUserIds.add(u.getId());
        }

        // Resolve all unique user IDs
        return memberUserIds.stream()
                .map(userId -> userRepository.findById(userId).orElse(null))
                .filter(u -> u != null && u.isActive() && STAFF_ROLES.contains(u.getRole()))
                .map(u -> mapToDTO(u, ownerId))
                .collect(Collectors.toList());
    }

    // ─── CREATE (with multi-owner reuse + inline permissions) ───────────

    @Transactional
    public StaffDTO createStaff(StaffDTO dto) {
        String ownerId = resolveOwnerId();
        String actor = resolveActorEmail();

        if (dto.getRole() == null || !STAFF_ROLES.contains(dto.getRole())) {
            throw new RuntimeException("Rol no válido para personal: " + dto.getRole());
        }

        // V54 — username + email + teléfono obligatorios para dar de alta staff.
        //   · username: identificador de login canónico (case-sensitive, único).
        //   · email: contactEmail del user — único email real, NO único global
        //     (varias cuentas pueden compartirlo). DTO expone `loginEmail` como
        //     alias de `contactEmail`.
        //   · teléfono: canal WhatsApp operativo, NO único.
        if (dto.getUsername() == null || dto.getUsername().isBlank()) {
            throw new RuntimeException("Usuario para iniciar sesión es obligatorio.");
        }
        if (dto.getContactEmail() == null || dto.getContactEmail().isBlank()) {
            throw new RuntimeException("Email es obligatorio.");
        }

        if (dto.getContactPhone() == null || dto.getContactPhone().isBlank()) {
            throw new RuntimeException("El teléfono de contacto es obligatorio.");
        }

        // V54 — sink único: `contactEmail` normalizado. Sin checks de colisión.
        String contactEmail = dto.getContactEmail().trim().toLowerCase();

        // V48: identidad por username. UsernameService valida unicidad global.
        String username = usernameService.resolveOrDerive(dto.getUsername(), contactEmail);

        // V54+: onboarding uniforme por contraseña temporal (de un solo uso
        // efectivo porque mustChangePassword=true la invalida al primer login).
        // El flujo histórico de link de activación fue retirado en favor de
        // mandar las credenciales reales al correo del destinatario, igual que
        // OwnerService.createOwner y TenantService.createTenant.
        byte[] rnd = new byte[8];
        new SecureRandom().nextBytes(rnd);
        String tempPassword = Base64.getUrlEncoder().withoutPadding().encodeToString(rnd);

        UserEntity user = new UserEntity();
        user.setLoginUsername(username);
        user.setName(dto.getName());
        user.setPassword(passwordEncoder.encode(tempPassword));
        user.setRole(dto.getRole());
        user.setOwnerId(ownerId);
        user.setMustChangePassword(true);
        user.setActive(true);

        // Contact fields
        user.setContactEmail(contactEmail);
        user.setContactPhone(dto.getContactPhone());
        user.setContactCountryCode(dto.getContactCountryCode() != null ? dto.getContactCountryCode() : "+52");

        UserEntity saved = userRepository.saveAndFlush(user);

        // Create membership
        createMembership(saved.getId(), ownerId, actor);

        // Create permission grant if template selected
        if (dto.getPermissionTemplateId() != null && !dto.getPermissionTemplateId().isBlank()) {
            createOrUpdateGrant(saved.getId(), ownerId, dto.getPermissionTemplateId(), actor);
        }

        dispatchWelcomeForStaff(saved, contactEmail, tempPassword, ownerId, actor);

        StaffDTO result = mapToDTO(saved, ownerId);
        result.setTempPassword(tempPassword);
        result.setActivationSent(true);
        result.setActivationChannel("EMAIL");
        return result;
    }

    /**
     * Despacha el evento de bienvenida correspondiente al rol del staff recién
     * creado/reseteado. REAL_ESTATE_AGENT usa AGENT_WELCOME (con plantilla
     * admindi_agent_welcome_v1 para el teaser WhatsApp); cualquier otro rol
     * staff usa STAFF_WELCOME (sin plantilla WhatsApp — correo es el canal
     * garantizado con las credenciales).
     */
    private void dispatchWelcomeForStaff(UserEntity user, String contactEmail,
                                          String tempPassword, String ownerId,
                                          String actor) {
        boolean isAgent = user.getRole() == Role.REAL_ESTATE_AGENT;
        String eventType = isAgent ? "AGENT_WELCOME" : "STAFF_WELCOME";
        String title = isAgent
                ? "Bienvenido a ADMINDI (agente inmobiliario)"
                : "Bienvenido a ADMINDI";
        String welcomeBody = buildStaffWelcomeBody(user, tempPassword);
        Map<String, String> tplVars = Map.of(
                "1", user.getName() != null ? user.getName() : "",
                "2", contactEmail != null ? contactEmail : "",
                "3", appUrl != null ? appUrl : ""
        );
        dispatcher.dispatch(
                eventType,
                title,
                welcomeBody,
                ownerId,
                actor,
                List.of(user.getId()),
                tplVars,
                null
        );
    }

    /**
     * Cuerpo humano-legible del correo de bienvenida para staff (incluyendo
     * agentes). Mismo contrato que {@code buildOwnerWelcomeBody}: incluye
     * usuario + contraseña temporal (invalidada al primer login por
     * mustChangePassword=true) + URL del portal. El listado de capacidades
     * cambia según el rol.
     */
    private String buildStaffWelcomeBody(UserEntity user, String tempPassword) {
        StringBuilder sb = new StringBuilder();
        sb.append("Hola ").append(user.getName() != null ? user.getName() : "").append(",\n\n");
        sb.append("Fuiste registrado en ADMINDI como ").append(humanRole(user.getRole())).append(".\n\n");
        sb.append("Datos de acceso:\n");
        sb.append("  • Portal: ").append(appUrl).append("\n");
        sb.append("  • Usuario: ").append(user.getUsername() != null ? user.getUsername() : "").append("\n");
        if (tempPassword != null && !tempPassword.isBlank()) {
            sb.append("  • Contraseña temporal: ").append(tempPassword).append("\n");
        }
        if (user.getContactEmail() != null && !user.getContactEmail().isBlank()) {
            sb.append("  • Correo de contacto registrado: ").append(user.getContactEmail()).append("\n");
        }
        sb.append("\nPor seguridad, el sistema te pedirá cambiar la contraseña en tu primer acceso.\n");
        sb.append("Recuerda: tu identificador de login es el USUARIO, no el correo.\n\n");
        sb.append("Desde tu portal podrás:\n");
        for (String bullet : roleCapabilityBullets(user.getRole())) {
            sb.append("  • ").append(bullet).append("\n");
        }
        sb.append("  • Configurar cómo quieres recibir notificaciones (email y WhatsApp).");
        return sb.toString();
    }

    private static String humanRole(Role role) {
        if (role == null) return "usuario";
        return switch (role) {
            case REAL_ESTATE_AGENT -> "agente inmobiliario";
            case MAINTENANCE_PROVIDER -> "proveedor de mantenimiento";
            case PROPERTY_ADMIN -> "administrador de propiedades";
            case ACCOUNTANT -> "contador";
            default -> role.name().toLowerCase().replace('_', ' ');
        };
    }

    private static List<String> roleCapabilityBullets(Role role) {
        if (role == null) return List.of();
        return switch (role) {
            case REAL_ESTATE_AGENT -> List.of(
                    "Recibir vacancias disponibles para comercialización.",
                    "Proponer prospectos de inquilinos a los dueños.",
                    "Registrar tu CLABE para recibir pago de comisiones SPEI."
            );
            case MAINTENANCE_PROVIDER -> List.of(
                    "Recibir y aceptar tickets de mantenimiento asignados.",
                    "Subir cotizaciones y evidencias del servicio.",
                    "Registrar tu CLABE para recibir pagos SPEI."
            );
            case PROPERTY_ADMIN -> List.of(
                    "Administrar los inmuebles, inquilinos y contratos a tu cargo.",
                    "Registrar egresos y movimientos operativos.",
                    "Gestionar tickets de mantenimiento en nombre del dueño."
            );
            case ACCOUNTANT -> List.of(
                    "Consultar ingresos, egresos y reportes mensuales del portafolio.",
                    "Auditar pagos validados y marcar movimientos relevantes.",
                    "Generar y descargar los reportes para conciliación fiscal."
            );
            default -> List.of();
        };
    }

    /**
     * Regenera una contraseña temporal para el staff y reenvía el correo de
     * bienvenida. Sustituye al flujo histórico "resend activation link" — el
     * link ya no existe; en su lugar el creador puede generar una nueva temp
     * password visible y comunicársela automáticamente por correo (mismo
     * contrato que el alta original).
     *
     * <p>Cualquier token legacy pendiente en {@code user_activation_tokens} se
     * revoca silenciosamente para que no quede un vector de login vigente tras
     * la rotación de contraseña.</p>
     *
     * <p>Marca {@code mustChangePassword=true} aunque el usuario ya hubiera
     * cambiado su contraseña — el reset debe invalidar la contraseña anterior
     * y forzar al user a fijar una nueva al entrar.</p>
     */
    @Transactional
    public Map<String, Object> resendActivation(String userId) {
        String ownerId = resolveOwnerId();
        String actor = resolveActorEmail();

        UserEntity user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("Usuario no encontrado."));

        // IDOR: el user debe estar linkeado a este owner.
        boolean isLinked = membershipRepository.findByUserId(userId).stream()
                .anyMatch(m -> m.getOwnerId().equals(ownerId));
        if (!isLinked && !ownerId.equals(user.getOwnerId())) {
            throw new RuntimeException("IDOR: Personal de otra organización.");
        }
        if (!STAFF_ROLES.contains(user.getRole())) {
            throw new RuntimeException("El usuario no es personal, no aplica reenviar credenciales.");
        }

        byte[] rnd = new byte[8];
        new SecureRandom().nextBytes(rnd);
        String tempPassword = Base64.getUrlEncoder().withoutPadding().encodeToString(rnd);

        user.setPassword(passwordEncoder.encode(tempPassword));
        user.setMustChangePassword(true);
        userRepository.save(user);

        // Defensa: revocar cualquier token legacy todavía activo para que la
        // rotación cierre todos los vectores de login previos.
        activationService.revokeAllForUser(user.getId(), "TEMP_PASSWORD_REISSUED");

        dispatchWelcomeForStaff(user,
                user.getContactEmail(),
                tempPassword,
                ownerId,
                actor);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("activationSent", true);
        body.put("channel", "EMAIL");
        body.put("tempPassword", tempPassword);
        return body;
    }

    // ─── UPDATE (identity + permissions) ────────────────────────────────

    @Transactional
    public StaffDTO updateStaff(String userId, StaffDTO dto) {
        String ownerId = resolveOwnerId();
        String actor = resolveActorEmail();

        UserEntity user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("Usuario no encontrado."));

        // IDOR: verify user is linked to this owner
        boolean isLinked = membershipRepository.findByUserId(userId).stream()
                .anyMatch(m -> m.getOwnerId().equals(ownerId));
        if (!isLinked && !ownerId.equals(user.getOwnerId())) {
            throw new RuntimeException("IDOR: Personal de otra organización.");
        }

        // Update name
        if (dto.getName() != null && !dto.getName().isBlank()) {
            user.setName(dto.getName());
        }

        // Update role (only if valid staff role)
        if (dto.getRole() != null && STAFF_ROLES.contains(dto.getRole())) {
            user.setRole(dto.getRole());
        }

        // V54 — contactEmail es el único email del user. NO único, compartible.
        if (dto.getContactEmail() != null) {
            user.setContactEmail(dto.getContactEmail().isBlank()
                    ? null
                    : dto.getContactEmail().trim().toLowerCase());
        }
        if (dto.getContactPhone() != null) {
            user.setContactPhone(dto.getContactPhone());
        }
        if (dto.getContactCountryCode() != null) {
            user.setContactCountryCode(dto.getContactCountryCode());
        }

        userRepository.save(user);

        // Update permission grant if template changed
        if (dto.getPermissionTemplateId() != null) {
            if (dto.getPermissionTemplateId().isBlank()) {
                // Empty string = revoke all grants for this user+owner
                revokeGrants(userId, ownerId);
            } else {
                createOrUpdateGrant(userId, ownerId, dto.getPermissionTemplateId(), actor);
            }
        }

        dispatcher.dispatch("STAFF_UPDATED",
                "Personal actualizado: " + user.getName(),
                null, ownerId, actor, null);

        return mapToDTO(user, ownerId);
    }

    // ─── DELETE ─────────────────────────────────────────────────────────

    /**
     * Desvincula a un staff de este owner. Si el staff todavía pertenece a otros
     * owners (multi-owner) conserva su cuenta; si este era el último owner, la
     * cuenta se elimina completamente (hard-delete sin actividad histórica,
     * o tombstone del username si sí la hay). V54: el email ya no era único
     * en DB, así que no hay "liberar correo" — cualquier nueva cuenta puede
     * usar el mismo correo sin trabas.
     */
    @Transactional
    public void deleteStaff(String userId) {
        String ownerId = resolveOwnerId();
        String actor = resolveActorEmail();

        UserEntity user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("Usuario no encontrado."));
        String name = user.getName();
        String originalContactEmail = user.getContactEmail();

        // Remove membership for this owner
        OwnerMembershipId membershipId = new OwnerMembershipId(userId, ownerId);
        membershipRepository.deleteById(membershipId);

        // Revoke grants for this owner
        revokeGrants(userId, ownerId);

        // Check if user has other memberships
        List<OwnerMembershipEntity> remaining = membershipRepository.findByUserId(userId);

        if (!remaining.isEmpty()) {
            // Multi-owner: conservar el user pero si su owner_id informativo apuntaba
            // a este dueño, moverlo al siguiente remanente para que resolveOwnerId()
            // no quede apuntando a un contexto perdido.
            if (ownerId.equals(user.getOwnerId())) {
                user.setOwnerId(remaining.get(0).getOwnerId());
                userRepository.save(user);
            }
            dispatcher.dispatch("STAFF_UNLINKED_FROM_OWNER",
                    "Personal desvinculado: " + name,
                    "Cuenta activa en otros contextos (contacto " + originalContactEmail + " conservado)",
                    ownerId, actor, null);
            return;
        }

        // Último owner del staff: eliminar completamente. El servicio decide si
        // hard-delete o tombstone según actividad histórica.
        OwnerCascadeDeletionService.StaffDeletionOutcome outcome =
                cascadeService.deleteOrTombstoneStaffOrProvider(userId);

        dispatcher.dispatch(
                outcome.hardDeleted() ? "STAFF_HARD_DELETED" : "STAFF_TOMBSTONED",
                "Personal eliminado: " + name,
                outcome.hardDeleted()
                        ? "Cuenta eliminada (contacto " + originalContactEmail + ")."
                        : "Cuenta tombstoneada (tenía actividad histórica). Contacto " + originalContactEmail + ".",
                ownerId, actor, null);
    }

    // ─── Helpers ────────────────────────────────────────────────────────

    private void createMembership(String userId, String ownerId, String actor) {
        OwnerMembershipId id = new OwnerMembershipId(userId, ownerId);
        if (membershipRepository.existsById(id)) return; // Already exists

        OwnerMembershipEntity membership = new OwnerMembershipEntity();
        membership.setUserId(userId);
        membership.setOwnerId(ownerId);
        membership.setAssignedAt(LocalDateTime.now());
        membership.setAssignedBy(actor);
        membershipRepository.save(membership);
    }

    private void createOrUpdateGrant(String userId, String ownerId, String templateId, String grantedBy) {
        // Verify template exists
        templateRepository.findById(templateId)
                .orElseThrow(() -> new RuntimeException("Plantilla de permisos no encontrada: " + templateId));

        // Remove existing grants for this user+owner
        List<PermissionGrantEntity> existing = grantRepository.findByUserIdAndOwnerId(userId, ownerId);
        for (PermissionGrantEntity g : existing) {
            grantRepository.delete(g);
        }

        // Create new grant
        PermissionGrantEntity grant = new PermissionGrantEntity();
        grant.setId(UUID.randomUUID().toString());
        grant.setUserId(userId);
        grant.setOwnerId(ownerId);
        grant.setTemplateId(templateId);
        grant.setGrantedAt(LocalDateTime.now());
        grant.setGrantedBy(grantedBy);
        grantRepository.save(grant);
    }

    private void revokeGrants(String userId, String ownerId) {
        List<PermissionGrantEntity> grants = grantRepository.findByUserIdAndOwnerId(userId, ownerId);
        for (PermissionGrantEntity g : grants) {
            grantRepository.delete(g);
        }
    }

    private StaffDTO mapToDTO(UserEntity u, String ownerId) {
        // Resolve current permission template name for this owner context
        String currentTemplateName = null;
        List<PermissionGrantEntity> grants = grantRepository.findByUserIdAndOwnerId(u.getId(), ownerId);
        if (!grants.isEmpty() && grants.get(0).getTemplateId() != null) {
            PermissionTemplateEntity tpl = templateRepository.findById(grants.get(0).getTemplateId()).orElse(null);
            currentTemplateName = tpl != null ? tpl.getName() : null;
        }

        // V54: loginEmail y contactEmail son el mismo backing field en el DTO
        // (alias). Pasamos null como loginEmail y el DTO usa contactEmail.
        StaffDTO dto = new StaffDTO(
                u.getId(),
                ownerId,
                u.getName(),
                null,                         // loginEmail legacy (alias de contactEmail)
                u.getContactEmail(),          // contactEmail — único email
                u.getContactPhone(),          // contactPhone
                u.getContactCountryCode(),    // contactCountryCode
                u.getRole(),
                currentTemplateName
        );
        dto.setUsername(u.getLoginUsername());
        return dto;
    }
}
