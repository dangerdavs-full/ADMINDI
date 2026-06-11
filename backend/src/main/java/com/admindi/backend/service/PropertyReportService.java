package com.admindi.backend.service;

import com.admindi.backend.dto.InvoiceDTO;
import com.admindi.backend.dto.PropertyAnnualReportDTO;
import com.admindi.backend.dto.PropertyMonthlyReportDTO;
import com.admindi.backend.model.CommercialActivityEntity;
import com.admindi.backend.model.ExpenseEntity;
import com.admindi.backend.model.MaintenanceTicketEntity;
import com.admindi.backend.model.PaymentAgreementEntity;
import com.admindi.backend.model.PaymentAgreementStatus;
import com.admindi.backend.model.PropertyFileEntity;
import com.admindi.backend.model.VacancyEntity;
import com.admindi.backend.repository.CommercialActivityRepository;
import com.admindi.backend.repository.ExpenseRepository;
import com.admindi.backend.repository.MaintenanceTicketRepository;
import com.admindi.backend.repository.PaymentAgreementRepository;
import com.admindi.backend.repository.PropertyFileRepository;
import com.admindi.backend.repository.UserRepository;
import com.admindi.backend.security.TenantContext;
import com.admindi.backend.repository.VacancyRepository;
import com.admindi.backend.util.ExpenseReportingUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class PropertyReportService {

    private final LedgerService ledgerService;
    private final ExpenseRepository expenseRepository;
    private final MaintenanceTicketRepository maintenanceTicketRepository;
    private final PaymentAgreementRepository agreementRepository;
    private final VacancyRepository vacancyRepository;
    private final CommercialActivityRepository commercialActivityRepository;
    private final PropertyFileRepository propertyFileRepository;
    private final UserRepository userRepository;
    private final ReportingPeriodService reportingPeriodService;

    private static final DateTimeFormatter YM_FMT = DateTimeFormatter.ofPattern("yyyy-MM");

    @Autowired
    public PropertyReportService(LedgerService ledgerService,
                                 ExpenseRepository expenseRepository,
                                 MaintenanceTicketRepository maintenanceTicketRepository,
                                 PaymentAgreementRepository agreementRepository,
                                 VacancyRepository vacancyRepository,
                                 CommercialActivityRepository commercialActivityRepository,
                                 PropertyFileRepository propertyFileRepository,
                                 UserRepository userRepository,
                                 ReportingPeriodService reportingPeriodService) {
        this.ledgerService = ledgerService;
        this.expenseRepository = expenseRepository;
        this.maintenanceTicketRepository = maintenanceTicketRepository;
        this.agreementRepository = agreementRepository;
        this.vacancyRepository = vacancyRepository;
        this.commercialActivityRepository = commercialActivityRepository;
        this.propertyFileRepository = propertyFileRepository;
        this.userRepository = userRepository;
        this.reportingPeriodService = reportingPeriodService;
    }

    private void assertPropertyAccess(String propertyId) {
        ledgerService.getInvoicesForProperty(propertyId);
    }

    public PropertyMonthlyReportDTO monthly(String propertyId, String monthYear) {
        reportingPeriodService.validatePropertyMonthYear(propertyId, monthYear);
        assertPropertyAccess(propertyId);
        return buildMonthlyReport(propertyId, monthYear);
    }

    private PropertyMonthlyReportDTO buildMonthlyReport(String propertyId, String monthYear) {
        PropertyMonthlyReportDTO r = new PropertyMonthlyReportDTO();
        r.setPropertyId(propertyId);
        r.setMonthYear(monthYear);
        YearMonth ym = YearMonth.parse(monthYear);
        LocalDateTime start = ym.atDay(1).atStartOfDay();
        LocalDateTime end = ym.plusMonths(1).atDay(1).atStartOfDay();

        List<InvoiceDTO> invs = ledgerService.getInvoicesForProperty(propertyId).stream()
                .filter(i -> monthYear.equals(i.getMonthYear()))
                .collect(Collectors.toList());
        for (InvoiceDTO inv : invs) {
            r.setExpectedRent(r.getExpectedRent().add(inv.getTotalAmount() != null ? inv.getTotalAmount() : BigDecimal.ZERO));
            r.setCollected(r.getCollected().add(inv.getPaidAmount() != null ? inv.getPaidAmount() : BigDecimal.ZERO));
            r.setOutstanding(r.getOutstanding().add(inv.getOutstandingAmount() != null ? inv.getOutstandingAmount() : BigDecimal.ZERO));
            r.setCreditBalance(r.getCreditBalance().add(inv.getCreditBalance() != null ? inv.getCreditBalance() : BigDecimal.ZERO));
            if ("PARTIALLY_PAID".equals(inv.getSettlementStatus())) {
                r.setPartialPaymentsCount(r.getPartialPaymentsCount() + 1);
            }
        }
        String ownerId = TenantContext.resolveOwnerId(userRepository);
        List<PaymentAgreementEntity> ags = agreementRepository.findByOwnerId(ownerId);
        for (PaymentAgreementEntity a : ags) {
            if (a.getInvoiceId() == null) continue;
            InvoiceDTO inv = invs.stream().filter(i -> i.getId().equals(a.getInvoiceId())).findFirst().orElse(null);
            if (inv == null) continue;
            if (a.getStatus() == PaymentAgreementStatus.ACTIVE) r.setActiveAgreements(r.getActiveAgreements() + 1);
            if (a.getStatus() == PaymentAgreementStatus.BREACHED) r.setBreachedAgreements(r.getBreachedAgreements() + 1);
            if (a.getDeferredAmount() != null) {
                r.setDeferredAmount(r.getDeferredAmount().add(a.getDeferredAmount()));
            }
        }
        for (MaintenanceTicketEntity t : maintenanceTicketRepository.findByPropertyId(propertyId)) {
            if (t.getCreatedAt() != null && !t.getCreatedAt().isBefore(start) && t.getCreatedAt().isBefore(end)) {
                r.setMaintenanceTickets(r.getMaintenanceTickets() + 1);
            }
        }
        for (ExpenseEntity e : expenseRepository.findByPropertyId(propertyId)) {
            if ("MAINTENANCE".equals(e.getType()) && ExpenseReportingUtil.expenseTouchesReportingMonth(e, ym)) {
                r.setMaintenanceCost(r.getMaintenanceCost().add(e.getAmount() != null ? e.getAmount() : BigDecimal.ZERO));
            }
        }
        for (VacancyEntity v : vacancyRepository.findByPropertyId(propertyId)) {
            for (CommercialActivityEntity c : commercialActivityRepository.findByVacancyId(v.getId())) {
                if (c.getCreatedAt() != null && !c.getCreatedAt().isBefore(start) && c.getCreatedAt().isBefore(end)) {
                    r.setCommercialActivities(r.getCommercialActivities() + 1);
                }
            }
        }
        for (PropertyFileEntity f : propertyFileRepository.findByPropertyId(propertyId)) {
            if (f.getUploadedAt() != null && !f.getUploadedAt().isBefore(start) && f.getUploadedAt().isBefore(end)) {
                r.setNewFilesCount(r.getNewFilesCount() + 1);
            }
        }
        if (r.getBreachedAgreements() > 0) r.getAlerts().add("Breached agreements linked to this property period.");
        if (r.getOutstanding().compareTo(BigDecimal.ZERO) > 0) r.getAlerts().add("Outstanding rent for period.");
        return r;
    }

    public PropertyAnnualReportDTO annual(String propertyId, int year) {
        reportingPeriodService.validatePropertyYear(propertyId, year);
        assertPropertyAccess(propertyId);
        YearMonth minYm = reportingPeriodService.getPropertyMinYearMonth(propertyId);
        YearMonth maxYm = reportingPeriodService.getMaxYearMonth();
        PropertyAnnualReportDTO a = new PropertyAnnualReportDTO();
        a.setPropertyId(propertyId);
        a.setYear(year);
        for (int m = 1; m <= 12; m++) {
            YearMonth ym = YearMonth.of(year, m);
            if (ym.isBefore(minYm) || ym.isAfter(maxYm)) {
                continue;
            }
            PropertyMonthlyReportDTO mo = buildMonthlyReport(propertyId, ym.format(YM_FMT));
            a.setExpectedAnnual(a.getExpectedAnnual().add(mo.getExpectedRent()));
            a.setCollectedAnnual(a.getCollectedAnnual().add(mo.getCollected()));
            a.setOutstandingAnnual(a.getOutstandingAnnual().add(mo.getOutstanding()));
            a.setMaintenanceTicketsYear(a.getMaintenanceTicketsYear() + mo.getMaintenanceTickets());
            a.setCommercialActivitiesYear(a.getCommercialActivitiesYear() + mo.getCommercialActivities());
        }
        for (ExpenseEntity e : expenseRepository.findByPropertyId(propertyId)) {
            if (ExpenseReportingUtil.expenseTouchesYear(e, year)) {
                a.setExpensesAnnual(a.getExpensesAnnual().add(e.getAmount() != null ? e.getAmount() : BigDecimal.ZERO));
            }
        }
        String ownerId = TenantContext.resolveOwnerId(userRepository);
        List<InvoiceDTO> allPropInv = ledgerService.getInvoicesForProperty(propertyId);
        a.setAgreementsHistoric((int) agreementRepository.findByOwnerId(ownerId).stream()
                .filter(ag -> ag.getCreatedAt() != null && ag.getCreatedAt().getYear() == year)
                .filter(ag -> ag.getInvoiceId() != null
                        && allPropInv.stream().anyMatch(inv -> inv.getId().equals(ag.getInvoiceId())))
                .count());
        long vacMonths = vacancyRepository.findByPropertyId(propertyId).stream()
                .filter(v -> v.getOpenedAt() != null && v.getOpenedAt().getYear() == year)
                .count();
        a.setMonthsWithVacancy((int) vacMonths);
        return a;
    }
}