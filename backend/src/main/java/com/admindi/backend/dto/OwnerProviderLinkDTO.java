package com.admindi.backend.dto;

public class OwnerProviderLinkDTO {
    private String assignmentId;
    private String providerUserId;
    private String name;
    /** V50 — identificador de login canónico. */
    private String username;
    /** V50 — email queda como contacto opcional. */
    private String email;
    private String contactPhone;
    private String providerType;
    private String assignmentSource;
    private boolean assignmentActive;
    private String assignedAt;
    /**
     * V63 — indica si el agente completó su onboarding bancario: CLABE + banco
     * + titular guardados. Es distinto de {@code assignmentActive}: esto último
     * es la decisión del dueño de tenerlo vinculado; {@code accountActive} es
     * la elegibilidad técnica para recibir pagos. Ambos deben ser true para que
     * el dueño lo considere "listo para operar".
     */
    private boolean accountActive;
    /**
     * V63 — última vez que el agente inició sesión en la plataforma. Permite al
     * dueño detectar agentes "fantasma" (vinculados pero que nunca entraron).
     */
    private String lastSignInAt;

    public String getAssignmentId() { return assignmentId; }
    public void setAssignmentId(String assignmentId) { this.assignmentId = assignmentId; }
    public String getProviderUserId() { return providerUserId; }
    public void setProviderUserId(String providerUserId) { this.providerUserId = providerUserId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public String getContactPhone() { return contactPhone; }
    public void setContactPhone(String contactPhone) { this.contactPhone = contactPhone; }
    public String getProviderType() { return providerType; }
    public void setProviderType(String providerType) { this.providerType = providerType; }
    public String getAssignmentSource() { return assignmentSource; }
    public void setAssignmentSource(String assignmentSource) { this.assignmentSource = assignmentSource; }
    public boolean isAssignmentActive() { return assignmentActive; }
    public void setAssignmentActive(boolean assignmentActive) { this.assignmentActive = assignmentActive; }
    public String getAssignedAt() { return assignedAt; }
    public void setAssignedAt(String assignedAt) { this.assignedAt = assignedAt; }
    public boolean isAccountActive() { return accountActive; }
    public void setAccountActive(boolean accountActive) { this.accountActive = accountActive; }
    public String getLastSignInAt() { return lastSignInAt; }
    public void setLastSignInAt(String lastSignInAt) { this.lastSignInAt = lastSignInAt; }
}
