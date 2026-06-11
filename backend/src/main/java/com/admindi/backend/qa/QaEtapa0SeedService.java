package com.admindi.backend.qa;

import com.admindi.backend.model.LeaseEntity;
import com.admindi.backend.model.LeaseStatus;
import com.admindi.backend.model.OwnerMembershipEntity;
import com.admindi.backend.model.PermissionGrantEntity;
import com.admindi.backend.model.PropertyEntity;
import com.admindi.backend.model.PropertyStatus;
import com.admindi.backend.model.Role;
import com.admindi.backend.model.TenantProfileEntity;
import com.admindi.backend.model.UserEntity;
import com.admindi.backend.repository.LeaseRepository;
import com.admindi.backend.repository.OwnerMembershipRepository;
import com.admindi.backend.repository.PermissionGrantRepository;
import com.admindi.backend.repository.PropertyRepository;
import com.admindi.backend.repository.TenantProfileRepository;
import com.admindi.backend.config.AdmindiQaSeedProperties;
import com.admindi.backend.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Semilla idempotente usuarios y datos minimos para smoke Etapa 0 (solo no-prod con propiedad habilitada).
 */
@Service
public class QaEtapa0SeedService {

    private static final Logger log = LoggerFactory.getLogger(QaEtapa0SeedService.class);

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final OwnerMembershipRepository ownerMembershipRepository;
    private final PropertyRepository propertyRepository;
    private final TenantProfileRepository tenantProfileRepository;
    private final LeaseRepository leaseRepository;
    private final PermissionGrantRepository permissionGrantRepository;
    private final AdmindiQaSeedProperties qaSeedProperties;

    public static final String TENANT_MULTI_USER_ID = "e0e00000-0000-4000-8000-000000000101";
    public static final String TENANT_ARCHIVE_USER_ID = "e0e00000-0000-4000-8000-000000000102";
    public static final String STAFF_PROP_ADMIN_ID = "e0e00000-0000-4000-8000-000000000201";
    public static final String STAFF_ACCOUNTANT_ID = "e0e00000-0000-4000-8000-000000000202";
    public static final String SUPERADMIN_QA_ID = "e0e00000-0000-4000-8000-000000000301";

    public QaEtapa0SeedService(
            UserRepository userRepository,
            PasswordEncoder passwordEncoder,
            OwnerMembershipRepository ownerMembershipRepository,
            PropertyRepository propertyRepository,
            TenantProfileRepository tenantProfileRepository,
            LeaseRepository leaseRepository,
            PermissionGrantRepository permissionGrantRepository,
            AdmindiQaSeedProperties qaSeedProperties) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.ownerMembershipRepository = ownerMembershipRepository;
        this.propertyRepository = propertyRepository;
        this.tenantProfileRepository = tenantProfileRepository;
        this.leaseRepository = leaseRepository;
        this.permissionGrantRepository = permissionGrantRepository;
        this.qaSeedProperties = qaSeedProperties;
    }

    @Transactional
    public void seedAll() {
        String enc = passwordEncoder.encode(QaEtapa0Constants.QA_PASSWORD_PLAINTEXT);

        // OWNER exige MFA en AuthService: sin secreto el login solo devuelve challenge de "setup" y /mfa/verify falla.
        // Mismo secreto Base32 que staff/superadmin QA para smoke (RUTA A: SMOKE_ARCHIVE_MFA_CODE con oathtool).
        UserEntity ownerAlpha = ensureUser(QaEtapa0Constants.OWNER_ALPHA_ID, QaEtapa0Constants.OWNER_ALPHA_EMAIL,
                "QA Owner Alpha", Role.OWNER, QaEtapa0Constants.OWNER_ALPHA_ID, enc, false, true, true, QaEtapa0Constants.QA_MFA_SECRET_BASE32);
        UserEntity ownerBravo = ensureUser(QaEtapa0Constants.OWNER_BRAVO_ID, QaEtapa0Constants.OWNER_BRAVO_EMAIL,
                "QA Owner Bravo", Role.OWNER, QaEtapa0Constants.OWNER_BRAVO_ID, enc, false, true, true, QaEtapa0Constants.QA_MFA_SECRET_BASE32);
        UserEntity ownerArchive = ensureUser(QaEtapa0Constants.OWNER_ARCHIVE_ID, QaEtapa0Constants.OWNER_ARCHIVE_EMAIL,
                "QA Owner Archive", Role.OWNER, QaEtapa0Constants.OWNER_ARCHIVE_ID, enc, false, true, true, QaEtapa0Constants.QA_MFA_SECRET_BASE32);

        // BD previa: propiedades/perfiles/leases apuntaban a UUID fijos de documentacion; los usuarios OWNER reales son otros ids.
        relinkLegacyQaOwnerRows(QaEtapa0Constants.OWNER_ALPHA_ID, ownerAlpha.getId());
        relinkLegacyQaOwnerRows(QaEtapa0Constants.OWNER_BRAVO_ID, ownerBravo.getId());
        relinkLegacyQaOwnerRows(QaEtapa0Constants.OWNER_ARCHIVE_ID, ownerArchive.getId());

        PropertyEntity propAlpha = ensurePropertyByOwnerAndName(ownerAlpha.getId(), "QA Prop Alpha", PropertyStatus.OCCUPIED);
        PropertyEntity propBravo = ensurePropertyByOwnerAndName(ownerBravo.getId(), "QA Prop Bravo", PropertyStatus.OCCUPIED);
        PropertyEntity propArchive = ensurePropertyByOwnerAndName(ownerArchive.getId(), "QA Prop Archive", PropertyStatus.OCCUPIED);
        propertyRepository.flush();
        userRepository.flush();

        UserEntity tenantMulti = ensureUser(TENANT_MULTI_USER_ID, QaEtapa0Constants.TENANT_MULTI_EMAIL,
                "QA Tenant Multi", Role.TENANT, null, enc, false, true, false, null);
        ensureMembership(tenantMulti.getId(), ownerAlpha.getId());
        ensureMembership(tenantMulti.getId(), ownerBravo.getId());
        ensureTenantExpediente(tenantMulti.getId(), ownerAlpha.getId(), propAlpha.getId());
        ensureTenantExpediente(tenantMulti.getId(), ownerBravo.getId(), propBravo.getId());
        syncTenantPointer(tenantMulti);

        UserEntity tenantArchive = ensureUser(TENANT_ARCHIVE_USER_ID, QaEtapa0Constants.TENANT_ARCHIVE_USER_EMAIL,
                "QA Tenant Archive", Role.TENANT, null, enc, false, true, false, null);
        ensureMembership(tenantArchive.getId(), ownerArchive.getId());
        repairArchiveExpediente(tenantArchive.getId(), ownerArchive.getId(), propArchive.getId());
        syncTenantPointer(tenantArchive);

        UserEntity superMfa = ensureUser(SUPERADMIN_QA_ID, QaEtapa0Constants.SUPERADMIN_MFA_EMAIL,
                "QA Super MFA", Role.SUPER_ADMIN, null, enc, false, true, true, QaEtapa0Constants.QA_MFA_SECRET_BASE32);

        UserEntity propAdmin = ensureUser(STAFF_PROP_ADMIN_ID, QaEtapa0Constants.STAFF_PROP_ADMIN_EMAIL,
                "QA Property Admin", Role.PROPERTY_ADMIN, null, enc, false, true, true, QaEtapa0Constants.QA_MFA_SECRET_BASE32);
        ensureMembership(propAdmin.getId(), ownerAlpha.getId());
        ensureMembership(propAdmin.getId(), ownerBravo.getId());
        // V40 consolidó niveles de permisos a 3 (Acceso Total / Contador / Solo Lectura).
        // El antiguo "tpl-property-admin-operational" fue colapsado en "tpl-full-access"
        // (ver V40__collapse_property_admin_template.sql). Ambos contextos del QA Property
        // Admin usan ahora el mismo template: sigue cubriendo la regresión amplia de
        // aprobaciones/gastos/archivo sin depender de un template que ya no existe.
        replaceGrantsWithSingleTemplate(propAdmin.getId(), ownerAlpha.getId(), "tpl-full-access");
        ensureGrant(propAdmin.getId(), ownerBravo.getId(), "tpl-full-access");

        UserEntity accountant = ensureUser(STAFF_ACCOUNTANT_ID, QaEtapa0Constants.STAFF_ACCOUNTANT_EMAIL,
                "QA Accountant", Role.ACCOUNTANT, null, enc, false, true, true, QaEtapa0Constants.QA_MFA_SECRET_BASE32);
        ensureMembership(accountant.getId(), ownerAlpha.getId());
        ensureMembership(accountant.getId(), ownerBravo.getId());
        ensureGrant(accountant.getId(), ownerAlpha.getId(), "tpl-accountant");
        ensureGrant(accountant.getId(), ownerBravo.getId(), "tpl-accountant");

        log.info("[QA_SEED] Etapa 0 seed aplicado (idempotente). Propiedades: alpha={} bravo={} archive={}. Ver scripts/QA_SEED_USERS_README.txt",
                propAlpha.getId(), propBravo.getId(), propArchive.getId());
    }

    private void syncTenantPointer(UserEntity tenant) {
        if (tenant.getRole() != Role.TENANT) {
            return;
        }
        long owners = ownerMembershipRepository.findByUserId(tenant.getId()).size();
        long activeProfiles = tenantProfileRepository.findByUserId(tenant.getId()).stream()
                .filter(p -> p.getArchivedAt() == null).count();
        if (owners + activeProfiles <= 1) {
            ownerMembershipRepository.findByUserId(tenant.getId()).stream().findFirst()
                    .ifPresent(m -> tenant.setOwnerId(m.getOwnerId()));
            if (owners == 0) {
                tenantProfileRepository.findByUserId(tenant.getId()).stream()
                        .filter(p -> p.getArchivedAt() == null).findFirst()
                        .ifPresent(p -> tenant.setOwnerId(p.getOwnerId()));
            }
        } else {
            tenant.setOwnerId(null);
        }
        userRepository.save(tenant);
    }

    /**
     * Usuario QA por id fijo: si ya existe, no admite deriva de rol (falla explícito).
     * Restaura contraseña esperada, nombre base y flags MFA/onboarding si difieren.
     *
     * V54: el campo {@code users.email} fue eliminado. El seed busca exclusivamente
     * por {@code fixedId} (clave estable de Etapa 0). El parámetro {@code contactEmail}
     * se persiste en {@code UserEntity.contactEmail} — ya no es identificador y
     * varios users pueden compartirlo.
     */
    private UserEntity ensureUser(
            String fixedId,
            String contactEmail,
            String name,
            Role role,
            String ownerIdForOwnerRole,
            String encodedPassword,
            boolean mustChangePassword,
            boolean onboarding,
            boolean mfaEnabled,
            String mfaSecretPlain) {
        java.util.Optional<UserEntity> byId = userRepository.findById(fixedId);
        return byId.map(existing -> {
            assertNoQaIdentityDrift(existing, fixedId, contactEmail, role);
            boolean changed = false;
            if (!name.equals(existing.getName())) {
                existing.setName(name);
                changed = true;
            }
            if (!passwordEncoder.matches(QaEtapa0Constants.QA_PASSWORD_PLAINTEXT, existing.getPassword())) {
                existing.setPassword(encodedPassword);
                changed = true;
            }
            if (mfaEnabled != existing.isMfaEnabled()) {
                existing.setMfaEnabled(mfaEnabled);
                changed = true;
            }
            if (mfaSecretPlain != null && (existing.getMfaSecret() == null || !mfaSecretPlain.equals(existing.getMfaSecret()))) {
                existing.setMfaSecret(mfaSecretPlain);
                changed = true;
            }
            if (mustChangePassword != existing.isMustChangePassword()) {
                existing.setMustChangePassword(mustChangePassword);
                changed = true;
            }
            if (onboarding != existing.isOnboardingCompleted()) {
                existing.setOnboardingCompleted(onboarding);
                changed = true;
            }
            if (role == Role.OWNER) {
                if (!existing.getId().equals(existing.getOwnerId())) {
                    existing.setOwnerId(existing.getId());
                    changed = true;
                }
            }
            if (role == Role.SUPER_ADMIN) {
                // Invariante: SUPER_ADMIN no tiene datos de contacto; si un seed previo los dejó, los limpiamos.
                if (existing.getContactEmail() != null) {
                    existing.setContactEmail(null);
                    changed = true;
                }
                if (existing.getContactPhone() != null) {
                    existing.setContactPhone(null);
                    changed = true;
                }
            }
            if (!existing.isActive()) {
                existing.setActive(true);
                changed = true;
            }
            if (changed) {
                return userRepository.save(existing);
            }
            return existing;
        }).orElseGet(() -> {
            UserEntity u = new UserEntity();
            u.setId(fixedId);
            u.setName(name);
            u.setPassword(encodedPassword);
            u.setRole(role);
            u.setMustChangePassword(mustChangePassword);
            u.setOnboardingCompleted(onboarding);
            u.setMfaEnabled(mfaEnabled);
            u.setMfaSecret(mfaSecretPlain);
            u.setActive(true);
            if (role == Role.OWNER && ownerIdForOwnerRole != null) {
                u.setOwnerId(ownerIdForOwnerRole);
            }
            if (role == Role.SUPER_ADMIN) {
                // Invariante: SUPER_ADMIN no recibe notificaciones, no tiene contacto.
                u.setContactEmail(null);
                u.setContactPhone(null);
            } else {
                // Otros roles QA: persistimos contactEmail como buzón de pruebas.
                u.setContactEmail(contactEmail);
            }
            // V54: el username es obligatorio. Para usuarios QA existentes (creados
            // con seeds previos) ya debería venir poblado; aquí solo lo defaulteamos
            // en altas fresh derivándolo del contactEmail si está disponible, o del
            // nombre — lo suficientemente único con el fixedId colisiones no son
            // posibles vía este seed.
            if (u.getLoginUsername() == null || u.getLoginUsername().isBlank()) {
                String derived = contactEmail != null && contactEmail.contains("@")
                        ? contactEmail.substring(0, contactEmail.indexOf('@'))
                        : (name != null ? name.toLowerCase().replaceAll("\\s+", "_") : "qa_user");
                u.setLoginUsername(derived + "_" + fixedId.substring(0, 8));
            }
            // Semillas antiguas (p. ej. Hibernate UUID) pueden haber dejado el mismo
            // username con otro PK; el id fijo de Etapa 0 no existe aún → INSERT
            // chocaría con idx_users_username_unique al hacer flush (p. ej. en relink).
            releaseStaleQaUsernameIfBlocked(u.getLoginUsername(), fixedId);
            return userRepository.save(u);
        });
    }

    /**
     * Libera {@code desiredUsername} si está tomado por una fila distinta al id fijo
     * de documentación, renombrando a tombstone (misma idea que cuentas archivadas).
     */
    private void releaseStaleQaUsernameIfBlocked(String desiredUsername, String fixedId) {
        userRepository.findByUsername(desiredUsername)
                .filter(stale -> !fixedId.equals(stale.getId()))
                .ifPresent(stale -> {
                    String id8 = stale.getId().length() >= 8
                            ? stale.getId().substring(0, 8)
                            : stale.getId().replace("-", "");
                    String tombstone = "tombstone-qaseed-" + id8 + "-" + System.currentTimeMillis();
                    if (tombstone.length() > 64) {
                        tombstone = tombstone.substring(0, 64);
                    }
                    stale.setLoginUsername(tombstone);
                    stale.setUsernameTombstonedAt(LocalDateTime.now());
                    userRepository.saveAndFlush(stale);
                    log.warn("[QA_SEED] Username '{}' ocupado por id={} (huérfano de seed antiguo). "
                                    + "Renombrado a '{}' para insertar usuario estable id={}.",
                            desiredUsername, stale.getId(), tombstone, fixedId);
                });
    }

    private void assertNoQaIdentityDrift(UserEntity existing, String fixedId, String contactEmail, Role expectedRole) {
        // V54: el lookup es sólo por fixedId, así que el id siempre coincide.
        // Esta verificación queda como safety neto por si se reintroduce un
        // lookup alternativo; hoy es no-op práctica.
        if (!fixedId.equals(existing.getId())) {
            if (qaSeedProperties.isStrictUserIds()) {
                throw new IllegalStateException(String.format(
                        "[QA_SEED] Deriva: el user QA con contactEmail \"%s\" apunta al id \"%s\"; la semilla Etapa 0 exige id \"%s\". "
                                + "Corrija la BD, desactive semilla, o arranque con admindi.qa-seed.strict-user-ids=false "
                                + "(perfiles local/qa lo ponen por defecto en false).",
                        contactEmail, existing.getId(), fixedId));
            }
            log.warn("[QA_SEED] User QA con id distinto al fijo (strict-user-ids=false); se adopta id existente. contactEmail={} id={} idFijoDoc={}",
                    contactEmail, existing.getId(), fixedId);
        }
        if (existing.getRole() != expectedRole) {
            throw new IllegalStateException(String.format(
                    "[QA_SEED] Deriva: el user QA id \"%s\" tiene rol %s; se esperaba %s.",
                    fixedId, existing.getRole(), expectedRole));
        }
    }

    /**
     * Reasigna filas que aún usan el UUID fijo de documentación como {@code owner_id} hacia el id real del usuario OWNER en BD.
     * Sin esto, GET /tenants del dueño real devuelve [] aunque existan expedientes creados con el id legado.
     * Las membresías compuestas (user_id, owner_id) se borran y {@link #ensureMembership} las vuelve a crear.
     */
    private void relinkLegacyQaOwnerRows(String legacyFixedOwnerId, String actualOwnerId) {
        if (legacyFixedOwnerId.equals(actualOwnerId)) {
            return;
        }
        boolean touched = false;
        for (PropertyEntity p : propertyRepository.findByOwnerId(legacyFixedOwnerId)) {
            p.setOwnerId(actualOwnerId);
            propertyRepository.save(p);
            touched = true;
        }
        for (TenantProfileEntity p : tenantProfileRepository.findByOwnerId(legacyFixedOwnerId)) {
            p.setOwnerId(actualOwnerId);
            tenantProfileRepository.save(p);
            touched = true;
        }
        for (LeaseEntity l : leaseRepository.findByOwnerId(legacyFixedOwnerId)) {
            l.setOwnerId(actualOwnerId);
            leaseRepository.save(l);
            touched = true;
        }
        for (PermissionGrantEntity g : permissionGrantRepository.findByOwnerId(legacyFixedOwnerId)) {
            g.setOwnerId(actualOwnerId);
            permissionGrantRepository.save(g);
            touched = true;
        }
        for (OwnerMembershipEntity m : ownerMembershipRepository.findByOwnerId(legacyFixedOwnerId)) {
            ownerMembershipRepository.delete(m);
            touched = true;
        }
        for (UserEntity u : userRepository.findByOwnerId(legacyFixedOwnerId)) {
            u.setOwnerId(actualOwnerId);
            userRepository.save(u);
            touched = true;
        }
        if (touched) {
            log.warn("[QA_SEED] owner_id legado {} reasignado a {} (propiedades/perfiles/leases/grants; membresías borradas para recrear).",
                    legacyFixedOwnerId, actualOwnerId);
        }
    }

    private void ensureMembership(String userId, String ownerId) {
        if (ownerMembershipRepository.findByUserIdAndOwnerId(userId, ownerId).isPresent()) {
            return;
        }
        OwnerMembershipEntity m = new OwnerMembershipEntity();
        m.setUserId(userId);
        m.setOwnerId(ownerId);
        m.setAssignedAt(LocalDateTime.now());
        m.setAssignedBy("qa-seed-etapa0");
        ownerMembershipRepository.save(m);
    }

    /**
     * Propiedad por (ownerId, nombre estable). Los ids son generados por JPA; no usar UUID fijos en insert.
     */
    private PropertyEntity ensurePropertyByOwnerAndName(String ownerId, String name, PropertyStatus status) {
        List<PropertyEntity> matches = propertyRepository.findByOwnerIdAndName(ownerId, name);
        if (matches.size() > 1) {
            throw new IllegalStateException(String.format(
                    "[QA_SEED] Deriva: varias propiedades con nombre \"%s\" para owner=%s; limpie duplicados en BD.",
                    name, ownerId));
        }
        if (matches.size() == 1) {
            PropertyEntity p = matches.get(0);
            boolean changed = false;
            if (p.getStatus() != status) {
                p.setStatus(status);
                changed = true;
            }
            if (!ownerId.equals(p.getOwnerId())) {
                p.setOwnerId(ownerId);
                changed = true;
            }
            if (!p.isActive()) {
                p.setActive(true);
                changed = true;
            }
            return changed ? propertyRepository.save(p) : p;
        }
        PropertyEntity p = new PropertyEntity(ownerId, name, "QA address", status);
        return propertyRepository.save(p);
    }

    private void ensureTenantExpediente(String tenantUserId, String ownerId, String propertyId) {
        if (tenantProfileRepository.existsByUserIdAndOwnerIdAndPropertyIdAndArchivedAtIsNull(tenantUserId, ownerId, propertyId)) {
            ensureActiveLease(ownerId, tenantUserId, propertyId);
            return;
        }
        TenantProfileEntity profile = new TenantProfileEntity(
                tenantUserId, ownerId, propertyId, new BigDecimal("7500"), 5);
        tenantProfileRepository.save(profile);
        ensureActiveLease(ownerId, tenantUserId, propertyId);
    }

    private void ensureActiveLease(String ownerId, String tenantUserId, String propertyId) {
        if (leaseRepository.findFirstByOwnerIdAndTenant_IdAndProperty_IdAndStatus(
                ownerId, tenantUserId, propertyId, LeaseStatus.ACTIVE).isPresent()) {
            return;
        }
        UserEntity tenant = userRepository.findById(tenantUserId).orElseThrow(
                () -> new IllegalStateException("[QA_SEED] Usuario inquilino no encontrado id=" + tenantUserId));
        PropertyEntity prop = propertyRepository.findById(propertyId).orElseThrow(
                () -> new IllegalStateException("[QA_SEED] Propiedad no encontrada id=" + propertyId
                        + " (ensureProperty debe ejecutarse antes del lease)."));
        LeaseEntity lease = new LeaseEntity();
        lease.setOwnerId(ownerId);
        lease.setProperty(prop);
        lease.setTenant(tenant);
        lease.setStartDate(LocalDate.now().minusMonths(2));
        lease.setEndDate(LocalDate.now().plusYears(2));
        lease.setMonthlyRent(new BigDecimal("7500"));
        lease.setDepositAmount(BigDecimal.ZERO);
        lease.setStatus(LeaseStatus.ACTIVE);
        lease.setPaymentDay(5);
        leaseRepository.save(lease);
    }

    /**
     * Un expediente activo (tenant + owner archivo + propiedad archivo). Id de perfil generado por JPA.
     */
    private void repairArchiveExpediente(String tenantUserId, String ownerId, String propertyId) {
        UserEntity u = userRepository.findById(tenantUserId).orElseThrow();
        u.setActive(true);
        userRepository.save(u);

        List<TenantProfileEntity> forPair = tenantProfileRepository.findByUserIdAndOwnerId(tenantUserId, ownerId);
        List<TenantProfileEntity> active = forPair.stream()
                .filter(p -> p.getArchivedAt() == null)
                .toList();
        if (active.size() > 1) {
            throw new IllegalStateException(String.format(
                    "[QA_SEED] Deriva: varios expedientes activos tenant=%s owner=%s; consolide o archive en BD.",
                    tenantUserId, ownerId));
        }
        TenantProfileEntity profile;
        if (active.size() == 1) {
            profile = active.get(0);
            if (!propertyId.equals(profile.getPropertyId())) {
                profile.setPropertyId(propertyId);
                tenantProfileRepository.save(profile);
            }
        } else {
            Optional<TenantProfileEntity> archived = forPair.stream()
                    .filter(p -> p.getArchivedAt() != null)
                    .max(Comparator.comparing(TenantProfileEntity::getArchivedAt,
                            Comparator.nullsFirst(Comparator.naturalOrder())));
            if (archived.isPresent()) {
                profile = archived.get();
                profile.setArchivedAt(null);
                profile.setPropertyId(propertyId);
                tenantProfileRepository.save(profile);
            } else {
                profile = new TenantProfileEntity(tenantUserId, ownerId, propertyId, new BigDecimal("6000"), 5);
                tenantProfileRepository.save(profile);
            }
        }
        ensureMembership(tenantUserId, ownerId);
        ensureActiveLease(ownerId, tenantUserId, propertyId);
    }

    private void ensureGrant(String userId, String ownerId, String templateId) {
        if (!permissionGrantRepository.findByUserIdAndOwnerId(userId, ownerId).isEmpty()) {
            boolean has = permissionGrantRepository.findByUserIdAndOwnerId(userId, ownerId).stream()
                    .anyMatch(g -> templateId.equals(g.getTemplateId()));
            if (has) {
                return;
            }
        }
        PermissionGrantEntity g = new PermissionGrantEntity();
        g.setId(UUID.randomUUID().toString());
        g.setUserId(userId);
        g.setOwnerId(ownerId);
        g.setTemplateId(templateId);
        g.setGrantedAt(LocalDateTime.now());
        g.setGrantedBy("qa-seed-etapa0");
        permissionGrantRepository.save(g);
    }

    /** Un solo grant por (user, owner): evita duplicados y permite cambiar plantilla entre corridas de semilla QA. */
    private void replaceGrantsWithSingleTemplate(String userId, String ownerId, String templateId) {
        for (PermissionGrantEntity existing : permissionGrantRepository.findByUserIdAndOwnerId(userId, ownerId)) {
            permissionGrantRepository.delete(existing);
        }
        PermissionGrantEntity g = new PermissionGrantEntity();
        g.setId(UUID.randomUUID().toString());
        g.setUserId(userId);
        g.setOwnerId(ownerId);
        g.setTemplateId(templateId);
        g.setGrantedAt(LocalDateTime.now());
        g.setGrantedBy("qa-seed-etapa0");
        permissionGrantRepository.save(g);
    }
}
