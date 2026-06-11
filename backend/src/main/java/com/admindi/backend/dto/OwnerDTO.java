package com.admindi.backend.dto;

public class OwnerDTO {
    private String id;
    private String username;           // login identifier (V48). Obligatorio en altas nuevas.
    private String name;
    private String phone;              // legacy/normalized phone (kept for compat)
    private String countryCode;        // used during creation for normalization
    private String rawPhone;           // raw digits input
    private String tempPassword;       // returned upon creation only

    // V54: un único email por user, en `contactEmail` (no único, compartible
    // entre cuentas). El campo histórico `email` se expone como alias vía
    // getter/setter — cualquier request con `"email"` escribe/lee sobre el
    // mismo backing field para no romper el contrato del frontend.
    private String contactEmail;
    private String contactPhone;       // normalized: +52XXXXXXXXXX
    private String contactCountryCode; // e.g. +52

    // --- Getters/Setters ---

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }

    /**
     * V54 — alias de {@link #getContactEmail()}. Mantiene compat con frontend/API
     * que seguía enviando el campo {@code email}.
     */
    public String getEmail() { return contactEmail; }
    /**
     * V54 — alias de {@link #setContactEmail(String)}. Si alguien envía ambos
     * campos ({@code email} y {@code contactEmail}), gana el último en llegar
     * al setter (Jackson los procesa en orden; el servicio es quien decide el
     * valor efectivo con normalización).
     */
    public void setEmail(String email) { this.contactEmail = email; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }

    public String getCountryCode() { return countryCode; }
    public void setCountryCode(String countryCode) { this.countryCode = countryCode; }

    public String getRawPhone() { return rawPhone; }
    public void setRawPhone(String rawPhone) { this.rawPhone = rawPhone; }

    public String getTempPassword() { return tempPassword; }
    public void setTempPassword(String tempPassword) { this.tempPassword = tempPassword; }

    public String getContactEmail() { return contactEmail; }
    public void setContactEmail(String contactEmail) { this.contactEmail = contactEmail; }

    public String getContactPhone() { return contactPhone; }
    public void setContactPhone(String contactPhone) { this.contactPhone = contactPhone; }

    public String getContactCountryCode() { return contactCountryCode; }
    public void setContactCountryCode(String contactCountryCode) { this.contactCountryCode = contactCountryCode; }

    /**
     * Normalize and build full phone from countryCode + rawPhone.
     */
    public String normalizePhone() {
        if (countryCode != null && rawPhone != null) {
            String digits = rawPhone.replaceAll("[^0-9]", "");
            String code = countryCode.startsWith("+") ? countryCode : "+" + countryCode;
            this.phone = code + digits;
        }
        return this.phone;
    }

    /**
     * Normalize contact phone from contactCountryCode + rawPhone.
     */
    public String normalizeContactPhone() {
        if (contactCountryCode != null && rawPhone != null) {
            String digits = rawPhone.replaceAll("[^0-9]", "");
            String code = contactCountryCode.startsWith("+") ? contactCountryCode : "+" + contactCountryCode;
            this.contactPhone = code + digits;
        }
        return this.contactPhone;
    }
}
