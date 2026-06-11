package com.admindi.backend.dto;

import java.math.BigDecimal;

public class GlobalMetricsDTO {
    private long totalProperties;
    private long occupiedProperties;
    private BigDecimal expectedMonthlyIncome;
    private BigDecimal collectedMonthlyIncome;
    private long delinquentTenants;

    public GlobalMetricsDTO() {}

    public GlobalMetricsDTO(long totalProperties, long occupiedProperties, BigDecimal expectedMonthlyIncome, BigDecimal collectedMonthlyIncome, long delinquentTenants) {
        this.totalProperties = totalProperties;
        this.occupiedProperties = occupiedProperties;
        this.expectedMonthlyIncome = expectedMonthlyIncome;
        this.collectedMonthlyIncome = collectedMonthlyIncome;
        this.delinquentTenants = delinquentTenants;
    }

    public long getTotalProperties() { return totalProperties; }
    public void setTotalProperties(long totalProperties) { this.totalProperties = totalProperties; }

    public long getOccupiedProperties() { return occupiedProperties; }
    public void setOccupiedProperties(long occupiedProperties) { this.occupiedProperties = occupiedProperties; }

    public BigDecimal getExpectedMonthlyIncome() { return expectedMonthlyIncome; }
    public void setExpectedMonthlyIncome(BigDecimal expectedMonthlyIncome) { this.expectedMonthlyIncome = expectedMonthlyIncome; }

    public BigDecimal getCollectedMonthlyIncome() { return collectedMonthlyIncome; }
    public void setCollectedMonthlyIncome(BigDecimal collectedMonthlyIncome) { this.collectedMonthlyIncome = collectedMonthlyIncome; }

    public long getDelinquentTenants() { return delinquentTenants; }
    public void setDelinquentTenants(long delinquentTenants) { this.delinquentTenants = delinquentTenants; }
}
