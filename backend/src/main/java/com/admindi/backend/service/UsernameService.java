package com.admindi.backend.service;

import com.admindi.backend.exception.UsernameTakenException;
import com.admindi.backend.model.UserEntity;
import com.admindi.backend.repository.UserRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Username management (V48 / Bloque 2, V51 — case-sensitive).
 *
 * <p>Dos responsabilidades únicas:</p>
 * <ol>
 *   <li>Normalizar un username candidato: <b>SOLO trim</b> (se preserva el case
 *       original). Valida patrón y longitud.</li>
 *   <li>Validar unicidad global. Si el caller no envía username explícito, este
 *       servicio <b>no</b> lo genera automáticamente: lanza
 *       {@link UsernameTakenException} con sugerencia para que el creador
 *       (OWNER / PROPERTY_ADMIN) elija conscientemente.</li>
 * </ol>
 *
 * <p><b>V51 — Case-sensitive usernames.</b> Por decisión explícita del product
 * owner, los usernames ahora preservan mayúsculas/minúsculas y son comparados
 * case-sensitive tanto en el índice UNIQUE de Postgres (default collation lo es)
 * como en todas las rutas de lookup. Esto amplía el espacio de usernames
 * disponibles (p.ej. {@code DavidSuperAdmin-2026} y {@code davidsuperadmin-2026}
 * son usernames distintos).</p>
 *
 * <p><b>Consideración de seguridad (homograph/phishing):</b> case-sensitive
 * permite que dos cuentas coexistan con grafías visualmente casi idénticas (ej.
 * {@code Admin} vs {@code admin}). Para mitigar en capas superiores: (a) la UI
 * debe mostrar el username tal como se creó (sin re-capitalizar); (b) flujos de
 * support/reset deben exigir identificador exacto, no "empieza con". Ver
 * docs/ARCHITECTURE_IDENTITY_AND_EXPEDIENTES.md §3.2 y §7.3.</p>
 */
@Service
public class UsernameService {

    private static final int MIN_LENGTH = 3;
    private static final int MAX_LENGTH = 64;
    // V51 — case-sensitive: acepta A-Z además de a-z. La validación sigue
    // exigiendo iniciar con letra o número y permitir ._- como separadores.
    private static final java.util.regex.Pattern VALID_PATTERN =
            java.util.regex.Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]{2,63}$");

    private final UserRepository userRepository;

    public UsernameService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    /**
     * Normaliza un username: solo {@code trim()}, preserva case. Valida patrón
     * y longitud. NO verifica unicidad (ver {@link #ensureAvailable(String)}).
     */
    public String normalize(String raw) {
        if (raw == null) {
            throw new IllegalArgumentException("Username requerido.");
        }
        String normalized = raw.trim();
        if (normalized.length() < MIN_LENGTH || normalized.length() > MAX_LENGTH) {
            throw new IllegalArgumentException(
                    "Username debe tener entre " + MIN_LENGTH + " y " + MAX_LENGTH + " caracteres.");
        }
        if (!VALID_PATTERN.matcher(normalized).matches()) {
            throw new IllegalArgumentException(
                    "Username inválido. Usa letras (mayúsculas o minúsculas), números, punto, guión o guión bajo (debe iniciar con letra o número).");
        }
        return normalized;
    }

    public boolean isAvailable(String username) {
        String normalized = normalize(username);
        return !userRepository.existsByUsername(normalized);
    }

    /**
     * Normaliza, valida formato y comprueba que no esté ocupado. Si está
     * ocupado, lanza {@link UsernameTakenException} con una sugerencia
     * determinística basada en el username solicitado.
     */
    public String ensureAvailable(String candidate) {
        String normalized = normalize(candidate);
        if (userRepository.existsByUsername(normalized)) {
            throw new UsernameTakenException(normalized, suggest(normalized));
        }
        return normalized;
    }

    /**
     * Resuelve el username efectivo en flujos de creación legacy donde el DTO
     * todavía no exige el campo. Prioridad:
     * <ol>
     *   <li>{@code explicit} si viene (se normaliza + valida unicidad).</li>
     *   <li>Deriva de {@code fallbackEmail} (ej. {@code juan@mail.com} → {@code juan}).</li>
     * </ol>
     * Si el derivado colisiona, le añade un sufijo aleatorio de 4 hex para no
     * romper el alta. Este modo fallback existe solo durante la transición;
     * los controllers nuevos SIEMPRE deben pedir username explícito.
     */
    public String resolveOrDerive(String explicit, String fallbackEmail) {
        if (explicit != null && !explicit.isBlank()) {
            return ensureAvailable(explicit);
        }
        if (fallbackEmail == null || fallbackEmail.isBlank()) {
            throw new IllegalArgumentException("Se requiere username o email para derivar identificador.");
        }
        // V51 — al derivar de email sí lowercaseamos: los emails son
        // case-insensitive por RFC 5321 y tratar `Juan@mail` y `juan@mail`
        // como dos identidades distintas generaría duplicados inesperados en
        // el fallback legacy. El caller siempre puede sobreescribir con un
        // username explícito que preserve case.
        String base = fallbackEmail.trim().toLowerCase();
        int at = base.indexOf('@');
        String candidate = at > 0 ? base.substring(0, at) : base;
        candidate = candidate.replaceAll("[^A-Za-z0-9._-]", "-");
        if (candidate.length() < MIN_LENGTH) {
            candidate = "user-" + candidate;
        }
        if (candidate.length() > MAX_LENGTH - 5) {
            candidate = candidate.substring(0, MAX_LENGTH - 5);
        }
        String attempt = candidate;
        int retries = 0;
        while (userRepository.existsByUsername(attempt)) {
            if (retries++ > 8) {
                throw new IllegalStateException("No se pudo generar username único automáticamente; envía uno explícito.");
            }
            attempt = candidate + "-" + Integer.toHexString(new java.security.SecureRandom().nextInt(0x10000));
        }
        return attempt;
    }

    /**
     * Tombstonea el username de una cuenta dada de baja: lo renombra a un
     * placeholder determinista y estampa {@code username_tombstoned_at} para
     * auditar la liberación. El username original queda disponible de inmediato
     * (no está sujeto al índice UNIQUE del placeholder).
     *
     * <p>Invariante de dominio: cualquier ruta que archive o purgue una cuenta
     * (tenant con o sin historial, staff/proveedor con actividad, cascada del
     * dueño sobre usuarios relacionados que no se hard-deletean) debe llamar a
     * este método. El identificador de login nunca queda ocupado por una fila
     * inactiva.</p>
     *
     * <p>Idempotente: si el username ya fue tombstoneado previamente (empieza
     * con {@code "tombstone-"} o {@code username_tombstoned_at} no es nulo) no
     * lo renombra de nuevo. Devuelve el username placeholder vigente, o
     * {@code null} si la fila no existe.</p>
     */
    public String tombstoneUsername(UserEntity u) {
        if (u == null || u.getId() == null) return null;
        String current = u.getLoginUsername();
        if (current != null && current.startsWith("tombstone-")) {
            return current;
        }
        if (u.getUsernameTombstonedAt() != null && current != null) {
            return current;
        }
        String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
        String shortId = u.getId().length() >= 8 ? u.getId().substring(0, 8) : u.getId();
        String placeholder = "tombstone-" + shortId + "-" + ts;
        u.setLoginUsername(placeholder);
        u.setUsernameTombstonedAt(LocalDateTime.now());
        userRepository.save(u);
        return placeholder;
    }

    private String suggest(String taken) {
        // Sugerencia determinística: append -<1..9>; si todas existen, random suffix.
        for (int i = 2; i <= 9; i++) {
            String s = taken + "-" + i;
            if (!userRepository.existsByUsername(s)) {
                return s;
            }
        }
        return taken + "-" + Integer.toHexString(new java.security.SecureRandom().nextInt(0x10000));
    }
}
