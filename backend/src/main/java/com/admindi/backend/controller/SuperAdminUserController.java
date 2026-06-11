package com.admindi.backend.controller;

import com.admindi.backend.model.AuditEventEntity;
import com.admindi.backend.model.Role;
import com.admindi.backend.model.TenantProfileEntity;
import com.admindi.backend.model.UserEntity;
import com.admindi.backend.repository.AuditEventRepository;
import com.admindi.backend.repository.TenantProfileRepository;
import com.admindi.backend.repository.UserRepository;
import com.admindi.backend.service.AccountLifecycleService;
import com.admindi.backend.service.OwnerCascadeDeletionService;
import com.admindi.backend.service.ReauthService;
import com.admindi.backend.service.RefreshTokenRevocationService;
import com.admindi.backend.service.TenantExpedienteArchiveService;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Panel SUPERADMIN (Etapa 2):
 *  * Editar contacto (email/teléfono) de REAL_ESTATE_AGENT y MAINTENANCE_PROVIDER
 *    desde el mismo módulo donde se resetea password/MFA.
 *  * Eliminar usuarios. Si el usuario es TENANT, se archivan primero todos sus
 *    expedientes operativos (snapshot financiero inmutable en cada uno) sin
 *    pedir autorización al dueño — es rol SUPER_ADMIN.
 *
 * Reauth: TODAS las acciones sensibles del panel SUPERADMIN requieren MFA + contraseña
 *         para prevenir filtraciones y uso indebido de la cuenta ante un compromiso.
 */
@RestController
@RequestMapping("/api/admin/users")
@PreAuthorize("hasRole('SUPER_ADMIN')")
public class SuperAdminUserController {

    private static final Logger logger = LoggerFactory.getLogger(SuperAdminUserController.class);

    private final UserRepository userRepository;
    private final TenantProfileRepository tenantProfileRepository;
    private final TenantExpedienteArchiveService archiveService;
    private final RefreshTokenRevocationService tokenRevocationService;
    private final ReauthService reauthService;
    private final AuditEventRepository auditEventRepository;
    private final OwnerCascadeDeletionService ownerCascadeDeletionService;
    private final AccountLifecycleService accountLifecycleService;

    public SuperAdminUserController(UserRepository userRepository,
                                    TenantProfileRepository tenantProfileRepository,
                                    TenantExpedienteArchiveService archiveService,
                                    RefreshTokenRevocationService tokenRevocationService,
                                    ReauthService reauthService,
                                    AuditEventRepository auditEventRepository,
                                    OwnerCascadeDeletionService ownerCascadeDeletionService,
                                    AccountLifecycleService accountLifecycleService) {
        this.userRepository = userRepository;
        this.tenantProfileRepository = tenantProfileRepository;
        this.archiveService = archiveService;
        this.tokenRevocationService = tokenRevocationService;
        this.reauthService = reauthService;
        this.auditEventRepository = auditEventRepository;
        this.ownerCascadeDeletionService = ownerCascadeDeletionService;
        this.accountLifecycleService = accountLifecycleService;
    }

    /**
     * Actualiza contacto (email y teléfono) de un agente o proveedor.
     * No cambia el email de login ni el rol.
     *
     * Seguridad: cambiar email/teléfono de contacto es sensible (puede usarse para
     * interceptar comunicaciones o redirigir notificaciones). Exige MFA + contraseña.
     * Body: { "password": "...", "mfaCode": "...", "contactEmail": "...", ... }
     */
    @PutMapping("/{userId}/contact")
    public ResponseEntity<Map<String, Object>> updateContact(@PathVariable String userId,
                                                             @RequestBody Map<String, String> body) {
        reauthService.verifyReauth(
                body.get("password"),
                body.get("mfaCode"),
                "SUPERADMIN_UPDATE_CONTACT"
        );
        UserEntity u = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("Usuario no encontrado."));
        if (u.getRole() != Role.REAL_ESTATE_AGENT && u.getRole() != Role.MAINTENANCE_PROVIDER) {
            throw new RuntimeException("Este endpoint solo aplica a REAL_ESTATE_AGENT o MAINTENANCE_PROVIDER.");
        }
        if (body.containsKey("contactEmail")) {
            // V54 — contactEmail NO único, compartible. Normalizar (trim + lower)
            // y permitir null/blank para borrar.
            String v = body.get("contactEmail");
            if (v == null || v.isBlank()) {
                u.setContactEmail(null);
            } else {
                u.setContactEmail(v.trim().toLowerCase());
            }
        }
        if (body.containsKey("contactPhone")) u.setContactPhone(body.get("contactPhone"));
        if (body.containsKey("contactCountryCode")) u.setContactCountryCode(body.get("contactCountryCode"));
        userRepository.save(u);
        logger.info("[SUPERADMIN] contact updated userId={} role={}", userId, u.getRole());
        return ResponseEntity.ok(Map.of(
                "userId", u.getId(),
                "contactEmail", u.getContactEmail() != null ? u.getContactEmail() : "",
                "contactPhone", u.getContactPhone() != null ? u.getContactPhone() : "",
                "contactCountryCode", u.getContactCountryCode() != null ? u.getContactCountryCode() : ""
        ));
    }

    /**
     * Elimina (soft-delete) un usuario. Para TENANT: archiva todos los expedientes
     * activos generando snapshot por cada uno.
     *
     * Seguridad:
     *  - Exige MFA + contraseña al SUPER_ADMIN actor (reauth).
     *  - Requiere `reason` (≥10 caracteres) que queda persistido en audit_events.
     *  - INVARIANTE DE SISTEMA: un SUPER_ADMIN NO puede eliminar a otro SUPER_ADMIN
     *    ni a sí mismo. Eliminar roots por UI puede dejar la plataforma sin acceso
     *    raíz y es un vector claro de ataque insider (root A borra a root B para
     *    tapar evidencia, o un token robado borra a todos los SA). Si hace falta
     *    remover un SA se hace por procedimiento elevado fuera de banda (DB+auditoría).
     *  - Los intentos bloqueados se persisten como SUPERADMIN_USER_DELETE_BLOCKED
     *    para tener señal temprana de abuso/ataque.
     *
     * Body: { "password": "...", "mfaCode": "...", "reason": "..." }
     */
    @DeleteMapping("/{userId}")
    @Transactional
    public ResponseEntity<Map<String, Object>> deleteUser(@PathVariable String userId,
                                                          @RequestBody Map<String, String> body) {
        String password = body.get("password");
        String mfaCode = body.get("mfaCode");
        String reason = body.get("reason");

        // 1) Validación barata primero: motivo obligatorio y auditable.
        if (reason == null || reason.trim().length() < 10) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Se requiere un motivo de al menos 10 caracteres para eliminar un usuario.");
        }
        String reasonClean = reason.trim();

        // 2) Reauth (password + MFA). ReauthService ya persiste su propio audit.
        reauthService.verifyReauth(password, mfaCode, "SUPERADMIN_DELETE_USER");

        String actorEmail = SecurityContextHolder.getContext().getAuthentication().getName();

        UserEntity u = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("Usuario no encontrado."));

        // 3) Invariantes de sistema. Intento bloqueado => audit SECURITY y 403.
        if (u.getRole() == Role.SUPER_ADMIN) {
            logger.warn("[SUPERADMIN] BLOCKED delete of SUPER_ADMIN targetId={} actor={}",
                    userId, actorEmail);
            persistBlockedAudit(actorEmail, u, reasonClean, "TARGET_IS_SUPER_ADMIN");
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "No se puede eliminar un SUPER_ADMIN desde esta interfaz. " +
                    "Ese cambio requiere procedimiento elevado fuera de banda.");
        }
        // V54: actorEmail es en realidad el username del actor (Authentication#getName()
        // devuelve username desde V48). Comparamos contra el username del target.
        if (actorEmail != null && actorEmail.equals(u.getLoginUsername())) {
            logger.warn("[SUPERADMIN] BLOCKED self-delete actor={}", actorEmail);
            persistBlockedAudit(actorEmail, u, reasonClean, "SELF_DELETE");
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "No puedes eliminar tu propia cuenta.");
        }

        // 4) Mutación según rol del objetivo:
        //
        //   (a) OWNER → hard-delete en cascada. Regla del operador: "si elimino
        //       un dueño debe borrarse TODO su rastro (inmuebles, contratos,
        //       cobranza, convenios, mantenimiento, egresos, archivos, expedientes,
        //       snapshots, memberships, notificaciones, audits propios). El email
        //       queda libre. Los staff/tenants que solo tenían contexto con ese
        //       dueño también se borran; los que tienen otro contexto conservan
        //       su cuenta y solo pierden acceso a este dueño."
        //
        //   (b) TENANT → se archivan expedientes (snapshot inmutable) y soft-delete
        //       del user. No borramos al user porque puede aparecer en expedientes
        //       de otros dueños o en histórico legal.
        //
        //   (c) Otros roles (staff, agentes, proveedores) → soft-delete clásico:
        //       se desactiva, se revocan sesiones, se libera la silla. No se
        //       purgan sus memberships automáticamente porque puede ser una baja
        //       temporal; el SUPERADMIN puede re-habilitar sin perder asignaciones.
        int archivedProfiles = 0;
        boolean hardDeleted = false;
        String tombstoneUsername = null;
        String originalContactEmail = u.getContactEmail();
        String originalUsername = u.getLoginUsername();
        OwnerCascadeDeletionService.CascadeResult cascade = null;

        if (u.getRole() == Role.OWNER) {
            cascade = ownerCascadeDeletionService.hardDeleteOwner(u.getId());
            hardDeleted = true;
        } else if (accountLifecycleService.isEnabled() && u.getRole() != Role.SUPER_ADMIN) {
            // V48 / Bloque 3: ruta unificada bajo feature flag `account.lifecycle.new`.
            //   Un único punto de decisión entre HARD_DELETE y ARCHIVE para tenant,
            //   staff, agente y provider; además tombstonea username para liberarlo.
            AccountLifecycleService.DeletionResult lr =
                    accountLifecycleService.deleteAccount(u.getId(), password, mfaCode);
            hardDeleted = lr.outcome() == AccountLifecycleService.LifecycleOutcome.HARD_DELETED;
            tombstoneUsername = lr.tombstoneUsername();
            if (u.getRole() == Role.TENANT && !hardDeleted) {
                archivedProfiles = tenantProfileRepository.findByUserIdAndArchivedAtIsNull(u.getId()).size();
            }
            tokenRevocationService.revokeAllRefreshSessionsForUser(u.getId());
        } else if (u.getRole() == Role.TENANT) {
            // TENANT: primero archivar cada expediente activo (snapshot inmutable),
            // luego soft-delete del user. No se hard-deletea porque puede aparecer
            // referenciado en snapshots históricos/leases legales.
            List<TenantProfileEntity> profiles =
                    tenantProfileRepository.findByUserIdAndArchivedAtIsNull(u.getId());
            for (TenantProfileEntity p : profiles) {
                archiveService.archiveOperational(p.getId(), password, mfaCode);
                archivedProfiles++;
            }
            if (u.isActive() || u.getDeletedAt() == null) {
                u.setActive(false);
                u.setDeletedAt(LocalDateTime.now());
                userRepository.save(u);
                tokenRevocationService.revokeAllRefreshSessionsForUser(u.getId());
            }
        } else {
            // PROPERTY_ADMIN / ACCOUNTANT / REAL_ESTATE_AGENT / MAINTENANCE_PROVIDER.
            // V54: email y phone ya no son únicos, lo único que se libera es el
            // username (vía tombstoneUsername del cascade). Hard-delete cuando
            // no hay actividad histórica; tombstone cuando sí hay trail que
            // preservar (cotizaciones, tickets, egresos...).
            OwnerCascadeDeletionService.StaffDeletionOutcome outcome =
                    ownerCascadeDeletionService.deleteOrTombstoneStaffOrProvider(u.getId());
            hardDeleted = outcome.hardDeleted();
            tombstoneUsername = outcome.tombstoneUsername();
            // Revocar sesiones si seguía activo (el servicio ya borra refresh_tokens,
            // pero esto asegura señal cross-service consistente).
            tokenRevocationService.revokeAllRefreshSessionsForUser(u.getId());
        }

        // 5) Auditoría real. Para OWNER registramos contadores de cascada; para
        //    el resto, expedientes archivados (cuando aplica) + contactEmail
        //    original para que el audit preserve el destinatario histórico.
        persistSuccessAudit(actorEmail, u, reasonClean, archivedProfiles, hardDeleted, cascade,
                originalContactEmail, tombstoneUsername);

        logger.info("[SUPERADMIN] user deleted userId={} role={} hardDeleted={} tombstoneUsername={} archivedProfiles={} reason='{}'",
                userId, u.getRole(), hardDeleted, tombstoneUsername, archivedProfiles, reasonClean);

        Map<String, Object> response = new java.util.HashMap<>();
        response.put("userId", u.getId());
        response.put("role", u.getRole().name());
        response.put("hardDeleted", hardDeleted);
        response.put("archivedTenantProfiles", archivedProfiles);
        response.put("originalContactEmail", originalContactEmail);
        // V48 / Bloque 3: reportamos el estado del username (tombstone o libre
        // por hard-delete) para que el panel pueda mostrar al operador que el
        // identificador queda disponible para reuso. V54: email y phone son
        // libres por construcción, no requieren reporte.
        if (originalUsername != null) {
            response.put("originalUsername", originalUsername);
        }
        if (tombstoneUsername != null) {
            response.put("tombstoneUsername", tombstoneUsername);
            response.put("usernameLiberated", true);
        } else if (hardDeleted) {
            response.put("usernameLiberated", true);
        }
        if (!hardDeleted && tombstoneUsername == null) {
            // Solo TENANT cae aquí (soft-delete puro, contactos conservados).
            response.put("deactivatedAt", u.getDeletedAt() != null ? u.getDeletedAt().toString() : "");
        }
        if (cascade != null) {
            Map<String, Integer> counters = new java.util.HashMap<>();
            counters.put("properties", cascade.properties);
            counters.put("units", cascade.units);
            counters.put("leases", cascade.leases);
            counters.put("invoices", cascade.invoices);
            counters.put("payments", cascade.payments);
            counters.put("agreements", cascade.agreements);
            counters.put("transferProofs", cascade.transferProofs);
            counters.put("tickets", cascade.tickets);
            counters.put("budgets", cascade.budgets);
            counters.put("expenses", cascade.expenses);
            counters.put("vacancies", cascade.vacancies);
            counters.put("tenantProfiles", cascade.tenantProfiles);
            counters.put("archiveSnapshots", cascade.archiveSnapshots);
            counters.put("memberships", cascade.memberships);
            counters.put("relatedUsersDeleted", cascade.relatedUsersDeleted);
            counters.put("relatedUsersKept", cascade.relatedUsersKept);
            response.put("cascade", counters);
        }
        return ResponseEntity.ok(response);
    }

    // --- Audit helpers ---------------------------------------------------

    private void persistSuccessAudit(String actorEmail, UserEntity target,
                                     String reason, int archivedProfiles,
                                     boolean hardDeleted,
                                     OwnerCascadeDeletionService.CascadeResult cascade,
                                     String originalContactEmail,
                                     String tombstoneUsername) {
        AuditEventEntity a = baseAudit(actorEmail, target);
        a.setEventType("SUPERADMIN_USER_DELETE");
        // Importante: para OWNER el audit del target se borró con la cascada, pero
        // ESTE audit se emite después y su owner_id es null (el user eliminado era
        // el dueño raíz), por lo que sobrevive como registro permanente del evento
        // en la línea de auditoría del SUPER_ADMIN actor.
        StringBuilder cascadeJson = new StringBuilder();
        if (cascade != null) {
            cascadeJson.append(String.format(
                    ",\"cascade\":{\"properties\":%d,\"units\":%d,\"leases\":%d,\"invoices\":%d,"
                            + "\"payments\":%d,\"agreements\":%d,\"transferProofs\":%d,\"tickets\":%d,"
                            + "\"budgets\":%d,\"expenses\":%d,\"vacancies\":%d,\"tenantProfiles\":%d,"
                            + "\"archiveSnapshots\":%d,\"memberships\":%d,\"relatedUsersDeleted\":%d,"
                            + "\"relatedUsersKept\":%d}",
                    cascade.properties, cascade.units, cascade.leases, cascade.invoices,
                    cascade.payments, cascade.agreements, cascade.transferProofs, cascade.tickets,
                    cascade.budgets, cascade.expenses, cascade.vacancies, cascade.tenantProfiles,
                    cascade.archiveSnapshots, cascade.memberships, cascade.relatedUsersDeleted,
                    cascade.relatedUsersKept));
        }
        String tombstonePart = tombstoneUsername != null
                ? String.format(",\"tombstoneUsername\":\"%s\"", escapeJson(tombstoneUsername))
                : "";
        a.setNewValues(String.format(
                "{\"targetContactEmail\":\"%s\",\"originalContactEmail\":\"%s\",\"targetRole\":\"%s\",\"reason\":\"%s\","
                        + "\"archivedProfiles\":%d,\"hardDeleted\":%s%s%s,\"outcome\":\"SUCCESS\"}",
                safe(target.getContactEmail()),
                safe(originalContactEmail),
                target.getRole() != null ? target.getRole().name() : "UNKNOWN",
                escapeJson(reason),
                archivedProfiles,
                hardDeleted,
                tombstonePart,
                cascadeJson.toString()
        ));
        auditEventRepository.save(a);
    }

    private void persistBlockedAudit(String actorEmail, UserEntity target,
                                     String reason, String blockReason) {
        AuditEventEntity a = baseAudit(actorEmail, target);
        a.setEventType("SUPERADMIN_USER_DELETE_BLOCKED");
        a.setNewValues(String.format(
                "{\"targetContactEmail\":\"%s\",\"targetRole\":\"%s\",\"reason\":\"%s\",\"blockReason\":\"%s\",\"outcome\":\"BLOCKED\"}",
                safe(target.getContactEmail()),
                target.getRole() != null ? target.getRole().name() : "UNKNOWN",
                escapeJson(reason),
                blockReason
        ));
        auditEventRepository.save(a);
    }

    private AuditEventEntity baseAudit(String actorEmail, UserEntity target) {
        HttpServletRequest req = getHttpRequest();
        AuditEventEntity a = new AuditEventEntity();
        a.setId(UUID.randomUUID().toString());
        a.setTimestamp(LocalDateTime.now());
        a.setActorId(actorEmail != null ? actorEmail : "unknown");
        a.setActorRole(Role.SUPER_ADMIN.name());
        a.setResourceType("User");
        a.setResourceId(target.getId());
        a.setOwnerId(computeAuditOwnerScope(target));
        a.setIpAddress(req != null ? req.getRemoteAddr() : "unknown");
        a.setUserAgent(req != null ? req.getHeader("User-Agent") : "unknown");
        a.setRequestId(UUID.randomUUID().toString());
        return a;
    }

    /**
     * Decide which {@code owner_id} a SUPERADMIN-scoped audit row should carry when
     * we're about to persist it right after a user-deletion flow. Isolated into a
     * package-private static so it's unit-testable — this invariant already broke
     * once (audits of hard-deleted owners disappeared silently) and the cost of a
     * regression is losing the sysadmin's action trail, so it's worth pinning.
     *
     * <p>Rules:
     * <ul>
     *   <li>Target is an OWNER → the cascade purged every audit with that
     *       {@code owner_id}; if we tagged the new row with the same scope Hibernate
     *       could reorder INSERT/DELETE at flush time and erase it. Return
     *       {@code null} so the row belongs to the SUPER_ADMIN actor timeline.</li>
     *   <li>Anyone else → return {@code target.ownerId}: the audit row legitimately
     *       belongs to whatever owner scope the deleted user was tied to.</li>
     * </ul>
     */
    static String computeAuditOwnerScope(UserEntity target) {
        if (target == null) return null;
        if (target.getRole() == Role.OWNER) return null;
        return target.getOwnerId();
    }

    private HttpServletRequest getHttpRequest() {
        try {
            ServletRequestAttributes attr = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            return attr != null ? attr.getRequest() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private String safe(String s) {
        return s == null ? "" : escapeJson(s);
    }

    /** Escape minimal JSON (comillas y backslashes). */
    private String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
