package com.admindi.backend.service;

import com.admindi.backend.model.AgentNotificationChainEntity;
import com.admindi.backend.model.OwnerAgentPriorityEntity;
import com.admindi.backend.model.PlatformProviderAssignmentEntity;
import com.admindi.backend.model.Role;
import com.admindi.backend.model.UserEntity;
import com.admindi.backend.repository.AgentNotificationChainRepository;
import com.admindi.backend.repository.OwnerAgentPriorityRepository;
import com.admindi.backend.repository.PlatformProviderAssignmentRepository;
import com.admindi.backend.repository.UserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Orquesta la cadena de notificaciones uno-a-uno a los agentes del dueño.
 *
 * <p>Usado tanto por el flujo de vacancia (REAL_ESTATE_AGENT) como por el de
 * mantenimiento (MAINTENANCE_PROVIDER). La estrategia es idéntica:
 * <ol>
 *   <li>Se arma la lista de agentes elegibles del dueño para el flujo
 *       ({@code MAINTENANCE} o {@code VACANCY}) leyendo {@code platform_provider_assignments}
 *       y sobreponiendo el orden de {@code owner_agent_priorities} (los que no tienen
 *       prioridad explícita se ordenan al final por fecha de asignación).</li>
 *   <li>Se notifica al primero con una fila {@code agent_notification_chain}
 *       {@code PENDING}. A partir de ahí:
 *     <ul>
 *       <li>Si acepta: el resto se marca {@code SUPERSEDED} y el recurso (ticket/vacancia)
 *           avanza a su siguiente estado.</li>
 *       <li>Si rechaza: se marca {@code REJECTED} y se abre el siguiente eslabón.</li>
 *       <li>Si vencen las 72h sin respuesta: un scheduler llama
 *           {@link #autoRejectExpired()} que marca {@code AUTO_REJECTED_TIMEOUT} y
 *           avanza igual que en el rechazo explícito.</li>
 *     </ul>
 *   </li>
 *   <li>Si se agotan los agentes sin aceptar, se invoca el callback
 *       {@link ChainExhaustedCallback} para que el servicio llamante notifique al
 *       dueño y cierre el recurso en un estado terminal.</li>
 * </ol>
 *
 * <p>El servicio NO decide qué hacer al aceptar/rechazar con el <strong>recurso</strong>
 * (ticket, vacancia). Expone hooks y devuelve info para que los servicios de mantenimiento
 * e inmobiliaria apliquen sus reglas de negocio — este orquestador solo gestiona la cadena.
 */
@Service
public class AgentChainOrchestrationService {

    private final OwnerAgentPriorityRepository priorityRepository;
    private final PlatformProviderAssignmentRepository assignmentRepository;
    private final AgentNotificationChainRepository chainRepository;
    private final UserRepository userRepository;

    @Value("${admindi.agents.chain-timeout-hours:72}")
    private int chainTimeoutHours;

    public AgentChainOrchestrationService(OwnerAgentPriorityRepository priorityRepository,
                                          PlatformProviderAssignmentRepository assignmentRepository,
                                          AgentNotificationChainRepository chainRepository,
                                          UserRepository userRepository) {
        this.priorityRepository = priorityRepository;
        this.assignmentRepository = assignmentRepository;
        this.chainRepository = chainRepository;
        this.userRepository = userRepository;
    }

    /** Callback invocado cuando se agota la cadena sin que nadie acepte. */
    @FunctionalInterface
    public interface ChainExhaustedCallback {
        void onExhausted(String resourceType, String resourceId, String ownerId);
    }

    /** Callback invocado cuando un agente rechaza/caduca y se abre el siguiente eslabón. */
    @FunctionalInterface
    public interface NextAgentCallback {
        void onNextAgent(AgentNotificationChainEntity newLink);
    }

    public record EligibleAgent(UserEntity user, int priorityOrder, String assignmentSource) {}

    /**
     * Resuelve la lista ordenada de agentes del owner para el flujo dado.
     *
     * <p>Fuente de datos:
     * <ul>
     *   <li>{@code platform_provider_assignments}: agentes vinculados (PLATFORM o PRIVATE)
     *       con el rol correcto y activos.</li>
     *   <li>{@code owner_agent_priorities}: orden preferido del dueño.</li>
     * </ul>
     *
     * <p>Política de orden cuando un agente NO tiene prioridad explícita: se sortea al
     * final por fecha de vinculación (más antiguos primero, para que no se cuelen nuevos
     * al frente de la cola sin decisión del dueño).
     */
    public List<EligibleAgent> resolveEligibleAgents(String ownerId, String flowType) {
        Role requiredRole = flowType.equals(OwnerAgentPriorityEntity.FLOW_MAINTENANCE)
                ? Role.MAINTENANCE_PROVIDER
                : Role.REAL_ESTATE_AGENT;

        // platform_provider_assignments tiene la columna provider_id (alias histórico del
        // user_id del provider). No almacena el rol — lo consultamos vía UserEntity.
        List<PlatformProviderAssignmentEntity> assignments = assignmentRepository.findByOwnerId(ownerId).stream()
                .filter(PlatformProviderAssignmentEntity::isActive)
                .toList();

        if (assignments.isEmpty()) {
            return List.of();
        }

        Map<String, OwnerAgentPriorityEntity> byAgent = new LinkedHashMap<>();
        for (OwnerAgentPriorityEntity p :
                priorityRepository.findByOwnerIdAndFlowTypeOrderByPriorityOrderAsc(ownerId, flowType)) {
            byAgent.put(p.getAgentUserId(), p);
        }

        List<EligibleAgent> out = new ArrayList<>();
        for (PlatformProviderAssignmentEntity a : assignments) {
            UserEntity u = userRepository.findById(a.getProviderId()).orElse(null);
            if (u == null || !u.isActive() || u.getRole() != requiredRole) continue;
            OwnerAgentPriorityEntity pri = byAgent.get(u.getId());
            int order = pri != null ? pri.getPriorityOrder() : Integer.MAX_VALUE;
            out.add(new EligibleAgent(u, order, a.getAssignmentSource()));
        }
        // Orden estable: priority_order asc; empates (MAX_VALUE) por nombre para ser deterministas.
        out.sort(Comparator.<EligibleAgent>comparingInt(EligibleAgent::priorityOrder)
                .thenComparing(e -> e.user().getName() == null ? "" : e.user().getName()));
        return out;
    }

    /**
     * Inicia la cadena para un recurso recién creado. Devuelve la primera fila abierta
     * (o {@code empty} si no hay agentes; el caller decide qué hacer — típicamente avisar
     * al dueño que configure agentes).
     */
    @Transactional
    public Optional<AgentNotificationChainEntity> startChain(String flowType, String resourceType,
                                                              String resourceId, String ownerId) {
        List<EligibleAgent> eligible = resolveEligibleAgents(ownerId, flowType);
        if (eligible.isEmpty()) {
            return Optional.empty();
        }
        EligibleAgent first = eligible.get(0);
        AgentNotificationChainEntity link = newLink(flowType, resourceType, resourceId, ownerId,
                first.user().getId(), 1);
        chainRepository.save(link);
        return Optional.of(link);
    }

    /**
     * Avanza la cadena al siguiente agente después de un rechazo o timeout. Si no hay más
     * candidatos, invoca {@code exhaustedCallback} y devuelve {@code empty}.
     */
    @Transactional
    public Optional<AgentNotificationChainEntity> advanceChain(AgentNotificationChainEntity rejectedLink,
                                                                ChainExhaustedCallback exhaustedCallback,
                                                                NextAgentCallback nextAgentCallback) {
        List<EligibleAgent> eligible = resolveEligibleAgents(rejectedLink.getOwnerId(), rejectedLink.getFlowType());
        // Agentes ya notificados en ciclos previos — no se les vuelve a molestar.
        List<String> alreadyNotified = chainRepository
                .findByResourceTypeAndResourceIdOrderByPriorityOrderAsc(
                        rejectedLink.getResourceType(), rejectedLink.getResourceId())
                .stream().map(AgentNotificationChainEntity::getAgentUserId).toList();

        for (EligibleAgent ea : eligible) {
            if (alreadyNotified.contains(ea.user().getId())) continue;
            int nextOrder = (rejectedLink.getPriorityOrder() != null ? rejectedLink.getPriorityOrder() : 0) + 1;
            AgentNotificationChainEntity next = newLink(rejectedLink.getFlowType(),
                    rejectedLink.getResourceType(), rejectedLink.getResourceId(),
                    rejectedLink.getOwnerId(), ea.user().getId(), nextOrder);
            chainRepository.save(next);
            if (nextAgentCallback != null) nextAgentCallback.onNextAgent(next);
            return Optional.of(next);
        }
        // Se agotó la cadena.
        if (exhaustedCallback != null) {
            exhaustedCallback.onExhausted(rejectedLink.getResourceType(), rejectedLink.getResourceId(),
                    rejectedLink.getOwnerId());
        }
        return Optional.empty();
    }

    /**
     * Marca la cadena como aceptada por el agente; el resto de las filas (si las hubiera)
     * pasan a {@code SUPERSEDED}. Devuelve la fila actualizada.
     */
    @Transactional
    public AgentNotificationChainEntity markAccepted(AgentNotificationChainEntity link) {
        link.setDecision(AgentNotificationChainEntity.DECISION_ACCEPTED);
        link.setRespondedAt(LocalDateTime.now());
        chainRepository.save(link);
        // Cualquier otra fila PENDING del mismo recurso queda SUPERSEDED (por si se notificó
        // a dos agentes en paralelo por error — el primero que acepta gana).
        for (AgentNotificationChainEntity other :
                chainRepository.findByResourceTypeAndResourceIdOrderByPriorityOrderAsc(
                        link.getResourceType(), link.getResourceId())) {
            if (!other.getId().equals(link.getId())
                    && AgentNotificationChainEntity.DECISION_PENDING.equals(other.getDecision())) {
                other.setDecision(AgentNotificationChainEntity.DECISION_SUPERSEDED);
                other.setRespondedAt(LocalDateTime.now());
                chainRepository.save(other);
            }
        }
        return link;
    }

    @Transactional
    public AgentNotificationChainEntity markRejected(AgentNotificationChainEntity link, String reason) {
        link.setDecision(AgentNotificationChainEntity.DECISION_REJECTED);
        link.setRespondedAt(LocalDateTime.now());
        link.setReason(reason);
        chainRepository.save(link);
        return link;
    }

    /**
     * Llamado por scheduler: marca todos los PENDING con expires_at < now como
     * AUTO_REJECTED_TIMEOUT y devuelve las filas afectadas para que el caller dispare la
     * notificación de timeout y abra el siguiente eslabón.
     */
    @Transactional
    public List<AgentNotificationChainEntity> autoRejectExpired() {
        List<AgentNotificationChainEntity> expired = chainRepository
                .findByDecisionAndExpiresAtBefore(AgentNotificationChainEntity.DECISION_PENDING,
                        LocalDateTime.now());
        List<AgentNotificationChainEntity> changed = new ArrayList<>();
        for (AgentNotificationChainEntity l : expired) {
            l.setDecision(AgentNotificationChainEntity.DECISION_AUTO_REJECTED);
            l.setRespondedAt(LocalDateTime.now());
            chainRepository.save(l);
            changed.add(l);
        }
        return changed;
    }

    /** Fila PENDING más reciente de este recurso, si existe. */
    public Optional<AgentNotificationChainEntity> findActiveLink(String resourceType, String resourceId) {
        return chainRepository.findFirstByResourceTypeAndResourceIdAndDecisionOrderByPriorityOrderDesc(
                resourceType, resourceId, AgentNotificationChainEntity.DECISION_PENDING);
    }

    private AgentNotificationChainEntity newLink(String flowType, String resourceType, String resourceId,
                                                  String ownerId, String agentUserId, int priorityOrder) {
        AgentNotificationChainEntity l = new AgentNotificationChainEntity();
        l.setFlowType(flowType);
        l.setResourceType(resourceType);
        l.setResourceId(resourceId);
        l.setOwnerId(ownerId);
        l.setAgentUserId(agentUserId);
        l.setPriorityOrder(priorityOrder);
        l.setNotifiedAt(LocalDateTime.now());
        l.setExpiresAt(LocalDateTime.now().plusHours(chainTimeoutHours));
        l.setDecision(AgentNotificationChainEntity.DECISION_PENDING);
        return l;
    }
}
