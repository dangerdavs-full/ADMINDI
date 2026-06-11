package com.admindi.backend.exception;

/**
 * Se lanza cuando un flujo de creación de cuenta (owner, tenant, staff,
 * proveedor) recibe un {@code username} que ya existe (activo, inactivo o
 * tombstoneado). La unicidad del username es global: la verificación se hace
 * via {@link com.admindi.backend.service.UsernameService#ensureAvailable(String)}.
 *
 * <p>El frontend debe reaccionar mostrando un mensaje claro al creador con la
 * sugerencia ({@link #getSuggestion()}) para que elija otro identificador sin
 * adivinar. Ver docs/ARCHITECTURE_IDENTITY_AND_EXPEDIENTES.md §3.1–§3.2.</p>
 */
public class UsernameTakenException extends RuntimeException {

    private final String requestedUsername;
    private final String suggestion;

    public UsernameTakenException(String requestedUsername, String suggestion) {
        super("El usuario '" + requestedUsername + "' ya está en uso. Sugerencia: " + suggestion);
        this.requestedUsername = requestedUsername;
        this.suggestion = suggestion;
    }

    public String getRequestedUsername() { return requestedUsername; }
    public String getSuggestion() { return suggestion; }
}
