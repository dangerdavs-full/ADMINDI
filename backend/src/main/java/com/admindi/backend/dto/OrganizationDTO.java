package com.admindi.backend.dto;

public class OrganizationDTO {
    private String id;
    private String name;
    private String contactEmail;
    private String contactPhone;
    private String rfc;
    private boolean isActive;
    
    private String stripePublicKey;
    private String stripeSecretKey;
    
    // Campo exclusivo para retornar la contraseña autogenerada en la primer creación
    private String tempPassword;

    public OrganizationDTO() {}

    public OrganizationDTO(String id, String name, String contactEmail, String contactPhone, String rfc, boolean isActive, String stripePublicKey, String stripeSecretKey) {
        this.id = id;
        this.name = name;
        this.contactEmail = contactEmail;
        this.contactPhone = contactPhone;
        this.rfc = rfc;
        this.isActive = isActive;
        this.stripePublicKey = stripePublicKey;
        this.stripeSecretKey = stripeSecretKey;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getContactEmail() { return contactEmail; }
    public void setContactEmail(String contactEmail) { this.contactEmail = contactEmail; }
    public String getContactPhone() { return contactPhone; }
    public void setContactPhone(String contactPhone) { this.contactPhone = contactPhone; }
    public String getRfc() { return rfc; }
    public void setRfc(String rfc) { this.rfc = rfc; }
    public boolean isActive() { return isActive; }
    public void setActive(boolean active) { isActive = active; }
    public String getStripePublicKey() { return stripePublicKey; }
    public void setStripePublicKey(String stripePublicKey) { this.stripePublicKey = stripePublicKey; }
    public String getStripeSecretKey() { return stripeSecretKey; }
    public void setStripeSecretKey(String stripeSecretKey) { this.stripeSecretKey = stripeSecretKey; }
    public String getTempPassword() { return tempPassword; }
    public void setTempPassword(String tempPassword) { this.tempPassword = tempPassword; }
}
