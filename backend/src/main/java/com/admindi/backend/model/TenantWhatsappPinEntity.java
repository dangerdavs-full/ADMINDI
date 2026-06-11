package com.admindi.backend.model;

import jakarta.persistence.*;

import java.time.LocalDateTime;

/**
 * NIP del inquilino para el chatbot de WhatsApp (Fase 3).
 *
 * No reemplaza al MFA del portal web (TOTP): es un segundo factor ligero
 * SOLO para WhatsApp, que se pide antes de confirmar operaciones sensibles
 * (subir comprobante, crear ticket). El hash usa BCrypt strength 12 (config
 * {@code whatsapp.pin.bcrypt-strength}).
 *
 * Ciclo de vida:
 *  - Primera interacción del inquilino → el bot pide "elige un NIP (4-6 dígitos)".
 *  - Guardamos hash + lo validamos en intentos subsecuentes.
 *  - 5 intentos fallidos → {@code lockedUntil} +30 min. Tras el lockout vuelve a
 *    aceptar intentos; el contador {@code failedAttempts} se resetea con un acierto.
 *  - SUPER_ADMIN u OWNER (dentro de su contexto) pueden resetear vía
 *    AccountRecoveryService.PIN_RESET — cae el hash y el inquilino debe fijar
 *    un nuevo NIP en la próxima interacción.
 */
@Entity
@Table(name = "tenant_whatsapp_pin")
public class TenantWhatsappPinEntity {

    @Id
    @Column(name = "user_id")
    private String userId;

    @Column(name = "pin_hash", nullable = false)
    private String pinHash;

    @Column(name = "failed_attempts", nullable = false)
    private int failedAttempts = 0;

    @Column(name = "locked_until")
    private LocalDateTime lockedUntil;

    @Column(name = "last_used_at")
    private LocalDateTime lastUsedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt = LocalDateTime.now();

    public TenantWhatsappPinEntity() {}

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public String getPinHash() { return pinHash; }
    public void setPinHash(String pinHash) { this.pinHash = pinHash; }
    public int getFailedAttempts() { return failedAttempts; }
    public void setFailedAttempts(int failedAttempts) { this.failedAttempts = failedAttempts; }
    public LocalDateTime getLockedUntil() { return lockedUntil; }
    public void setLockedUntil(LocalDateTime lockedUntil) { this.lockedUntil = lockedUntil; }
    public LocalDateTime getLastUsedAt() { return lastUsedAt; }
    public void setLastUsedAt(LocalDateTime lastUsedAt) { this.lastUsedAt = lastUsedAt; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }

    @PreUpdate
    public void touch() {
        this.updatedAt = LocalDateTime.now();
    }
}
