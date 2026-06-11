package com.admindi.backend.dto;

import com.admindi.backend.model.Role;

public class StaffDTO {
    private String id;
    private String ownerId;
    private String name;

    // V48: username = identificador de login del staff. Obligatorio en altas
    // nuevas. Unicidad global validada por UsernameService.
    private String username;

    // V54: un único email por user, en `contactEmail` (no único, compartible).
    // El campo histórico `loginEmail` se expone como alias vía getter/setter —
    // el frontend puede seguir enviando "loginEmail" y el backend lo guarda en
    // el mismo backing field que `contactEmail`.
    private String contactEmail;
    private String contactPhone;     // With country code prefix
    private String contactCountryCode; // e.g. "+52"

    private Role role;
    /**
     * Contraseña temporal generada al crear la cuenta (y al reenviar el
     * welcome vía {@code resendActivation}). Se rellena para TODOS los roles
     * staff — el agente / admin / contador / proveedor la necesita para
     * iniciar sesión; queda invalidada al primer login por
     * {@code mustChangePassword=true} (contraseña de un solo uso efectiva).
     * El flujo histórico de link de activación fue retirado.
     */
    private String tempPassword;

    /** true si al crear se emitió y despachó un link de activación al user. */
    private boolean activationSent;
    /** Canal por el que se envió el link (EMAIL, WHATSAPP, BOTH). */
    private String activationChannel;

    // Permission inline management
    private String permissionTemplateId;   // Template to assign (write)
    private String currentTemplateName;    // Currently assigned template name (read-only)

    // Multi-owner indicator
    private boolean reuseExisting;   // True if account was reused (read-only)

    public StaffDTO() {}

    public StaffDTO(String id, String ownerId, String name, String loginEmail,
                    String contactEmail, String contactPhone, String contactCountryCode,
                    Role role, String currentTemplateName) {
        this.id = id;
        this.ownerId = ownerId;
        this.name = name;
        // V54: loginEmail y contactEmail son el mismo campo. Preferimos el
        // valor explícito de contactEmail; si viene null/blank usamos loginEmail.
        this.contactEmail = (contactEmail != null && !contactEmail.isBlank())
                ? contactEmail : loginEmail;
        this.contactPhone = contactPhone;
        this.contactCountryCode = contactCountryCode;
        this.role = role;
        this.currentTemplateName = currentTemplateName;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getOwnerId() { return ownerId; }
    public void setOwnerId(String ownerId) { this.ownerId = ownerId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    /** V54 — alias de {@link #getContactEmail()}. */
    public String getLoginEmail() { return contactEmail; }
    /** V54 — alias de {@link #setContactEmail(String)}. */
    public void setLoginEmail(String loginEmail) { this.contactEmail = loginEmail; }

    public String getContactEmail() { return contactEmail; }
    public void setContactEmail(String contactEmail) { this.contactEmail = contactEmail; }
    public String getContactPhone() { return contactPhone; }
    public void setContactPhone(String contactPhone) { this.contactPhone = contactPhone; }
    public String getContactCountryCode() { return contactCountryCode; }
    public void setContactCountryCode(String contactCountryCode) { this.contactCountryCode = contactCountryCode; }
    public Role getRole() { return role; }
    public void setRole(Role role) { this.role = role; }
    public String getTempPassword() { return tempPassword; }
    public void setTempPassword(String tempPassword) { this.tempPassword = tempPassword; }
    public boolean isActivationSent() { return activationSent; }
    public void setActivationSent(boolean activationSent) { this.activationSent = activationSent; }
    public String getActivationChannel() { return activationChannel; }
    public void setActivationChannel(String activationChannel) { this.activationChannel = activationChannel; }
    public String getPermissionTemplateId() { return permissionTemplateId; }
    public void setPermissionTemplateId(String permissionTemplateId) { this.permissionTemplateId = permissionTemplateId; }
    public String getCurrentTemplateName() { return currentTemplateName; }
    public void setCurrentTemplateName(String currentTemplateName) { this.currentTemplateName = currentTemplateName; }
    public boolean isReuseExisting() { return reuseExisting; }
    public void setReuseExisting(boolean reuseExisting) { this.reuseExisting = reuseExisting; }

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }

    /** V54 — alias legacy: 'email' = contactEmail. */
    @Deprecated
    public String getEmail() { return contactEmail; }
    @Deprecated
    public void setEmail(String email) { this.contactEmail = email; }

    /** V54 — alias legacy: 'phone' = contactPhone. */
    @Deprecated
    public String getPhone() { return contactPhone; }
    @Deprecated
    public void setPhone(String phone) { this.contactPhone = phone; }
}
