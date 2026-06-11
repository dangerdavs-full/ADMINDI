package com.admindi.backend.service;

import com.admindi.backend.dto.LeaseDTO;
import com.admindi.backend.model.*;
import com.admindi.backend.repository.LeaseRepository;
import com.admindi.backend.repository.OwnerMembershipRepository;
import com.admindi.backend.repository.PropertyRepository;
import com.admindi.backend.repository.TenantProfileRepository;
import com.admindi.backend.repository.UnitRepository;
import com.admindi.backend.repository.UserRepository;
import com.admindi.backend.security.TenantContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

@Service
public class LeaseService {

    private static final long MAX_LEASE_PDF_BYTES = 10 * 1024 * 1024;

    private final LeaseRepository leaseRepository;
    private final UnitRepository unitRepository;
    private final UserRepository userRepository;
    private final DomainEventDispatcher dispatcher;
    private final PropertyMovementService propertyMovementService;
    private final VacancyService vacancyService;
    private final PropertyRepository propertyRepository;
    private final FileStorageService fileStorageService;
    private final OwnerMembershipRepository ownerMembershipRepository;
    private final TenantProfileRepository tenantProfileRepository;

    @Autowired
    public LeaseService(LeaseRepository leaseRepository, UnitRepository unitRepository,
                        UserRepository userRepository, DomainEventDispatcher dispatcher,
                        PropertyMovementService propertyMovementService,
                        VacancyService vacancyService,
                        PropertyRepository propertyRepository,
                        FileStorageService fileStorageService,
                        OwnerMembershipRepository ownerMembershipRepository,
                        TenantProfileRepository tenantProfileRepository) {
        this.leaseRepository = leaseRepository;
        this.unitRepository = unitRepository;
        this.userRepository = userRepository;
        this.dispatcher = dispatcher;
        this.propertyMovementService = propertyMovementService;
        this.vacancyService = vacancyService;
        this.propertyRepository = propertyRepository;
        this.fileStorageService = fileStorageService;
        this.ownerMembershipRepository = ownerMembershipRepository;
        this.tenantProfileRepository = tenantProfileRepository;
    }

    private boolean tenantLinkedToOwner(String tenantUserId, String ownerId) {
        if (ownerMembershipRepository.findByUserIdAndOwnerId(tenantUserId, ownerId).isPresent()) {
            return true;
        }
        return tenantProfileRepository.existsByUserIdAndOwnerIdAndArchivedAtIsNull(tenantUserId, ownerId);
    }

    public String resolveOwnerId() {
        return TenantContext.resolveOwnerId(userRepository);
    }

    private String resolveActorEmail() {
        return SecurityContextHolder.getContext().getAuthentication().getName();
    }

    private void assertAtMostOneActiveLeasePerProperty(String ownerId, String propertyId) {
        if (propertyId == null || propertyId.isBlank()) {
            return;
        }
        if (leaseRepository.existsByOwnerIdAndProperty_IdAndStatus(ownerId, propertyId, LeaseStatus.ACTIVE)) {
            throw new RuntimeException("Ya existe un contrato ACTIVO para este inmueble.");
        }
    }

    /**
     * Legado: creacion por {@code unitId}. Siempre persiste {@code property_id} desde la unidad.
     */
    @Transactional
    public LeaseDTO createLease(String unitId, String tenantId, LocalDate start, LocalDate end,
                                BigDecimal rent, BigDecimal deposit, int paymentDay, String documentUrl,
                                String documentFileName, String documentContentType) {
        String ownerId = resolveOwnerId();
        String actor = resolveActorEmail();

        UnitEntity unit = unitRepository.findById(unitId)
                .orElseThrow(() -> new RuntimeException("Unidad no encontrada."));
        if (!unit.getOwnerId().equals(ownerId)) {
            throw new RuntimeException("IDOR: Unidad no pertenece a este Owner.");
        }
        PropertyEntity property = unit.getProperty();
        if (property == null) {
            throw new RuntimeException("La unidad no esta asociada a un inmueble.");
        }
        assertAtMostOneActiveLeasePerProperty(ownerId, property.getId());

        if (unit.getStatus() == UnitOccupancyStatus.OCCUPIED) {
            throw new RuntimeException("La unidad ya esta ocupada.");
        }

        UserEntity tenant = userRepository.findById(tenantId)
                .orElseThrow(() -> new RuntimeException("Inquilino no encontrado."));
        if (tenant.getRole() != Role.TENANT) {
            throw new RuntimeException("El usuario seleccionado no tiene rol de inquilino (rol actual: " + tenant.getRole() + ").");
        }
        if (!tenantLinkedToOwner(tenant.getId(), ownerId)) {
            throw new RuntimeException("El inquilino no pertenece a esta organizacion.");
        }
        if (!tenant.isActive()) {
            throw new RuntimeException("El inquilino esta desactivado.");
        }

        LeaseEntity lease = new LeaseEntity();
        lease.setOwnerId(ownerId);
        lease.setProperty(property);
        lease.setUnit(unit);
        lease.setTenant(tenant);
        lease.setStartDate(start);
        lease.setEndDate(end);
        lease.setMonthlyRent(rent);
        lease.setDepositAmount(deposit);
        lease.setStatus(LeaseStatus.ACTIVE);
        lease.setPaymentDay(paymentDay);
        lease.setDocumentUrl(documentUrl);
        lease.setDocumentFileName(documentFileName);
        lease.setDocumentContentType(documentContentType);

        unit.setStatus(UnitOccupancyStatus.OCCUPIED);
        unitRepository.save(unit);
        syncPropertyOccupiedForActiveLease(property);

        LeaseEntity saved = leaseRepository.save(lease);

        dispatcher.dispatch("LEASE_CREATED",
                "Contrato creado: " + property.getName() + " — " + unit.getName() + " -> " + tenant.getName(),
                "Renta: $" + rent + " | Inicio: " + start + " | Fin: " + end,
                ownerId, actor, null);

        return mapToDTO(saved);
    }

    /**
     * Flujo principal: contrato por inmueble (sin elegir unidad; {@code unit_id} queda nulo).
     */
    @Transactional
    public LeaseDTO createLeaseForProperty(String propertyId, String tenantUserId, LocalDate start, LocalDate end,
                                           BigDecimal rent, BigDecimal deposit, int paymentDay, String documentUrl,
                                           String documentFileName, String documentContentType) {
        String ownerId = resolveOwnerId();
        String actor = resolveActorEmail();

        PropertyEntity property = propertyRepository.findById(propertyId)
                .orElseThrow(() -> new RuntimeException("Inmueble no encontrado."));
        if (!property.getOwnerId().equals(ownerId)) {
            throw new RuntimeException("El inmueble no pertenece a su organizacion.");
        }
        if (property.getStatus() == PropertyStatus.DELETED) {
            throw new RuntimeException("El inmueble no esta disponible para contratos.");
        }
        assertAtMostOneActiveLeasePerProperty(ownerId, propertyId);

        UserEntity tenant = userRepository.findById(tenantUserId)
                .orElseThrow(() -> new RuntimeException("Inquilino no encontrado."));
        if (tenant.getRole() != Role.TENANT) {
            throw new RuntimeException("El usuario seleccionado no tiene rol de inquilino (rol actual: " + tenant.getRole() + ").");
        }
        if (!tenantLinkedToOwner(tenant.getId(), ownerId)) {
            throw new RuntimeException("El inquilino no pertenece a esta organizacion.");
        }
        if (!tenant.isActive()) {
            throw new RuntimeException("El inquilino esta desactivado.");
        }

        LeaseEntity lease = new LeaseEntity();
        lease.setOwnerId(ownerId);
        lease.setProperty(property);
        lease.setUnit(null);
        lease.setTenant(tenant);
        lease.setStartDate(start);
        lease.setEndDate(end);
        lease.setMonthlyRent(rent);
        lease.setDepositAmount(deposit);
        lease.setStatus(LeaseStatus.ACTIVE);
        lease.setPaymentDay(paymentDay);
        lease.setDocumentUrl(documentUrl);
        lease.setDocumentFileName(documentFileName);
        lease.setDocumentContentType(documentContentType);

        syncPropertyOccupiedForActiveLease(property);

        LeaseEntity saved = leaseRepository.save(lease);

        dispatcher.dispatch("LEASE_CREATED",
                "Contrato creado: " + property.getName() + " -> " + tenant.getName(),
                "Renta: $" + rent + " | Inicio: " + start + " | Fin: " + end,
                ownerId, actor, null);

        return mapToDTO(saved);
    }

    public String storeLeaseContractPdf(MultipartFile file, String ownerId) {
        if (file == null || file.isEmpty()) {
            return null;
        }
        if (file.getSize() > MAX_LEASE_PDF_BYTES) {
            throw new RuntimeException("El PDF del contrato excede 10 MB.");
        }
        String ct = file.getContentType();
        if (ct == null || !"application/pdf".equalsIgnoreCase(ct)) {
            throw new RuntimeException("Solo se permiten archivos PDF para el contrato firmado.");
        }
        String name = file.getOriginalFilename();
        if (name != null && !name.toLowerCase(Locale.ROOT).endsWith(".pdf")) {
            throw new RuntimeException("El archivo debe tener extension .pdf.");
        }
        return fileStorageService.store(file, "leases/contracts/" + ownerId);
    }

    private void syncPropertyOccupiedForActiveLease(PropertyEntity prop) {
        if (prop == null) {
            return;
        }
        if (prop.getStatus() == PropertyStatus.DELETED) {
            return;
        }
        prop.setStatus(PropertyStatus.OCCUPIED);
        propertyRepository.save(prop);
    }

    /**
     * Refresco idempotente de la ocupación a nivel de inmueble.
     *
     * <p><strong>Única fuente de verdad: contratos (leases) en estado ACTIVE.</strong>
     * Un inmueble se considera OCCUPIED si y solo si existe al menos un lease ACTIVE ligado
     * a {@code (ownerId, propertyId)}; en caso contrario vuelve a AVAILABLE.
     *
     * <p>Antes este método también contaba {@code tenant_profiles} no archivados como señal de
     * ocupación, lo que dejaba inmuebles "pegados" en OCCUPIED tras terminar el contrato
     * directamente desde la pantalla de Contratos (el perfil queda sin archivar hasta la baja
     * operativa explícita). El banner de Detalle entonces mostraba "Ocupado" junto a "Sin
     * contrato ACTIVO · Vacancia Abierta", una contradicción visible para el usuario.
     *
     * <p>La regla nueva alinea {@code properties.status} con lo que ve el usuario en la banner
     * (activeLease + vacancyOpen) y con el flujo de vacancias comerciales: si el contrato se
     * terminó, el inmueble está disponible, sin importar si el expediente del arrendatario
     * permanece archivado o no. Respetamos MAINTENANCE y DELETED sin tocarlos.
     *
     * <p>Idempotente: correrlo N veces converge al valor correcto.
     */
    public void refreshPropertyOccupancyIncludingProfiles(String ownerId, String propertyId) {
        PropertyEntity p = propertyRepository.findById(propertyId).orElse(null);
        if (p == null || p.getStatus() == PropertyStatus.DELETED) {
            return;
        }
        long activeLeases = leaseRepository.countByOwnerIdAndProperty_IdAndStatus(
                ownerId, propertyId, LeaseStatus.ACTIVE);
        PropertyStatus target = activeLeases > 0 ? PropertyStatus.OCCUPIED : PropertyStatus.AVAILABLE;
        // Respetamos MAINTENANCE explícito: el dueño pudo haber marcado manualmente el inmueble
        // en mantenimiento; no lo desplazamos a OCCUPIED/AVAILABLE si no hay cambios reales.
        if (p.getStatus() == PropertyStatus.MAINTENANCE && target == PropertyStatus.AVAILABLE) {
            return;
        }
        // Fase 2: estados intermedios del ciclo comercial (PENDING_RENT, PROSPECT_PROPOSED,
        // AWAITING_CONTRACT) son responsabilidad exclusiva del VacancyAgentOrchestrationService:
        // si el reconciliador entra con target=AVAILABLE mientras la vacancia está a medio
        // camino, no debe pisar el estado comercial — eso se siente como "el sistema me borró
        // el prospecto al recargar". El orquestador comercial es quien vuelve a AVAILABLE si
        // la cadena se cancela o agota.
        if (target == PropertyStatus.AVAILABLE && isInCommercialCycle(p.getStatus())) {
            return;
        }
        if (p.getStatus() != target) {
            p.setStatus(target);
            propertyRepository.save(p);
        }
    }

    private boolean isInCommercialCycle(PropertyStatus s) {
        return s == PropertyStatus.PENDING_RENT
                || s == PropertyStatus.PROSPECT_PROPOSED
                || s == PropertyStatus.AWAITING_CONTRACT;
    }

    private void refreshPropertyOccupancyAfterLeaseChange(String propertyId, String ownerId) {
        refreshPropertyOccupancyIncludingProfiles(ownerId, propertyId);
    }

    @Transactional
    public LeaseDTO terminateLease(String leaseId) {
        String ownerId = resolveOwnerId();
        String actor = resolveActorEmail();

        LeaseEntity lease = leaseRepository.findById(leaseId)
                .orElseThrow(() -> new RuntimeException("Contrato no encontrado."));
        if (!lease.getOwnerId().equals(ownerId)) {
            throw new RuntimeException("IDOR: Contrato no pertenece a este Owner.");
        }

        PropertyEntity property = lease.resolvePropertyEntity();
        UnitEntity unit = lease.getUnit();

        lease.setStatus(LeaseStatus.TERMINATED);
        leaseRepository.save(lease);

        String propertyId = property != null ? property.getId() : null;
        if (unit != null) {
            unit.setStatus(UnitOccupancyStatus.VACANT);
            unitRepository.save(unit);
        }
        if (propertyId != null) {
            refreshPropertyOccupancyAfterLeaseChange(propertyId, ownerId);
        }

        String label = property != null ? property.getName() : (unit != null ? unit.getName() : "Contrato");
        dispatcher.dispatch("LEASE_TERMINATED",
                "Contrato terminado: " + label,
                null, ownerId, actor, null);

        UserEntity actorUser = userRepository.findByLoginIdentifier(actor).orElse(null);
        String actorUserId = actorUser != null ? actorUser.getId() : null;
        String actorRole = actorUser != null ? actorUser.getRole().name() : "OWNER";
        if (propertyId != null) {
            String meta = "{\"leaseId\":\"" + lease.getId() + "\",\"propertyId\":\"" + propertyId + "\""
                    + (unit != null ? ",\"unitId\":\"" + unit.getId() + "\"" : "")
                    + "}";
            propertyMovementService.record(ownerId, propertyId, "LEASE", lease.getId(),
                    actorUserId, actorRole, PropertyMovementEventType.LEASE_TERMINATED,
                    "Contrato terminado — " + label,
                    unit != null ? "Unidad asociada (legado) liberada." : "Inmueble liberado a nivel de propiedad.",
                    java.time.LocalDateTime.now(),
                    meta,
                    null);
        }

        try {
            vacancyService.openVacancyFromLeaseTermination(lease);
        } catch (Exception ex) {
            org.slf4j.LoggerFactory.getLogger(LeaseService.class).warn("Vacancy open after lease end failed: {}", ex.getMessage());
        }

        return mapToDTO(lease);
    }

    public List<LeaseDTO> getLeasesForOwner() {
        String ownerId = resolveOwnerId();
        return leaseRepository.findByOwnerId(ownerId).stream()
                .map(this::mapToDTO)
                .collect(Collectors.toList());
    }

    private LeaseDTO mapToDTO(LeaseEntity e) {
        PropertyEntity prop = e.resolvePropertyEntity();
        UnitEntity unit = e.getUnit();
        LeaseDTO dto = new LeaseDTO();
        dto.setId(e.getId());
        dto.setPropertyId(prop != null ? prop.getId() : null);
        dto.setUnitId(unit != null ? unit.getId() : null);
        dto.setUnitName(unit != null ? unit.getName() : null);
        dto.setPropertyName(prop != null ? prop.getName() : null);
        dto.setTenantId(e.getTenant() != null ? e.getTenant().getId() : null);
        dto.setTenantName(e.getTenant() != null ? e.getTenant().getName() : null);
        dto.setTenantEmail(e.getTenant() != null ? e.getTenant().getContactEmail() : null);
        dto.setStartDate(e.getStartDate());
        dto.setEndDate(e.getEndDate());
        dto.setMonthlyRent(e.getMonthlyRent());
        dto.setDepositAmount(e.getDepositAmount());
        dto.setPaymentDay(e.getPaymentDay());
        dto.setStatus(e.getStatus());
        dto.setDocumentUrl(e.getDocumentUrl());
        dto.setDocumentFileName(e.getDocumentFileName());
        dto.setDocumentContentType(e.getDocumentContentType());
        return dto;
    }
}
