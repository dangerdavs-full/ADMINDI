package com.admindi.backend.dto;

public class MaintenanceProviderDTO {
    private String id;
    private String name;
    // V54: un único email por user, en `contactEmail` (no único, compartible).
    // El campo histórico `email` se expone como alias vía getter/setter.
    private String contactEmail;
    private String contactPhone;
    private String contactCountryCode;
    private String rawPhone;           // input only
    private String countryCode;        // input only (for creation)
    /**
     * Contraseña temporal generada al crear la cuenta (y al reenviar el
     * welcome vía {@code resendActivation}). Se rellena para ambos providerType
     * (REAL_ESTATE_AGENT y MAINTENANCE_PROVIDER); queda invalidada al primer
     * login por {@code mustChangePassword=true} (contraseña de un solo uso
     * efectiva). El flujo histórico de link de activación fue retirado.
     */
    private String tempPassword;
    private boolean activationSent;
    private String activationChannel;
    private String providerType;       // MAINTENANCE_PROVIDER or REAL_ESTATE_AGENT

    /**
     * V48 / Bloque 2: identificador de login único global.
     * Obligatorio en altas nuevas desde V48. En colisión el backend responde 409
     * {@code UsernameTakenException} con suggestion.
     */
    private String username;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    /** V54 — alias de {@link #getContactEmail()}. */
    public String getEmail() { return contactEmail; }
    /** V54 — alias de {@link #setContactEmail(String)}. */
    public void setEmail(String email) { this.contactEmail = email; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getContactEmail() { return contactEmail; }
    public void setContactEmail(String contactEmail) { this.contactEmail = contactEmail; }
    public String getContactPhone() { return contactPhone; }
    public void setContactPhone(String contactPhone) { this.contactPhone = contactPhone; }
    public String getContactCountryCode() { return contactCountryCode; }
    public void setContactCountryCode(String contactCountryCode) { this.contactCountryCode = contactCountryCode; }
    public String getRawPhone() { return rawPhone; }
    public void setRawPhone(String rawPhone) { this.rawPhone = rawPhone; }
    public String getCountryCode() { return countryCode; }
    public void setCountryCode(String countryCode) { this.countryCode = countryCode; }
    public String getTempPassword() { return tempPassword; }
    public void setTempPassword(String tempPassword) { this.tempPassword = tempPassword; }
    public boolean isActivationSent() { return activationSent; }
    public void setActivationSent(boolean activationSent) { this.activationSent = activationSent; }
    public String getActivationChannel() { return activationChannel; }
    public void setActivationChannel(String activationChannel) { this.activationChannel = activationChannel; }
    public String getProviderType() { return providerType; }
    public void setProviderType(String providerType) { this.providerType = providerType; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
}
