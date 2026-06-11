package com.admindi.backend.service;

import com.admindi.backend.model.ActionTaskEntity;
import com.admindi.backend.model.LeaseEntity;
import com.admindi.backend.model.PropertyEntity;
import com.admindi.backend.model.PropertyMovementEventType;
import com.admindi.backend.model.Role;
import com.admindi.backend.model.UserEntity;
import com.admindi.backend.model.VacancyEntity;
import com.admindi.backend.repository.ActionTaskRepository;
import com.admindi.backend.repository.PropertyRepository;
import com.admindi.backend.repository.TenantProfileRepository;
import com.admindi.backend.repository.UserRepository;
import com.admindi.backend.repository.VacancyRepository;
import com.admindi.backend.security.TenantContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
public class VacancyService {

    private final VacancyRepository vacancyRepository;
    private final UserRepository userRepository;
    private final PropertyMovementService propertyMovementService;
    private final ProviderAgentRoutingService providerAgentRoutingService;
    private final ActionTaskRepository actionTaskRepository;
    private final DomainEventDispatcher domainEventDispatcher;
    private final PropertyRepository propertyRepository;
    private final TenantProfileRepository tenantProfileRepository;

    @Value("${app.url:https://app.admindi.com}")
    private String appUrl;

    @Autowired
    public VacancyService(VacancyRepository vacancyRepository, UserRepository userRepository,
                          PropertyMovementService propertyMovementService,
                          ProviderAgentRoutingService providerAgentRoutingService,
                          ActionTaskRepository actionTaskRepository,
                          DomainEventDispatcher domainEventDispatcher,
                          PropertyRepository propertyRepository,
                          TenantProfileRepository tenantProfileRepository) {
        this.vacancyRepository = vacancyRepository;
        this.userRepository = userRepository;
        this.propertyMovementService = propertyMovementService;
        this.providerAgentRoutingService = providerAgentRoutingService;
        this.actionTaskRepository = actionTaskRepository;
        this.domainEventDispatcher = domainEventDispatcher;
        this.propertyRepository = propertyRepository;
        this.tenantProfileRepository = tenantProfileRepository;
    }

    private String resolveOwnerId() {
        return TenantContext.resolveOwnerId(userRepository);
    }

    /**
     * Cierra vacancias abiertas del inmueble al abrir un nuevo expediente (contrato ACTIVO).
     */
    @Transactional
    public void closeOpenVacanciesForPropertyOnNewExpediente(String ownerId, String propertyId, String actorUserId,
                                                             String actorRole, String tenantProfileId) {
        List<VacancyEntity> open = vacancyRepository.findByPropertyId(propertyId).stream()
                .filter(v -> v.getClosedAt() == null)
                .filter(v -> ownerId.equals(v.getOwnerId()))
                .toList();
        for (VacancyEntity v : open) {
            v.setClosedAt(LocalDateTime.now());
            v.setStatus("CLOSED");
            vacancyRepository.save(v);
            propertyMovementService.record(ownerId, propertyId, "VACANCY", v.getId(),
                    actorUserId, actorRole, PropertyMovementEventType.VACANCY_CLOSED,
                    "Vacancia cerrada",
                    "Nuevo expediente de arrendatario en el inmueble.",
                    LocalDateTime.now(),
                    "{\"vacancyId\":\"" + v.getId() + "\",\"tenantProfileId\":\"" + tenantProfileId + "\"}",
                    null);
            domainEventDispatcher.dispatch("VACANCY_CLOSED",
                    "Vacancia cerrada por nuevo expediente",
                    "propertyId=" + propertyId,
                    ownerId, null, null);
            for (ActionTaskEntity t : actionTaskRepository.findByResourceTypeAndResourceIdAndStatus(
                    "VACANCY", v.getId(), "OPEN")) {
                t.setStatus("CANCELLED");
                t.setDescription((t.getDescription() != null ? t.getDescription() + " " : "")
                        + "[Cerrado automáticamente: nuevo expediente.]");
                actionTaskRepository.save(t);
            }
        }
    }

    /**
     * Si el dueño eligió solo agentes PRIVADOS y no hay agente vinculado, bloquea cotización/comisión explícita.
     */
    public void assertPrivateRoutingAllowsCommercialStep(VacancyEntity vacancy) {
        UserEntity ownerRow = userRepository.findById(vacancy.getOwnerId()).orElseThrow();
        Boolean flag = ownerRow.getUsePlatformAgents();
        if (Boolean.FALSE.equals(flag) && (vacancy.getAssignedAgentId() == null || vacancy.getAssignedAgentId().isBlank())) {
            throw new RuntimeException(
                    "Configuración PRIVATE: no hay agente inmobiliario privado activo vinculado a su cuenta. "
                            + "Vincule un agente en Ajustes o cambie el modo de routing (PLATFORM/MIXED) antes de registrar cotización de comisión.");
        }
    }

    public Optional<VacancyEntity> findOpenVacancyForProperty(String propertyId) {
        return vacancyRepository.findFirstByPropertyIdAndClosedAtIsNullOrderByOpenedAtDesc(propertyId);
    }

    @Transactional
    public VacancyEntity openVacancyFromLeaseTermination(LeaseEntity lease) {
        PropertyEntity prop = lease.resolvePropertyEntity();
        if (prop == null) {
            return null;
        }
        String propertyId = prop.getId();
        String ownerId = lease.getOwnerId();
        long activeProfiles = tenantProfileRepository.countByOwnerIdAndPropertyIdAndArchivedAtIsNull(ownerId, propertyId);
        if (activeProfiles > 0) {
            return null;
        }
        Optional<VacancyEntity> existing = vacancyRepository.findFirstByPropertyIdAndClosedAtIsNullOrderByOpenedAtDesc(propertyId);
        if (existing.isPresent()) {
            return existing.get();
        }
        VacancyEntity saved = insertOpenVacancy(ownerId, propertyId, "Opened automatically when lease terminated.");
        propertyMovementService.record(ownerId, propertyId, "VACANCY", saved.getId(),
                null, "SYSTEM", PropertyMovementEventType.VACANCY_OPENED,
                "Vacancy opened", "Inmueble liberado.",
                LocalDateTime.now(),
                "{\"vacancyId\":\"" + saved.getId() + "\",\"leaseId\":\"" + lease.getId() + "\"}",
                null);
        routeCommercialAfterVacancy(saved, null, "SYSTEM");
        return vacancyRepository.findById(saved.getId()).orElse(saved);
    }

    /**
     * Vacancia tras archivo sin paso por terminateLease (p.ej. sin contrato ACTIVO previo).
     */
    @Transactional
    public VacancyEntity ensureOpenVacancyAfterPropertyReleased(String ownerId, String propertyId, String note) {
        Optional<VacancyEntity> existing = vacancyRepository.findFirstByPropertyIdAndClosedAtIsNullOrderByOpenedAtDesc(propertyId);
        if (existing.isPresent()) {
            return existing.get();
        }
        VacancyEntity saved = insertOpenVacancy(ownerId, propertyId, note != null ? note : "Opened after property released.");
        propertyMovementService.record(ownerId, propertyId, "VACANCY", saved.getId(),
                null, "SYSTEM", PropertyMovementEventType.VACANCY_OPENED,
                "Vacancy opened", note != null ? note : "Inmueble disponible.",
                LocalDateTime.now(), "{\"vacancyId\":\"" + saved.getId() + "\"}", null);
        routeCommercialAfterVacancy(saved, null, "SYSTEM");
        return vacancyRepository.findById(saved.getId()).orElse(saved);
    }

    @Transactional
    public VacancyEntity createVacancyManual(String propertyId) {
        String ownerId = resolveOwnerId();
        Optional<VacancyEntity> existing = vacancyRepository.findFirstByPropertyIdAndClosedAtIsNullOrderByOpenedAtDesc(propertyId);
        if (existing.isPresent()) {
            return existing.get();
        }
        VacancyEntity saved = insertOpenVacancy(ownerId, propertyId, null);
        UserEntity u = userRepository.findByLoginIdentifier(SecurityContextHolder.getContext().getAuthentication().getName()).orElse(null);
        String uid = u != null ? u.getId() : null;
        String role = u != null && u.getRole() != null ? u.getRole().name() : null;
        propertyMovementService.record(ownerId, propertyId, "VACANCY", saved.getId(),
                uid, role, PropertyMovementEventType.VACANCY_OPENED,
                "Vacancy registered", "Manual vacancy",
                LocalDateTime.now(), "{\"vacancyId\":\"" + saved.getId() + "\"}", null);
        routeCommercialAfterVacancy(saved, u, u != null ? u.getLoginUsername() : "SYSTEM");
        return vacancyRepository.findById(saved.getId()).orElse(saved);
    }

    private VacancyEntity insertOpenVacancy(String ownerId, String propertyId, String notes) {
        VacancyEntity v = new VacancyEntity();
        v.setOwnerId(ownerId);
        v.setPropertyId(propertyId);
        v.setStatus("OPEN");
        if (notes != null) {
            v.setNotes(notes);
        }
        return vacancyRepository.save(v);
    }

    private void routeCommercialAfterVacancy(VacancyEntity vacancy, UserEntity actorUser, String actorEmail) {
        Optional<String> agentId = providerAgentRoutingService.resolveRealEstateAgentId(vacancy.getOwnerId());
        String propLabel = propertyRepository.findById(vacancy.getPropertyId()).map(PropertyEntity::getName).orElse(vacancy.getPropertyId());
        String actorUid = actorUser != null ? actorUser.getId() : null;
        String actorRole = actorUser != null && actorUser.getRole() != null ? actorUser.getRole().name() : "SYSTEM";
        if (agentId.isPresent()) {
            vacancy.setAssignedAgentId(agentId.get());
            vacancy.setStatus("ASSIGNED");
            vacancyRepository.save(vacancy);
            UserEntity agent = userRepository.findById(agentId.get()).orElseThrow();
            propertyMovementService.record(vacancy.getOwnerId(), vacancy.getPropertyId(), "VACANCY", vacancy.getId(),
                    actorUid, actorRole, PropertyMovementEventType.VACANCY_AGENT_ASSIGNED,
                    "Agente inmobiliario asignado", agent.getName(),
                    LocalDateTime.now(), "{\"agentId\":\"" + agent.getId() + "\",\"vacancyId\":\"" + vacancy.getId() + "\"}", null);
            createActionTask(agent.getId(), vacancy.getOwnerId(), "VACANCY_COMMERCIAL_ASSIGNED",
                    "Vacancia comercial: " + propLabel,
                    "Inmueble en vacancia. Coordinar visitas y actividad comercial.",
                    "VACANCY", vacancy.getId());
            // Plantilla admindi_vacancy_agent_assigned_v1 (destinatario dueño):
            //   {{1}}dueño {{2}}agente {{3}}inmueble {{4}}URL portal dueño.
            // Nota legacy: el mensaje anterior se enviaba al agente, pero la plantilla
            // aprobada por Meta es para el dueño, así que corregimos el destinatario y
            // mantenemos el IN_APP al agente vía la ActionTask creada arriba.
            UserEntity owner = userRepository.findById(vacancy.getOwnerId()).orElse(null);
            Map<String, String> vars = new LinkedHashMap<>();
            vars.put("1", firstName(owner));
            vars.put("2", nullSafe(agent.getName()));
            vars.put("3", propLabel);
            vars.put("4", appUrl + "/dashboard?panel=owner&vacancy=" + vacancy.getId());
            domainEventDispatcher.dispatch("VACANCY_AGENT_ASSIGNED",
                    "Nueva vacancia con actividad comercial",
                    "Inmueble: " + propLabel + ". Agente: " + agent.getName(),
                    vacancy.getOwnerId(), actorEmail, List.of(vacancy.getOwnerId()), vars, null);
        } else {
            vacancy.setStatus("OPEN");
            vacancyRepository.save(vacancy);
            propertyMovementService.record(vacancy.getOwnerId(), vacancy.getPropertyId(), "VACANCY", vacancy.getId(),
                    actorUid, actorRole, PropertyMovementEventType.VACANCY_AGENT_ASSIGNMENT_PENDING,
                    "Sin agente inmobiliario disponible",
                    "Asigne un agente ligado a su cuenta o cambie el modo de routing (PRIVATE requiere agente privado activo).",
                    LocalDateTime.now(), "{\"vacancyId\":\"" + vacancy.getId() + "\"}", null);
            createActionTask(vacancy.getOwnerId(), vacancy.getOwnerId(), "VACANCY_AGENT_NEEDED",
                    "Asignar agente para vacancia",
                    "Hay una vacancia sin agente: " + propLabel,
                    "VACANCY", vacancy.getId());
            // Plantilla admindi_vacancy_agent_needed_v1 (destinatario dueño):
            //   {{1}}dueño {{2}}inmueble {{3}}URL portal dueño.
            UserEntity owner = userRepository.findById(vacancy.getOwnerId()).orElse(null);
            Map<String, String> vars = new LinkedHashMap<>();
            vars.put("1", firstName(owner));
            vars.put("2", propLabel);
            vars.put("3", appUrl + "/dashboard?panel=owner&tab=team");
            domainEventDispatcher.dispatch("VACANCY_AGENT_NEEDED",
                    "Vacancia sin agente comercial",
                    "Inmueble: " + propLabel,
                    vacancy.getOwnerId(), actorEmail, ownerAndAdminRecipientIds(vacancy.getOwnerId()), vars, null);
        }
    }

    public List<VacancyEntity> listForCurrentOrganization() {
        String orgId = resolveOwnerId();
        return vacancyRepository.findByOwnerId(orgId);
    }

    public List<VacancyEntity> listForProperty(String propertyId) {
        String orgId = resolveOwnerId();
        return vacancyRepository.findByPropertyId(propertyId).stream()
                .filter(v -> orgId.equals(v.getOwnerId()))
                .toList();
    }

    public List<VacancyEntity> listOpenAssignedToAgent(String agentUserId) {
        return vacancyRepository.findByAssignedAgentIdAndClosedAtIsNullOrderByOpenedAtDesc(agentUserId);
    }

    public List<VacancyEntity> listOpenVacanciesForCurrentAgentUser() {
        String email = SecurityContextHolder.getContext().getAuthentication().getName();
        UserEntity u = userRepository.findByLoginIdentifier(email).orElseThrow();
        return listOpenAssignedToAgent(u.getId());
    }

    private void createActionTask(String assigneeUserId, String ownerId, String eventType, String title,
                                  String description, String resourceType, String resourceId) {
        ActionTaskEntity task = new ActionTaskEntity();
        task.setId(UUID.randomUUID().toString());
        task.setUserId(assigneeUserId);
        task.setOwnerId(ownerId);
        task.setEventType(eventType);
        task.setTitle(title);
        task.setDescription(description);
        task.setResourceType(resourceType);
        task.setResourceId(resourceId);
        task.setStatus("OPEN");
        task.setCreatedAt(LocalDateTime.now());
        actionTaskRepository.save(task);
    }

    private String firstName(UserEntity u) {
        if (u == null || u.getName() == null || u.getName().isBlank()) return "";
        return u.getName().trim().split("\\s+")[0];
    }

    private String nullSafe(String s) { return s == null ? "" : s; }

    private List<String> ownerAndAdminRecipientIds(String ownerId) {
        List<String> out = new ArrayList<>();
        out.add(ownerId);
        userRepository.findByOwnerId(ownerId).stream()
                .filter(u -> u.getRole() == Role.PROPERTY_ADMIN && u.isActive())
                .map(UserEntity::getId)
                .distinct()
                .filter(id -> !out.contains(id))
                .forEach(out::add);
        return out;
    }
}
