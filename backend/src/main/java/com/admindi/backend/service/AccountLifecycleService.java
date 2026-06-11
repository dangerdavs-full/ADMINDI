package com.admindi.backend.service;

import com.admindi.backend.model.Role;
import com.admindi.backend.model.TenantProfileEntity;
import com.admindi.backend.model.UserEntity;
import com.admindi.backend.repository.TenantProfileRepository;
import com.admindi.backend.repository.UserRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * V48 / Bloque 3: punto único de decisión para dar de baja a cualquier cuenta
 * NO-OWNER / NO-SUPERADMIN. Unifica la regla:
 *
 * <ol>
 *   <li>Si la cuenta no tiene <b>trail contable ni operativo</b> (ni pagos, ni
 *       invoices, ni cotizaciones, ni egresos, ni expedientes archivados...)
 *       ⇒ <b>HARD_DELETE</b>: la fila se borra físicamente y el username queda
 *       libre de inmediato. V54 — email y phone ya eran libres.</li>
 *   <li>Si hay algún trail ⇒ <b>ARCHIVE</b>: la fila persiste pero
 *       {@code active=false}, {@code deleted_at} timestamp, password scrambled,
 *       MFA revocado, sesiones invalidadas, <b>username tombstoneado</b>
 *       ({@code username_tombstoned_at}) para liberarlo y permitir reutilización.
 *       El contactEmail queda en null por privacidad.</li>
 * </ol>
 *
 * <p>Este service NO reimplementa la cascada completa: delega a los servicios
 * ya probados ({@link TenantExpedienteArchiveService},
 * {@link OwnerCascadeDeletionService#deleteOrTombstoneStaffOrProvider(String)})
 * y añade la capa de <b>tombstone de username</b> que todavía falta en los
 * caminos legacy. Controlado por feature flag
 * {@code account.lifecycle.new} (default {@code false}); mientras esté apagado
 * los controllers siguen llamando a los servicios antiguos sin cambios.</p>
 *
 * <p>Ver {@code docs/ARCHITECTURE_IDENTITY_AND_EXPEDIENTES.md §4 Retención}.</p>
 */
@Service
public class AccountLifecycleService {

    private static final Logger log = LoggerFactory.getLogger(AccountLifecycleService.class);

    public enum LifecycleOutcome { HARD_DELETED, ARCHIVED }

    /**
     * V54: {@code originalContactEmail} reemplaza al antiguo {@code originalEmail}
     * porque el campo {@code users.email} fue eliminado. {@code tombstoneEmail}
     * desaparece del record — tras V54 sólo se tombstonea el username (es el
     * único campo único). El contactEmail del archivado queda null por privacidad.
     */
    public record DeletionResult(
            LifecycleOutcome outcome,
            String originalUsername,
            String originalContactEmail,
            String tombstoneUsername) {}

    private final UserRepository userRepository;
    private final TenantProfileRepository tenantProfileRepository;
    private final TenantExpedienteArchiveService tenantArchiveService;
    private final OwnerCascadeDeletionService ownerCascadeService;

    @PersistenceContext
    private EntityManager em;

    @Value("${account.lifecycle.new:false}")
    private boolean featureEnabled;

    public AccountLifecycleService(UserRepository userRepository,
                                   TenantProfileRepository tenantProfileRepository,
                                   TenantExpedienteArchiveService tenantArchiveService,
                                   OwnerCascadeDeletionService ownerCascadeService) {
        this.userRepository = userRepository;
        this.tenantProfileRepository = tenantProfileRepository;
        this.tenantArchiveService = tenantArchiveService;
        this.ownerCascadeService = ownerCascadeService;
    }

    public boolean isEnabled() { return featureEnabled; }

    /**
     * Da de baja una cuenta aplicando la regla unificada. Se asume que el caller
     * ya ejecutó reauth/MFA contextual (no duplicamos para que cada panel mantenga
     * su política de autorización pre-existente).
     *
     * @throws IllegalStateException si la cuenta es SUPER_ADMIN u OWNER: esos
     *         flujos se mantienen segregados por su criticidad.
     */
    @Transactional
    public DeletionResult deleteAccount(String userId, String reauthPassword, String reauthMfaCode) {
        UserEntity u = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("Usuario no encontrado: " + userId));
        Role r = u.getRole();
        if (r == Role.SUPER_ADMIN) {
            throw new IllegalStateException("SUPER_ADMIN no se elimina por esta vía.");
        }
        if (r == Role.OWNER) {
            throw new IllegalStateException(
                    "Para OWNER usa OwnerCascadeDeletionService.hardDeleteOwner.");
        }

        String originalUsername = u.getLoginUsername();
        String originalContactEmail = u.getContactEmail();

        if (r == Role.TENANT) {
            return deleteTenantUnified(u, originalUsername, originalContactEmail, reauthPassword, reauthMfaCode);
        }
        return deleteStaffOrProviderUnified(u, originalUsername, originalContactEmail);
    }

    // ─── TENANT ────────────────────────────────────────────────────────────────
    private DeletionResult deleteTenantUnified(UserEntity tenant,
                                               String originalUsername,
                                               String originalContactEmail,
                                               String reauthPassword,
                                               String reauthMfaCode) {
        List<TenantProfileEntity> activeProfiles =
                tenantProfileRepository.findByUserId(tenant.getId()).stream()
                        .filter(p -> p.getArchivedAt() == null)
                        .toList();
        boolean anyTrail = hasTenantPaymentTrail(tenant.getId())
                || !tenantProfileRepository.findByUserId(tenant.getId()).stream()
                        .filter(p -> p.getArchivedAt() != null).toList().isEmpty();

        for (TenantProfileEntity profile : activeProfiles) {
            // Delegamos al service especializado: genera snapshot si hay pagos,
            // termina leases, anula invoices, reabre vacancias, notifica.
            tenantArchiveService.archiveOperational(profile.getId(), reauthPassword, reauthMfaCode);
        }

        // Releer el user tras el archivado (puede haberlo dejado active=false si
        // ya no le queda expediente activo, ver archiveOperational:188).
        UserEntity refreshed = userRepository.findById(tenant.getId()).orElse(tenant);
        boolean stillExists = refreshed.getId() != null;

        // V54: detección de rama purgeWithoutTrace vía flags de archivo (active=false
        // y deletedAt timestamp). El marker histórico era un email "purged_..." pero
        // el campo ya no existe; usamos el triple active=false + deletedAt + contactEmail
        // wipeado como señal equivalente.
        boolean purgedMarker = refreshed.getDeletedAt() != null
                && !refreshed.isActive()
                && (refreshed.getContactEmail() == null || refreshed.getContactEmail().isBlank());

        if (!stillExists || (!anyTrail && purgedMarker)) {
            // El archiveService ya ejecutó la rama purgeWithoutTrace: la cuenta
            // quedó anonimizada sin trail y el username original queda libre
            // (tombstoneamos explícitamente para garantizar liberación).
            tombstoneUsernameIfPresent(refreshed, originalUsername);
            log.warn("[LIFECYCLE] TENANT hard-purge userId={} originalUsername={} originalContactEmail={}",
                    tenant.getId(), originalUsername, originalContactEmail);
            return new DeletionResult(LifecycleOutcome.HARD_DELETED, originalUsername, originalContactEmail, null);
        }

        // Rama ARCHIVE: expediente conservado. Tombstone username para liberar
        // el identificador único — el contactEmail queda null por privacidad.
        String newUsername = tombstoneUsernameIfPresent(refreshed, originalUsername);
        log.warn("[LIFECYCLE] TENANT archived userId={} originalUsername={} tombstoneUsername={}",
                tenant.getId(), originalUsername, newUsername);
        return new DeletionResult(LifecycleOutcome.ARCHIVED, originalUsername, originalContactEmail, newUsername);
    }

    // ─── STAFF / PROVIDER ─────────────────────────────────────────────────────
    private DeletionResult deleteStaffOrProviderUnified(UserEntity u,
                                                        String originalUsername,
                                                        String originalContactEmail) {
        OwnerCascadeDeletionService.StaffDeletionOutcome outcome =
                ownerCascadeService.deleteOrTombstoneStaffOrProvider(u.getId());
        if (outcome.hardDeleted()) {
            log.warn("[LIFECYCLE] STAFF hard-delete userId={} originalUsername={} originalContactEmail={}",
                    u.getId(), originalUsername, originalContactEmail);
            return new DeletionResult(LifecycleOutcome.HARD_DELETED,
                    originalUsername, originalContactEmail, null);
        }
        UserEntity refreshed = userRepository.findById(u.getId()).orElse(u);
        String newUsername = tombstoneUsernameIfPresent(refreshed, originalUsername);
        log.warn("[LIFECYCLE] STAFF archived userId={} originalUsername={} tombstoneUsername={}",
                u.getId(), originalUsername, newUsername);
        return new DeletionResult(LifecycleOutcome.ARCHIVED,
                originalUsername, originalContactEmail, newUsername);
    }

    // ─── Username tombstone ───────────────────────────────────────────────────
    /**
     * Renombra el username de una cuenta archivada para liberar el identificador
     * original. Formato determinista: {@code tombstone-{id8}-{yyyyMMddHHmmss}}.
     * Estampa {@code username_tombstoned_at} para auditar la liberación.
     */
    private String tombstoneUsernameIfPresent(UserEntity u, String originalUsername) {
        if (u == null || u.getId() == null) return null;
        // Releer fila fresca para no sobreescribir cambios del delegado.
        UserEntity fresh = userRepository.findById(u.getId()).orElse(null);
        if (fresh == null) return null;
        String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
        String shortId = fresh.getId().length() >= 8 ? fresh.getId().substring(0, 8) : fresh.getId();
        String tombstone = "tombstone-" + shortId + "-" + ts;
        if (originalUsername != null && originalUsername.equalsIgnoreCase(fresh.getLoginUsername())) {
            fresh.setLoginUsername(tombstone);
            fresh.setUsernameTombstonedAt(LocalDateTime.now());
            userRepository.save(fresh);
        }
        return tombstone;
    }

    // ─── Reglas de "trail" ─────────────────────────────────────────────────────
    /**
     * True si el tenant tiene algún rastro de pago/factura en la contabilidad.
     * Espejo de {@link TenantExpedienteArchiveService#hasAnyPaidHistory} pero a
     * nivel de user (no de profile individual): cualquier profile con historial
     * cuenta como trail global.
     */
    private boolean hasTenantPaymentTrail(String userId) {
        try {
            Number invoices = (Number) em.createNativeQuery(
                    "SELECT COUNT(*) FROM invoices i " +
                    "JOIN tenant_profiles p ON p.id = i.tenant_profile_id " +
                    "WHERE p.user_id = :uid AND i.status = 'PAID'")
                    .setParameter("uid", userId).getSingleResult();
            if (invoices != null && invoices.longValue() > 0) return true;
        } catch (Exception e) { log.debug("[LIFECYCLE] invoice trail probe failed: {}", e.getMessage()); }
        try {
            Number payments = (Number) em.createNativeQuery(
                    "SELECT COUNT(*) FROM payments pay " +
                    "JOIN tenant_profiles p ON p.id = pay.tenant_profile_id " +
                    "WHERE p.user_id = :uid")
                    .setParameter("uid", userId).getSingleResult();
            if (payments != null && payments.longValue() > 0) return true;
        } catch (Exception e) { log.debug("[LIFECYCLE] payment trail probe failed: {}", e.getMessage()); }
        return false;
    }
}
