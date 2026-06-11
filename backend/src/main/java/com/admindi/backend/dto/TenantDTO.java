package com.admindi.backend.dto;

import com.admindi.backend.model.LeaseStatus;

import java.math.BigDecimal;
import java.time.LocalDate;

public class TenantDTO {
    private String id; // TenantProfile ID
    private String userId; // El User ID
    private String ownerId;
    private String name;
    private String username; // V48: login identifier del tenant (obligatorio en altas nuevas)
    // V54: un único email por user, en `contactEmail` (no único, compartible).
    // El campo histórico `email` se expone como alias vía getter/setter.
    private String contactEmail;
    private String phone; // Para N8N

    /**
     * V48 / Bloque 2: para añadir un segundo (o N) inmueble al <b>mismo</b>
     * arrendatario del mismo dueño, el creador envía este id. Si es null, el
     * sistema crea siempre una cuenta nueva (sin reusar por email).
     */
    private String reuseExistingUserId;
    private String propertyId;
    private BigDecimal rentAmount;    
    private int paymentDay;
    
    // Motor Moratorio
    private boolean hasLateFee;
    private String lateFeeType;
    private BigDecimal lateFeeValue;
    private int gracePeriodDays;

    /** Alta integral (Paso 2): fechas y depósito del contrato / tenencia. */
    private LocalDate leaseStartDate;
    private LocalDate leaseEndDate;
    private BigDecimal depositAmount;

    /** Respuesta: lease ACTIVO vinculado al expediente en este inmueble. */
    private String leaseId;
    private LeaseStatus leaseStatus;
    
    // Contraseña inicial autogenerada para enviarle por primera vez
    private String tempPassword;

    public TenantDTO() {}

    public TenantDTO(String id, String userId, String ownerId, String name, String email, String phone, 
                     String propertyId, BigDecimal rentAmount, int paymentDay, 
                     boolean hasLateFee, String lateFeeType, BigDecimal lateFeeValue, int gracePeriodDays) {
        this.id = id;
        this.userId = userId;
        this.ownerId = ownerId;
        this.name = name;
        this.contactEmail = email;
        this.phone = phone;
        this.propertyId = propertyId;
        this.rentAmount = rentAmount;
        this.paymentDay = paymentDay;
        this.hasLateFee = hasLateFee;
        this.lateFeeType = lateFeeType;
        this.lateFeeValue = lateFeeValue;
        this.gracePeriodDays = gracePeriodDays;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public String getOwnerId() { return ownerId; }
    public void setOwnerId(String ownerId) { this.ownerId = ownerId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    /** V54 — alias de {@link #getContactEmail()}. */
    public String getEmail() { return contactEmail; }
    /** V54 — alias de {@link #setContactEmail(String)}. */
    public void setEmail(String email) { this.contactEmail = email; }
    public String getContactEmail() { return contactEmail; }
    public void setContactEmail(String contactEmail) { this.contactEmail = contactEmail; }
    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }
    public String getPropertyId() { return propertyId; }
    public void setPropertyId(String propertyId) { this.propertyId = propertyId; }
    public BigDecimal getRentAmount() { return rentAmount; }
    public void setRentAmount(BigDecimal rentAmount) { this.rentAmount = rentAmount; }
    public int getPaymentDay() { return paymentDay; }
    public void setPaymentDay(int paymentDay) { this.paymentDay = paymentDay; }
    
    public boolean isHasLateFee() { return hasLateFee; }
    public void setHasLateFee(boolean hasLateFee) { this.hasLateFee = hasLateFee; }

    public String getLateFeeType() { return lateFeeType; }
    public void setLateFeeType(String lateFeeType) { this.lateFeeType = lateFeeType; }

    public BigDecimal getLateFeeValue() { return lateFeeValue; }
    public void setLateFeeValue(BigDecimal lateFeeValue) { this.lateFeeValue = lateFeeValue; }

    public int getGracePeriodDays() { return gracePeriodDays; }
    public void setGracePeriodDays(int gracePeriodDays) { this.gracePeriodDays = gracePeriodDays; }
    
    public String getTempPassword() { return tempPassword; }
    public void setTempPassword(String tempPassword) { this.tempPassword = tempPassword; }

    public LocalDate getLeaseStartDate() { return leaseStartDate; }
    public void setLeaseStartDate(LocalDate leaseStartDate) { this.leaseStartDate = leaseStartDate; }

    public LocalDate getLeaseEndDate() { return leaseEndDate; }
    public void setLeaseEndDate(LocalDate leaseEndDate) { this.leaseEndDate = leaseEndDate; }

    public BigDecimal getDepositAmount() { return depositAmount; }
    public void setDepositAmount(BigDecimal depositAmount) { this.depositAmount = depositAmount; }

    public String getLeaseId() { return leaseId; }
    public void setLeaseId(String leaseId) { this.leaseId = leaseId; }

    public LeaseStatus getLeaseStatus() { return leaseStatus; }
    public void setLeaseStatus(LeaseStatus leaseStatus) { this.leaseStatus = leaseStatus; }

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }

    public String getReuseExistingUserId() { return reuseExistingUserId; }
    public void setReuseExistingUserId(String reuseExistingUserId) { this.reuseExistingUserId = reuseExistingUserId; }
}
