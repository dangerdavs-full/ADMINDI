package com.admindi.backend.service;

import com.admindi.backend.dto.OwnerAccountingSummaryDTO;
import com.admindi.backend.dto.OwnerAccountingSummaryDTO.ExpenseLineDTO;
import com.admindi.backend.dto.OwnerAccountingSummaryDTO.ReceivableLineDTO;
import com.admindi.backend.model.ExpenseEntity;
import com.admindi.backend.model.InvoiceEntity;
import com.admindi.backend.model.MaintenanceTicketEntity;
import com.admindi.backend.model.PaymentAgreementEntity;
import com.admindi.backend.model.PaymentAgreementStatus;
import com.admindi.backend.model.PropertyEntity;
import com.admindi.backend.model.TenantProfileEntity;
import com.admindi.backend.model.UserEntity;
import com.admindi.backend.model.VacancyEntity;
import com.admindi.backend.repository.ExpenseRepository;
import com.admindi.backend.repository.InvoiceRepository;
import com.admindi.backend.repository.LeaseRepository;
import com.admindi.backend.repository.MaintenanceTicketRepository;
import com.admindi.backend.repository.PaymentAgreementRepository;
import com.admindi.backend.repository.PropertyRepository;
import com.admindi.backend.repository.TenantProfileRepository;
import com.admindi.backend.repository.UserRepository;
import com.admindi.backend.repository.VacancyRepository;
import com.admindi.backend.security.TenantContext;
import com.admindi.backend.util.ExpenseReportingUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class OwnerAccountingSummaryService {

    private final InvoiceRepository invoiceRepository;
    private final ExpenseRepository expenseRepository;
    private final PaymentAgreementRepository agreementRepository;
    private final TenantProfileRepository tenantProfileRepository;
    private final UserRepository userRepository;
    private final MaintenanceTicketRepository maintenanceTicketRepository;
    private final VacancyRepository vacancyRepository;
    private final LeaseRepository leaseRepository;
    private final PropertyRepository propertyRepository;
    private final ReportingPeriodService reportingPeriodService;

    @Autowired
    public OwnerAccountingSummaryService(InvoiceRepository invoiceRepository,
                                         ExpenseRepository expenseRepository,
                                         PaymentAgreementRepository agreementRepository,
                                         TenantProfileRepository tenantProfileRepository,
                                         UserRepository userRepository,
                                         MaintenanceTicketRepository maintenanceTicketRepository,
                                         VacancyRepository vacancyRepository,
                                         LeaseRepository leaseRepository,
                                         PropertyRepository propertyRepository,
                                         ReportingPeriodService reportingPeriodService) {
        this.invoiceRepository = invoiceRepository;
        this.expenseRepository = expenseRepository;
        this.agreementRepository = agreementRepository;
        this.tenantProfileRepository = tenantProfileRepository;
        this.userRepository = userRepository;
        this.maintenanceTicketRepository = maintenanceTicketRepository;
        this.vacancyRepository = vacancyRepository;
        this.leaseRepository = leaseRepository;
        this.propertyRepository = propertyRepository;
        this.reportingPeriodService = reportingPeriodService;
    }

    private String resolveOwnerId() {
        return TenantContext.resolveOwnerId(userRepository);
    }

    public OwnerAccountingSummaryDTO buildSummary(String monthYear) {
        reportingPeriodService.validateOwnerMonthYear(monthYear);
        String ownerId = resolveOwnerId();
        YearMonth ym = YearMonth.parse(monthYear);
        OwnerAccountingSummaryDTO dto = new OwnerAccountingSummaryDTO();

        // Self-heal sobre DBs sucias: si existen facturas abiertas asociadas a inquilinos archivados
        // (legado de un cron anterior o de bajas previas al fix) o a inquilinos cuyo usuario fue
        // desactivado pero el profile quedó con archivedAt=NULL (estado inconsistente por crashes
        // previos al fix de reauth), las anulamos en línea y las excluimos del resumen.
        //
        // Regla de negocio: si el operador dio de baja a un arrendatario, ya no debe aparecer como
        // "renta esperada" ni como "moroso". Su historial PAID se preserva intacto; los meses que
        // no pagó quedan como VOID/CANCELLED — el snapshot inmutable (tenant_archive_snapshots) y
        // el ledger mes-a-mes ya registran "NO PAGÓ $0" como historia del expediente.
        //
        // Solo se toca status/settlement/outstanding; el historial PAID jamás se modifica.
        List<InvoiceEntity> ownerInvs = invoiceRepository.findByOwnerId(ownerId);
        java.util.Set<String> profileIds = ownerInvs.stream()
                .map(InvoiceEntity::getTenantProfileId)
                .filter(id -> id != null && !id.isBlank())
                .collect(Collectors.toSet());
        java.util.Map<String, TenantProfileEntity> profileCache = new java.util.HashMap<>();
        if (!profileIds.isEmpty()) {
            for (TenantProfileEntity tp : tenantProfileRepository.findAllById(profileIds)) {
                profileCache.put(tp.getId(), tp);
            }
        }
        java.util.Set<String> userIds = profileCache.values().stream()
                .map(TenantProfileEntity::getUserId)
                .filter(uid -> uid != null && !uid.isBlank())
                .collect(Collectors.toSet());
        java.util.Map<String, UserEntity> userCache = new java.util.HashMap<>();
        if (!userIds.isEmpty()) {
            for (UserEntity u : userRepository.findAllById(userIds)) {
                userCache.put(u.getId(), u);
            }
        }

        List<InvoiceEntity> invs = new java.util.ArrayList<>();
        for (InvoiceEntity i : ownerInvs) {
            if (!monthYear.equals(i.getMonthYear())) {
                continue;
            }
            TenantProfileEntity tp = i.getTenantProfileId() != null
                    ? profileCache.get(i.getTenantProfileId())
                    : null;
            boolean tenantArchived = tp != null && tp.getArchivedAt() != null;
            boolean tenantOrphan = i.getTenantProfileId() != null && tp == null;
            boolean tenantUserInactive = false;
            if (tp != null && tp.getUserId() != null) {
                UserEntity u = userCache.get(tp.getUserId());
                tenantUserInactive = (u == null) || !u.isActive();
            }
            boolean effectivelyArchived = tenantArchived || tenantOrphan || tenantUserInactive;
            if (effectivelyArchived && !isVoidStatus(i.getStatus()) && !"PAID".equalsIgnoreCase(i.getStatus())) {
                i.setStatus("VOID");
                i.setSettlementStatus("CANCELLED");
                i.setOutstandingAmount(BigDecimal.ZERO);
                invoiceRepository.save(i);
            }
            if (isVoidStatus(i.getStatus())) {
                continue;
            }
            if (effectivelyArchived) {
                // Invoice PAID de un perfil archivado/inactivo es historial legítimo; la dejamos
                // fuera del resumen mensual porque el panel muestra el "mes vigente" y el inquilino
                // ya salió, pero no la modificamos (se conserva tal cual en el histórico financiero).
                continue;
            }
            invs.add(i);
        }
        Set<String> invoiceIdsInMonth = invs.stream().map(InvoiceEntity::getId).collect(Collectors.toSet());

        BigDecimal expected = BigDecimal.ZERO;
        BigDecimal collected = BigDecimal.ZERO;
        BigDecimal outstanding = BigDecimal.ZERO;
        BigDecimal credits = BigDecimal.ZERO;
        BigDecimal lateFees = BigDecimal.ZERO;
        Set<String> delinquentTenants = new HashSet<>();

        for (InvoiceEntity inv : invs) {
            expected = expected.add(inv.getTotalAmount() != null ? inv.getTotalAmount() : BigDecimal.ZERO);
            collected = collected.add(inv.getPaidAmount() != null ? inv.getPaidAmount() : BigDecimal.ZERO);
            BigDecimal out = inv.getOutstandingAmount() != null ? inv.getOutstandingAmount() : BigDecimal.ZERO;
            outstanding = outstanding.add(out);
            credits = credits.add(inv.getCreditBalance() != null ? inv.getCreditBalance() : BigDecimal.ZERO);
            lateFees = lateFees.add(inv.getAppliedLateFee() != null ? inv.getAppliedLateFee() : BigDecimal.ZERO);
            if (out.compareTo(BigDecimal.ZERO) > 0 && ("LATE".equals(inv.getStatus()) || "PARTIALLY_PAID".equals(inv.getStatus()) || "PENDING".equals(inv.getStatus()))) {
                delinquentTenants.add(inv.getTenantProfileId());
            }
            if (out.compareTo(BigDecimal.ZERO) > 0) {
                dto.getReceivables().add(buildReceivableLine(inv, out));
            }
        }
        dto.setExpectedIncome(expected);
        dto.setCollectedIncome(collected);
        dto.setOutstandingIncome(outstanding);
        dto.setOverpaidCredits(credits);
        dto.setLateFeeAccrued(lateFees);
        dto.setDelinquentTenantsCount(delinquentTenants.size());

        List<PaymentAgreementEntity> ags = agreementRepository.findByOwnerId(ownerId);
        dto.setActiveAgreementsCount((int) ags.stream()
                .filter(a -> a.getStatus() == PaymentAgreementStatus.ACTIVE || a.getStatus() == PaymentAgreementStatus.APPROVED)
                .filter(a -> a.getInvoiceId() != null && invoiceIdsInMonth.contains(a.getInvoiceId()))
                .count());
        dto.setBreachedAgreementsCount((int) ags.stream()
                .filter(a -> a.getStatus() == PaymentAgreementStatus.BREACHED)
                .filter(a -> a.getInvoiceId() != null && invoiceIdsInMonth.contains(a.getInvoiceId()))
                .count());

        BigDecimal appr = BigDecimal.ZERO;
        BigDecimal paid = BigDecimal.ZERO;
        BigDecimal pend = BigDecimal.ZERO;
        BigDecimal platformSavings = BigDecimal.ZERO;
        for (ExpenseEntity e : expenseRepository.findByOwnerId(ownerId)) {
            if (!ExpenseReportingUtil.expenseTouchesReportingMonth(e, ym)) {
                continue;
            }
            // V64 — el egreso real del dueño es net_expense_amount (lo que
            // efectivamente sale de su cuenta). amount se conserva como bruto
            // pero la contabilidad debe reportar el neto; así los números ya
            // reflejan el ahorro del 15% de plataforma sin doble contar.
            BigDecimal bruto = e.getAmount() != null ? e.getAmount() : BigDecimal.ZERO;
            BigDecimal credit = e.getPlatformCreditAmount() != null
                    ? e.getPlatformCreditAmount() : BigDecimal.ZERO;
            BigDecimal net = e.getNetExpenseAmount() != null
                    ? e.getNetExpenseAmount()
                    : bruto.subtract(credit);
            if (net.signum() < 0) net = BigDecimal.ZERO;

            ExpenseLineDTO el = new ExpenseLineDTO();
            el.setId(e.getId());
            el.setPropertyId(e.getPropertyId());
            el.setAmount(net); // V64 — reportamos el NETO como "amount" al DTO
            el.setStatus(e.getStatus());
            el.setType(e.getType());
            el.setDescription(e.getDescription());
            el.setLinkedResourceType(e.getLinkedResourceType());
            el.setLinkedResourceId(e.getLinkedResourceId());
            propertyRepository.findById(e.getPropertyId()).ifPresent(p -> el.setPropertyName(p.getName()));
            dto.getExpenses().add(el);
            if ("APPROVED".equals(e.getStatus())) {
                appr = appr.add(net);
            }
            if ("PAID".equals(e.getStatus())) {
                paid = paid.add(net);
                platformSavings = platformSavings.add(credit);
            }
            if ("QUOTED".equals(e.getStatus())) {
                pend = pend.add(net);
            }
        }
        dto.setApprovedExpenses(appr);
        dto.setPaidExpenses(paid);
        dto.setPendingExpenses(pend);
        // V64 — exponemos el ahorro al dueño como línea independiente para
        // que el dashboard pueda comunicar el beneficio de usar la plataforma.
        dto.setPlatformSavings(platformSavings);

        Set<String> issueProps = new HashSet<>();
        for (MaintenanceTicketEntity t : maintenanceTicketRepository.findByOwnerId(ownerId)) {
            if ("OPEN".equals(t.getStatus()) || "QUOTED".equals(t.getStatus())) {
                issueProps.add(t.getPropertyId());
            }
        }
        for (VacancyEntity v : vacancyRepository.findByOwnerId(ownerId)) {
            if ("OPEN".equals(v.getStatus())) {
                issueProps.add(v.getPropertyId());
            }
        }
        dto.setPropertiesWithIssuesCount(issueProps.size());

        if (dto.getBreachedAgreementsCount() > 0) {
            dto.getAlerts().add("Hay convenios incumplidos en el periodo seleccionado.");
        }
        if (dto.getDelinquentTenantsCount() > 0) {
            dto.getAlerts().add("Saldos vencidos o pendientes en " + dto.getDelinquentTenantsCount() + " arrendatario(s).");
        }
        if (dto.getPropertiesWithIssuesCount() > 0) {
            dto.getAlerts().add("Inmuebles con mantenimiento o vacancia abierta: " + dto.getPropertiesWithIssuesCount());
        }
        return dto;
    }

    /** VOID/VOIDED/CANCELLED son estados terminales sin obligación de cobro ni ingreso — se excluyen. */
    private static boolean isVoidStatus(String status) {
        if (status == null) return false;
        String s = status.toUpperCase();
        return "VOID".equals(s) || "VOIDED".equals(s) || "CANCELLED".equals(s) || "CANCELED".equals(s);
    }

    private ReceivableLineDTO buildReceivableLine(InvoiceEntity inv, BigDecimal outstandingAmt) {
        ReceivableLineDTO line = new ReceivableLineDTO();
        line.setInvoiceId(inv.getId());
        line.setMonthYear(inv.getMonthYear());
        line.setExpectedRent(inv.getTotalAmount());
        line.setPaidAmount(inv.getPaidAmount() != null ? inv.getPaidAmount() : BigDecimal.ZERO);
        line.setOutstanding(outstandingAmt);
        line.setStatus(inv.getStatus());
        line.setSettlementStatus(inv.getSettlementStatus());
        line.setShortfallReason(inv.getShortfallReason());
        if (inv.getPromisedCompletionDate() != null) {
            line.setPromisedCompletionDate(inv.getPromisedCompletionDate().toString());
        }
        tenantProfileRepository.findById(inv.getTenantProfileId()).ifPresent(tp ->
                userRepository.findById(tp.getUserId()).ifPresent(u -> line.setTenantName(u.getName())));
        if (inv.getLeaseId() != null) {
            leaseRepository.findById(inv.getLeaseId())
                    .flatMap(lease -> Optional.ofNullable(lease.resolvePropertyEntity()))
                    .ifPresent(prop -> {
                        line.setPropertyId(prop.getId());
                        line.setPropertyName(prop.getName());
                    });
        }
        line.setAgreementSummaryStatus(summarizeAgreementStatus(inv.getId()));
        line.setBalanceDriver(resolveBalanceDriver(inv, outstandingAmt));
        return line;
    }

    private String summarizeAgreementStatus(String invoiceId) {
        return agreementRepository.findByInvoiceId(invoiceId).stream()
                .filter(a -> a.getStatus() == PaymentAgreementStatus.ACTIVE
                        || a.getStatus() == PaymentAgreementStatus.APPROVED
                        || a.getStatus() == PaymentAgreementStatus.REQUESTED
                        || a.getStatus() == PaymentAgreementStatus.BREACHED)
                .findFirst()
                .map(a -> a.getStatus().name())
                .orElse(null);
    }

    private String resolveBalanceDriver(InvoiceEntity inv, BigDecimal outstandingAmt) {
        if (outstandingAmt == null || outstandingAmt.compareTo(BigDecimal.ZERO) <= 0) {
            return "NONE";
        }
        boolean hasProtectingAgreement = agreementRepository.findByInvoiceId(inv.getId()).stream()
                .anyMatch(a -> a.getStatus() == PaymentAgreementStatus.ACTIVE
                        || a.getStatus() == PaymentAgreementStatus.APPROVED);
        boolean shortfallSignal = inv.getShortfallReason() != null && !inv.getShortfallReason().isBlank();
        boolean partial = "PARTIALLY_PAID".equals(inv.getSettlementStatus());
        if (hasProtectingAgreement && (shortfallSignal || partial)) {
            return "MIXED";
        }
        if (hasProtectingAgreement) {
            return "AGREEMENT_DEFERRAL";
        }
        if (shortfallSignal || partial) {
            return "PAYMENT_SHORTFALL";
        }
        return "PAYMENT_DELINQUENCY";
    }
}