package com.admindi.backend.controller;

/**
 * Payload para verificación MFA (/auth/verify-mfa).
 *
 * <p>V50 — el identificador de la cuenta en verificación es {@code username}, no
 * email. El campo {@code email} fue eliminado para evitar flujos de login
 * residuales por correo. Un campo legacy {@code getEmail()} se deja como alias
 * read-only apuntando a {@code username} para no romper integraciones externas
 * que todavía envíen la clave antigua (aceptamos ambos keys en JSON mediante
 * {@code setEmail(String)}).</p>
 */
public class MfaRequest {
    private String code;
    private String username;
    private String challengeToken;

    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }

    public String getChallengeToken() { return challengeToken; }
    public void setChallengeToken(String challengeToken) { this.challengeToken = challengeToken; }

    /**
     * Alias deprecado: si un cliente viejo envía {@code "email": "..."} lo
     * interpretamos como username. Sólo para transición; los clientes nuevos
     * deben enviar {@code "username"}.
     */
    @Deprecated
    public void setEmail(String email) {
        if (email != null && (this.username == null || this.username.isBlank())) {
            this.username = email;
        }
    }
}
