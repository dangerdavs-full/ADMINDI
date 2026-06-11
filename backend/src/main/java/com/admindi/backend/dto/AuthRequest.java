package com.admindi.backend.dto;

/**
 * Payload de login. V50/V51: {@code username} es el único identificador aceptado —
 * el campo {@code email} ya no existe en este DTO.
 *
 * <p>V51 — <b>case-sensitive</b>: el valor se recorta pero NO se convierte a
 * lowercase. {@code DavidSuperAdmin-2026} y {@code davidsuperadmin-2026} son dos
 * identidades distintas.</p>
 *
 * <p>{@code name} se conserva como campo opcional usado por algunos flujos de
 * alta self-service (activate / register) que reutilizan esta estructura; no
 * afecta al login.</p>
 */
public class AuthRequest {
    private String username;
    private String password;
    private String name;

    public AuthRequest() {}
    public AuthRequest(String username, String password) {
        this.username = username;
        this.password = password;
    }

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }

    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    /**
     * Identificador efectivo para login: el username trimmed (preserva case).
     * Devuelve {@code null} si el cliente no envió username.
     */
    public String getLoginIdentifier() {
        return (username != null && !username.isBlank()) ? username.trim() : null;
    }
}
