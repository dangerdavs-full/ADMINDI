package com.admindi.backend.model;

import com.admindi.backend.config.EncryptedStringConverter;
import jakarta.persistence.*;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Entity
@Table(name = "users")
public class UserEntity implements UserDetails {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(name = "provider_id")
    private String providerId;

    @Column(name = "owner_id")
    private String ownerId;

    // Identificador de login (V48). UNIQUE global. Ver docs/ARCHITECTURE_IDENTITY_AND_EXPEDIENTES.md §3.2.
    // Obligatorio en toda alta: UserService/OwnerService/TenantService/StaffService/MaintenanceProviderService
    // exigen username explícito desde Bloque 2. El índice unique vive en V48.
    @Column(name = "username", nullable = false, length = 64)
    private String username;

    // Cuando se archiva una cuenta liberando el username, se renombra a
    // "tombstone-<id8>-<ts>" y se registra el momento aquí. La fila sigue existiendo
    // (integridad referencial del rastro contable) pero su username original queda libre.
    @Column(name = "username_tombstoned_at")
    private java.time.LocalDateTime usernameTombstonedAt;

    // V54: el campo `email` desaparece. El único email del user vive en
    // `contactEmail` (más abajo), que es dato de contacto/notificación —
    // NO único, NO identificador de login. El login es `username` (V48) y
    // la password es exclusiva del user.
    // Ver docs/ARCHITECTURE_IDENTITY_AND_EXPEDIENTES.md §3.3.

    @Column(nullable = false)
    private String password;

    @Column(nullable = false)
    private String name;

    @Column(name = "phone")
    @Convert(converter = EncryptedStringConverter.class)
    private String phone;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Role role;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "user_permissions", joinColumns = @JoinColumn(name = "user_id"))
    @Column(name = "permission")
    private Set<String> permissions = new HashSet<>();

    @Column(name = "provider_type")
    private String providerType;

    @Column(nullable = false, name = "must_change_password", columnDefinition = "boolean default true")
    private boolean mustChangePassword = true;

    @Column(name = "mfa_enabled", columnDefinition = "boolean default false")
    private boolean mfaEnabled = false;

    @Column(name = "mfa_secret")
    @Convert(converter = EncryptedStringConverter.class)
    private String mfaSecret;

    @Column(name = "onboarding_completed", columnDefinition = "boolean default false")
    private boolean onboardingCompleted = false;

    @Column(name = "use_platform_maintenance")
    private Boolean usePlatformMaintenance;

    @Column(name = "use_platform_agents")
    private Boolean usePlatformAgents;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    @Column(name = "deleted_at")
    private java.time.LocalDateTime deletedAt;

    // V54: contact_email es el ÚNICO email del user. NO único — varios users
    // pueden compartirlo. Se cifra columnwise (AES-GCM) sólo por confidencialidad
    // en DB, no por necesidad de integridad referencial.
    @Column(name = "contact_email")
    @Convert(converter = EncryptedStringConverter.class)
    private String contactEmail;

    @Column(name = "contact_phone")
    @Convert(converter = EncryptedStringConverter.class)
    private String contactPhone;

    @Column(name = "contact_country_code")
    @Convert(converter = EncryptedStringConverter.class)
    private String contactCountryCode;

    // Datos bancarios del dueño para recibir transferencias SPEI (Fase 1 notificaciones).
    // Solo aplica a role=OWNER; el resto lo deja null. Cifrados a nivel columna porque una
    // CLABE en claro permite a un atacante con lectura de DB suplantar transferencias o
    // enriquecer phishing con datos reales. Longitud 512 por overhead del cifrado.
    @Column(name = "clabe")
    @Convert(converter = EncryptedStringConverter.class)
    private String clabe;

    @Column(name = "bank_name")
    @Convert(converter = EncryptedStringConverter.class)
    private String bankName;

    @Column(name = "account_holder_name")
    @Convert(converter = EncryptedStringConverter.class)
    private String accountHolderName;

    /** Mercado Pago — ID público del vendedor (collector). */
    @Column(name = "mp_user_id")
    private String mpUserId;

    @Column(name = "mp_access_token")
    @Convert(converter = EncryptedStringConverter.class)
    private String mpAccessToken;

    @Column(name = "mp_refresh_token")
    @Convert(converter = EncryptedStringConverter.class)
    private String mpRefreshToken;

    @Column(name = "mp_token_expires_at")
    private java.time.LocalDateTime mpTokenExpiresAt;

    @Column(name = "mp_connected_at")
    private java.time.LocalDateTime mpConnectedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private java.time.LocalDateTime createdAt = java.time.LocalDateTime.now();

    public UserEntity() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    
    public String getOwnerId() {
        return ownerId;
    }

    public void setOwnerId(String ownerId) {
        this.ownerId = ownerId;
    }

    public String getLoginUsername() { return username; }
    public void setLoginUsername(String username) { this.username = username; }

    public java.time.LocalDateTime getUsernameTombstonedAt() { return usernameTombstonedAt; }
    public void setUsernameTombstonedAt(java.time.LocalDateTime usernameTombstonedAt) { this.usernameTombstonedAt = usernameTombstonedAt; }
    public void setPassword(String password) { this.password = password; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }
    public Role getRole() { return role; }
    public void setRole(Role role) { this.role = role; }
    public boolean isMustChangePassword() { return mustChangePassword; }
    public void setMustChangePassword(boolean mustChangePassword) { this.mustChangePassword = mustChangePassword; }
    public Set<String> getPermissions() { return permissions; }
    public void setPermissions(Set<String> permissions) { this.permissions = permissions; }
    public String getProviderType() { return providerType; }
    public void setProviderType(String providerType) { this.providerType = providerType; }
    public boolean isMfaEnabled() { return mfaEnabled; }
    public void setMfaEnabled(boolean mfaEnabled) { this.mfaEnabled = mfaEnabled; }
    public String getMfaSecret() { return mfaSecret; }
    public void setMfaSecret(String mfaSecret) { this.mfaSecret = mfaSecret; }
    public boolean isOnboardingCompleted() { return onboardingCompleted; }
    public void setOnboardingCompleted(boolean onboardingCompleted) { this.onboardingCompleted = onboardingCompleted; }
    public Boolean getUsePlatformMaintenance() { return usePlatformMaintenance; }
    public void setUsePlatformMaintenance(Boolean usePlatformMaintenance) { this.usePlatformMaintenance = usePlatformMaintenance; }
    public Boolean getUsePlatformAgents() { return usePlatformAgents; }
    public void setUsePlatformAgents(Boolean usePlatformAgents) { this.usePlatformAgents = usePlatformAgents; }
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
    public java.time.LocalDateTime getDeletedAt() { return deletedAt; }
    public void setDeletedAt(java.time.LocalDateTime deletedAt) { this.deletedAt = deletedAt; }
    public String getContactEmail() { return contactEmail; }
    public void setContactEmail(String contactEmail) { this.contactEmail = contactEmail; }

    public String getContactPhone() { return contactPhone; }
    public void setContactPhone(String contactPhone) { this.contactPhone = contactPhone; }
    public String getContactCountryCode() { return contactCountryCode; }
    public void setContactCountryCode(String contactCountryCode) { this.contactCountryCode = contactCountryCode; }
    public String getClabe() { return clabe; }
    public void setClabe(String clabe) { this.clabe = clabe; }
    public String getBankName() { return bankName; }
    public void setBankName(String bankName) { this.bankName = bankName; }
    public String getAccountHolderName() { return accountHolderName; }
    public void setAccountHolderName(String accountHolderName) { this.accountHolderName = accountHolderName; }

    public String getMpUserId() { return mpUserId; }
    public void setMpUserId(String mpUserId) { this.mpUserId = mpUserId; }
    public String getMpAccessToken() { return mpAccessToken; }
    public void setMpAccessToken(String mpAccessToken) { this.mpAccessToken = mpAccessToken; }
    public String getMpRefreshToken() { return mpRefreshToken; }
    public void setMpRefreshToken(String mpRefreshToken) { this.mpRefreshToken = mpRefreshToken; }
    public java.time.LocalDateTime getMpTokenExpiresAt() { return mpTokenExpiresAt; }
    public void setMpTokenExpiresAt(java.time.LocalDateTime mpTokenExpiresAt) { this.mpTokenExpiresAt = mpTokenExpiresAt; }
    public java.time.LocalDateTime getMpConnectedAt() { return mpConnectedAt; }
    public void setMpConnectedAt(java.time.LocalDateTime mpConnectedAt) { this.mpConnectedAt = mpConnectedAt; }

    public boolean hasMercadoPagoConnected() {
        return mpAccessToken != null && !mpAccessToken.isBlank();
    }

    public java.time.LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(java.time.LocalDateTime createdAt) { this.createdAt = createdAt; }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        List<GrantedAuthority> authorities = new java.util.ArrayList<>();
        authorities.add(new SimpleGrantedAuthority("ROLE_" + role.name()));
        if (permissions != null && !permissions.isEmpty()) {
            // Expand each direct permission to include its legacy UPPER_UNDERSCORE aliases
            // so @PreAuthorize annotations written against either convention work for
            // users whose permissions live on the user row (not via template).
            for (String p : com.admindi.backend.security.PermissionAliasResolver.expand(permissions)) {
                authorities.add(new SimpleGrantedAuthority(p));
            }
        }
        return authorities;
    }

    @Override
    public String getPassword() { return this.password; }

    /**
     * Identificador de login usado por Spring Security, JWT subject y
     * {@link org.springframework.security.core.Authentication#getName()}.
     * V54: siempre devuelve {@code username}. El campo es NOT NULL en DB y en
     * toda ruta de alta. El antiguo fallback a email desapareció con el borrado
     * del campo — si alguna fila queda sin username es un bug de datos.
     */
    @Override
    public String getUsername() {
        return this.username;
    }

    @Override
    public boolean isAccountNonExpired() { return true; }

    @Override
    public boolean isAccountNonLocked() { return true; }

    @Override
    public boolean isCredentialsNonExpired() { return true; }

    @Override
    public boolean isEnabled() { return this.active; }
}
