package com.admindi.backend.dto;

import com.admindi.backend.model.LeaseStatus;

import java.math.BigDecimal;
import java.time.LocalDate;

public class TenantExpedienteSummaryDTO {
    private String tenantProfileId;
    private String tenantName;
    private String tenantEmail;
    private String tenantPhone;
    private String organizationName;
    private String propertyId;
    private String propertyName;
    private String propertyAddress;
    private BigDecimal rentAmount;
    private int paymentDay;
    private String leaseId;
    private LeaseStatus leaseStatus;
    private LocalDate leaseStartDate;
    private LocalDate leaseEndDate;
    /** URL del PDF del contrato en almacenamiento (si se subió en el alta). */
    private String leaseDocumentUrl;
    private String leaseDocumentFileName;

    public TenantExpedienteSummaryDTO() {}

    public String getTenantProfileId() { return tenantProfileId; }
    public void setTenantProfileId(String tenantProfileId) { this.tenantProfileId = tenantProfileId; }
    public String getTenantName() { return tenantName; }
    public void setTenantName(String tenantName) { this.tenantName = tenantName; }
    public String getTenantEmail() { return tenantEmail; }
    public void setTenantEmail(String tenantEmail) { this.tenantEmail = tenantEmail; }
    public String getTenantPhone() { return tenantPhone; }
    public void setTenantPhone(String tenantPhone) { this.tenantPhone = tenantPhone; }
    public String getOrganizationName() { return organizationName; }
    public void setOrganizationName(String organizationName) { this.organizationName = organizationName; }
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
    public LocalDate getLeaseStartDate() { return leaseStartDate; }
    public void setLeaseStartDate(LocalDate leaseStartDate) { this.leaseStartDate = leaseStartDate; }
    public LocalDate getLeaseEndDate() { return leaseEndDate; }
    public void setLeaseEndDate(LocalDate leaseEndDate) { this.leaseEndDate = leaseEndDate; }
    public String getLeaseDocumentUrl() { return leaseDocumentUrl; }
    public void setLeaseDocumentUrl(String leaseDocumentUrl) { this.leaseDocumentUrl = leaseDocumentUrl; }
    public String getLeaseDocumentFileName() { return leaseDocumentFileName; }
    public void setLeaseDocumentFileName(String leaseDocumentFileName) { this.leaseDocumentFileName = leaseDocumentFileName; }
}
