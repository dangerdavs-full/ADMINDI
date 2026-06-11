package com.admindi.backend.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * Token de un solo uso para activación de una cuenta recién creada
 * (staff / agente inmobiliario / proveedor de mantenimiento).
 *
 * <p>La columna {@code token_hash} contiene SHA-256 hex del token emitido;
 * el token en claro nunca se persiste. El flujo de consumo:
 * <ol>
 *   <li>Se emite el token (32 bytes random → base64url) → cliente recibe URL.</li>
 *   <li>Cliente abre URL, backend calcula hash y busca la fila.</li>
 *   <li>Si {@code consumed_at} y {@code revoked_at} son null y {@code expires_at} &gt; now,
 *       el backend actualiza password del user, marca {@code consumed_at} y revoca
 *       todos los otros tokens pendientes del mismo user.</li>
 * </ol>
 */
@Entity
@Table(name = "user_activation_tokens")
public class UserActivationTokenEntity {

    @Id
    @Column(name = "id", length = 64, nullable = false)
    private String id;

    @Column(name = "user_id", length = 64, nullable = false)
    private String userId;

    @Column(name = "token_hash", length = 128, nullable = false, unique = true)
    private String tokenHash;

    /** Canal(es) por los que se entregó el token: EMAIL, WHATSAPP, BOTH. */
    @Column(name = "channel", length = 16, nullable = false)
    private String channel;

    @Column(name = "issued_at", nullable = false)
    private LocalDateTime issuedAt = LocalDateTime.now();

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Column(name = "consumed_at")
    private LocalDateTime consumedAt;

    @Column(name = "revoked_at")
    private LocalDateTime revokedAt;

    @Column(name = "issued_by", length = 255)
    private String issuedBy;

    @Column(name = "issued_ip", length = 64)
    private String issuedIp;

    @Column(name = "consumed_ip", length = 64)
    private String consumedIp;

    public UserActivationTokenEntity() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public String getTokenHash() { return tokenHash; }
    public void setTokenHash(String tokenHash) { this.tokenHash = tokenHash; }
    public String getChannel() { return channel; }
    public void setChannel(String channel) { this.channel = channel; }
    public LocalDateTime getIssuedAt() { return issuedAt; }
    public void setIssuedAt(LocalDateTime issuedAt) { this.issuedAt = issuedAt; }
    public LocalDateTime getExpiresAt() { return expiresAt; }
    public void setExpiresAt(LocalDateTime expiresAt) { this.expiresAt = expiresAt; }
    public LocalDateTime getConsumedAt() { return consumedAt; }
    public void setConsumedAt(LocalDateTime consumedAt) { this.consumedAt = consumedAt; }
    public LocalDateTime getRevokedAt() { return revokedAt; }
    public void setRevokedAt(LocalDateTime revokedAt) { this.revokedAt = revokedAt; }
    public String getIssuedBy() { return issuedBy; }
    public void setIssuedBy(String issuedBy) { this.issuedBy = issuedBy; }
    public String getIssuedIp() { return issuedIp; }
    public void setIssuedIp(String issuedIp) { this.issuedIp = issuedIp; }
    public String getConsumedIp() { return consumedIp; }
    public void setConsumedIp(String consumedIp) { this.consumedIp = consumedIp; }

    public boolean isUsable(LocalDateTime now) {
        return consumedAt == null
                && revokedAt == null
                && expiresAt != null
                && expiresAt.isAfter(now);
    }
}
