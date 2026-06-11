package com.admindi.backend.config;

import com.admindi.backend.model.Role;
import com.admindi.backend.model.UserEntity;
import com.admindi.backend.repository.UserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;

/**
 * Seeder del SUPER_ADMIN canonico.
 *
 * <p>V50 / rediseño de identidad: el SA se identifica únicamente por
 * {@code username}; la invariante de dominio es que SUPER_ADMIN no tiene
 * datos de contacto y no recibe notificaciones (ver
 * {@code DomainEventDispatcher.filterOutSuperAdmins} y
 * {@code docs/ARCHITECTURE_IDENTITY_AND_EXPEDIENTES.md §4.6}).</p>
 *
 * <p>V54: el campo {@code users.email} se eliminó de la tabla; el único email
 * del user vive en {@code contactEmail} — para SA queda NULL.</p>
 *
 * <p>Configuración:
 * <ul>
 *   <li>{@code app.admin.username} — username canonico. <b>V51: case-sensitive</b>,
 *       se conserva tal cual se define en properties (sólo {@code trim}).</li>
 *   <li>{@code app.admin.password} — password en claro para el bootstrap; el seeder
 *       lo codifica con BCrypt antes de persistir.</li>
 *   <li>{@code app.admin.name} — nombre visible. Default: "David Super Admin".</li>
 * </ul>
 * </p>
 *
 * <p>Comportamiento:
 * <ol>
 *   <li>Si no existe ninguna fila con el {@code username} configurado, crea la
 *       cuenta con {@code id} determinístico ({@code admin-0000-0000}) y
 *       {@code contactEmail=null}, {@code contactPhone=null}. La primera sesión
 *       NO exige cambio de password porque el plaintext viene de config con
 *       valor explícito del operador.</li>
 *   <li>Si ya existe, sólo re-impone banderas estructurales
 *       ({@code role=SUPER_ADMIN}, {@code active=true},
 *       {@code onboardingCompleted=true}) y limpia cualquier dato de contacto
 *       residual. NO sobrescribe password ni username.</li>
 * </ol>
 * </p>
 */
@Component
public class AdminSeeder implements CommandLineRunner {

    private static final Logger logger = LoggerFactory.getLogger(AdminSeeder.class);

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Value("${app.admin.username:DavidSuperAdmin-2026}")
    private String adminUsername;

    // Hardening: sin default hardcodeado en el repo. La contraseña real se
    // inyecta por ADMIN_PASSWORD o por el perfil "secrets"; si queda vacía el
    // seeder aborta en lugar de crear un SUPER_ADMIN con credencial conocida.
    @Value("${app.admin.password:}")
    private String adminPassword;

    @Value("${app.admin.name:David Super Admin}")
    private String adminName;

    public AdminSeeder(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public void run(String... args) {
        // V51 — case-sensitive: sólo trim, NO lowercase. El username del SA se
        // preserva tal como se definió en properties (ej. DavidSuperAdmin-2026).
        String normalizedUsername = adminUsername == null ? "" : adminUsername.trim();
        if (normalizedUsername.isBlank()) {
            logger.error("[ADMINDI] app.admin.username vacío; abortando seed del SUPER_ADMIN.");
            return;
        }

        if (adminPassword == null || adminPassword.isBlank()) {
            logger.error("[ADMINDI] app.admin.password vacío; abortando seed del SUPER_ADMIN. "
                    + "Define ADMIN_PASSWORD o app.admin.password en el perfil secrets.");
            return;
        }

        Optional<UserEntity> existing = userRepository.findByUsername(normalizedUsername);

        if (existing.isEmpty()) {
            UserEntity admin = new UserEntity();
            admin.setId("admin-0000-0000");
            admin.setLoginUsername(normalizedUsername);
            // Invariante: SUPER_ADMIN no tiene datos de contacto. V54: el campo
            // users.email ya no existe; contactEmail/contactPhone quedan null.
            admin.setContactEmail(null);
            admin.setContactPhone(null);
            admin.setPassword(passwordEncoder.encode(adminPassword));
            admin.setName(adminName != null && !adminName.isBlank() ? adminName : "Super Administrador");
            admin.setRole(Role.SUPER_ADMIN);
            // Bootstrap explícito del operador: no forzamos cambio de password
            // porque la credencial viene de config dedicada.
            admin.setMustChangePassword(false);
            admin.setOnboardingCompleted(true);
            admin.setActive(true);
            userRepository.save(admin);
            logger.info("[ADMINDI] SUPER_ADMIN sembrado con username='{}' (email NULL, sin contacto).", normalizedUsername);
            return;
        }

        // Reconciliación defensiva: impone invariantes sin tocar password/username.
        UserEntity admin = existing.get();
        boolean changed = false;
        if (admin.getRole() != Role.SUPER_ADMIN) {
            admin.setRole(Role.SUPER_ADMIN);
            changed = true;
        }
        if (!admin.isOnboardingCompleted()) {
            admin.setOnboardingCompleted(true);
            changed = true;
        }
        if (!admin.isActive()) {
            admin.setActive(true);
            changed = true;
        }
        if (admin.getContactEmail() != null) {
            admin.setContactEmail(null);
            changed = true;
        }
        if (admin.getContactPhone() != null) {
            admin.setContactPhone(null);
            changed = true;
        }
        if (changed) {
            userRepository.save(admin);
            logger.info("[ADMINDI] SUPER_ADMIN '{}' reconciliado (flags + limpieza de contacto).", normalizedUsername);
        } else {
            logger.info("[ADMINDI] SUPER_ADMIN '{}' ya consistente; no se realizaron cambios.", normalizedUsername);
        }
    }
}
