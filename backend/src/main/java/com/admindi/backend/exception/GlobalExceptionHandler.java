package com.admindi.backend.exception;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(UsernameTakenException.class)
    public ResponseEntity<Map<String, Object>> handleUsernameTaken(UsernameTakenException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of(
                        "error", "USERNAME_TAKEN",
                        "message", ex.getMessage(),
                        "requestedUsername", ex.getRequestedUsername(),
                        "suggestion", ex.getSuggestion()
                ));
    }

    /**
     * Spring Security {@code @PreAuthorize} failures throw AccessDeniedException (extends RuntimeException).
     * Must be handled before the generic RuntimeException handler so clients get 403, not 400.
     */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Map<String, String>> handleAccessDenied(AccessDeniedException ex) {
        String msg = ex.getMessage();
        if (msg == null || msg.isBlank()) {
            msg = "Access Denied";
        }
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(Map.of("message", msg));
    }

    /**
     * Explicit HTTP status from controllers/services (must not be downgraded to 400).
     */
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, String>> handleResponseStatus(ResponseStatusException ex) {
        String msg = ex.getReason();
        if (msg == null || msg.isBlank()) {
            msg = ex.getStatusCode().toString();
        }
        return ResponseEntity.status(ex.getStatusCode())
                .body(Map.of("message", msg));
    }

    /**
     * V54 — red de seguridad: sólo quedan constraints únicos sobre `username`.
     * Email y teléfono dejaron de ser únicos; cualquier violación residual debe
     * ser username. Si no coincide, devolvemos 400 con el mensaje SQL para que
     * operaciones lo investigue.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<Map<String, Object>> handleDataIntegrity(DataIntegrityViolationException ex) {
        String root = ex.getMostSpecificCause() != null
                ? ex.getMostSpecificCause().getMessage() : ex.getMessage();
        String rootLc = root == null ? "" : root.toLowerCase();
        if (rootLc.contains("idx_users_username_unique")) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "error", "USERNAME_TAKEN",
                    "message", "El nombre de usuario ya está registrado en otra cuenta."
            ));
        }
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of(
                "error", "DATA_INTEGRITY_VIOLATION",
                "message", root != null ? root : "Violación de integridad de datos."
        ));
    }

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<Map<String, String>> handleRuntimeException(RuntimeException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("message", ex.getMessage()));
    }
}
