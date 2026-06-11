package com.admindi.backend.service;

import com.admindi.backend.dto.MaintenanceProviderDTO;
import com.admindi.backend.dto.OwnerProviderLinkDTO;
import com.admindi.backend.model.PlatformProviderAssignmentEntity;
import com.admindi.backend.model.Role;
import com.admindi.backend.model.UserEntity;
import com.admindi.backend.repository.PlatformProviderAssignmentRepository;
import com.admindi.backend.repository.UserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class MaintenanceProviderService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final PlatformProviderAssignmentRepository assignmentRepo;
    private final DomainEventDispatcher dispatcher;
    private final OwnerCascadeDeletionService cascadeService;
    private final AccountActivationService activationService;
    private final UsernameService usernameService;
    private final AgentBankAccountService bankAccountService;

    @Value("${app.url:http://localhost:3000}")
    private String appUrl;

    public MaintenanceProviderService(UserRepository userRepository, PasswordEncoder passwordEncoder,
                                       PlatformProviderAssignmentRepository assignmentRepo,
                                       DomainEventDispatcher dispatcher,
                                       OwnerCascadeDeletionService cascadeService,
                                       AccountActivationService activationService,
                                       UsernameService usernameService,
                                       AgentBankAccountService bankAccountService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.assignmentRepo = assignmentRepo;
        this.dispatcher = dispatcher;
        this.cascadeService = cascadeService;
        this.activationService = activationService;
        this.usernameService = usernameService;
        this.bankAccountService = bankAccountService;
    }

    private static final Set<String> VALID_PROVIDER_TYPES = Set.of(
            Role.MAINTENANCE_PROVIDER.name(), Role.REAL_ESTATE_AGENT.name());

    public List<MaintenanceProviderDTO> getAllProviders() {
        return userRepository.findAll().stream()
                .filter(u -> VALID_PROVIDER_TYPES.contains(u.getRole().name()) && u.isActive())
                .map(this::mapToDTO)
                .collect(Collectors.toList());
    }

    public List<MaintenanceProviderDTO> getProvidersByType(String type) {
        return userRepository.findAll().stream()
                .filter(u -> u.getRole().name().equals(type) && u.isActive())
                .map(this::mapToDTO)
                .collect(Collectors.toList());
    }

    public MaintenanceProviderDTO getProvider(String id) {
        UserEntity provider = userRepository.findById(id)
            .filter(u -> VALID_PROVIDER_TYPES.contains(u.getRole().name()) && u.isActive())
            .orElseThrow(() -> new RuntimeException("Proveedor no encontrado"));
        return mapToDTO(provider);
    }

    @Transactional
    public MaintenanceProviderDTO createProvider(MaintenanceProviderDTO dto) {
        // V54 — username + email + teléfono son OBLIGATORIOS.
        //   · username: único, case-sensitive (login).
        //   · email: contactEmail del user — único correo real, NO único global
        //     (compartible entre cuentas). DTO expone `email` como alias.
        //   · teléfono: canal WhatsApp operativo, NO único.
        if (dto.getUsername() == null || dto.getUsername().isBlank())
            throw new RuntimeException("Usuario para iniciar sesión es obligatorio.");
        if (dto.getContactEmail() == null || dto.getContactEmail().isBlank())
            throw new RuntimeException("Email es obligatorio.");
        if (dto.getName() == null || dto.getName().isBlank())
            throw new RuntimeException("Nombre es obligatorio.");
        if (dto.getCountryCode() == null || dto.getRawPhone() == null ||
            dto.getRawPhone().replaceAll("[^0-9]", "").length() < 7)
            throw new RuntimeException("Teléfono de contacto con lada es obligatorio (mínimo 7 dígitos).");

        // Validate provider type
        String providerType = dto.getProviderType();
        if (providerType == null || !VALID_PROVIDER_TYPES.contains(providerType)) {
            providerType = Role.MAINTENANCE_PROVIDER.name(); // default
        }
        Role role = Role.valueOf(providerType);

        // V54 — sink único: `contactEmail` normalizado (trim + lower). Sin checks
        // de colisión porque el campo ya no es único.
        String contactEmail = dto.getContactEmail().trim().toLowerCase();
        String username = usernameService.resolveOrDerive(dto.getUsername(), contactEmail);

        // Normalize phone
        String digits = dto.getRawPhone().replaceAll("[^0-9]", "");
        String code = dto.getCountryCode().startsWith("+") ? dto.getCountryCode() : "+" + dto.getCountryCode();
        String normalizedPhone = code + digits;

        UserEntity provider = new UserEntity();
        provider.setLoginUsername(username);
        provider.setName(dto.getName().trim());
        provider.setPhone(normalizedPhone);
        provider.setRole(role);
        provider.setMustChangePassword(true);

        provider.setContactEmail(contactEmail);
        provider.setContactPhone(normalizedPhone);
        provider.setContactCountryCode(code);

        // V54+: onboarding uniforme por contraseña temporal (de un solo uso
        // efectivo por mustChangePassword=true). El flujo de link de activación
        // fue retirado; tanto REAL_ESTATE_AGENT como MAINTENANCE_PROVIDER reciben
        // sus credenciales reales por correo.
        byte[] rnd = new byte[8];
        new SecureRandom().nextBytes(rnd);
        String tempPassword = Base64.getUrlEncoder().withoutPadding().encodeToString(rnd);
        provider.setPassword(passwordEncoder.encode(tempPassword));

        provider = userRepository.saveAndFlush(provider);
        String actualProviderId = provider.getId();

        autoAssignToAllOwners(actualProviderId);

        String createdBy = resolveActorEmail();

        dispatchWelcomeForProvider(provider, contactEmail, tempPassword, null, createdBy);

        MaintenanceProviderDTO saved = mapToDTO(provider);
        saved.setTempPassword(tempPassword);
        saved.setActivationSent(true);
        saved.setActivationChannel("EMAIL");
        return saved;
    }

    /**
     * Despacha el evento de bienvenida correspondiente al rol del proveedor
     * recién creado / reseteado. REAL_ESTATE_AGENT usa AGENT_WELCOME (con
     * plantilla admindi_agent_welcome_v1 para el teaser WhatsApp);
     * MAINTENANCE_PROVIDER usa STAFF_WELCOME (sin plantilla WhatsApp — el
     * correo es el canal garantizado con las credenciales).
     */
    private void dispatchWelcomeForProvider(UserEntity provider, String contactEmail,
                                             String tempPassword, String ownerId,
                                             String createdBy) {
        boolean isAgent = provider.getRole() == Role.REAL_ESTATE_AGENT;
        String eventType = isAgent ? "AGENT_WELCOME" : "STAFF_WELCOME";
        String title = isAgent
                ? "Bienvenido a ADMINDI (agente inmobiliario)"
                : "Bienvenido a ADMINDI";
        String welcomeBody = buildProviderWelcomeBody(provider, tempPassword);
        Map<String, String> tplVars = Map.of(
                "1", provider.getName() != null ? provider.getName() : "",
                "2", contactEmail != null ? contactEmail : "",
                "3", appUrl != null ? appUrl : ""
        );
        dispatcher.dispatch(
                eventType,
                title,
                welcomeBody,
                ownerId,
                createdBy,
                List.of(provider.getId()),
                tplVars,
                null
        );
    }

    /**
     * Cuerpo humano-legible del correo de bienvenida para proveedor/agente.
     * Mismo contrato que {@code OwnerService.buildOwnerWelcomeBody}: incluye
     * usuario + contraseña temporal (invalidada al primer login por
     * mustChangePassword=true) + URL del portal. Las capacidades listadas
     * cambian según el rol.
     */
    private String buildProviderWelcomeBody(UserEntity user, String tempPassword) {
        StringBuilder sb = new StringBuilder();
        sb.append("Hola ").append(user.getName() != null ? user.getName() : "").append(",\n\n");
        sb.append("Fuiste registrado en ADMINDI como ").append(humanProviderRole(user.getRole())).append(".\n\n");
        sb.append("Datos de acceso:\n");
        sb.append("  • Portal: ").append(appUrl).append("\n");
        sb.append("  • Usuario: ").append(user.getLoginUsername() != null ? user.getLoginUsername() : "").append("\n");
        if (tempPassword != null && !tempPassword.isBlank()) {
            sb.append("  • Contraseña temporal: ").append(tempPassword).append("\n");
        }
        if (user.getContactEmail() != null && !user.getContactEmail().isBlank()) {
            sb.append("  • Correo de contacto registrado: ").append(user.getContactEmail()).append("\n");
        }
        sb.append("\nPor seguridad, el sistema te pedirá cambiar la contraseña en tu primer acceso.\n");
        sb.append("Recuerda: tu identificador de login es el USUARIO, no el correo.\n\n");
        sb.append("Desde tu portal podrás:\n");
        for (String bullet : providerCapabilityBullets(user.getRole())) {
            sb.append("  • ").append(bullet).append("\n");
        }
        sb.append("  • Configurar cómo quieres recibir notificaciones (email y WhatsApp).");
        return sb.toString();
    }

    private static String humanProviderRole(Role role) {
        if (role == null) return "colaborador";
        return switch (role) {
            case REAL_ESTATE_AGENT -> "agente inmobiliario";
            case MAINTENANCE_PROVIDER -> "proveedor de mantenimiento";
            default -> role.name().toLowerCase().replace('_', ' ');
        };
    }

    private static List<String> providerCapabilityBullets(Role role) {
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
            default -> List.of();
        };
    }

    /**
     * Regenera la contraseña temporal del proveedor/agente y reenvía su correo
     * de bienvenida. Sustituye al flujo histórico "resend activation link" —
     * el link fue retirado; en su lugar el creador puede disparar un reset
     * visible (la nueva temp password viaja por correo con mismo contrato del
     * alta original).
     *
     * <p>Autorizado para SUPER_ADMIN en catálogo global o para el owner
     * propietario si es un private provider asignado. Cualquier token legacy
     * pendiente se revoca para que no quede un vector de login vigente tras
     * la rotación.</p>
     */
    @Transactional
    public java.util.Map<String, Object> resendActivation(String providerUserId, String ownerOrgIdForAuthorization) {
        UserEntity provider = userRepository.findById(providerUserId)
                .filter(u -> VALID_PROVIDER_TYPES.contains(u.getRole().name()))
                .orElseThrow(() -> new RuntimeException("Proveedor no encontrado."));

        // Authorization: si el caller pasó ownerOrgId significa que es un OWNER
        // y hay que verificar que el provider está ASIGNADO a ese owner (PRIVATE
        // o PLATFORM activo). SUPER_ADMIN pasa null como ownerOrgId y omite check.
        if (ownerOrgIdForAuthorization != null) {
            boolean linked = assignmentRepo.findByProviderIdAndOwnerId(providerUserId, ownerOrgIdForAuthorization)
                    .filter(PlatformProviderAssignmentEntity::isActive)
                    .isPresent();
            if (!linked) {
                throw new RuntimeException("IDOR: El proveedor no está vinculado a tu organización.");
            }
        }

        String actor = resolveActorEmail();

        byte[] rnd = new byte[8];
        new SecureRandom().nextBytes(rnd);
        String tempPassword = Base64.getUrlEncoder().withoutPadding().encodeToString(rnd);
        provider.setPassword(passwordEncoder.encode(tempPassword));
        provider.setMustChangePassword(true);
        userRepository.save(provider);

        activationService.revokeAllForUser(provider.getId(), "TEMP_PASSWORD_REISSUED");

        dispatchWelcomeForProvider(provider,
                provider.getContactEmail(),
                tempPassword,
                ownerOrgIdForAuthorization,
                actor);

        java.util.Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("activationSent", true);
        body.put("channel", "EMAIL");
        body.put("tempPassword", tempPassword);
        return body;
    }

    public MaintenanceProviderDTO updateProviderContact(String id, MaintenanceProviderDTO dto) {
        UserEntity provider = userRepository.findById(id)
            .filter(u -> VALID_PROVIDER_TYPES.contains(u.getRole().name()) && u.isActive())
            .orElseThrow(() -> new RuntimeException("Proveedor no encontrado"));

        boolean changed = false;
        if (dto.getContactEmail() != null && !dto.getContactEmail().isBlank()) {
            // V54 — contactEmail normalizado. NO único, compartible.
            provider.setContactEmail(dto.getContactEmail().trim().toLowerCase());
            changed = true;
        }
        if (dto.getContactCountryCode() != null && dto.getRawPhone() != null) {
            String digits = dto.getRawPhone().replaceAll("[^0-9]", "");
            if (digits.length() < 7) throw new RuntimeException("Teléfono inválido. Mínimo 7 dígitos.");
            String code = dto.getContactCountryCode().startsWith("+") ? dto.getContactCountryCode() : "+" + dto.getContactCountryCode();
            provider.setContactPhone(code + digits);
            provider.setContactCountryCode(code);
            provider.setPhone(code + digits);
            changed = true;
        }
        if (dto.getName() != null && !dto.getName().isBlank()) {
            provider.setName(dto.getName().trim());
            changed = true;
        }
        if (changed) {
            userRepository.save(provider);
            dispatcher.dispatch("PROVIDER_UPDATED",
                "Proveedor actualizado: " + provider.getName(), null,
                null, resolveActorEmail(), null);
        }
        return mapToDTO(provider);
    }

    /**
     * Baja definitiva de un proveedor/agente.
     *
     * <p>V54: el email dejó de ser único en DB, así que "liberar el correo" ya
     * no tiene sentido — cualquier nuevo provider puede usar el mismo email. El
     * ciclo de baja queda:</p>
     * <ul>
     *   <li>Sin actividad histórica → hard-delete en cascada.</li>
     *   <li>Con actividad → tombstone del username + wipe de contactos.
     *       El trail histórico (cotizaciones, tickets, egresos) se preserva
     *       porque esas tablas apuntan al id del user.</li>
     * </ul>
     */
    @Transactional
    public void deactivateProvider(String id) {
        UserEntity provider = userRepository.findById(id)
            .filter(u -> VALID_PROVIDER_TYPES.contains(u.getRole().name()))
            .orElseThrow(() -> new RuntimeException("Proveedor no encontrado"));
        String name = provider.getName();
        String actor = resolveActorEmail();

        OwnerCascadeDeletionService.StaffDeletionOutcome outcome =
                cascadeService.deleteOrTombstoneStaffOrProvider(provider.getId());

        dispatcher.dispatch(
                outcome.hardDeleted() ? "PROVIDER_HARD_DELETED" : "PROVIDER_TOMBSTONED",
                "Proveedor dado de baja: " + name,
                outcome.hardDeleted()
                        ? "El proveedor " + name + " fue eliminado. Contacto: " + outcome.originalContactEmail() + "."
                        : "El proveedor " + name + " fue tombstoneado (tenía actividad histórica). Contacto: " + outcome.originalContactEmail() + ".",
                null, actor, null);
    }

    /**
     * Assign a provider to a specific owner.
     */
    public void assignProviderToOwner(String providerId, String ownerId) {
        Optional<PlatformProviderAssignmentEntity> existing = assignmentRepo.findByProviderIdAndOwnerId(providerId, ownerId);
        if (existing.isPresent()) {
            PlatformProviderAssignmentEntity a = existing.get();
            a.setActive(true);
            a.setAssignmentSource("PLATFORM");
            assignmentRepo.save(a);
            return;
        }
        PlatformProviderAssignmentEntity a = new PlatformProviderAssignmentEntity();
        a.setId(UUID.randomUUID().toString());
        a.setProviderId(providerId);
        a.setOwnerId(ownerId);
        a.setAssignedAt(LocalDateTime.now());
        a.setActive(true);
        a.setAssignmentSource("PLATFORM");
        assignmentRepo.save(a);
    }

    /**
     * Alta integral de un proveedor/agente privado asociado únicamente al owner que lo crea.
     * A diferencia de {@link #createProvider} (catálogo de plataforma) esta ruta NO hace
     * {@code autoAssignToAllOwners}: el contacto pertenece al contexto privado del owner
     * (solo él lo ve y puede desvincularlo). Se emite un PROVIDER_CREATED dirigido al proveedor
     * para que cambie su contraseña temporal al primer ingreso.
     */
    @Transactional
    public MaintenanceProviderDTO createPrivateProviderForOwner(MaintenanceProviderDTO dto, String ownerOrgId) {
        if (dto == null) throw new RuntimeException("Datos del proveedor son obligatorios.");
        // V54 — username + email + teléfono obligatorios.
        if (dto.getUsername() == null || dto.getUsername().isBlank())
            throw new RuntimeException("Usuario para iniciar sesión es obligatorio.");
        if (dto.getContactEmail() == null || dto.getContactEmail().isBlank())
            throw new RuntimeException("Email es obligatorio.");
        if (dto.getName() == null || dto.getName().isBlank())
            throw new RuntimeException("Nombre completo es obligatorio.");
        if (dto.getCountryCode() == null || dto.getRawPhone() == null
                || dto.getRawPhone().replaceAll("[^0-9]", "").length() < 7)
            throw new RuntimeException("Teléfono de contacto con lada es obligatorio (mínimo 7 dígitos).");

        String providerType = dto.getProviderType();
        if (providerType == null || !VALID_PROVIDER_TYPES.contains(providerType)) {
            throw new RuntimeException("Tipo de proveedor inválido. Esperado MAINTENANCE_PROVIDER o REAL_ESTATE_AGENT.");
        }
        Role role = Role.valueOf(providerType);

        // V54 — sink único: contactEmail normalizado (trim + lower). Sin checks
        // de colisión porque el campo ya no es único.
        String contactEmail = dto.getContactEmail().trim().toLowerCase();
        String username = usernameService.resolveOrDerive(dto.getUsername(), contactEmail);

        String digits = dto.getRawPhone().replaceAll("[^0-9]", "");
        String code = dto.getCountryCode().startsWith("+") ? dto.getCountryCode() : "+" + dto.getCountryCode();
        String normalizedPhone = code + digits;

        UserEntity provider = new UserEntity();
        provider.setLoginUsername(username);
        provider.setName(dto.getName().trim());
        provider.setPhone(normalizedPhone);
        provider.setRole(role);
        provider.setMustChangePassword(true);
        provider.setContactCountryCode(code);
        provider.setContactPhone(normalizedPhone);
        provider.setContactEmail(contactEmail);

        // V54+: onboarding por contraseña temporal (ver createProvider).
        byte[] rnd = new byte[8];
        new SecureRandom().nextBytes(rnd);
        String tempPassword = Base64.getUrlEncoder().withoutPadding().encodeToString(rnd);
        provider.setPassword(passwordEncoder.encode(tempPassword));

        provider = userRepository.saveAndFlush(provider);

        // Vínculo privado exclusivo al owner que lo crea (no se propaga a toda la plataforma).
        PlatformProviderAssignmentEntity a = new PlatformProviderAssignmentEntity();
        a.setId(UUID.randomUUID().toString());
        a.setProviderId(provider.getId());
        a.setOwnerId(ownerOrgId);
        a.setAssignedAt(LocalDateTime.now());
        a.setActive(true);
        a.setAssignmentSource("PRIVATE");
        assignmentRepo.save(a);

        String createdBy = resolveActorEmail();

        dispatchWelcomeForProvider(provider, contactEmail, tempPassword, ownerOrgId, createdBy);

        MaintenanceProviderDTO saved = mapToDTO(provider);
        saved.setTempPassword(tempPassword);
        saved.setActivationSent(true);
        saved.setActivationChannel("EMAIL");
        return saved;
    }

    /**
     * Owner-linked private contractor or agent (assignment_source PRIVATE).
     *
     * <p>V67 — Regla estricta de single-context para agentes PRIVATE:
     * un agente/proveedor PRIVATE pertenece a <b>un único dueño</b>. Si se
     * intenta enlazarlo a un dueño cuando ya tiene otro assignment activo
     * con otro dueño, se rechaza con <b>409 CONFLICT</b>. La única excepción
     * es si el assignment existente es con el <b>mismo</b> dueño (re-link
     * idempotente: re-activar un vínculo inactivo previo).</p>
     *
     * <p>Racional: la regla del operador es "sólo agentes PLATFORM son
     * multi-contexto". Los PRIVATE creados o invitados por un dueño viven en
     * su contexto privado y no deben circular entre dueños — si un segundo
     * dueño los quiere, el primero debe desvincularlo primero (lo que
     * tombstonea el user por regla).</p>
     */
    @Transactional
    public void linkPrivateProviderToOwner(String providerUserId, String ownerOrgId) {
        UserEntity provider = userRepository.findById(providerUserId)
                .filter(u -> VALID_PROVIDER_TYPES.contains(u.getRole().name()) && u.isActive())
                .orElseThrow(() -> new RuntimeException("Proveedor o agente no encontrado o inactivo."));

        // #region agent log
        writeDebug("A", "linkPrivateProviderToOwner:entry",
                Map.of("providerUserId", providerUserId, "ownerOrgId", ownerOrgId));
        // #endregion

        // Verificar si el provider ya tiene algún assignment activo con OTRO
        // dueño. Si sí → 409 (regla single-context estricta).
        List<PlatformProviderAssignmentEntity> allActive =
                assignmentRepo.findByProviderIdAndActiveTrue(providerUserId);
        for (PlatformProviderAssignmentEntity other : allActive) {
            if (!ownerOrgId.equals(other.getOwnerId())) {
                // #region agent log
                writeDebug("A", "linkPrivateProviderToOwner:blocked_multi_owner",
                        Map.of("providerUserId", providerUserId,
                                "requestingOwner", ownerOrgId,
                                "alreadyLinkedTo", other.getOwnerId(),
                                "source", other.getAssignmentSource()));
                // #endregion
                throw new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.CONFLICT,
                        "Este proveedor ya está vinculado como privado a otro dueño. Un agente PRIVATE sólo puede pertenecer a un dueño a la vez.");
            }
        }

        Optional<PlatformProviderAssignmentEntity> existing =
                assignmentRepo.findByProviderIdAndOwnerId(providerUserId, ownerOrgId);
        if (existing.isPresent()) {
            PlatformProviderAssignmentEntity a = existing.get();
            a.setActive(true);
            a.setAssignmentSource("PRIVATE");
            assignmentRepo.save(a);
        } else {
            PlatformProviderAssignmentEntity a = new PlatformProviderAssignmentEntity();
            a.setId(UUID.randomUUID().toString());
            a.setProviderId(provider.getId());
            a.setOwnerId(ownerOrgId);
            a.setAssignedAt(LocalDateTime.now());
            a.setActive(true);
            a.setAssignmentSource("PRIVATE");
            assignmentRepo.save(a);
        }
    }

    /**
     * Auto-assign a provider to all active owners.
     */
    private void autoAssignToAllOwners(String providerId) {
        userRepository.findAll().stream()
            .filter(u -> u.getRole() == Role.OWNER && u.isActive())
            .forEach(owner -> {
                try {
                    assignProviderToOwner(providerId, owner.getId());
                } catch (Exception e) {
                    // Ignore duplicates
                }
            });
    }

    /**
     * Auto-assign all active platform providers (both types) to a new owner.
     */
    public void assignAllProvidersToOwner(String ownerId) {
        userRepository.findAll().stream()
            .filter(u -> VALID_PROVIDER_TYPES.contains(u.getRole().name()) && u.isActive())
            .forEach(provider -> {
                try {
                    assignProviderToOwner(provider.getId(), ownerId);
                } catch (Exception e) {
                    // Ignore duplicates
                }
            });
    }

    public List<MaintenanceProviderDTO> getProvidersByOwner(String ownerId) {
        return assignmentRepo.findByOwnerIdAndActiveTrue(ownerId).stream()
            .map(a -> userRepository.findById(a.getProviderId()).orElse(null))
            .filter(u -> u != null && u.isActive())
            .map(this::mapToDTO)
            .collect(Collectors.toList());
    }

    /**
     * Catálogo de proveedores/agentes activos en la plataforma (para vincular desde el panel del dueño).
     */
    public List<MaintenanceProviderDTO> listPlatformCatalog(String typeFilter) {
        return userRepository.findAll().stream()
                .filter(u -> VALID_PROVIDER_TYPES.contains(u.getRole().name()) && u.isActive())
                .filter(u -> typeFilter == null || typeFilter.isBlank() || u.getRole().name().equals(typeFilter))
                .map(this::mapToDTO)
                .sorted(Comparator.comparing(MaintenanceProviderDTO::getName,
                        Comparator.nullsLast(String::compareToIgnoreCase)))
                .collect(Collectors.toList());
    }

    /**
     * Todos los vínculos owner–proveedor (activos e históricos recientes) con metadatos de origen.
     */
    public List<OwnerProviderLinkDTO> listOwnerProviderLinks(String ownerId) {
        return assignmentRepo.findByOwnerId(ownerId).stream()
                .sorted(Comparator.comparing(PlatformProviderAssignmentEntity::getAssignedAt,
                        Comparator.nullsLast(Comparator.naturalOrder())).reversed())
                .map(this::mapAssignmentToOwnerLink)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    /**
     * V67 — Desvincula al agente/proveedor del dueño con políticas distintas
     * según el origen del vínculo.
     *
     * <p><b>PRIVATE</b> (agente invitado por el dueño): single-context por
     * regla del operador. Al desvincular se <b>elimina la cuenta
     * completamente</b> — hard-delete del user si no tiene historial, o
     * tombstone si sí (para preservar FKs contables). El username queda
     * libre inmediatamente. Es el comportamiento explícito: "si desvinculo
     * a mi contratista privado, desaparece".</p>
     *
     * <p><b>PLATFORM</b> (agente del catálogo oficial): multi-contexto. Al
     * desvincular <b>sólo se desactiva el vínculo</b> con este dueño. La
     * cuenta del provider, su username, su CLABE y su pertenencia al
     * catálogo permanecen intactos. Otro dueño (o este mismo) puede
     * re-vincularlo después — es la promesa de la plataforma al catálogo.</p>
     *
     * <p>Garantía de seguridad: nunca se elimina una cuenta que tenga
     * assignments activos con otros dueños (caso anómalo de data legacy).</p>
     */
    @Transactional
    public void unlinkProviderFromOwner(String providerUserId, String ownerOrgId) {
        Optional<PlatformProviderAssignmentEntity> assignment =
                assignmentRepo.findByProviderIdAndOwnerId(providerUserId, ownerOrgId);
        if (assignment.isEmpty()) {
            return;
        }
        PlatformProviderAssignmentEntity a = assignment.get();
        String source = a.getAssignmentSource();
        boolean wasPrivate = "PRIVATE".equalsIgnoreCase(source);
        a.setActive(false);
        assignmentRepo.save(a);

        if (wasPrivate) {
            // PRIVATE → eliminación total de la cuenta. Antes de delegar al
            // cascade, verificación defensiva: si por data legacy aún tiene
            // otros assignments activos, no lo matamos (sería corromper
            // contextos ajenos). Este caso no debería existir con V67+
            // porque linkPrivateProviderToOwner rechaza dobles PRIVATE.
            List<PlatformProviderAssignmentEntity> stillActive =
                    assignmentRepo.findByProviderIdAndActiveTrue(providerUserId);
            if (!stillActive.isEmpty()) {
                return;
            }
            UserEntity u = userRepository.findById(providerUserId).orElse(null);
            if (u == null || u.getRole() == Role.SUPER_ADMIN) return;

            // OwnerCascadeDeletionService.deleteOrTombstoneStaffOrProvider
            // decide entre HARD_DELETE y TOMBSTONE según si hay actividad
            // histórica. HARD_DELETE libera username y borra la fila;
            // TOMBSTONE desactiva la cuenta y renombra el username a un
            // placeholder — ambos hacen desaparecer al contacto para el
            // dueño y liberan el username para un nuevo alta.
            try {
                cascadeService.deleteOrTombstoneStaffOrProvider(providerUserId);
            } catch (Exception ex) {
                // El vínculo ya está inactivo → el dueño ya no lo ve. El
                // fallo en el cascade se loggea pero no se relanza para
                // no bloquear al dueño.
            }
        }
        // Si es PLATFORM → no tocamos el user. El provider sigue en el
        // catálogo disponible para re-vincularse con este u otro dueño.
    }

    private OwnerProviderLinkDTO mapAssignmentToOwnerLink(PlatformProviderAssignmentEntity a) {
        UserEntity u = userRepository.findById(a.getProviderId()).orElse(null);
        if (u == null) {
            return null;
        }
        OwnerProviderLinkDTO d = new OwnerProviderLinkDTO();
        d.setAssignmentId(a.getId());
        d.setProviderUserId(u.getId());
        d.setName(u.getName());
        d.setUsername(u.getLoginUsername());
        // V54: `email` en este DTO refleja el único email del user (contactEmail).
        d.setEmail(u.getContactEmail());
        d.setContactPhone(u.getContactPhone() != null ? u.getContactPhone() : u.getPhone());
        d.setProviderType(u.getRole().name());
        d.setAssignmentSource(a.getAssignmentSource());
        d.setAssignmentActive(a.isActive());
        d.setAssignedAt(a.getAssignedAt() != null ? a.getAssignedAt().toString() : null);
        // V63 — cuenta operativa = completó onboarding bancario. El dueño
        // necesita esta bandera para saber a quién de su equipo puede asignarle
        // tickets/vacancies sin encontrarse con 412 en el endpoint del agente.
        d.setAccountActive(bankAccountService.isAccountComplete(u.getId()));
        // lastSignInAt queda null por ahora — agregarlo requiere un campo
        // nuevo en UserEntity + migración. Se difiere a mejora futura.
        d.setLastSignInAt(null);
        return d;
    }

    private MaintenanceProviderDTO mapToDTO(UserEntity entity) {
        MaintenanceProviderDTO dto = new MaintenanceProviderDTO();
        dto.setId(entity.getId());
        dto.setName(entity.getName());
        // V54: contactEmail es el único email. El alias `email` del DTO lee
        // el mismo backing field.
        dto.setContactEmail(entity.getContactEmail());
        dto.setContactPhone(entity.getContactPhone());
        dto.setContactCountryCode(entity.getContactCountryCode());
        dto.setProviderType(entity.getRole().name());
        dto.setUsername(entity.getLoginUsername());
        return dto;
    }

    private String resolveActorEmail() {
        try {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            return auth != null ? auth.getName() : "SYSTEM";
        } catch (Exception e) { return "SYSTEM"; }
    }

    // #region agent log
    private static final java.nio.file.Path DEBUG_LOG_PATH =
            java.nio.file.Path.of("..", "debug-93290f.log").toAbsolutePath().normalize();
    private static void writeDebug(String hypothesisId, String msg, Map<String, Object> data) {
        try {
            StringBuilder sb = new StringBuilder();
            sb.append("{\"sessionId\":\"93290f\",\"hypothesisId\":\"").append(hypothesisId)
              .append("\",\"location\":\"MaintenanceProviderService.java\",\"message\":\"")
              .append(msg.replace("\"", "\\\"")).append("\",\"timestamp\":")
              .append(System.currentTimeMillis()).append(",\"data\":{");
            boolean first = true;
            for (Map.Entry<String, Object> e : data.entrySet()) {
                if (!first) sb.append(",");
                first = false;
                sb.append("\"").append(e.getKey()).append("\":");
                Object v = e.getValue();
                if (v == null) sb.append("null");
                else if (v instanceof Number || v instanceof Boolean) sb.append(v);
                else sb.append("\"").append(String.valueOf(v).replace("\\", "\\\\").replace("\"", "\\\"")).append("\"");
            }
            sb.append("}}\n");
            java.nio.file.Files.writeString(DEBUG_LOG_PATH, sb.toString(),
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.APPEND);
        } catch (Exception ignore) { /* best-effort logging */ }
    }
    // #endregion
}
