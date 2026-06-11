package com.admindi.backend.service;

import com.admindi.backend.dto.InvoiceDTO;
import com.admindi.backend.dto.PropertyDTO;
import com.admindi.backend.dto.PropertyExpedienteDTO;
import com.admindi.backend.dto.PropertyExpedienteDTO.ExpenseSummary;
import com.admindi.backend.dto.PropertyExpedienteDTO.LeaseSummary;
import com.admindi.backend.dto.PropertyExpedienteDTO.TenantProfileSummary;
import com.admindi.backend.dto.PropertyMovementDTO;
import com.admindi.backend.model.ExpenseEntity;
import com.admindi.backend.model.LeaseEntity;
import com.admindi.backend.model.LeaseStatus;
import com.admindi.backend.model.PropertyEntity;
import com.admindi.backend.model.TenantProfileEntity;
import com.admindi.backend.model.UserEntity;
import com.admindi.backend.repository.ExpenseRepository;
import com.admindi.backend.repository.LeaseRepository;
import com.admindi.backend.repository.TenantProfileRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 * V49 / Bloque 4: agregador de expediente. Consolida en una sola llamada todo
 * lo que el dueño y el administrador necesitan ver del inmueble:
 *
 * <ul>
 *   <li>Metadatos + estatus operativo.</li>
 *   <li>Resumen financiero: ingreso cobrado, egreso pagado, balance neto.</li>
 *   <li>Expedientes de inquilinos activos (con sus tenant_profile completo).</li>
 *   <li>Contratos (leases) históricos y vigentes.</li>
 *   <li>Facturas (incluye {@code leaseId} — ver Gap B).</li>
 *   <li>Egresos (con {@code budgetFileId} y {@code paymentProofFileId} — Gaps A/C).</li>
 *   <li>Timeline consolidado de movimientos.</li>
 * </ul>
 *
 * <p>Este service no hace acceso control: el controller aplica
 * {@code @PreAuthorize} + TenantContext vía PropertyService.getPropertyDetail
 * (IDOR gate). Aquí sólo orquestamos lecturas read-only. Ver
 * {@code docs/ARCHITECTURE_IDENTITY_AND_EXPEDIENTES.md §5}.</p>
 */
@Service
public class PropertyExpedienteService {

    private final PropertyService propertyService;
    private final TenantProfileRepository tenantProfileRepository;
    private final LeaseRepository leaseRepository;
    private final LedgerService ledgerService;
    private final ExpenseRepository expenseRepository;
    private final PropertyMovementService propertyMovementService;

    public PropertyExpedienteService(PropertyService propertyService,
                                     TenantProfileRepository tenantProfileRepository,
                                     LeaseRepository leaseRepository,
                                     LedgerService ledgerService,
                                     ExpenseRepository expenseRepository,
                                     PropertyMovementService propertyMovementService) {
        this.propertyService = propertyService;
        this.tenantProfileRepository = tenantProfileRepository;
        this.leaseRepository = leaseRepository;
        this.ledgerService = ledgerService;
        this.expenseRepository = expenseRepository;
        this.propertyMovementService = propertyMovementService;
    }

    /**
     * {@code @Transactional(readOnly=true)}: los leases tienen asociaciones LAZY
     * a propiedad y tenant user. Al mapear dentro del scope transaccional podemos
     * resolver sus ids sin lanzar {@code LazyInitializationException}, y devolvemos
     * sólo los scalars que el DTO expone (records {@code LeaseSummary} /
     * {@code TenantProfileSummary} / {@code ExpenseSummary}).
     */
    @Transactional(readOnly = true)
    public PropertyExpedienteDTO getExpediente(String propertyId) {
        PropertyDTO property = propertyService.getPropertyDetail(propertyId);
        String ownerId = property.getOwnerId();

        List<TenantProfileEntity> allProfiles = tenantProfileRepository
                .findByOwnerIdAndPropertyIdAndArchivedAtIsNull(ownerId, propertyId);
        List<LeaseEntity> leases = leaseRepository.findByOwnerIdAndProperty_Id(ownerId, propertyId);
        List<InvoiceDTO> invoices = ledgerService.getInvoicesForProperty(propertyId);
        List<ExpenseEntity> expenses = expenseRepository.findByPropertyId(propertyId);
        List<PropertyMovementDTO> timeline = propertyMovementService.getTimelineForProperty(propertyId);

        List<TenantProfileSummary> profileSummaries = allProfiles.stream()
                .map(this::toTenantProfileSummary)
                .collect(Collectors.toList());
        List<LeaseSummary> leaseSummaries = leases.stream()
                .map(this::toLeaseSummary)
                .collect(Collectors.toList());
        List<ExpenseSummary> expenseSummaries = expenses.stream()
                .map(this::toExpenseSummary)
                .collect(Collectors.toList());

        PropertyExpedienteDTO dto = new PropertyExpedienteDTO();
        dto.setPropertyId(propertyId);
        dto.setOwnerId(ownerId);
        dto.setName(property.getName());
        dto.setStatus(property.getStatus() != null ? property.getStatus().name() : null);
        dto.setAddressLine(property.getAddress());
        dto.setGeneratedAt(LocalDateTime.now());
        dto.setActiveTenantProfiles(profileSummaries);
        dto.setLeases(leaseSummaries);
        dto.setInvoices(invoices);
        dto.setExpenses(expenseSummaries);
        dto.setTimeline(timeline);

        BigDecimal income = invoices.stream()
                .filter(i -> "PAID".equalsIgnoreCase(i.getStatus()))
                .map(InvoiceDTO::getPaidAmount)
                .filter(a -> a != null)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal spent = expenses.stream()
                .filter(e -> "PAID".equalsIgnoreCase(e.getStatus()))
                .map(ExpenseEntity::getPaidAmount)
                .filter(a -> a != null)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        dto.setTotalIncomeCollected(income);
        dto.setTotalExpensesPaid(spent);
        dto.setNetBalance(income.subtract(spent));
        dto.setOpenInvoices((int) invoices.stream()
                .filter(i -> !"PAID".equalsIgnoreCase(i.getStatus())
                          && !"VOID".equalsIgnoreCase(i.getStatus()))
                .count());
        dto.setPaidInvoices((int) invoices.stream()
                .filter(i -> "PAID".equalsIgnoreCase(i.getStatus()))
                .count());
        dto.setActiveTenants(allProfiles.size());
        dto.setArchivedTenants(0);
        return dto;
    }

    private TenantProfileSummary toTenantProfileSummary(TenantProfileEntity t) {
        return new TenantProfileSummary(
                t.getId(),
                t.getUserId(),
                t.getPropertyId(),
                t.getRentAmount(),
                t.getPaymentDay(),
                t.getArchivedAt());
    }

    private LeaseSummary toLeaseSummary(LeaseEntity l) {
        // property y tenant son @ManyToOne(LAZY): dentro de @Transactional
        // acceder a getId() no dispara el proxy completo, sólo resuelve el fk.
        PropertyEntity p = l.getProperty();
        UserEntity t = l.getTenant();
        LeaseStatus status = l.getStatus();
        return new LeaseSummary(
                l.getId(),
                t != null ? t.getId() : null,
                p != null ? p.getId() : null,
                status != null ? status.name() : null,
                l.getStartDate(),
                l.getEndDate(),
                l.getMonthlyRent(),
                l.getDepositAmount(),
                l.getPaymentDay(),
                l.getDocumentUrl());
    }

    private ExpenseSummary toExpenseSummary(ExpenseEntity e) {
        return new ExpenseSummary(
                e.getId(),
                e.getType(),
                e.getDescription(),
                e.getStatus(),
                e.getPaymentSettlementStatus(),
                e.getApprovedAmount(),
                e.getPaidAmount(),
                e.getOutstandingAmount(),
                e.getCreatedAt(),
                e.getPaidAt(),
                e.getBudgetFileId(),
                e.getPaymentProofFileId(),
                e.getPropertyId(),
                e.getLinkedResourceType(),
                e.getLinkedResourceId());
    }
}
