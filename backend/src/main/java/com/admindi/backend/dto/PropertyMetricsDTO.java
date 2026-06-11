package com.admindi.backend.dto;

import java.math.BigDecimal;

public class PropertyMetricsDTO {
    private String propertyId;
    private String status;
    private String currentTenantName;
    private BigDecimal monthlyRent;
    private BigDecimal lifetimeCollected;
    private long totalInvoices;
    private long paidInvoices;
    private long lateInvoices;

    public PropertyMetricsDTO() {}

    // Getters
    public String getPropertyId() { return propertyId; }
    public String getStatus() { return status; }
    public String getCurrentTenantName() { return currentTenantName; }
    public BigDecimal getMonthlyRent() { return monthlyRent; }
    public BigDecimal getLifetimeCollected() { return lifetimeCollected; }
    public long getTotalInvoices() { return totalInvoices; }
    public long getPaidInvoices() { return paidInvoices; }
    public long getLateInvoices() { return lateInvoices; }

    // Setters
    public void setPropertyId(String propertyId) { this.propertyId = propertyId; }
    public void setStatus(String status) { this.status = status; }
    public void setCurrentTenantName(String currentTenantName) { this.currentTenantName = currentTenantName; }
    public void setMonthlyRent(BigDecimal monthlyRent) { this.monthlyRent = monthlyRent; }
    public void setLifetimeCollected(BigDecimal lifetimeCollected) { this.lifetimeCollected = lifetimeCollected; }
    public void setTotalInvoices(long totalInvoices) { this.totalInvoices = totalInvoices; }
    public void setPaidInvoices(long paidInvoices) { this.paidInvoices = paidInvoices; }
    public void setLateInvoices(long lateInvoices) { this.lateInvoices = lateInvoices; }
}
