package com.admindi.backend.dto;

import java.math.BigDecimal;

public class TenantPortalDTO {
    private String tenantName;
    private String tenantEmail;
    private String tenantPhone;
    
    private String organizationName;
    
    private String propertyName;
    private String propertyAddress;
    
    private BigDecimal rentAmount;
    private int paymentDay;

    public TenantPortalDTO() {}

    public TenantPortalDTO(String tenantName, String tenantEmail, String tenantPhone, String organizationName, String propertyName, String propertyAddress, BigDecimal rentAmount, int paymentDay) {
        this.tenantName = tenantName;
        this.tenantEmail = tenantEmail;
        this.tenantPhone = tenantPhone;
        this.organizationName = organizationName;
        this.propertyName = propertyName;
        this.propertyAddress = propertyAddress;
        this.rentAmount = rentAmount;
        this.paymentDay = paymentDay;
    }

    public String getTenantName() { return tenantName; }
    public void setTenantName(String tenantName) { this.tenantName = tenantName; }

    public String getTenantEmail() { return tenantEmail; }
    public void setTenantEmail(String tenantEmail) { this.tenantEmail = tenantEmail; }

    public String getTenantPhone() { return tenantPhone; }
    public void setTenantPhone(String tenantPhone) { this.tenantPhone = tenantPhone; }

    public String getOrganizationName() { return organizationName; }
    public void setOrganizationName(String organizationName) { this.organizationName = organizationName; }

    public String getPropertyName() { return propertyName; }
    public void setPropertyName(String propertyName) { this.propertyName = propertyName; }

    public String getPropertyAddress() { return propertyAddress; }
    public void setPropertyAddress(String propertyAddress) { this.propertyAddress = propertyAddress; }

    public BigDecimal getRentAmount() { return rentAmount; }
    public void setRentAmount(BigDecimal rentAmount) { this.rentAmount = rentAmount; }

    public int getPaymentDay() { return paymentDay; }
    public void setPaymentDay(int paymentDay) { this.paymentDay = paymentDay; }
}
