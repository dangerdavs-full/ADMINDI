package com.admindi.backend.service;

import com.admindi.backend.dto.GlobalMetricsDTO;
import com.admindi.backend.dto.PropertyMetricsDTO;
import com.admindi.backend.model.InvoiceEntity;
import com.admindi.backend.model.LeaseEntity;
import com.admindi.backend.model.LeaseStatus;
import com.admindi.backend.model.PropertyEntity;
import com.admindi.backend.model.PropertyStatus;
import com.admindi.backend.model.TenantProfileEntity;
import com.admindi.backend.model.UserEntity;
import com.admindi.backend.repository.InvoiceRepository;
import com.admindi.backend.repository.LeaseRepository;
import com.admindi.backend.repository.PropertyRepository;
import com.admindi.backend.repository.TenantProfileRepository;
import com.admindi.backend.repository.UserRepository;
import com.admindi.backend.security.TenantContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class MetricsService {

    @Autowired private PropertyRepository propertyRepository;
    @Autowired private TenantProfileRepository profileRepository;
    @Autowired private InvoiceRepository invoiceRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private LeaseRepository leaseRepository;

    private String getOrgId() {
        return TenantContext.resolveOwnerId(userRepository);
    }

    public GlobalMetricsDTO getGlobalMetrics() {
        String orgId = getOrgId();
        List<PropertyEntity> props = propertyRepository.findByOwnerId(orgId).stream()
                .filter(p -> p.getStatus() != PropertyStatus.DELETED)
                .collect(Collectors.toList());

        // Un "arrendatario vigente" para métricas = expediente NO archivado Y usuario ACTIVO.
        // Si el usuario fue desactivado (borrado/dado de baja en Users) pero por datos sucios
        // el profile quedó con archivedAt=NULL, NO debe contarse como renta esperada ni como
        // moroso: su expediente vive solo como historial financiero (invoices PAID) y snapshot.
        // Esto mantiene el mismo criterio que TenantService.getMyTenants(), evitando que el
        // panel muestre 2 "inquilinos en adeudo" cuando la pantalla de inquilinos está vacía.
        java.util.List<TenantProfileEntity> allActiveProfiles = profileRepository.findByOwnerId(orgId).stream()
                .filter(t -> t.getArchivedAt() == null)
                .collect(Collectors.toList());
        java.util.Set<String> userIds = allActiveProfiles.stream()
                .map(TenantProfileEntity::getUserId)
                .filter(id -> id != null && !id.isBlank())
                .collect(Collectors.toSet());
        java.util.Map<String, UserEntity> userById = new java.util.HashMap<>();
        if (!userIds.isEmpty()) {
            for (UserEntity u : userRepository.findAllById(userIds)) {
                userById.put(u.getId(), u);
            }
        }
        List<TenantProfileEntity> tenants = allActiveProfiles.stream()
                .filter(t -> {
                    UserEntity u = userById.get(t.getUserId());
                    return u != null && u.isActive();
                })
                .collect(Collectors.toList());

        long totalProperties = props.size();

        // "Ocupadas" derivado del estado real, no del campo cacheado p.status (puede quedar stale
        // si un refresh falló): una propiedad se considera ocupada si tiene al menos un inquilino
        // activo (expediente sin archivar) o un contrato ACTIVE. Así, al borrar al último
        // arrendatario, el contador se actualiza inmediatamente en la próxima lectura.
        Set<String> occupiedPropertyIds = new HashSet<>();
        for (TenantProfileEntity t : tenants) {
            if (t.getPropertyId() != null && !t.getPropertyId().isBlank()) {
                occupiedPropertyIds.add(t.getPropertyId());
            }
        }
        for (LeaseEntity l : leaseRepository.findByOwnerId(orgId)) {
            if (l.getStatus() != LeaseStatus.ACTIVE) continue;
            PropertyEntity lp = l.resolvePropertyEntity();
            if (lp != null && lp.getId() != null) {
                occupiedPropertyIds.add(lp.getId());
            }
        }
        // Intersección con inmuebles visibles del owner (no contamos propiedades eliminadas).
        Set<String> visibleIds = props.stream().map(PropertyEntity::getId).collect(Collectors.toSet());
        occupiedPropertyIds.retainAll(visibleIds);
        long occupiedProps = occupiedPropertyIds.size();

        // ROI esperado = suma de rentas de expedientes vigentes (archivados excluidos).
        BigDecimal expectedIncome = tenants.stream()
                .map(TenantProfileEntity::getRentAmount)
                .filter(a -> a != null)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // Ingreso recaudado = invoices PAID en el mes actual (ignoramos VOID/VOIDED/CANCELLED).
        List<InvoiceEntity> orgInvoices = invoiceRepository.findByOwnerId(orgId);
        LocalDate now = LocalDate.now();
        BigDecimal collectedIncome = orgInvoices.stream()
                .filter(i -> "PAID".equalsIgnoreCase(i.getStatus()))
                .filter(i -> i.getIssueDate() != null
                        && i.getIssueDate().getYear() == now.getYear()
                        && i.getIssueDate().getMonthValue() == now.getMonthValue())
                .map(InvoiceEntity::getTotalAmount)
                .filter(a -> a != null)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // Morosidad solo contabiliza arrendatarios vigentes con al menos 1 invoice LATE no anulada.
        long delinquentTenants = tenants.stream()
                .filter(t -> orgInvoices.stream().anyMatch(i ->
                        i.getTenantProfileId() != null
                        && i.getTenantProfileId().equals(t.getId())
                        && "LATE".equalsIgnoreCase(i.getStatus())))
                .count();

        return new GlobalMetricsDTO(totalProperties, occupiedProps, expectedIncome, collectedIncome, delinquentTenants);
    }

    public PropertyMetricsDTO getPropertyMetrics(String propertyId) {
        String orgId = getOrgId();
        PropertyEntity prop = propertyRepository.findById(propertyId).orElseThrow(() -> new RuntimeException("Property Not Found"));
        if (!orgId.equals(prop.getOwnerId())) throw new RuntimeException("IDOR Prevented");

        PropertyMetricsDTO dto = new PropertyMetricsDTO();
        dto.setPropertyId(propertyId);
        dto.setStatus(prop.getStatus() != null ? prop.getStatus().name() : "Desconocido");

        // Buscar al perfil actual ligado a esta propiedad
        TenantProfileEntity currentAuth = profileRepository.findByOwnerId(orgId).stream()
                .filter(t -> propertyId.equals(t.getPropertyId()))
                .findFirst().orElse(null);

        if (currentAuth != null) {
            UserEntity u = userRepository.findById(currentAuth.getUserId()).orElse(null);
            dto.setCurrentTenantName(u != null ? u.getName() : "Inquilino Anonimo");
            dto.setMonthlyRent(currentAuth.getRentAmount());

            List<InvoiceEntity> tenantInvoices = invoiceRepository.findByTenantProfileId(currentAuth.getId());
            dto.setTotalInvoices(tenantInvoices.size());
            dto.setPaidInvoices(tenantInvoices.stream().filter(i -> "PAID".equals(i.getStatus())).count());
            dto.setLateInvoices(tenantInvoices.stream().filter(i -> "LATE".equals(i.getStatus())).count());

            BigDecimal sumPaid = tenantInvoices.stream()
                    .filter(i -> "PAID".equals(i.getStatus()))
                    .map(InvoiceEntity::getTotalAmount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            dto.setLifetimeCollected(sumPaid);
        } else {
            dto.setCurrentTenantName("Ninguno (Libre)");
            dto.setMonthlyRent(BigDecimal.ZERO);
            dto.setTotalInvoices(0);
            dto.setPaidInvoices(0);
            dto.setLateInvoices(0);
            dto.setLifetimeCollected(BigDecimal.ZERO);
        }
        
        return dto;
    }

    /**
     * Recibos / cobranza por inquilinos ligados al inmueble (solo dimensión financiera).
     * El historial operativo integral del inmueble es la línea de tiempo ({@code PropertyMovement} / API timeline).
     */
    public List<com.admindi.backend.dto.InvoiceHistoryDTO> listPropertyInvoiceFinancialHistory(String propertyId) {
        String orgId = getOrgId();
        PropertyEntity prop = propertyRepository.findById(propertyId).orElseThrow(() -> new RuntimeException("Property Not Found"));
        if (!orgId.equals(prop.getOwnerId())) throw new RuntimeException("IDOR Prevented");

        List<TenantProfileEntity> allPropTenants = profileRepository.findByOwnerId(orgId).stream()
                .filter(t -> propertyId.equals(t.getPropertyId()))
                .collect(Collectors.toList());

        List<String> tenantIds = allPropTenants.stream().map(TenantProfileEntity::getId).collect(Collectors.toList());
        List<InvoiceEntity> propertyInvoices = invoiceRepository.findByOwnerId(orgId).stream()
                .filter(i -> tenantIds.contains(i.getTenantProfileId()))
                .collect(Collectors.toList());

        return propertyInvoices.stream().map(entity -> {
            com.admindi.backend.dto.InvoiceHistoryDTO dto = new com.admindi.backend.dto.InvoiceHistoryDTO();
            dto.setInvoiceId(entity.getId());
            dto.setMonthYear(entity.getMonthYear());
            dto.setPaidDate(entity.getPaidDate());
            dto.setAmountCollected(entity.getTotalAmount());
            dto.setStatus(entity.getStatus());
            dto.setPaymentReference(entity.getPaymentReference());
            dto.setPaymentNotes(entity.getPaymentNotes());

            UserEntity user = userRepository.findById(
                allPropTenants.stream()
                        .filter(t -> t.getId().equals(entity.getTenantProfileId()))
                        .findFirst()
                        .orElseThrow().getUserId()
            ).orElse(null);
            
            dto.setTenantName(user != null ? user.getName() : "Inquilino Anonimo");
            return dto;
        })
        .sorted((a, b) -> a.getMonthYear().compareTo(b.getMonthYear()))
        .collect(Collectors.toList());
    }

    /** @deprecated Usar {@link #listPropertyInvoiceFinancialHistory(String)} — nombre explícito (solo cobranza). */
    @Deprecated
    public List<com.admindi.backend.dto.InvoiceHistoryDTO> getPropertyHistory(String propertyId) {
        return listPropertyInvoiceFinancialHistory(propertyId);
    }
}
