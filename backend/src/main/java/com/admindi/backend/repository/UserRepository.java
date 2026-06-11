package com.admindi.backend.repository;

import com.admindi.backend.model.UserEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface UserRepository extends JpaRepository<UserEntity, String> {

    // ── Identidad: username es el ÚNICO identificador de login ─────────────
    //
    // V54: el campo `email` desapareció por completo de users. El único
    // correo del user vive en `contact_email` (cifrado AES-GCM, NO único).
    // · username: identificador de login canónico, case-sensitive, UNIQUE global (V48).
    // · password: única del user.
    // · contact_email / phone / contact_phone: datos de contacto, NO únicos.
    //   Varios users pueden compartirlos. NO sirven para login ni lookup
    //   autoritativo de identidad.
    Optional<UserEntity> findByUsername(String username);
    boolean existsByUsername(String username);

    /**
     * Resuelve un usuario a partir del identificador de login producido por el
     * contexto de seguridad (típicamente {@code Authentication#getName()}).
     *
     * <p>V54 — la identidad de login es exclusivamente {@code username}
     * (case-sensitive, UNIQUE global desde V48). No existe fallback a email:
     * el campo se eliminó de la tabla users. Los JWT emitidos desde V48
     * llevan {@code sub = username}; tokens legacy con {@code sub = email}
     * ya no son soportados.</p>
     */
    default Optional<UserEntity> findByLoginIdentifier(String identifier) {
        if (identifier == null || identifier.isBlank()) return Optional.empty();
        return findByUsername(identifier.trim());
    }

    // Staff/tenant lookup by owner context
    List<UserEntity> findByOwnerId(String ownerId);

    /**
     * V55 — WhatsApp bot: conjunto acotado de users por role para hacer lookup
     * por teléfono descifrado en memoria. El campo {@code phone} está cifrado
     * columnwise (AES-GCM), por lo que no se puede filtrar por SQL.
     *
     * <p>Se usa desde {@code PhoneIdentityResolver}: filtra role=TENANT activo,
     * descifra cada phone y compara contra el E.164 recibido por webhook.</p>
     */
    List<UserEntity> findByRoleAndActiveTrue(com.admindi.backend.model.Role role);

    /** Bot WhatsApp: detectar teléfonos de cuentas desactivadas (mensaje distinto a "desconocido"). */
    List<UserEntity> findByRoleAndActiveFalse(com.admindi.backend.model.Role role);

    // Motor de Búsqueda Root — solo activos. V54: el campo email desapareció
    // y contact_email está cifrado (ciphertext no-determinístico), por lo que
    // LIKE contra ese campo no es viable. La búsqueda queda sobre name y phone;
    // para búsquedas por username/contacto exactos existen endpoints dedicados.
    @org.springframework.data.jpa.repository.Query("SELECT u FROM UserEntity u WHERE u.active = true AND (" +
           "LOWER(COALESCE(u.name, '')) LIKE LOWER(CONCAT('%', :query, '%')) " +
           "OR LOWER(COALESCE(u.phone, '')) LIKE LOWER(CONCAT('%', :query, '%'))) " +
           "ORDER BY u.name ASC")
    java.util.List<UserEntity> searchAllByQuery(String query, org.springframework.data.domain.Pageable pageable);

    // Search including inactive (SUPER_ADMIN explicit flag).
    @org.springframework.data.jpa.repository.Query("SELECT u FROM UserEntity u WHERE " +
           "LOWER(COALESCE(u.name, '')) LIKE LOWER(CONCAT('%', :query, '%')) " +
           "OR LOWER(COALESCE(u.phone, '')) LIKE LOWER(CONCAT('%', :query, '%')) " +
           "ORDER BY u.name ASC")
    java.util.List<UserEntity> searchAllIncludingInactive(String query, org.springframework.data.domain.Pageable pageable);
}
