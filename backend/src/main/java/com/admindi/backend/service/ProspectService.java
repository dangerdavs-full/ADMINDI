package com.admindi.backend.service;

import com.admindi.backend.model.PropertyEntity;
import com.admindi.backend.model.ProspectSubmissionEntity;
import com.admindi.backend.model.Role;
import com.admindi.backend.model.UserEntity;
import com.admindi.backend.model.VacancyEntity;
import com.admindi.backend.repository.PropertyRepository;
import com.admindi.backend.repository.ProspectSubmissionRepository;
import com.admindi.backend.repository.UserRepository;
import com.admindi.backend.repository.VacancyRepository;
import com.admindi.backend.security.TenantContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Gestión de prospectos propuestos por el agente inmobiliario al dueño.
 *
 * <p>Un prospecto existe <strong>dentro</strong> de una vacancia; no puede haber dos
 * prospectos PENDING simultáneos en la misma vacancia — si el agente quiere proponer
 * otro, primero debe esperar la decisión del dueño o marcar el anterior como
 * {@code REJECTED} (solo el owner puede rechazar formalmente; el agente puede
 * cancelar el suyo si el prospecto desistió).
 */
@Service
public class ProspectService {

    private final ProspectSubmissionRepository prospectRepository;
    private final VacancyRepository vacancyRepository;
    private final UserRepository userRepository;
    private final PropertyRepository propertyRepository;
    private final VacancyAgentOrchestrationService vacancyOrchestrator;
    private final DomainEventDispatcher domainEventDispatcher;

    @Value("${app.url:https://app.admindi.com}")
    private String appUrl;

    public ProspectService(ProspectSubmissionRepository prospectRepository,
                           VacancyRepository vacancyRepository,
                           UserRepository userRepository,
                           PropertyRepository propertyRepository,
                           VacancyAgentOrchestrationService vacancyOrchestrator,
                           DomainEventDispatcher domainEventDispatcher) {
        this.prospectRepository = prospectRepository;
        this.vacancyRepository = vacancyRepository;
        this.userRepository = userRepository;
        this.propertyRepository = propertyRepository;
        this.vacancyOrchestrator = vacancyOrchestrator;
        this.domainEventDispatcher = domainEventDispatcher;
    }

    private UserEntity currentUser() {
        return userRepository.findByLoginIdentifier(SecurityContextHolder.getContext().getAuthentication().getName())
                .orElseThrow();
    }

    /** El agente propone un prospecto; la vacancia pasa a PROSPECT_PROPOSED. */
    @Transactional
    public ProspectSubmissionEntity submit(String vacancyId, String name, String phone, String email, String notes) {
        UserEntity agent = currentUser();
        if (agent.getRole() != Role.REAL_ESTATE_AGENT) {
            throw new SecurityException("Solo el agente inmobiliario puede proponer prospectos.");
        }
        VacancyEntity vacancy = vacancyRepository.findById(vacancyId).orElseThrow(() ->
                new IllegalArgumentException("Vacancia no existe: " + vacancyId));
        if (vacancy.getClosedAt() != null) {
            throw new IllegalStateException("Esta vacancia ya está cerrada.");
        }
        if (vacancy.getAssignedAgentId() == null || !vacancy.getAssignedAgentId().equals(agent.getId())) {
            throw new SecurityException("Esta vacancia no está asignada a ti.");
        }
        // Validación: no debe haber otro prospecto PENDING.
        prospectRepository.findFirstByVacancyIdAndOwnerDecisionOrderBySubmittedAtDesc(vacancyId,
                ProspectSubmissionEntity.DECISION_PENDING).ifPresent(p -> {
            throw new IllegalStateException("Ya propusiste un prospecto pendiente de decisión para esta vacancia.");
        });
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Nombre del prospecto obligatorio.");
        }

        ProspectSubmissionEntity p = new ProspectSubmissionEntity();
        p.setVacancyId(vacancyId);
        p.setPropertyId(vacancy.getPropertyId());
        p.setOwnerId(vacancy.getOwnerId());
        p.setAgentUserId(agent.getId());
        p.setProspectName(name.trim());
        p.setProspectPhone(phone);
        p.setProspectEmail(email);
        p.setNotes(notes);
        ProspectSubmissionEntity saved = prospectRepository.save(p);

        vacancyOrchestrator.transitionToProspectProposed(vacancy);

        String propLabel = propLabel(vacancy.getPropertyId());
        UserEntity owner = userRepository.findById(vacancy.getOwnerId()).orElse(null);
        // Plantilla admindi_prospect_proposed_v1: {{1}}dueño {{2}}agente {{3}}inmueble
        //   {{4}}prospecto {{5}}contacto {{6}}URL portal.
        Map<String, String> vars = new LinkedHashMap<>();
        vars.put("1", firstName(owner));
        vars.put("2", nullSafe(agent.getName()));
        vars.put("3", propLabel);
        vars.put("4", p.getProspectName());
        vars.put("5", prospectContact(p));
        vars.put("6", appUrl + "/dashboard?panel=owner&prospect=" + saved.getId());
        domainEventDispatcher.dispatch("PROSPECT_PROPOSED",
                "Nuevo prospecto: " + p.getProspectName(),
                "Tu agente " + agent.getName() + " propuso a " + p.getProspectName()
                        + " para " + propLabel + ". Acepta o rechaza en tu panel: "
                        + vars.get("6"),
                vacancy.getOwnerId(), agent.getUsername(), List.of(vacancy.getOwnerId()), vars, null);
        return saved;
    }

    /** El dueño acepta el prospecto: la vacancia pasa a AWAITING_CONTRACT. */
    @Transactional
    public ProspectSubmissionEntity ownerAccept(String prospectId) {
        UserEntity owner = currentUser();
        ProspectSubmissionEntity p = prospectRepository.findById(prospectId).orElseThrow();
        assertOwnerOf(p, owner);
        if (!ProspectSubmissionEntity.DECISION_PENDING.equals(p.getOwnerDecision())) {
            throw new IllegalStateException("Este prospecto ya fue decidido.");
        }
        p.setOwnerDecision(ProspectSubmissionEntity.DECISION_ACCEPTED);
        p.setDecidedAt(LocalDateTime.now());
        p.setDecidedBy(owner.getId());
        ProspectSubmissionEntity saved = prospectRepository.save(p);

        VacancyEntity vacancy = vacancyRepository.findById(p.getVacancyId()).orElseThrow();
        vacancyOrchestrator.transitionToAwaitingContract(vacancy);

        UserEntity agent = userRepository.findById(p.getAgentUserId()).orElse(null);
        String propLabel = propLabel(vacancy.getPropertyId());
        // Plantilla admindi_prospect_owner_accepted_v1: {{1}}agente {{2}}prospecto
        //   {{3}}inmueble {{4}}URL portal agente.
        Map<String, String> vars = new LinkedHashMap<>();
        vars.put("1", firstName(agent));
        vars.put("2", p.getProspectName());
        vars.put("3", propLabel);
        vars.put("4", appUrl + "/dashboard?vacancy=" + vacancy.getId());
        domainEventDispatcher.dispatch("PROSPECT_OWNER_ACCEPTED",
                "Prospecto aceptado — coordinar firma",
                "El dueño aceptó a " + p.getProspectName() + ". Coordina la firma del contrato y sube evidencia al cerrar: "
                        + vars.get("4"),
                p.getOwnerId(), owner.getUsername(), List.of(p.getAgentUserId()), vars, null);
        return saved;
    }

    /** El dueño rechaza el prospecto: la vacancia vuelve a PENDING_RENT. */
    @Transactional
    public ProspectSubmissionEntity ownerReject(String prospectId, String reason) {
        UserEntity owner = currentUser();
        ProspectSubmissionEntity p = prospectRepository.findById(prospectId).orElseThrow();
        assertOwnerOf(p, owner);
        if (!ProspectSubmissionEntity.DECISION_PENDING.equals(p.getOwnerDecision())) {
            throw new IllegalStateException("Este prospecto ya fue decidido.");
        }
        p.setOwnerDecision(ProspectSubmissionEntity.DECISION_REJECTED);
        p.setDecidedAt(LocalDateTime.now());
        p.setDecidedBy(owner.getId());
        p.setRejectionReason(reason);
        ProspectSubmissionEntity saved = prospectRepository.save(p);

        VacancyEntity vacancy = vacancyRepository.findById(p.getVacancyId()).orElseThrow();
        vacancyOrchestrator.rollbackToPendingRent(vacancy);

        UserEntity agent = userRepository.findById(p.getAgentUserId()).orElse(null);
        // Plantilla admindi_prospect_owner_rejected_v1: {{1}}agente {{2}}prospecto
        //   {{3}}motivo {{4}}URL portal agente.
        Map<String, String> vars = new LinkedHashMap<>();
        vars.put("1", firstName(agent));
        vars.put("2", p.getProspectName());
        vars.put("3", (reason != null && !reason.isBlank()) ? reason : "Sin motivo especificado");
        vars.put("4", appUrl + "/dashboard?vacancy=" + vacancy.getId());
        domainEventDispatcher.dispatch("PROSPECT_OWNER_REJECTED",
                "Prospecto rechazado",
                "El dueño rechazó a " + p.getProspectName()
                        + (reason != null && !reason.isBlank() ? " — Motivo: " + reason : "")
                        + ". Busca otro candidato: " + vars.get("4"),
                p.getOwnerId(), owner.getUsername(), List.of(p.getAgentUserId()), vars, null);
        return saved;
    }

    /** Bandeja del agente. */
    public List<ProspectSubmissionEntity> listMineAsAgent() {
        UserEntity u = currentUser();
        if (u.getRole() != Role.REAL_ESTATE_AGENT) {
            throw new SecurityException("Solo el agente accede a esta bandeja.");
        }
        return prospectRepository.findByAgentUserIdOrderBySubmittedAtDesc(u.getId());
    }

    /** Bandeja del owner — solo PENDING. */
    public List<ProspectSubmissionEntity> listPendingForOwner() {
        String oid = TenantContext.resolveOwnerId(userRepository);
        return prospectRepository.findByOwnerIdAndOwnerDecisionOrderBySubmittedAtDesc(oid,
                ProspectSubmissionEntity.DECISION_PENDING);
    }

    /** Scheduler: ejecuta los recordatorios 24h para prospectos PENDING. */
    @Transactional
    public int runPendingReminders() {
        LocalDateTime cutoff = LocalDateTime.now().minusHours(24);
        List<ProspectSubmissionEntity> candidates = prospectRepository
                .findByOwnerDecisionAndSubmittedAtBefore(ProspectSubmissionEntity.DECISION_PENDING, cutoff);
        int sent = 0;
        for (ProspectSubmissionEntity p : candidates) {
            if (p.getLastReminderAt() != null
                    && p.getLastReminderAt().isAfter(LocalDateTime.now().minusHours(24))) {
                continue;
            }
            UserEntity ownerUser = userRepository.findById(p.getOwnerId()).orElse(null);
            String propLabel = propLabel(p.getPropertyId());
            long days = Math.max(1, Duration.between(p.getSubmittedAt(), LocalDateTime.now()).toDays());
            // Plantilla admindi_prospect_reminder_v1: {{1}}dueño {{2}}prospecto
            //   {{3}}inmueble {{4}}tiempo {{5}}URL portal.
            Map<String, String> vars = new LinkedHashMap<>();
            vars.put("1", firstName(ownerUser));
            vars.put("2", p.getProspectName());
            vars.put("3", propLabel);
            vars.put("4", days == 1 ? "1 día" : days + " días");
            vars.put("5", appUrl + "/dashboard?panel=owner&prospect=" + p.getId());
            domainEventDispatcher.dispatch("PROSPECT_REMINDER",
                    "Recordatorio — prospecto pendiente de decisión",
                    "Tienes a " + p.getProspectName() + " esperando tu decisión hace " + vars.get("4")
                            + ". Entra a tu panel para aceptar o rechazar: " + vars.get("5"),
                    p.getOwnerId(), null, List.of(p.getOwnerId()), vars, null);
            p.setLastReminderAt(LocalDateTime.now());
            prospectRepository.save(p);
            sent++;
        }
        return sent;
    }

    // ─── Helpers de plantilla ─────────────────────────────────────────────────────

    private String firstName(UserEntity u) {
        if (u == null || u.getName() == null || u.getName().isBlank()) return "";
        return u.getName().trim().split("\\s+")[0];
    }

    private String nullSafe(String s) {
        return s == null ? "" : s;
    }

    private String propLabel(String propertyId) {
        return propertyRepository.findById(propertyId).map(PropertyEntity::getName).orElse(propertyId);
    }

    private String prospectContact(ProspectSubmissionEntity p) {
        StringBuilder sb = new StringBuilder();
        if (p.getProspectPhone() != null && !p.getProspectPhone().isBlank()) sb.append(p.getProspectPhone());
        if (p.getProspectEmail() != null && !p.getProspectEmail().isBlank()) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append(p.getProspectEmail());
        }
        return sb.length() > 0 ? sb.toString() : "Contacto no proporcionado";
    }

    private void assertOwnerOf(ProspectSubmissionEntity p, UserEntity owner) {
        if (owner.getRole() != Role.OWNER && owner.getRole() != Role.SUPER_ADMIN) {
            throw new SecurityException("Solo el dueño puede decidir sobre el prospecto.");
        }
        if (owner.getRole() == Role.OWNER && !p.getOwnerId().equals(owner.getId())) {
            throw new SecurityException("Este prospecto no es tuyo.");
        }
    }
}
