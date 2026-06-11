package com.admindi.backend.service;

import com.admindi.backend.dto.TenantExpedienteListItemDTO;
import com.admindi.backend.dto.TenantExpedienteSummaryDTO;
import com.admindi.backend.model.LeaseEntity;
import com.admindi.backend.model.LeaseStatus;
import com.admindi.backend.model.PropertyEntity;
import com.admindi.backend.model.TenantProfileEntity;
import com.admindi.backend.model.UserEntity;
import com.admindi.backend.repository.LeaseRepository;
import com.admindi.backend.repository.PropertyRepository;
import com.admindi.backend.repository.TenantProfileRepository;
import com.admindi.backend.repository.UserRepository;
import com.admindi.backend.security.TenantContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service
public class TenantExpedienteService {

    private final TenantProfileRepository tenantProfileRepository;
    private final PropertyRepository propertyRepository;
    private final LeaseRepository leaseRepository;
    private final UserRepository userRepository;

    @Autowired
    public TenantExpedienteService(TenantProfileRepository tenantProfileRepository,
                                   PropertyRepository propertyRepository,
                                   LeaseRepository leaseRepository,
                                   UserRepository userRepository) {
        this.tenantProfileRepository = tenantProfileRepository;
        this.propertyRepository = propertyRepository;
        this.leaseRepository = leaseRepository;
        this.userRepository = userRepository;
    }

    public List<TenantExpedienteListItemDTO> listExpedientesForActiveOwner() {
        UserEntity tenant = currentTenantUser();
        String ownerId = requireOwnerContext();
        return tenantProfileRepository.findByUserIdAndOwnerIdAndArchivedAtIsNull(tenant.getId(), ownerId).stream()
                .map(p -> toListItem(tenant.getId(), ownerId, p))
                .collect(Collectors.toList());
    }

    public TenantExpedienteSummaryDTO getSummary(String tenantProfileId) {
        UserEntity tenant = currentTenantUser();
        String ownerId = requireOwnerContext();
        TenantProfileEntity profile = tenantProfileRepository.findByIdAndUserId(tenantProfileId, tenant.getId())
                .orElseThrow(() -> new RuntimeException("Expediente no encontrado."));
        if (profile.getArchivedAt() != null) {
            throw new RuntimeException("Expediente archivado.");
        }
        if (!ownerId.equals(profile.getOwnerId())) {
            throw new RuntimeException("El expediente no pertenece al contexto activo.");
        }
        return toSummary(tenant, ownerId, profile);
    }

    /**
     * Mismo resumen que el portal del inquilino, para dueño/admin con contexto de organización activo.
     */
    public TenantExpedienteSummaryDTO getSummaryForOwnerOrganization(String tenantProfileId) {
        String ownerId = TenantContext.resolveOwnerId(userRepository);
        TenantProfileEntity profile = tenantProfileRepository.findById(tenantProfileId)
                .orElseThrow(() -> new RuntimeException("Expediente no encontrado."));
        if (!ownerId.equals(profile.getOwnerId())) {
            throw new RuntimeException("IDOR: expediente de otra organización.");
        }
        if (profile.getArchivedAt() != null) {
            throw new RuntimeException("Expediente archivado.");
        }
        UserEntity tenant = userRepository.findById(profile.getUserId()).orElseThrow();
        return toSummary(tenant, ownerId, profile);
    }

    private TenantExpedienteListItemDTO toListItem(String tenantUserId, String ownerId, TenantProfileEntity p) {
        TenantExpedienteListItemDTO dto = new TenantExpedienteListItemDTO();
        dto.setTenantProfileId(p.getId());
        dto.setPropertyId(p.getPropertyId());
        dto.setRentAmount(p.getRentAmount());
        dto.setPaymentDay(p.getPaymentDay());
        PropertyEntity prop = p.getPropertyId() != null
                ? propertyRepository.findById(p.getPropertyId()).orElse(null) : null;
        dto.setPropertyName(prop != null ? prop.getName() : null);
        dto.setPropertyAddress(prop != null ? prop.getAddress() : null);
        LeaseEntity lease = null;
        if (p.getPropertyId() != null) {
            lease = leaseRepository.findFirstByOwnerIdAndTenant_IdAndProperty_IdAndStatus(
                    ownerId, tenantUserId, p.getPropertyId(), LeaseStatus.ACTIVE).orElse(null);
        }
        if (lease != null) {
            dto.setLeaseId(lease.getId());
            dto.setLeaseStatus(lease.getStatus());
        }
        return dto;
    }

    private TenantExpedienteSummaryDTO toSummary(UserEntity tenant, String ownerId, TenantProfileEntity p) {
        TenantExpedienteSummaryDTO dto = new TenantExpedienteSummaryDTO();
        dto.setTenantProfileId(p.getId());
        dto.setTenantName(tenant.getName());
        dto.setTenantEmail(tenant.getContactEmail());
        dto.setTenantPhone(tenant.getPhone());
        UserEntity org = userRepository.findById(ownerId).orElse(null);
        dto.setOrganizationName(org != null ? org.getName() : ownerId);
        dto.setPropertyId(p.getPropertyId());
        dto.setRentAmount(p.getRentAmount());
        dto.setPaymentDay(p.getPaymentDay());
        PropertyEntity prop = p.getPropertyId() != null
                ? propertyRepository.findById(p.getPropertyId()).orElse(null) : null;
        dto.setPropertyName(prop != null ? prop.getName() : null);
        dto.setPropertyAddress(prop != null ? prop.getAddress() : null);
        LeaseEntity lease = null;
        if (p.getPropertyId() != null) {
            lease = leaseRepository.findFirstByOwnerIdAndTenant_IdAndProperty_IdAndStatus(
                    ownerId, tenant.getId(), p.getPropertyId(), LeaseStatus.ACTIVE).orElse(null);
        }
        if (lease != null) {
            dto.setLeaseId(lease.getId());
            dto.setLeaseStatus(lease.getStatus());
            dto.setLeaseStartDate(lease.getStartDate());
            dto.setLeaseEndDate(lease.getEndDate());
            dto.setLeaseDocumentUrl(lease.getDocumentUrl());
            dto.setLeaseDocumentFileName(lease.getDocumentFileName());
        }
        return dto;
    }

    private UserEntity currentTenantUser() {
        String email = SecurityContextHolder.getContext().getAuthentication().getName();
        UserEntity u = userRepository.findByLoginIdentifier(email).orElseThrow();
        return u;
    }

    private String requireOwnerContext() {
        String ownerId = TenantContext.getCurrentOwner();
        if (ownerId == null || ownerId.isBlank()) {
            throw new RuntimeException("Seleccione la organizacion (contexto) antes de continuar.");
        }
        return ownerId;
    }
}
