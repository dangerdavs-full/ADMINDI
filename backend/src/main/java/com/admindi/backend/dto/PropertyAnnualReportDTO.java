package com.admindi.backend.dto;

import java.math.BigDecimal;

public class PropertyAnnualReportDTO {
    private String propertyId;
    private int year;
    private BigDecimal expectedAnnual = BigDecimal.ZERO;
    private BigDecimal collectedAnnual = BigDecimal.ZERO;
    private BigDecimal outstandingAnnual = BigDecimal.ZERO;
    private BigDecimal expensesAnnual = BigDecimal.ZERO;
    private int agreementsHistoric;
    private int monthsWithVacancy;
    private int maintenanceTicketsYear;
    private int commercialActivitiesYear;

    public String getPropertyId() { return propertyId; }
    public void setPropertyId(String propertyId) { this.propertyId = propertyId; }
    public int getYear() { return year; }
    public void setYear(int year) { this.year = year; }
    public BigDecimal getExpectedAnnual() { return expectedAnnual; }
    public void setExpectedAnnual(BigDecimal expectedAnnual) { this.expectedAnnual = expectedAnnual; }
    public BigDecimal getCollectedAnnual() { return collectedAnnual; }
    public void setCollectedAnnual(BigDecimal collectedAnnual) { this.collectedAnnual = collectedAnnual; }
    public BigDecimal getOutstandingAnnual() { return outstandingAnnual; }
    public void setOutstandingAnnual(BigDecimal outstandingAnnual) { this.outstandingAnnual = outstandingAnnual; }
    public BigDecimal getExpensesAnnual() { return expensesAnnual; }
    public void setExpensesAnnual(BigDecimal expensesAnnual) { this.expensesAnnual = expensesAnnual; }
    public int getAgreementsHistoric() { return agreementsHistoric; }
    public void setAgreementsHistoric(int agreementsHistoric) { this.agreementsHistoric = agreementsHistoric; }
    public int getMonthsWithVacancy() { return monthsWithVacancy; }
    public void setMonthsWithVacancy(int monthsWithVacancy) { this.monthsWithVacancy = monthsWithVacancy; }
    public int getMaintenanceTicketsYear() { return maintenanceTicketsYear; }
    public void setMaintenanceTicketsYear(int maintenanceTicketsYear) { this.maintenanceTicketsYear = maintenanceTicketsYear; }
    public int getCommercialActivitiesYear() { return commercialActivitiesYear; }
    public void setCommercialActivitiesYear(int commercialActivitiesYear) { this.commercialActivitiesYear = commercialActivitiesYear; }
}