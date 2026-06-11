package com.admindi.backend.service;

import com.admindi.backend.model.OwnerAgentPriorityEntity;
import com.admindi.backend.model.Role;
import com.admindi.backend.model.UserEntity;
import com.admindi.backend.repository.OwnerAgentPriorityRepository;
import com.admindi.backend.repository.PlatformProviderAssignmentRepository;
import com.admindi.backend.repository.UserRepository;
import com.admindi.backend.security.TenantContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Gestiona el orden en el que un owner quiere que se notifique a sus agentes para un
 * flujo dado (MAINTENANCE o VACANCY).
 *
 * <p>Reglas:
 * <ul>
 *   <li>Solo el OWNER (o SUPER_ADMIN con contexto) puede editar sus prioridades.</li>
 *   <li>No se pueden incluir agentes que no estén vinculados al owner vía
 *       {@code platform_provider_assignments} ni cuyo rol no coincida con el flujo.</li>
 *   <li>El UI envía un array ordenado de agentUserIds; el servicio reemplaza
 *       atómicamente las filas: los que no vienen en el array pierden la prioridad
 *       explícita (irán al final de la cadena por defecto).</li>
 * </ul>
 */
@Service
public class OwnerAgentPriorityService {

    private final OwnerAgentPriorityRepository priorityRepository;
    private final PlatformProviderAssignmentRepository assignmentRepository;
    private final UserRepository userRepository;

    public OwnerAgentPriorityService(OwnerAgentPriorityRepository priorityRepository,
                                     PlatformProviderAssignmentRepository assignmentRepository,
                                     UserRepository userRepository) {
        this.priorityRepository = priorityRepository;
        this.assignmentRepository = assignmentRepository;
        this.userRepository = userRepository;
    }

    private String resolveOwnerId() {
        return TenantContext.resolveOwnerId(userRepository);
    }

    private UserEntity currentUser() {
        return userRepository.findByLoginIdentifier(SecurityContextHolder.getContext().getAuthentication().getName())
                .orElseThrow();
    }

    private void assertFlow(String flowType) {
        if (!OwnerAgentPriorityEntity.FLOW_MAINTENANCE.equals(flowType)
                && !OwnerAgentPriorityEntity.FLOW_VACANCY.equals(flowType)) {
            throw new IllegalArgumentException("flowType inválido: debe ser MAINTENANCE o VACANCY.");
        }
    }

    /** Lista ordenada de prioridades del owner actual. */
    public List<OwnerAgentPriorityEntity> listMine(String flowType) {
        assertFlow(flowType);
        String oid = resolveOwnerId();
        return priorityRepository.findByOwnerIdAndFlowTypeOrderByPriorityOrderAsc(oid, flowType);
    }

    /**
     * Reemplaza el orden de prioridades del owner actual para el flujo dado.
     *
     * <p>Valida que cada agentUserId:
     * <ul>
     *   <li>Exista como usuario activo.</li>
     *   <li>Esté vinculado al owner vía platform_provider_assignments.active=true.</li>
     *   <li>Tenga el rol correcto (MAINTENANCE_PROVIDER para MAINTENANCE,
     *       REAL_ESTATE_AGENT para VACANCY).</li>
     * </ul>
     *
     * <p>Los agentes que queden fuera del array pierden su prioridad explícita (pasan
     * al final de la cadena por defecto en {@link AgentChainOrchestrationService}).
     */
    @Transactional
    public List<OwnerAgentPriorityEntity> replaceOrdering(String flowType, List<String> orderedAgentIds) {
        assertFlow(flowType);
        UserEntity me = currentUser();
        String oid = resolveOwnerId();
        if (me.getRole() != Role.OWNER && me.getRole() != Role.SUPER_ADMIN) {
            throw new SecurityException("Solo el dueño puede configurar prioridades de agentes.");
        }
        if (orderedAgentIds == null) orderedAgentIds = List.of();

        // Validación atómica: todos los IDs deben ser agentes válidos del owner antes de tocar nada.
        Role requiredRole = OwnerAgentPriorityEntity.FLOW_MAINTENANCE.equals(flowType)
                ? Role.MAINTENANCE_PROVIDER
                : Role.REAL_ESTATE_AGENT;
        Set<String> linked = new HashSet<>();
        assignmentRepository.findByOwnerId(oid).forEach(a -> {
            if (a.isActive()) linked.add(a.getProviderId());
        });
        for (String agentId : orderedAgentIds) {
            if (!linked.contains(agentId)) {
                throw new IllegalArgumentException("Agente " + agentId
                        + " no está vinculado a tu cuenta. No puedes asignarle prioridad.");
            }
            UserEntity u = userRepository.findById(agentId).orElse(null);
            if (u == null || !u.isActive()) {
                throw new IllegalArgumentException("Agente " + agentId + " no existe o está inactivo.");
            }
            if (u.getRole() != requiredRole) {
                throw new IllegalArgumentException("Agente " + agentId + " no tiene el rol requerido ("
                        + requiredRole + ") para el flujo " + flowType + ".");
            }
        }
        // Dedupe preservando orden.
        Map<String, Integer> slot = new LinkedHashMap<>();
        int order = 1;
        for (String id : orderedAgentIds) {
            if (!slot.containsKey(id)) {
                slot.put(id, order++);
            }
        }

        // Reemplazo: borramos las filas del owner+flow y las recreamos.
        List<OwnerAgentPriorityEntity> existing =
                priorityRepository.findByOwnerIdAndFlowTypeOrderByPriorityOrderAsc(oid, flowType);
        priorityRepository.deleteAll(existing);

        List<OwnerAgentPriorityEntity> saved = new ArrayList<>();
        LocalDateTime now = LocalDateTime.now();
        for (Map.Entry<String, Integer> e : slot.entrySet()) {
            OwnerAgentPriorityEntity p = new OwnerAgentPriorityEntity();
            p.setOwnerId(oid);
            p.setFlowType(flowType);
            p.setAgentUserId(e.getKey());
            p.setPriorityOrder(e.getValue());
            p.setCreatedAt(now);
            p.setUpdatedAt(now);
            saved.add(priorityRepository.save(p));
        }
        return saved;
    }

    /** Ajuste fino para la UI de flechas: mueve un agente una posición arriba o abajo. */
    @Transactional
    public List<OwnerAgentPriorityEntity> move(String flowType, String agentUserId, int delta) {
        assertFlow(flowType);
        List<OwnerAgentPriorityEntity> current = listMine(flowType);
        // Asegurar que todos los agentes actualmente vinculados estén representados para que
        // mover a uno no pise al resto; los que no tengan fila explícita se agregan al final.
        List<String> order = new ArrayList<>(current.stream()
                .map(OwnerAgentPriorityEntity::getAgentUserId).toList());
        String oid = resolveOwnerId();
        Role requiredRole = OwnerAgentPriorityEntity.FLOW_MAINTENANCE.equals(flowType)
                ? Role.MAINTENANCE_PROVIDER
                : Role.REAL_ESTATE_AGENT;
        assignmentRepository.findByOwnerId(oid).stream()
                .filter(a -> a.isActive())
                .map(a -> userRepository.findById(a.getProviderId()).orElse(null))
                .filter(u -> u != null && u.isActive() && u.getRole() == requiredRole)
                .map(UserEntity::getId)
                .forEach(id -> { if (!order.contains(id)) order.add(id); });

        int idx = order.indexOf(agentUserId);
        if (idx < 0) {
            throw new IllegalArgumentException("Agente no está en tu lista de prioridades.");
        }
        int target = Math.max(0, Math.min(order.size() - 1, idx + delta));
        if (target == idx) {
            return listMine(flowType);
        }
        order.remove(idx);
        order.add(target, agentUserId);
        return replaceOrdering(flowType, order);
    }
}
