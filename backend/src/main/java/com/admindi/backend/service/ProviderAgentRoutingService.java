package com.admindi.backend.service;

import com.admindi.backend.model.PlatformProviderAssignmentEntity;
import com.admindi.backend.model.Role;
import com.admindi.backend.model.UserEntity;
import com.admindi.backend.repository.PlatformProviderAssignmentRepository;
import com.admindi.backend.repository.UserRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Resolves maintenance provider and real estate agent from platform_provider_assignments
 * plus owner flags usePlatformMaintenance / usePlatformAgents.
 * <p>
 * assignment_source: PLATFORM (catalog) vs PRIVATE (POST /api/owner/linked-providers/private).
 * <p>
 * Rules: TRUE = PLATFORM only; FALSE = PRIVATE only; null = mixed.
 * Mixed mode tie-break (explicit, deterministic): among eligible rows for the required role,
 * prefer PRIVATE over PLATFORM; within each group, earliest assigned_at wins (nulls last).
 */
@Service
public class ProviderAgentRoutingService {

    private final PlatformProviderAssignmentRepository assignmentRepository;
    private final UserRepository userRepository;

    public ProviderAgentRoutingService(PlatformProviderAssignmentRepository assignmentRepository,
                                       UserRepository userRepository) {
        this.assignmentRepository = assignmentRepository;
        this.userRepository = userRepository;
    }

    public Optional<String> resolveMaintenanceProviderId(String organizationOwnerId) {
        UserEntity ownerRow = userRepository.findById(organizationOwnerId).orElse(null);
        Boolean flag = ownerRow != null ? ownerRow.getUsePlatformMaintenance() : null;
        return pickCandidate(organizationOwnerId, Role.MAINTENANCE_PROVIDER, flag);
    }

    public Optional<String> resolveRealEstateAgentId(String organizationOwnerId) {
        UserEntity ownerRow = userRepository.findById(organizationOwnerId).orElse(null);
        Boolean flag = ownerRow != null ? ownerRow.getUsePlatformAgents() : null;
        return pickCandidate(organizationOwnerId, Role.REAL_ESTATE_AGENT, flag);
    }

    /**
     * True if {@code providerUserId} is an active {@link Role#MAINTENANCE_PROVIDER} with an active
     * {@code platform_provider_assignments} row for this organization owner.
     * Used to validate SUPER_ADMIN overrides on maintenance quotes.
     */
    public boolean isLinkedMaintenanceProviderForOwner(String organizationOwnerId, String providerUserId) {
        for (PlatformProviderAssignmentEntity a : assignmentRepository.findByOwnerIdAndActiveTrue(organizationOwnerId)) {
            if (!providerUserId.equals(a.getProviderId())) {
                continue;
            }
            UserEntity candidate = userRepository.findById(a.getProviderId()).orElse(null);
            if (candidate != null && candidate.isActive() && candidate.getRole() == Role.MAINTENANCE_PROVIDER) {
                return true;
            }
        }
        return false;
    }

    private static String sourceOf(PlatformProviderAssignmentEntity a) {
        String s = a.getAssignmentSource();
        return (s == null || s.isBlank()) ? "PLATFORM" : s;
    }

    private static Comparator<PlatformProviderAssignmentEntity> mixedModeOrder() {
        return Comparator
                .comparing((PlatformProviderAssignmentEntity a) -> "PRIVATE".equals(sourceOf(a)) ? 0 : 1)
                .thenComparing(a -> a.getAssignedAt() != null ? a.getAssignedAt() : LocalDateTime.MAX);
    }

    private Optional<String> pickCandidate(String ownerId, Role requiredRole, Boolean usePlatformFlag) {
        List<PlatformProviderAssignmentEntity> links = new ArrayList<>(
                assignmentRepository.findByOwnerIdAndActiveTrue(ownerId));
        links.sort(mixedModeOrder());

        List<String> platformIds = new ArrayList<>();
        List<String> privateIds = new ArrayList<>();
        for (PlatformProviderAssignmentEntity a : links) {
            UserEntity u = userRepository.findById(a.getProviderId()).orElse(null);
            if (u == null || !u.isActive() || u.getRole() != requiredRole) {
                continue;
            }
            if ("PRIVATE".equals(sourceOf(a))) {
                privateIds.add(u.getId());
            } else {
                platformIds.add(u.getId());
            }
        }
        if (Boolean.TRUE.equals(usePlatformFlag)) {
            return platformIds.isEmpty() ? Optional.empty() : Optional.of(platformIds.get(0));
        }
        if (Boolean.FALSE.equals(usePlatformFlag)) {
            return privateIds.isEmpty() ? Optional.empty() : Optional.of(privateIds.get(0));
        }
        if (!privateIds.isEmpty()) {
            return Optional.of(privateIds.get(0));
        }
        if (!platformIds.isEmpty()) {
            return Optional.of(platformIds.get(0));
        }
        return Optional.empty();
    }
}