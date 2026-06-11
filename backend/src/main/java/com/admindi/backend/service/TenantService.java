package com.admindi.backend.service;

import com.admindi.backend.dto.LeaseDTO;
import com.admindi.backend.dto.TenantDTO;
import com.admindi.backend.model.LeaseStatus;
import com.admindi.backend.model.OwnerMembershipEntity;
import com.admindi.backend.model.PropertyEntity;
import com.admindi.backend.model.PropertyMovementEventType;
import com.admindi.backend.model.Role;
import com.admindi.backend.model.TenantProfileEntity;
import com.admindi.backend.model.UserEntity;
import com.admindi.backend.repository.LeaseRepository;
import com.admindi.backend.repository.OwnerMembershipRepository;
import com.admindi.backend.repository.PropertyRepository;
import com.admindi.backend.repository.TenantProfileRepository;
import com.admindi.backend.repository.UserRepository;
import com.admindi.backend.security.TenantContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class TenantService {

    private final UserRepository userRepository;
    private final TenantProfileRepository tenantProfileRepository;
    private final PropertyRepository propertyRepository;
    private final PasswordEncoder passwordEncoder;
    private final DomainEventDispatcher dispatcher;
    private final LeaseService leaseService;
    private final LeaseRepository leaseRepository;
    private final PropertyMovementService propertyMovementService;
    private final OwnerMembershipRepository ownerMembershipRepository;
    private final VacancyService vacancyService;
    private final LedgerService ledgerService;
    private final UsernameService usernameService;
    private final org.springframework.beans.factory.ObjectProvider<ContractClosureService> contractClosureServiceProvider;

    private static final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(TenantService.class);

    @Value("${app.url:http://localhost:3000}")
    private String appUrl;

    @Autowired
    public TenantService(UserRepository userRepository, TenantProfileRepository tenantProfileRepository,
                         PropertyRepository propertyRepository,
                         PasswordEncoder passwordEncoder, DomainEventDispatcher dispatcher,
                         LeaseService leaseService, LeaseRepository leaseRepository,
                         PropertyMovementService propertyMovementService,
                         OwnerMembershipRepository ownerMembershipRepository,
                         VacancyService vacancyService,
                         LedgerService ledgerService,
                         UsernameService usernameService,
                         org.springframework.beans.factory.ObjectProvider<ContractClosureService> contractClosureServiceProvider) {
        this.userRepository = userRepository;
        this.tenantProfileRepository = tenantProfileRepository;
        this.propertyRepository = propertyRepository;
        this.passwordEncoder = passwordEncoder;
        this.dispatcher = dispatcher;
        this.leaseService = leaseService;
        this.leaseRepository = leaseRepository;
        this.propertyMovementService = propertyMovementService;
        this.ownerMembershipRepository = ownerMembershipRepository;
        this.vacancyService = vacancyService;
        this.ledgerService = ledgerService;
        this.usernameService = usernameService;
        this.contractClosureServiceProvider = contractClosureServiceProvider;
    }

    private String resolveOwnerId() {
        return TenantContext.resolveOwnerId(userRepository);
    }

    private void ensureOwnerMembership(String userId, String ownerId, String actorEmail) {
        if (ownerMembershipRepository.findByUserIdAndOwnerId(userId, ownerId).isPresent()) {
            return;
        }
        OwnerMembershipEntity m = new OwnerMembershipEntity();
        m.setUserId(userId);
        m.setOwnerId(ownerId);
        m.setAssignedAt(LocalDateTime.now());
        m.setAssignedBy(actorEmail);
        ownerMembershipRepository.save(m);
    }

    /**
     * Un solo dueño en BD: conservamos {@link UserEntity#setOwnerId(String)} para compatibilidad.
     * Varios dueños: {@code ownerId} se anula; el contexto activo va solo en el JWT ({@code TenantContext}).
     */
    private void syncTenantUserOwnerPointer(UserEntity user) {
        if (user.getRole() != Role.TENANT) {
            return;
        }
        LinkedHashSet<String> owners = new LinkedHashSet<>();
        ownerMembershipRepository.findByUserId(user.getId()).forEach(x -> owners.add(x.getOwnerId()));
        tenantProfileRepository.findByUserId(user.getId()).stream()
                .filter(p -> p.getArchivedAt() == null)
                .forEach(p -> owners.add(p.getOwnerId()));
        if (owners.size() <= 1) {
            user.setOwnerId(owners.isEmpty() ? null : owners.iterator().next());
        } else {
            user.setOwnerId(null);
        }
        userRepository.save(user);
    }

    private String resolveActorEmail() {
        return SecurityContextHolder.getContext().getAuthentication().getName();
    }

    private void validatePropertyId(String propertyId, String ownerId) {
        if (propertyId != null && !propertyId.isBlank()) {
            PropertyEntity prop = propertyRepository.findById(propertyId)
                    .orElseThrow(() -> new RuntimeException("Inmueble no encontrado (id: " + propertyId + ")."));
            if (!prop.getOwnerId().equals(ownerId)) {
                throw new RuntimeException("El inmueble no pertenece a esta organización.");
            }
            if (!prop.isActive()) {
                throw new RuntimeException("El inmueble está inactivo/eliminado.");
            }
        }
    }

    /**
     * Paso 2: el inmueble del expediente no se modifica por edición de perfil; mover de inmueble será otro flujo explícito.
     */
    private void assertPropertyIdUnchangedOnEdit(TenantProfileEntity profile, TenantDTO dto) {
        String current = profile.getPropertyId();
        String requested = dto.getPropertyId();
        boolean curBlank = current == null || current.isBlank();
        boolean reqBlank = requested == null || requested.isBlank();

        if (!curBlank && reqBlank) {
            throw new RuntimeException(
                    "No se puede quitar el inmueble del expediente desde la edición. Movimiento o baja serán flujos aparte.");
        }
        if (curBlank && !reqBlank) {
            throw new RuntimeException(
                    "No se puede asignar un inmueble nuevo desde la edición; use el alta integral de arrendatario.");
        }
        if (!curBlank && !reqBlank && !current.trim().equals(requested.trim())) {
            throw new RuntimeException(
                    "No se puede cambiar el inmueble del expediente desde esta pantalla. Trasladar arrendatario a otro inmueble será un flujo explícito posterior.");
        }
    }

    private void validateIntegralContractFields(TenantDTO dto) {
        if (dto.getPropertyId() == null || dto.getPropertyId().isBlank()) {
            throw new RuntimeException("El inmueble es obligatorio para el expediente.");
        }
        if (dto.getLeaseStartDate() == null || dto.getLeaseEndDate() == null) {
            throw new RuntimeException("Las fechas de inicio y fin del contrato son obligatorias.");
        }
        if (dto.getLeaseEndDate().isBefore(dto.getLeaseStartDate())) {
            throw new RuntimeException("La fecha de fin del contrato debe ser posterior o igual al inicio.");
        }
        if (dto.getPhone() == null || dto.getPhone().isBlank()) {
            throw new RuntimeException("El teléfono es obligatorio.");
        }
    }

    /**
     * V51 — valida que el alta de un tenant nuevo (no reuso) traiga el trío
     * obligatorio: username (identificador login, case-sensitive), email
     * (contacto + credencial) y nombre. El teléfono se valida aparte en
     * {@link #validateIntegralContractFields(TenantDTO)}.
     */
    private void validateNewTenantIdentityFields(TenantDTO dto) {
        if (dto.getName() == null || dto.getName().isBlank()) {
            throw new RuntimeException("Nombre es obligatorio.");
        }
        if (dto.getUsername() == null || dto.getUsername().isBlank()) {
            throw new RuntimeException("Usuario para iniciar sesión es obligatorio.");
        }
        if (dto.getContactEmail() == null || dto.getContactEmail().isBlank()) {
            throw new RuntimeException("Email es obligatorio.");
        }
    }

    /**
     * Alta integral (Paso 2): usuario TENANT, perfil, lease ACTIVO por {@code propertyId}, inmueble ocupado, auditoría.
     */
    @Transactional
    public TenantDTO createTenant(TenantDTO dto, MultipartFile contractPdf) {
        String ownerId = resolveOwnerId();
        String actor = resolveActorEmail();

        validateIntegralContractFields(dto);

        validatePropertyId(dto.getPropertyId(), ownerId);

        if (leaseRepository.existsByOwnerIdAndProperty_IdAndStatus(ownerId, dto.getPropertyId(), LeaseStatus.ACTIVE)) {
            throw new RuntimeException(
                    "Ya existe un expediente activo: hay un contrato ACTIVO para este inmueble. Termine o cierre el contrato actual antes de asignar otro arrendatario.");
        }

        // V48 / Bloque 2 — identidad del arrendatario:
        //
        //   Opción A (reuso explícito): el OWNER añade otro inmueble a un tenant
        //     que ya pertenece a su universo. Manda `reuseExistingUserId` con el
        //     id del UserEntity tenant existente. Se valida que sea TENANT y que
        //     tenga al menos un tenant_profile en este mismo ownerId (para evitar
        //     que un OWNER reuse al tenant de otro OWNER, eso sería multi-contexto
        //     prohibido por el modelo).
        //
        //   Opción B (creación nueva): no viene `reuseExistingUserId`. Se crea
        //     una cuenta TENANT nueva. El username se toma explícito del DTO o
        //     se deriva del email; la unicidad es global. El email puede coincidir
        //     con otras cuentas (ya no es identificador); el humano recibirá su
        //     credencial por email.
        //
        // Se elimina el comportamiento legacy de "si existe un user con ese email,
        // reusarlo automáticamente": eso permitía fugas de contexto entre dueños y
        // es la raíz del bug histórico de multi-contexto.
        UserEntity savedUser;
        String tempPassword = null;

        if (dto.getReuseExistingUserId() != null && !dto.getReuseExistingUserId().isBlank()) {
            UserEntity existing = userRepository.findById(dto.getReuseExistingUserId())
                    .orElseThrow(() -> new RuntimeException("Arrendatario a reutilizar no encontrado."));
            if (existing.getRole() != Role.TENANT) {
                throw new RuntimeException("La cuenta referenciada no es de tipo TENANT.");
            }
            // El tenant debe pertenecer a este owner (vía tenant_profile), si no el
            // OWNER intentaría añadir como tenant a alguien ajeno a su universo.
            boolean belongsToOwner = tenantProfileRepository.findByUserId(existing.getId()).stream()
                    .anyMatch(p -> ownerId.equals(p.getOwnerId()) && p.getArchivedAt() == null);
            if (!belongsToOwner) {
                throw new RuntimeException(
                        "Esa cuenta no pertenece a este dueño. Crea un arrendatario nuevo en su lugar.");
            }
            if (tenantProfileRepository.existsByUserIdAndOwnerIdAndPropertyIdAndArchivedAtIsNull(
                    existing.getId(), ownerId, dto.getPropertyId())) {
                throw new RuntimeException(
                        "Este arrendatario ya tiene un expediente activo vinculado a este inmueble.");
            }
            if (dto.getName() != null && !dto.getName().isBlank()) existing.setName(dto.getName());
            if (dto.getPhone() != null && !dto.getPhone().isBlank()) existing.setPhone(dto.getPhone());
            if (!existing.isActive()) existing.setActive(true);
            savedUser = userRepository.save(existing);
            // Camino LEGACY (reuso): refrescamos owner_membership para mantener
            // en pie cualquier join que aún dependa de la tabla histórica.
            ensureOwnerMembership(savedUser.getId(), ownerId, actor);
        } else {
            // V54 — creación pura: exige trío username + email + nombre (phone ya
            // validado arriba). El único email del user vive en contactEmail
            // (NO único, compartible entre cuentas). Username preserva case.
            validateNewTenantIdentityFields(dto);
            String normalizedEmail = dto.getContactEmail().trim().toLowerCase();
            String username = usernameService.resolveOrDerive(dto.getUsername(), normalizedEmail);
            tempPassword = UUID.randomUUID().toString().substring(0, 8).toUpperCase();
            UserEntity user = new UserEntity();
            user.setLoginUsername(username);
            user.setName(dto.getName());
            user.setPhone(dto.getPhone());
            user.setPassword(passwordEncoder.encode(tempPassword));
            user.setRole(Role.TENANT);
            user.setOwnerId(ownerId);
            user.setMustChangePassword(true);
            user.setActive(true);
            user.setContactEmail(normalizedEmail);
            if (dto.getPhone() != null && !dto.getPhone().isBlank()) {
                user.setContactPhone(dto.getPhone());
            }
            savedUser = userRepository.saveAndFlush(user);
            // Camino NUEVO (V48 / Bloque 2): NO escribimos owner_membership.
            // La pertenencia del tenant a este owner queda definida exclusivamente
            // por tenant_profiles — ver AuthService.buildTenantOwnerContexts y
            // docs/ARCHITECTURE_IDENTITY_AND_EXPEDIENTES.md §2.3.
        }

        TenantProfileEntity profile = new TenantProfileEntity(
                savedUser.getId(), ownerId, dto.getPropertyId(),
                dto.getRentAmount() != null ? dto.getRentAmount() : BigDecimal.ZERO,
                dto.getPaymentDay()
        );
        profile.setHasLateFee(dto.isHasLateFee());
        profile.setLateFeeType(dto.getLateFeeType());
        profile.setLateFeeValue(dto.getLateFeeValue());
        profile.setGracePeriodDays(dto.getGracePeriodDays());
        tenantProfileRepository.save(profile);

        syncTenantUserOwnerPointer(savedUser);

        BigDecimal deposit = dto.getDepositAmount() != null ? dto.getDepositAmount() : BigDecimal.ZERO;

        String docUrl = null;
        String docName = null;
        String docCt = null;
        if (contractPdf != null && !contractPdf.isEmpty()) {
            docUrl = leaseService.storeLeaseContractPdf(contractPdf, ownerId);
            docName = contractPdf.getOriginalFilename();
            docCt = contractPdf.getContentType();
        }

        LeaseDTO leaseDto = leaseService.createLeaseForProperty(
                dto.getPropertyId(),
                savedUser.getId(),
                dto.getLeaseStartDate(),
                dto.getLeaseEndDate(),
                dto.getRentAmount() != null ? dto.getRentAmount() : BigDecimal.ZERO,
                deposit,
                dto.getPaymentDay(),
                docUrl,
                docName,
                docCt);

        // Hook Fase 2 (agente inmobiliario): si este property venía de una vacancia
        // con contrato firmado y comisión pendiente, amarramos el leaseId para que
        // el agente vea el pendiente de cobro en su panel. Si no hay match, silencio.
        try {
            ContractClosureService ccs = contractClosureServiceProvider.getIfAvailable();
            if (ccs != null) {
                ccs.onLeaseConfirmedForVacancy(ownerId, dto.getPropertyId(), leaseDto.getId(), savedUser.getContactEmail());
            }
        } catch (Exception ex) {
            logger.warn("[TENANT_CREATE] no se pudo amarrar comisión a lease={} propertyId={} — {}",
                    leaseDto.getId(), dto.getPropertyId(), ex.getMessage());
        }

        // Generación inmediata de la factura del mes en curso para el nuevo expediente.
        // Evita la espera al cron nocturno que dejaría al dueño/inquilino sin recibo visible
        // hasta el día siguiente. Idempotente: si por cualquier razón la factura ya existe
        // (re-alta tras archivo), no se duplica.
        //
        // Se envuelve en try/catch defensivo: un fallo aquí NO debe bloquear la creación
        // del expediente. El cron diario de LedgerService recupera cualquier factura faltante
        // al siguiente tick.
        try {
            ledgerService.generateCurrentMonthInvoiceIfMissing(profile, LocalDate.now());
        } catch (Exception invoiceErr) {
            logger.warn("[TENANT_CREATE] fallo generando invoice inmediata tenantProfile={} err={} — el cron nocturno recuperará",
                    profile.getId(), invoiceErr.getMessage());
        }

        UserEntity actorUser = userRepository.findByUsername(actor).orElse(null);
        String actorUserId = actorUser != null ? actorUser.getId() : null;
        String actorRole = actorUser != null && actorUser.getRole() != null ? actorUser.getRole().name() : "OWNER";
        String meta = String.format(
                "{\"tenantProfileId\":\"%s\",\"leaseId\":\"%s\",\"userId\":\"%s\"}",
                profile.getId(), leaseDto.getId(), savedUser.getId());
        propertyMovementService.record(ownerId, dto.getPropertyId(), "LEASE", leaseDto.getId(),
                actorUserId, actorRole, PropertyMovementEventType.TENANT_EXPEDIENTE_OPENED,
                "Expediente abierto",
                "Alta de arrendatario y contrato ACTIVO sobre el inmueble.",
                LocalDateTime.now(),
                meta,
                null);

        dispatcher.dispatch("TENANT_CREATED",
                "Expediente abierto: " + savedUser.getName(),
                "Inmueble vinculado y contrato ACTIVO registrado.",
                ownerId, actor, null);

        // TENANT_WELCOME — destinatario = el arrendatario (no el dueño). Solo se envía
        // cuando el usuario es nuevo (tempPassword != null) para incluir credenciales.
        // Si el tenant ya existía (otro expediente), no tiene sentido mandar "bienvenida
        // con tu contraseña temporal" porque el tenant ya tiene contraseña definitiva;
        // en ese caso se podría enviar un "nuevo contrato" aparte (fuera de Fase 1).
        if (tempPassword != null) {
            try {
                String welcomeBody = buildTenantWelcomeBody(savedUser, dto, tempPassword, ownerId);

                // Resolver nombre del inmueble para WhatsApp (no corta el flujo si falla).
                String propertyName = "(inmueble)";
                try {
                    if (dto.getPropertyId() != null) {
                        PropertyEntity prop = propertyRepository.findById(dto.getPropertyId()).orElse(null);
                        if (prop != null && prop.getName() != null) propertyName = prop.getName();
                    }
                } catch (Exception ignored) { /* fallback "(inmueble)" */ }

                // Variables WhatsApp — plantilla admindi_tenant_welcome_v2 (4 slots).
                // V54: el único email del user es contactEmail. El texto Meta-aprobado
                // dice "Correo registrado: {{3}}" y así queda: WhatsApp es "teaser"
                // amistoso para avisar que ya hay cuenta; las credenciales reales
                // (username + temp password) viajan por email al contactEmail. El login
                // sigue siendo username case-sensitive — no se muestra aquí porque el
                // email es autoritativo y ya incluye el username + temp password.
                String contactEmail = savedUser.getContactEmail() != null
                        ? savedUser.getContactEmail() : "";
                Map<String, String> tplVars = Map.of(
                        "1", savedUser.getName() != null ? savedUser.getName() : "",
                        "2", propertyName,
                        "3", contactEmail,
                        "4", appUrl != null ? appUrl : ""
                );

                dispatcher.dispatch(
                        "TENANT_WELCOME",
                        "Bienvenido a ADMINDI",
                        welcomeBody,
                        ownerId,
                        actor,
                        List.of(savedUser.getId()),
                        tplVars,
                        null
                );
            } catch (Exception e) {
                // No romper la alta por un fallo en la bienvenida.
            }
        }

        vacancyService.closeOpenVacanciesForPropertyOnNewExpediente(
                ownerId, dto.getPropertyId(), actorUserId, actorRole, profile.getId());

        TenantDTO result = mapToDTO(profile, savedUser);
        if (tempPassword != null) {
            result.setTempPassword(tempPassword);
        }
        result.setLeaseId(leaseDto.getId());
        result.setLeaseStatus(leaseDto.getStatus());
        return result;
    }

    public TenantDTO updateTenant(String profileId, TenantDTO dto) {
        String ownerId = resolveOwnerId();
        String actor = resolveActorEmail();

        TenantProfileEntity profile = tenantProfileRepository.findById(profileId)
                .orElseThrow(() -> new RuntimeException("Perfil de inquilino no encontrado."));

        if (!profile.getOwnerId().equals(ownerId)) {
            throw new RuntimeException("IDOR: Inquilino de otro dueño.");
        }

        UserEntity user = userRepository.findById(profile.getUserId())
                .orElseThrow(() -> new RuntimeException("Usuario no encontrado."));
        user.setName(dto.getName());
        user.setPhone(dto.getPhone());
        userRepository.save(user);

        assertPropertyIdUnchangedOnEdit(profile, dto);

        profile.setRentAmount(dto.getRentAmount() != null ? dto.getRentAmount() : profile.getRentAmount());
        profile.setPaymentDay(dto.getPaymentDay());
        profile.setHasLateFee(dto.isHasLateFee());
        profile.setLateFeeType(dto.getLateFeeType());
        profile.setLateFeeValue(dto.getLateFeeValue());
        profile.setGracePeriodDays(dto.getGracePeriodDays());
        tenantProfileRepository.save(profile);

        dispatcher.dispatch("TENANT_UPDATED",
                "Inquilino actualizado: " + user.getName(),
                null, ownerId, actor, null);

        return mapToDTO(profile, user);
    }

    /**
     * Equivalente operativo a {@link TenantExpedienteArchiveService#archiveOperational(String)}: termina contrato,
     * archivo de expediente, vacancia y revocación de acceso según reglas de archivo.
     */
    public void deactivateTenant(String profileId) {
        throw new RuntimeException("Use POST /api/tenants/{tenantProfileId}/archive para la baja operativa del expediente.");
    }

    /**
     * Tras archivo de expediente o cambio de membresías: mantiene {@link UserEntity#getOwnerId()} coherente.
     */
    public void resyncTenantOwnerPointer(String tenantUserId) {
        UserEntity user = userRepository.findById(tenantUserId).orElseThrow();
        if (user.getRole() != Role.TENANT) {
            return;
        }
        syncTenantUserOwnerPointer(user);
    }

    public List<TenantDTO> getMyTenants() {
        String ownerId = resolveOwnerId();
        return tenantProfileRepository.findByOwnerId(ownerId).stream()
                .filter(p -> p.getArchivedAt() == null)
                .map(profile -> {
                    UserEntity user = userRepository.findById(profile.getUserId()).orElse(null);
                    if (user == null || !user.isActive()) return null;
                    return mapToDTO(profile, user);
                })
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toList());
    }

    private TenantDTO mapToDTO(TenantProfileEntity p, UserEntity u) {
        TenantDTO dto = new TenantDTO();
        dto.setId(p.getId());
        dto.setUserId(u.getId());
        dto.setName(u.getName());
        dto.setUsername(u.getLoginUsername());
        // V54: contactEmail es el único email. El alias `email` del DTO lee
        // el mismo backing field, por lo que el frontend ve exactamente lo mismo.
        dto.setContactEmail(u.getContactEmail());
        dto.setPhone(u.getPhone());
        dto.setPropertyId(p.getPropertyId());
        dto.setRentAmount(p.getRentAmount());
        dto.setPaymentDay(p.getPaymentDay());
        dto.setHasLateFee(p.isHasLateFee());
        dto.setLateFeeType(p.getLateFeeType());
        dto.setLateFeeValue(p.getLateFeeValue());
        dto.setGracePeriodDays(p.getGracePeriodDays());

        if (p.getPropertyId() != null && !p.getPropertyId().isBlank()) {
            leaseRepository
                    .findFirstByOwnerIdAndTenant_IdAndProperty_IdAndStatus(
                            p.getOwnerId(), u.getId(), p.getPropertyId(), LeaseStatus.ACTIVE)
                    .ifPresent(lease -> {
                        dto.setLeaseId(lease.getId());
                        dto.setLeaseStatus(lease.getStatus());
                    });
        }
        return dto;
    }

    /**
     * Cuerpo del mensaje de bienvenida al arrendatario nuevo. Se entrega por EMAIL y
     * WhatsApp (Twilio). La CLABE del dueño se incluye en claro (el arrendatario la
     * necesita para transferir); el dueño la capturó previamente en su portal y aceptó
     * exponerla a sus inquilinos por diseño.
     */
    private String buildTenantWelcomeBody(UserEntity tenantUser, TenantDTO dto,
                                          String tempPassword, String ownerId) {
        String propertyName = "tu inmueble";
        try {
            PropertyEntity prop = propertyRepository.findById(dto.getPropertyId()).orElse(null);
            if (prop != null && prop.getName() != null && !prop.getName().isBlank()) {
                propertyName = prop.getName();
            }
        } catch (Exception ignored) { /* best-effort */ }

        UserEntity owner = userRepository.findById(ownerId).orElse(null);
        String ownerClabe = owner != null && owner.getClabe() != null ? owner.getClabe() : null;
        String ownerBank = owner != null && owner.getBankName() != null ? owner.getBankName() : null;
        String ownerHolder = owner != null && owner.getAccountHolderName() != null
                ? owner.getAccountHolderName() : null;

        StringBuilder sb = new StringBuilder();
        sb.append("Hola ").append(tenantUser.getName() != null ? tenantUser.getName() : "").append(",\n\n");
        sb.append("Se creó tu expediente de arrendamiento en ADMINDI.\n\n");
        sb.append("Datos del contrato:\n");
        sb.append("  • Inmueble: ").append(propertyName).append("\n");
        if (dto.getRentAmount() != null) {
            sb.append("  • Renta mensual: $").append(dto.getRentAmount().toPlainString()).append("\n");
        }
        if (dto.getPaymentDay() > 0) {
            sb.append("  • Día de pago: ").append(dto.getPaymentDay()).append(" de cada mes\n");
        }
        sb.append("\nDatos de acceso al portal:\n");
        sb.append("  • Portal: ").append(appUrl).append("\n");
        sb.append("  • Usuario: ").append(tenantUser.getUsername() != null ? tenantUser.getUsername() : "").append("\n");
        sb.append("  • Contraseña temporal: ").append(tempPassword).append("\n");
        if (tenantUser.getContactEmail() != null && !tenantUser.getContactEmail().isBlank()) {
            sb.append("  • Correo de contacto registrado: ").append(tenantUser.getContactEmail()).append("\n");
        }
        sb.append("\nEl sistema te pedirá cambiar la contraseña al ingresar.\n");
        sb.append("Recuerda: tu identificador de login es el USUARIO, no el correo.\n");
        if (ownerClabe != null && !ownerClabe.isBlank()) {
            sb.append("\nDatos para transferencia SPEI:\n");
            if (ownerBank != null) sb.append("  • Banco: ").append(ownerBank).append("\n");
            if (ownerHolder != null) sb.append("  • Titular: ").append(ownerHolder).append("\n");
            sb.append("  • CLABE: ").append(ownerClabe).append("\n");
        } else {
            sb.append("\nCuando el dueño capture su cuenta bancaria te enviaremos los datos para transferencia.");
        }
        sb.append("\nRecibirás recordatorios 5, 3, 2 y 1 día antes de la fecha de pago.");
        return sb.toString();
    }
}
