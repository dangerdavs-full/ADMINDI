package com.admindi.backend.dto;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

public class PropertyMonthlyReportDTO {
    private String propertyId;
    private String monthYear;
    private BigDecimal expectedRent = BigDecimal.ZERO;
    private BigDecimal collected = BigDecimal.ZERO;
    private BigDecimal outstanding = BigDecimal.ZERO;
    private BigDecimal creditBalance = BigDecimal.ZERO;
    private int partialPaymentsCount;
    private int activeAgreements;
    private int breachedAgreements;
    private BigDecimal deferredAmount = BigDecimal.ZERO;
    private int maintenanceTickets;
    private BigDecimal maintenanceCost = BigDecimal.ZERO;
    private int commercialActivities;
    private int newFilesCount;
    private List<String> alerts = new ArrayList<>();

    public String getPropertyId() { return propertyId; }
    public void setPropertyId(String propertyId) { this.propertyId = propertyId; }
    public String getMonthYear() { return monthYear; }
    public void setMonthYear(String monthYear) { this.monthYear = monthYear; }
    public BigDecimal getExpectedRent() { return expectedRent; }
    public void setExpectedRent(BigDecimal expectedRent) { this.expectedRent = expectedRent; }
    public BigDecimal getCollected() { return collected; }
    public void setCollected(BigDecimal collected) { this.collected = collected; }
    public BigDecimal getOutstanding() { return outstanding; }
    public void setOutstanding(BigDecimal outstanding) { this.outstanding = outstanding; }
    public BigDecimal getCreditBalance() { return creditBalance; }
    public void setCreditBalance(BigDecimal creditBalance) { this.creditBalance = creditBalance; }
    public int getPartialPaymentsCount() { return partialPaymentsCount; }
    public void setPartialPaymentsCount(int partialPaymentsCount) { this.partialPaymentsCount = partialPaymentsCount; }
    public int getActiveAgreements() { return activeAgreements; }
    public void setActiveAgreements(int activeAgreements) { this.activeAgreements = activeAgreements; }
    public int getBreachedAgreements() { return breachedAgreements; }
    public void setBreachedAgreements(int breachedAgreements) { this.breachedAgreements = breachedAgreements; }
    public BigDecimal getDeferredAmount() { return deferredAmount; }
    public void setDeferredAmount(BigDecimal deferredAmount) { this.deferredAmount = deferredAmount; }
    public int getMaintenanceTickets() { return maintenanceTickets; }
    public void setMaintenanceTickets(int maintenanceTickets) { this.maintenanceTickets = maintenanceTickets; }
    public BigDecimal getMaintenanceCost() { return maintenanceCost; }
    public void setMaintenanceCost(BigDecimal maintenanceCost) { this.maintenanceCost = maintenanceCost; }
    public int getCommercialActivities() { return commercialActivities; }
    public void setCommercialActivities(int commercialActivities) { this.commercialActivities = commercialActivities; }
    public int getNewFilesCount() { return newFilesCount; }
    public void setNewFilesCount(int newFilesCount) { this.newFilesCount = newFilesCount; }
    public List<String> getAlerts() { return alerts; }
    public void setAlerts(List<String> alerts) { this.alerts = alerts; }
}