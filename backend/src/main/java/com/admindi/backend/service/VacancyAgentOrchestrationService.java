package com.admindi.backend.service;

import com.admindi.backend.model.AgentNotificationChainEntity;
import com.admindi.backend.model.LeaseEntity;
import com.admindi.backend.model.OwnerAgentPriorityEntity;
import com.admindi.backend.model.PropertyEntity;
import com.admindi.backend.model.PropertyStatus;
import com.admindi.backend.model.Role;
import com.admindi.backend.model.UserEntity;
import com.admindi.backend.model.VacancyEntity;
import com.admindi.backend.repository.LeaseRepository;
import com.admindi.backend.repository.PropertyRepository;
import com.admindi.backend.repository.UserRepository;
import com.admindi.backend.repository.VacancyRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Orquesta todo el ciclo de vida del flujo inmobiliario (REAL_ESTATE_AGENT) en la
 * Fase 2, apoyándose en {@link AgentChainOrchestrationService} para la cadena.
 *
 * <p>Sus responsabilidades son:
 * <ol>
 *   <li><strong>Abrir la vacancia</strong> cuando se libera un inmueble (integrado con
 *       {@code LeaseService.terminateLease} y borrado de último tenant). Arranca la
 *       cadena de notificaciones con los datos del inmueble y del dueño.</li>
 *   <li><strong>Recepción de la respuesta del agente</strong> (accept/reject/timeout)
 *       y avance automático al siguiente; cuando se agota, notifica al owner.</li>
 *   <li><strong>Registrar fotos</strong> cuando el agente visita y sube evidencia
 *       (inmueble pasa a PENDING_RENT).</li>
 *   <li><strong>Coordinar el prospecto</strong>: el agente propone, el dueño decide;
 *       transiciones PENDING_RENT → PROSPECT_PROPOSED → AWAITING_CONTRACT.</li>
 *   <li><strong>Cierre del contrato</strong>: el agente sube evidencia (PDF) y marca
 *       el trabajo como terminado. A partir de ahí el {@code ContractClosureService}
 *       crea el lease y la comisión (delegado).</li>
 * </ol>
 *
 * <p>Este servicio NO duplica los métodos de {@code VacancyService} (legacy); ambos
 * coexisten: el legacy es auto-assign inmediato al primer agente resuelto, el nuevo
 * hace cadena con prioridades + autorización. La integración se activa solo cuando
 * el owner tiene al menos una prioridad configurada para VACANCY; si no, el flujo
 * legado sigue vigente (retrocompatibilidad).
 */
@Service
public class VacancyAgentOrchestrationService {

    private static final Logger logger = LoggerFactory.getLogger(VacancyAgentOrchestrationService.class);

    private final VacancyRepository vacancyRepository;
    private final PropertyRepository propertyRepository;
    private final UserRepository userRepository;
    private final LeaseRepository leaseRepository;
    private final AgentChainOrchestrationService chainOrchestrator;
    private final DomainEventDispatcher domainEventDispatcher;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${app.url:https://app.admindi.com}")
    private String appUrl;

    /**
     * V51 — umbral de historial de renta que un inmueble debe acreditar ANTES de
     * ser difundido a la cadena de agentes inmobiliarios. Antes del umbral, el
     * dueño debe contactar agentes manualmente; esto previene spam masivo cada
     * vez que un dueño da de alta un inmueble nuevo.
     */
    public static final int MIN_RENTAL_HISTORY_DAYS = 30;

    public VacancyAgentOrchestrationService(VacancyRepository vacancyRepository,
                                            PropertyRepository propertyRepository,
                                            UserRepository userRepository,
                                            LeaseRepository leaseRepository,
                                            AgentChainOrchestrationService chainOrchestrator,
                                            DomainEventDispatcher domainEventDispatcher) {
        this.vacancyRepository = vacancyRepository;
        this.propertyRepository = propertyRepository;
        this.userRepository = userRepository;
        this.leaseRepository = leaseRepository;
        this.chainOrchestrator = chainOrchestrator;
        this.domainEventDispatcher = domainEventDispatcher;
    }

    /**
     * Extensión del flujo legacy: si la vacancia está OPEN y el owner tiene prioridades
     * configuradas, arrancamos la cadena Fase 2 (uno-a-uno con timeout).
     *
     * <p>Llamar a este método es idempotente: si la cadena ya está activa para la
     * vacancia, no se abre otra.
     */
    @Transactional
    public void startChainIfApplicable(VacancyEntity vacancy) {
        if (vacancy == null || vacancy.getClosedAt() != null) return;
        Optional<AgentNotificationChainEntity> existing = chainOrchestrator.findActiveLink(
                AgentNotificationChainEntity.RESOURCE_VACANCY, vacancy.getId());
        if (existing.isPresent()) return;

        // V51 — anti-spam: no difundimos a agentes inmobiliarios si el inmueble
        // nunca ha acreditado un inquilino real con al menos 1 mes de ocupación.
        // El dueño debe contactar agentes manualmente hasta tener historial; a
        // partir del primer lease con ≥30 días la cadena se habilita.
        if (!hasQualifyingRentalHistory(vacancy.getPropertyId(), vacancy.getOwnerId())) {
            logger.info("[VACANCY] Chain NOT started for property {}: no lease with >= {} days of history (V51 anti-spam guardrail).",
                    vacancy.getPropertyId(), MIN_RENTAL_HISTORY_DAYS);
            throw new IllegalStateException(
                    "NO_RENTAL_HISTORY — Este inmueble todavía no tiene historial de renta (mínimo "
                            + MIN_RENTAL_HISTORY_DAYS + " días con un inquilino). Para evitar saturar a los agentes con "
                            + "difusiones masivas, la primera colocación debe gestionarse manualmente. Una vez que el "
                            + "inmueble acumule un inquilino con al menos 1 mes de renta, podrás activar la difusión "
                            + "automática a tu cadena de agentes.");
        }

        Optional<AgentNotificationChainEntity> firstLinkOpt = chainOrchestrator.startChain(
                OwnerAgentPriorityEntity.FLOW_VACANCY,
                AgentNotificationChainEntity.RESOURCE_VACANCY,
                vacancy.getId(), vacancy.getOwnerId());
        if (firstLinkOpt.isEmpty()) return;

        AgentNotificationChainEntity firstLink = firstLinkOpt.get();
        vacancy.setChainState(VacancyEntity.CHAIN_AWAITING_AGENT);
        vacancy.setCurrentPriorityOrder(firstLink.getPriorityOrder());
        vacancy.setStatus("OPEN");
        vacancy.setAssignedAgentId(null);
        vacancyRepository.save(vacancy);

        setPropertyStatus(vacancy.getPropertyId(), PropertyStatus.AVAILABLE);
        notifyAgentOfVacancy(vacancy, firstLink);
    }

    /**
     * V51 — evalúa si un inmueble acredita historial suficiente de renta para ser
     * difundido automáticamente a la cadena de agentes.
     *
     * <p>Criterio: existe al menos un {@link LeaseEntity} asociado al inmueble
     * cuya ocupación efectiva (desde {@code startDate} hasta
     * {@code min(endDate, hoy)}) sea de al menos {@link #MIN_RENTAL_HISTORY_DAYS}
     * días. Aplica a leases activos, terminados o archivados.
     *
     * <p>Intencionalmente NO requiere status {@code COMPLETED}: un inquilino que
     * lleva ≥30 días vigente también califica como "historial real". Lo que se
     * intenta prevenir es que un inmueble recién dado de alta (sin ningún lease)
     * dispare invitaciones a todos los agentes configurados.
     *
     * @return {@code true} si el inmueble puede difundirse automáticamente.
     */
    public boolean hasQualifyingRentalHistory(String propertyId, String ownerId) {
        if (propertyId == null || ownerId == null) return false;
        List<LeaseEntity> leases = leaseRepository.findByOwnerIdAndProperty_Id(ownerId, propertyId);
        LocalDate today = LocalDate.now();
        for (LeaseEntity l : leases) {
            if (l.getStartDate() == null) continue;
            LocalDate effectiveEnd = (l.getEndDate() == null || l.getEndDate().isAfter(today))
                    ? today
                    : l.getEndDate();
            if (effectiveEnd.isBefore(l.getStartDate())) continue;
            long days = ChronoUnit.DAYS.between(l.getStartDate(), effectiveEnd);
            if (days >= MIN_RENTAL_HISTORY_DAYS) return true;
        }
        return false;
    }

    /**
     * Agente acepta la invitación: la vacancia pasa a AGENT_ACCEPTED, el resto de la
     * cadena queda SUPERSEDED y se espera que el agente suba fotos para avanzar a
     * PENDING_RENT.
     */
    @Transactional
    public VacancyEntity agentAccept(String vacancyId, String reason) {
        UserEntity agent = currentAgent();
        VacancyEntity vacancy = requireVacancy(vacancyId);
        AgentNotificationChainEntity link = requireActiveLinkForAgent(
                AgentNotificationChainEntity.RESOURCE_VACANCY, vacancyId, agent.getId());

        chainOrchestrator.markAccepted(link);
        vacancy.setAssignedAgentId(agent.getId());
        vacancy.setChainState(VacancyEntity.CHAIN_AGENT_ACCEPTED);
        vacancy.setStatus("ASSIGNED");
        if (reason != null && !reason.isBlank()) {
            vacancy.setNotes(appendNote(vacancy.getNotes(),
                    "Agente " + agent.getName() + " aceptó: " + reason));
        }
        return vacancyRepository.save(vacancy);
    }

    /**
     * Agente rechaza la invitación: se marca rechazado y se avanza al siguiente en la
     * cadena. Si se agota, se notifica al dueño.
     */
    @Transactional
    public VacancyEntity agentReject(String vacancyId, String reason) {
        UserEntity agent = currentAgent();
        VacancyEntity vacancy = requireVacancy(vacancyId);
        AgentNotificationChainEntity link = requireActiveLinkForAgent(
                AgentNotificationChainEntity.RESOURCE_VACANCY, vacancyId, agent.getId());

        chainOrchestrator.markRejected(link, reason);

        Optional<AgentNotificationChainEntity> next = chainOrchestrator.advanceChain(link,
                (resType, resId, ownerId) -> handleChainExhausted(vacancy),
                newLink -> {
                    vacancy.setCurrentPriorityOrder(newLink.getPriorityOrder());
                    vacancyRepository.save(vacancy);
                    notifyAgentOfVacancy(vacancy, newLink);
                });

        String propLabel = propLabel(vacancy.getPropertyId());
        UserEntity owner = userRepository.findById(vacancy.getOwnerId()).orElse(null);
        String nextStep = next
                .map(n -> "Pasamos al siguiente agente de tu lista.")
                .orElse("No hay más agentes en la cadena.");
        Map<String, String> vars = new LinkedHashMap<>();
        vars.put("1", firstName(owner));
        vars.put("2", nullSafe(agent.getName()));
        vars.put("3", propLabel);
        vars.put("4", nextStep);
        vars.put("5", ownerPortalUrl(vacancy));
        domainEventDispatcher.dispatch("VACANCY_AGENT_REJECTED",
                "Agente " + agent.getName() + " rechazó la vacancia",
                "Inmueble: " + propLabel + (reason != null ? " — Motivo: " + reason : "")
                        + " " + nextStep,
                vacancy.getOwnerId(), null, List.of(vacancy.getOwnerId()), vars, null);

        return vacancyRepository.findById(vacancy.getId()).orElse(vacancy);
    }

    /**
     * Callback desde el scheduler cuando un link PENDING expira; avanza la cadena.
     */
    @Transactional
    public void handleAutoTimeout(AgentNotificationChainEntity expiredLink) {
        VacancyEntity vacancy = vacancyRepository.findById(expiredLink.getResourceId()).orElse(null);
        if (vacancy == null) return;
        String propLabel = propLabel(vacancy.getPropertyId());
        UserEntity agent = userRepository.findById(expiredLink.getAgentUserId()).orElse(null);
        UserEntity owner = userRepository.findById(vacancy.getOwnerId()).orElse(null);

        Optional<AgentNotificationChainEntity> next = chainOrchestrator.advanceChain(expiredLink,
                (resType, resId, ownerId) -> handleChainExhausted(vacancy),
                newLink -> {
                    vacancy.setCurrentPriorityOrder(newLink.getPriorityOrder());
                    vacancyRepository.save(vacancy);
                    notifyAgentOfVacancy(vacancy, newLink);
                });

        String context = next.isPresent()
                ? "Pasamos al siguiente agente de la cadena."
                : "Ya no hay agentes disponibles en la lista.";

        // Aviso al dueño.
        Map<String, String> ownerVars = new LinkedHashMap<>();
        ownerVars.put("1", firstName(owner));
        ownerVars.put("2", propLabel);
        ownerVars.put("3", context);
        ownerVars.put("4", ownerPortalUrl(vacancy));
        domainEventDispatcher.dispatch("VACANCY_AGENT_TIMEOUT",
                "Agente no respondió en 72h",
                "Inmueble: " + propLabel + (agent != null ? " — Agente: " + agent.getName() : "")
                        + ". " + context,
                vacancy.getOwnerId(), null, List.of(vacancy.getOwnerId()), ownerVars, null);

        // Aviso al agente (cortesía para que sepa que venció su turno).
        if (agent != null) {
            Map<String, String> agentVars = new LinkedHashMap<>();
            agentVars.put("1", firstName(agent));
            agentVars.put("2", propLabel);
            agentVars.put("3", "Ya no puedes aceptar esta vacancia, tu turno expiró.");
            agentVars.put("4", agentPortalUrl());
            domainEventDispatcher.dispatch("VACANCY_AGENT_TIMEOUT",
                    "Tu turno en la vacancia expiró",
                    "Inmueble " + propLabel + ": ya no puedes aceptar esta vacancia, tu turno de 72h expiró.",
                    vacancy.getOwnerId(), null, List.of(agent.getId()), agentVars, null);
        }
    }

    /**
     * El agente sube fotos del inmueble (visita física). Marca la vacancia como
     * PHOTOS_UPLOADED y el inmueble como PENDING_RENT para que el dueño y otros
     * roles sepan que ya está en "vitrina".
     */
    @Transactional
    public VacancyEntity recordPhotosUploaded(String vacancyId, List<String> propertyFileIds) {
        UserEntity agent = currentAgent();
        VacancyEntity vacancy = requireVacancy(vacancyId);
        if (vacancy.getAssignedAgentId() == null || !vacancy.getAssignedAgentId().equals(agent.getId())) {
            throw new SecurityException("Esta vacancia no está asignada a ti.");
        }
        vacancy.setChainState(VacancyEntity.CHAIN_PHOTOS_UPLOADED);
        vacancy.setPhotosUploadedAt(LocalDateTime.now());
        vacancyRepository.save(vacancy);

        setPropertyStatus(vacancy.getPropertyId(), PropertyStatus.PENDING_RENT);

        String propLabel = propLabel(vacancy.getPropertyId());
        UserEntity owner = userRepository.findById(vacancy.getOwnerId()).orElse(null);
        int photoCount = propertyFileIds != null ? propertyFileIds.size() : 0;
        Map<String, String> vars = new LinkedHashMap<>();
        vars.put("1", firstName(owner));
        vars.put("2", nullSafe(agent.getName()));
        vars.put("3", String.valueOf(photoCount));
        vars.put("4", propLabel);
        vars.put("5", ownerPortalUrl(vacancy));
        domainEventDispatcher.dispatch("VACANCY_PHOTOS_UPLOADED",
                "Agente subió fotos del inmueble",
                "Inmueble " + propLabel + " está listo para vitrina (PENDIENTE DE RENTA). "
                        + photoCount + " fotos disponibles.",
                vacancy.getOwnerId(), agent.getUsername(), List.of(vacancy.getOwnerId()), vars, null);

        return vacancy;
    }

    /** Transición a PROSPECT_PROPOSED; llamado por {@code ProspectService.submit}. */
    @Transactional
    public void transitionToProspectProposed(VacancyEntity vacancy) {
        vacancy.setChainState(VacancyEntity.CHAIN_PROSPECT_PROPOSED);
        vacancyRepository.save(vacancy);
        setPropertyStatus(vacancy.getPropertyId(), PropertyStatus.PROSPECT_PROPOSED);
    }

    /** Owner rechazó prospecto; volvemos a PENDING_RENT. */
    @Transactional
    public void rollbackToPendingRent(VacancyEntity vacancy) {
        vacancy.setChainState(VacancyEntity.CHAIN_PHOTOS_UPLOADED);
        vacancyRepository.save(vacancy);
        setPropertyStatus(vacancy.getPropertyId(), PropertyStatus.PENDING_RENT);
    }

    /** Owner aceptó prospecto; pasamos a AWAITING_CONTRACT. */
    @Transactional
    public void transitionToAwaitingContract(VacancyEntity vacancy) {
        vacancy.setChainState(VacancyEntity.CHAIN_AWAITING_CONTRACT);
        vacancyRepository.save(vacancy);
        setPropertyStatus(vacancy.getPropertyId(), PropertyStatus.AWAITING_CONTRACT);
    }

    /** Llamado por ContractClosureService al firmar; vacancia pasa a CONTRACT_SIGNED. */
    @Transactional
    public void markContractSigned(VacancyEntity vacancy, String evidenceFileId,
                                   Integer months, java.math.BigDecimal monthlyRent,
                                   java.math.BigDecimal deposit) {
        vacancy.setChainState(VacancyEntity.CHAIN_CONTRACT_SIGNED);
        vacancy.setContractSignedAt(LocalDateTime.now());
        vacancy.setContractEvidenceFileId(evidenceFileId);
        vacancy.setContractMonths(months);
        vacancy.setContractMonthlyRent(monthlyRent);
        vacancy.setContractDeposit(deposit);
        vacancy.setClosedAt(LocalDateTime.now());
        vacancy.setStatus("CLOSED");
        vacancyRepository.save(vacancy);
        // PropertyStatus no cambia aquí — lo maneja LeaseService al crear el lease ACTIVE
        // (pasará a OCCUPIED por el flujo existente).
    }

    // ─── Helpers internos ─────────────────────────────────────────────────────────

    private void handleChainExhausted(VacancyEntity vacancy) {
        vacancy.setChainState(VacancyEntity.CHAIN_EXHAUSTED);
        vacancy.setAssignedAgentId(null);
        vacancyRepository.save(vacancy);
        String propLabel = propLabel(vacancy.getPropertyId());
        UserEntity owner = userRepository.findById(vacancy.getOwnerId()).orElse(null);
        Map<String, String> vars = new LinkedHashMap<>();
        vars.put("1", firstName(owner));
        vars.put("2", propLabel);
        vars.put("3", ownerPortalUrl(vacancy));
        domainEventDispatcher.dispatch("VACANCY_CHAIN_EXHAUSTED",
                "Ningún agente aceptó la vacancia",
                "Inmueble: " + propLabel + ". Ningún agente aceptó atender la vacancia. Revisa tu lista de agentes.",
                vacancy.getOwnerId(), null, List.of(vacancy.getOwnerId()), vars, null);
    }

    private void notifyAgentOfVacancy(VacancyEntity vacancy, AgentNotificationChainEntity link) {
        UserEntity agent = userRepository.findById(link.getAgentUserId()).orElse(null);
        PropertyEntity property = propertyRepository.findById(vacancy.getPropertyId()).orElse(null);
        String propLabel = property != null ? property.getName() : vacancy.getPropertyId();
        String address = property != null ? nullSafe(property.getAddress()) : "";
        String expectedRent = vacancy.getContractMonthlyRent() != null
                ? vacancy.getContractMonthlyRent().toPlainString()
                : "A negociar";

        // Contrato plantilla admindi_property_vacancy_opened_v1 (5 slots):
        //   {{1}} agente · {{2}} inmueble · {{3}} dirección · {{4}} renta · {{5}} URL portal.
        Map<String, String> templateVars = new LinkedHashMap<>();
        templateVars.put("1", firstName(agent));
        templateVars.put("2", propLabel);
        templateVars.put("3", address);
        templateVars.put("4", expectedRent);
        templateVars.put("5", agentPortalUrl() + "?vacancy=" + vacancy.getId());

        domainEventDispatcher.dispatch("PROPERTY_VACANCY_OPENED",
                "Nueva vacancia — tienes 72h para aceptar",
                "Inmueble: " + propLabel + " (" + address + "). Renta esperada: " + expectedRent
                        + ". Responde ACCEPT o REJECT desde tu panel: " + templateVars.get("5"),
                vacancy.getOwnerId(), null, List.of(link.getAgentUserId()), templateVars, null, true);
    }

    // ─── Helpers de plantilla ─────────────────────────────────────────────────────

    private String firstName(UserEntity u) {
        if (u == null || u.getName() == null || u.getName().isBlank()) return "";
        String[] parts = u.getName().trim().split("\\s+");
        return parts[0];
    }

    private String nullSafe(String s) {
        return s == null ? "" : s;
    }

    private String ownerPortalUrl(VacancyEntity vacancy) {
        return appUrl + "/dashboard?panel=owner&vacancy=" + vacancy.getId();
    }

    private String agentPortalUrl() {
        return appUrl + "/dashboard";
    }

    private void setPropertyStatus(String propertyId, PropertyStatus target) {
        PropertyEntity prop = propertyRepository.findById(propertyId).orElse(null);
        if (prop == null) return;
        if (prop.getStatus() == PropertyStatus.DELETED || prop.getStatus() == PropertyStatus.MAINTENANCE) {
            return;
        }
        if (prop.getStatus() != target) {
            prop.setStatus(target);
            propertyRepository.save(prop);
        }
    }

    private UserEntity currentAgent() {
        UserEntity u = userRepository.findByLoginIdentifier(
                SecurityContextHolder.getContext().getAuthentication().getName()).orElseThrow();
        if (u.getRole() != Role.REAL_ESTATE_AGENT) {
            throw new SecurityException("Acción reservada al agente inmobiliario.");
        }
        return u;
    }

    private VacancyEntity requireVacancy(String id) {
        return vacancyRepository.findById(id).orElseThrow(() ->
                new IllegalArgumentException("Vacancia no encontrada: " + id));
    }

    private AgentNotificationChainEntity requireActiveLinkForAgent(String resourceType, String resourceId,
                                                                    String agentUserId) {
        AgentNotificationChainEntity link = chainOrchestrator.findActiveLink(resourceType, resourceId)
                .orElseThrow(() -> new IllegalStateException("No hay cadena activa para este recurso."));
        if (!link.getAgentUserId().equals(agentUserId)) {
            throw new SecurityException("La invitación vigente no es para ti.");
        }
        return link;
    }

    private String propLabel(String propertyId) {
        return propertyRepository.findById(propertyId).map(PropertyEntity::getName).orElse(propertyId);
    }

    private String appendNote(String existing, String addition) {
        if (existing == null || existing.isBlank()) return addition;
        return existing + "\n" + addition;
    }

    /** Utilidad para serializar arrays de file IDs en columnas jsonb sin tener que
     *  inyectar Jackson en cada servicio que los use. */
    public String serializeFileIds(List<String> fileIds) {
        try {
            return objectMapper.writeValueAsString(fileIds);
        } catch (JsonProcessingException e) {
            logger.warn("No pude serializar fileIds {}", fileIds, e);
            return null;
        }
    }
}
