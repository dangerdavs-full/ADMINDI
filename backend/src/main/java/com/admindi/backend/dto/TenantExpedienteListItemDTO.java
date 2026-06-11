package com.admindi.backend.dto;

import com.admindi.backend.model.LeaseStatus;

import java.math.BigDecimal;

public class TenantExpedienteListItemDTO {
    private String tenantProfileId;
    private String propertyId;
    private String propertyName;
    private String propertyAddress;
    private BigDecimal rentAmount;
    private int paymentDay;
    private String leaseId;
    private LeaseStatus leaseStatus;

    public TenantExpedienteListItemDTO() {}

    public String getTenantProfileId() { return tenantProfileId; }
    public void setTenantProfileId(String tenantProfileId) { this.tenantProfileId = tenantProfileId; }
    public String getPropertyId() { return propertyId; }
    public void setPropertyId(String propertyId) { this.propertyId = propertyId; }
    public String getPropertyName() { return propertyName; }
    public void setPropertyName(String propertyName) { this.propertyName = propertyName; }
    public String getPropertyAddress() { return propertyAddress; }
    public void setPropertyAddress(String propertyAddress) { this.propertyAddress = propertyAddress; }
    public BigDecimal getRentAmount() { return rentAmount; }
    public void setRentAmount(BigDecimal rentAmount) { this.rentAmount = rentAmount; }
    public int getPaymentDay() { return paymentDay; }
    public void setPaymentDay(int paymentDay) { this.paymentDay = paymentDay; }
    public String getLeaseId() { return leaseId; }
    public void setLeaseId(String leaseId) { this.leaseId = leaseId; }
    public LeaseStatus getLeaseStatus() { return leaseStatus; }
    public void setLeaseStatus(LeaseStatus leaseStatus) { this.leaseStatus = leaseStatus; }
}
