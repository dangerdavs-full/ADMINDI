package com.admindi.backend.dto;

/**
 * Payload del alta integral de un proveedor/agente privado desde el panel del owner.
 * El owner captura aquí todos los datos del contacto; el backend crea el usuario y
 * lo vincula exclusivamente a su organización (assignment_source PRIVATE).
 */
public class CreatePrivateProviderRequest {
    private String name;
    /** V50 — identificador de login canónico. Obligatorio en altas nuevas desde UI. */
    private String username;
    /** V50 — opcional: correo de contacto. Ya no es identificador de login. */
    private String email;
    private String contactEmail;
    private String countryCode;
    private String rawPhone;
    private String providerType; // MAINTENANCE_PROVIDER | REAL_ESTATE_AGENT

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }

    public String getContactEmail() { return contactEmail; }
    public void setContactEmail(String contactEmail) { this.contactEmail = contactEmail; }

    public String getCountryCode() { return countryCode; }
    public void setCountryCode(String countryCode) { this.countryCode = countryCode; }

    public String getRawPhone() { return rawPhone; }
    public void setRawPhone(String rawPhone) { this.rawPhone = rawPhone; }

    public String getProviderType() { return providerType; }
    public void setProviderType(String providerType) { this.providerType = providerType; }
}
