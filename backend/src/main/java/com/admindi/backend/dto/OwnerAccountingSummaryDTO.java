package com.admindi.backend.dto;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

public class OwnerAccountingSummaryDTO {
    private BigDecimal expectedIncome = BigDecimal.ZERO;
    private BigDecimal collectedIncome = BigDecimal.ZERO;
    private BigDecimal outstandingIncome = BigDecimal.ZERO;
    private BigDecimal overpaidCredits = BigDecimal.ZERO;
    private BigDecimal approvedExpenses = BigDecimal.ZERO;
    private BigDecimal paidExpenses = BigDecimal.ZERO;
    private BigDecimal pendingExpenses = BigDecimal.ZERO;
    /**
     * V64 — Suma del crédito que la plataforma ADMINDI aplicó al dueño en el
     * mes (15% cuando el proveedor era PLATFORM). Se muestra al dueño como
     * "ahorro" en el dashboard. Los gastos reales ya vienen netos.
     */
    private BigDecimal platformSavings = BigDecimal.ZERO;
    private BigDecimal lateFeeAccrued = BigDecimal.ZERO;
    private int activeAgreementsCount;
    private int breachedAgreementsCount;
    private int delinquentTenantsCount;
    private int propertiesWithIssuesCount;
    private List<ReceivableLineDTO> receivables = new ArrayList<>();
    private List<ExpenseLineDTO> expenses = new ArrayList<>();
    private List<String> alerts = new ArrayList<>();

    public BigDecimal getExpectedIncome() { return expectedIncome; }
    public void setExpectedIncome(BigDecimal expectedIncome) { this.expectedIncome = expectedIncome; }
    public BigDecimal getCollectedIncome() { return collectedIncome; }
    public void setCollectedIncome(BigDecimal collectedIncome) { this.collectedIncome = collectedIncome; }
    public BigDecimal getOutstandingIncome() { return outstandingIncome; }
    public void setOutstandingIncome(BigDecimal outstandingIncome) { this.outstandingIncome = outstandingIncome; }
    public BigDecimal getOverpaidCredits() { return overpaidCredits; }
    public void setOverpaidCredits(BigDecimal overpaidCredits) { this.overpaidCredits = overpaidCredits; }
    public BigDecimal getApprovedExpenses() { return approvedExpenses; }
    public void setApprovedExpenses(BigDecimal approvedExpenses) { this.approvedExpenses = approvedExpenses; }
    public BigDecimal getPaidExpenses() { return paidExpenses; }
    public void setPaidExpenses(BigDecimal paidExpenses) { this.paidExpenses = paidExpenses; }
    public BigDecimal getPendingExpenses() { return pendingExpenses; }
    public void setPendingExpenses(BigDecimal pendingExpenses) { this.pendingExpenses = pendingExpenses; }
    public BigDecimal getPlatformSavings() { return platformSavings; }
    public void setPlatformSavings(BigDecimal platformSavings) {
        this.platformSavings = platformSavings != null ? platformSavings : BigDecimal.ZERO;
    }
    public BigDecimal getLateFeeAccrued() { return lateFeeAccrued; }
    public void setLateFeeAccrued(BigDecimal lateFeeAccrued) { this.lateFeeAccrued = lateFeeAccrued; }
    public int getActiveAgreementsCount() { return activeAgreementsCount; }
    public void setActiveAgreementsCount(int activeAgreementsCount) { this.activeAgreementsCount = activeAgreementsCount; }
    public int getBreachedAgreementsCount() { return breachedAgreementsCount; }
    public void setBreachedAgreementsCount(int breachedAgreementsCount) { this.breachedAgreementsCount = breachedAgreementsCount; }
    public int getDelinquentTenantsCount() { return delinquentTenantsCount; }
    public void setDelinquentTenantsCount(int delinquentTenantsCount) { this.delinquentTenantsCount = delinquentTenantsCount; }
    public int getPropertiesWithIssuesCount() { return propertiesWithIssuesCount; }
    public void setPropertiesWithIssuesCount(int propertiesWithIssuesCount) { this.propertiesWithIssuesCount = propertiesWithIssuesCount; }
    public List<ReceivableLineDTO> getReceivables() { return receivables; }
    public void setReceivables(List<ReceivableLineDTO> receivables) { this.receivables = receivables; }
    public List<ExpenseLineDTO> getExpenses() { return expenses; }
    public void setExpenses(List<ExpenseLineDTO> expenses) { this.expenses = expenses; }
    public List<String> getAlerts() { return alerts; }
    public void setAlerts(List<String> alerts) { this.alerts = alerts; }

    public static class ReceivableLineDTO {
        private String invoiceId;
        private String propertyId;
        private String propertyName;
        private String tenantName;
        private String monthYear;
        private BigDecimal expectedRent;
        private BigDecimal paidAmount;
        private BigDecimal outstanding;
        private String status;
        private String settlementStatus;
        private String shortfallReason;
        private String promisedCompletionDate;
        private String agreementSummaryStatus;
        /** PAYMENT_SHORTFALL, AGREEMENT_DEFERRAL, MIXED, PAYMENT_DELINQUENCY, NONE */
        private String balanceDriver;
        public String getInvoiceId() { return invoiceId; }
        public void setInvoiceId(String invoiceId) { this.invoiceId = invoiceId; }
        public String getPropertyId() { return propertyId; }
        public void setPropertyId(String propertyId) { this.propertyId = propertyId; }
        public String getPropertyName() { return propertyName; }
        public void setPropertyName(String propertyName) { this.propertyName = propertyName; }
        public String getTenantName() { return tenantName; }
        public void setTenantName(String tenantName) { this.tenantName = tenantName; }
        public String getMonthYear() { return monthYear; }
        public void setMonthYear(String monthYear) { this.monthYear = monthYear; }
        public BigDecimal getExpectedRent() { return expectedRent; }
        public void setExpectedRent(BigDecimal expectedRent) { this.expectedRent = expectedRent; }
        public BigDecimal getPaidAmount() { return paidAmount; }
        public void setPaidAmount(BigDecimal paidAmount) { this.paidAmount = paidAmount; }
        public BigDecimal getOutstanding() { return outstanding; }
        public void setOutstanding(BigDecimal outstanding) { this.outstanding = outstanding; }
        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
        public String getSettlementStatus() { return settlementStatus; }
        public void setSettlementStatus(String settlementStatus) { this.settlementStatus = settlementStatus; }
        public String getShortfallReason() { return shortfallReason; }
        public void setShortfallReason(String shortfallReason) { this.shortfallReason = shortfallReason; }
        public String getPromisedCompletionDate() { return promisedCompletionDate; }
        public void setPromisedCompletionDate(String promisedCompletionDate) { this.promisedCompletionDate = promisedCompletionDate; }
        public String getAgreementSummaryStatus() { return agreementSummaryStatus; }
        public void setAgreementSummaryStatus(String agreementSummaryStatus) { this.agreementSummaryStatus = agreementSummaryStatus; }
        public String getBalanceDriver() { return balanceDriver; }
        public void setBalanceDriver(String balanceDriver) { this.balanceDriver = balanceDriver; }
    }

    public static class ExpenseLineDTO {
        private String id;
        private String propertyId;
        private String propertyName;
        private BigDecimal amount;
        private String status;
        private String type;
        private String description;
        private String linkedResourceType;
        private String linkedResourceId;
        public String getId() { return id; }
        public void setId(String id) { this.id = id; }
        public String getPropertyId() { return propertyId; }
        public void setPropertyId(String propertyId) { this.propertyId = propertyId; }
        public String getPropertyName() { return propertyName; }
        public void setPropertyName(String propertyName) { this.propertyName = propertyName; }
        public BigDecimal getAmount() { return amount; }
        public void setAmount(BigDecimal amount) { this.amount = amount; }
        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
        public String getType() { return type; }
        public void setType(String type) { this.type = type; }
        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }
        public String getLinkedResourceType() { return linkedResourceType; }
        public void setLinkedResourceType(String linkedResourceType) { this.linkedResourceType = linkedResourceType; }
        public String getLinkedResourceId() { return linkedResourceId; }
        public void setLinkedResourceId(String linkedResourceId) { this.linkedResourceId = linkedResourceId; }
    }
}