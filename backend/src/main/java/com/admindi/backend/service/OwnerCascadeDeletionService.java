package com.admindi.backend.service;

import com.admindi.backend.model.*;
import com.admindi.backend.repository.*;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Hard-delete en cascada de un dueño (OWNER) iniciado por un SUPER_ADMIN.
 *
 * Regla de negocio (solicitada por el operador):
 *   · Si el SUPER_ADMIN elimina un dueño se debe borrar por completo todo rastro
 *     operativo y contable del dueño en el sistema. El email queda libre para
 *     volver a registrarse.
 *   · Los usuarios que dependían exclusivamente de ese dueño (tenants sin otro
 *     expediente activo, staff sin otra membership) se eliminan también: quedan
 *     sin contexto alguno en la plataforma.
 *   · Los usuarios que tienen contextos adicionales (otro dueño) se conservan;
 *     solo pierden acceso al dueño eliminado.
 *   · Nunca se elimina un SUPER_ADMIN por esta vía.
 *
 * Orden de borrado (respeta foreign keys):
 *   1. Hijos de convenios: agreement_installments → payment_agreements.
 *   2. Hijos de comprobantes: cep_validation_attempts → transfer_proof_submissions.
 *   3. Pagos e invoices (las invoices ya no tienen pagos ni comprobantes vivos).
 *   4. Archivos de contratos → contratos (leases).
 *   5. Actividades comerciales → vacancies.
 *   6. Cotizaciones de mantenimiento → tickets de mantenimiento.
 *   7. Presupuestos de mantenimiento, egresos, movimientos de inmueble.
 *   8. Archivos de inmueble, unidades, inmuebles.
 *   9. Expedientes de arrendatarios (profiles) y snapshots archivados.
 *  10. Asignaciones de proveedores plataforma, permission_grants, owner_memberships.
 *  11. Notifications y action_tasks con owner_id del eliminado.
 *  12. Audit events del dueño (por solicitud explícita del operador).
 *  13. User del dueño (hard-delete → libera email).
 *  14. Cascada opcional sobre usuarios relacionados: tenants/staff que se
 *      quedaron sin ningún otro contexto → hard-delete.
 *
 * Todo corre dentro de un único @Transactional: si algo falla, rollback total.
 */
@Service
public class OwnerCascadeDeletionService {

    private static final Logger log = LoggerFactory.getLogger(OwnerCascadeDeletionService.class);

    // --- Repositorios ---------------------------------------------------
    @Autowired private UserRepository userRepository;
    @Autowired private PropertyRepository propertyRepository;
    @Autowired private UnitRepository unitRepository;
    @Autowired private PropertyFileRepository propertyFileRepository;
    @Autowired private PropertyMovementRepository propertyMovementRepository;
    @Autowired private LeaseRepository leaseRepository;
    @Autowired private LeaseFileRepository leaseFileRepository;
    @Autowired private InvoiceRepository invoiceRepository;
    @Autowired private PaymentRepository paymentRepository;
    @Autowired private PaymentAgreementRepository paymentAgreementRepository;
    @Autowired private AgreementInstallmentRepository agreementInstallmentRepository;
    @Autowired private TransferProofSubmissionRepository transferProofSubmissionRepository;
    @Autowired private CepValidationAttemptRepository cepValidationAttemptRepository;
    @Autowired private MaintenanceTicketRepository maintenanceTicketRepository;
    @Autowired private MaintenanceQuoteRepository maintenanceQuoteRepository;
    @Autowired private MaintenanceBudgetRepository maintenanceBudgetRepository;
    @Autowired private ExpenseRepository expenseRepository;
    @Autowired private VacancyRepository vacancyRepository;
    @Autowired private CommercialActivityRepository commercialActivityRepository;
    @Autowired private TenantProfileRepository tenantProfileRepository;
    @Autowired private TenantArchiveSnapshotRepository tenantArchiveSnapshotRepository;
    @Autowired private PlatformProviderAssignmentRepository platformProviderAssignmentRepository;
    @Autowired private PermissionGrantRepository permissionGrantRepository;
    @Autowired private OwnerMembershipRepository ownerMembershipRepository;
    @Autowired private NotificationRepository notificationRepository;
    @Autowired private NotificationPreferenceRepository notificationPreferenceRepository;
    @Autowired private ActionTaskRepository actionTaskRepository;
    @Autowired private RefreshTokenSessionRepository refreshTokenSessionRepository;
    @Autowired private AuditEventRepository auditEventRepository;
    @Autowired private UserActivationTokenRepository userActivationTokenRepository;
    // V66 — metadata de file_upload_claims: los archivos físicos se borran
    // uno a uno vía safeDeleteFile (que sí los elimina del storage), pero las
    // filas huérfanas en la tabla de claims quedaban vivas. Se purgan al final.
    @Autowired private FileUploadClaimRepository fileUploadClaimRepository;

    // Storage físico (disco local hoy; mañana S3/GCS). Se usa para borrar contratos,
    // comprobantes SPEI/CEP, evidencias de convenios y presupuestos de mantenimiento
    // cuando se purga a un dueño — "de db y de almacenamiento" según la regla operativa.
    @Autowired private StorageService storageService;

    // Password encoder para "scramble" del password al tombstonear un user. Un BCrypt
    // sobre un UUID aleatorio vuelve la cuenta imposible de abrir incluso si alguien
    // consiguiera leer la columna: el plaintext nunca sale de este método.
    @Autowired private PasswordEncoder passwordEncoder;

    // Liberación de username en el camino de tombstone (staff/proveedor con actividad).
    // Garantiza invariante: aunque la fila sobreviva por auditoría, el username original
    // queda disponible para reuso (otro dueño u otro admin puede tomarlo).
    @Autowired private UsernameService usernameService;

    // EntityManager para queries COUNT defensivas sobre tablas con FK a users.id.
    // Usado por hasBlockingStaffActivity(): cada tabla se envuelve en try/catch por
    // si en algún entorno un nombre cambiara (tests, branches); un fallo aislado
    // cuenta como "sin actividad" en esa tabla, que es el fallback más conservador
    // para NO tombstonear de más (el peor caso sería hard-delete de alguien con
    // actividad real — mitigado por las tablas que sí podemos consultar).
    @PersistenceContext private EntityManager em;

    /**
     * Resultado devuelto al controller para auditar contadores reales del borrado.
     */
    public static class CascadeResult {
        public int properties;
        public int units;
        public int leases;
        public int invoices;
        public int payments;
        public int agreements;
        public int transferProofs;
        public int tickets;
        public int budgets;
        public int expenses;
        public int vacancies;
        public int tenantProfiles;
        public int archiveSnapshots;
        public int memberships;
        public int relatedUsersDeleted;
        public int relatedUsersKept;
        public int filesDeleted;
        public int filesMissing;
    }

    /**
     * Borra un archivo del storage de forma tolerante: si la ruta está vacía o el
     * archivo ya no existe físicamente, no aborta la transacción. Cuenta solo los
     * éxitos reales. Pensada para usarse dentro de la cascada donde un fallo
     * individual de I/O no debe tumbar todo el hard-delete.
     */
    private void safeDeleteFile(String path, CascadeResult r) {
        if (path == null || path.isBlank()) return;
        try {
            if (storageService.exists(path)) {
                storageService.delete(path);
                r.filesDeleted++;
            } else {
                r.filesMissing++;
            }
        } catch (Exception e) {
            // Regla: la consistencia de la DB es prioridad. Si el disco falla,
            // dejamos rastro en log y seguimos: el row se borrará igualmente.
            log.warn("[CASCADE-DELETE] no se pudo borrar archivo en storage path={} err={}", path, e.getMessage());
            r.filesMissing++;
        }
        // V66 — también eliminamos la fila del claim para no dejar metadatos
        // huérfanos apuntando a un archivo inexistente. Idempotente.
        try {
            fileUploadClaimRepository.findByFilePath(path)
                    .ifPresent(fileUploadClaimRepository::delete);
        } catch (Exception ignore) {
            // La tabla puede no tener la fila; no es crítico.
        }
    }

    @Transactional
    public CascadeResult hardDeleteOwner(String ownerId) {
        CascadeResult r = new CascadeResult();
        log.warn("[CASCADE-DELETE] start ownerId={}", ownerId);

        // 0) Snapshot de usuarios que podrían quedar huérfanos tras la cascada.
        //    Tenants del dueño (vía tenant_profiles) + staff con membership a este dueño.
        Set<String> candidateRelatedUserIds = new HashSet<>();
        List<TenantProfileEntity> ownerProfiles = tenantProfileRepository.findByOwnerId(ownerId);
        for (TenantProfileEntity tp : ownerProfiles) {
            if (tp.getUserId() != null && !tp.getUserId().isBlank()) {
                candidateRelatedUserIds.add(tp.getUserId());
            }
        }
        List<OwnerMembershipEntity> ownerMemberships = ownerMembershipRepository.findByOwnerId(ownerId);
        for (OwnerMembershipEntity m : ownerMemberships) {
            if (m.getUserId() != null && !m.getUserId().isBlank()) {
                candidateRelatedUserIds.add(m.getUserId());
            }
        }
        // El propio owner user también está como usuario, pero lo tratamos aparte.
        candidateRelatedUserIds.remove(ownerId);

        // 1) Convenios: primero installments (hijos), luego el convenio.
        //    También se borra el PDF firmado del convenio en storage si existía.
        List<PaymentAgreementEntity> agreements = paymentAgreementRepository.findByOwnerId(ownerId);
        for (PaymentAgreementEntity ag : agreements) {
            List<AgreementInstallmentEntity> inst = agreementInstallmentRepository.findByAgreementId(ag.getId());
            if (!inst.isEmpty()) {
                agreementInstallmentRepository.deleteAll(inst);
            }
            safeDeleteFile(ag.getEvidenceFileUrl(), r);
        }
        if (!agreements.isEmpty()) {
            paymentAgreementRepository.deleteAll(agreements);
            r.agreements = agreements.size();
        }

        // 2) Comprobantes de transferencia: primero intentos CEP, luego comprobantes.
        //    También se borra el PDF/imagen del comprobante SPEI en storage.
        List<TransferProofSubmission> proofs = transferProofSubmissionRepository.findByOwnerId(ownerId);
        for (TransferProofSubmission p : proofs) {
            List<CepValidationAttempt> atts = cepValidationAttemptRepository.findByTransferProofId(p.getId());
            if (!atts.isEmpty()) {
                cepValidationAttemptRepository.deleteAll(atts);
            }
            safeDeleteFile(p.getFileUrl(), r);
        }
        if (!proofs.isEmpty()) {
            transferProofSubmissionRepository.deleteAll(proofs);
            r.transferProofs = proofs.size();
        }

        // 3) Pagos y facturas. Primero pagos (referencian invoice_id), luego facturas.
        List<PaymentEntity> payments = paymentRepository.findByOwnerId(ownerId);
        if (!payments.isEmpty()) {
            paymentRepository.deleteAll(payments);
            r.payments = payments.size();
        }
        List<InvoiceEntity> invoices = invoiceRepository.findByOwnerId(ownerId);
        if (!invoices.isEmpty()) {
            invoiceRepository.deleteAll(invoices);
            r.invoices = invoices.size();
        }

        // 4) Contratos y archivos de contratos. Borramos también archivos en storage:
        //    · lease.documentUrl (contrato PDF original adjuntado al firmar).
        //    · lease_files.filePath (anexos subidos durante la vigencia).
        List<LeaseEntity> leases = leaseRepository.findByOwnerId(ownerId);
        for (LeaseEntity l : leases) {
            List<LeaseFileEntity> files = leaseFileRepository.findByLeaseId(l.getId());
            for (LeaseFileEntity lf : files) {
                safeDeleteFile(lf.getFilePath(), r);
            }
            if (!files.isEmpty()) {
                leaseFileRepository.deleteAll(files);
            }
            safeDeleteFile(l.getDocumentUrl(), r);
        }
        if (!leases.isEmpty()) {
            leaseRepository.deleteAll(leases);
            r.leases = leases.size();
        }

        // 5) Vacancias y actividades comerciales ligadas.
        List<VacancyEntity> vacancies = vacancyRepository.findByOwnerId(ownerId);
        for (VacancyEntity v : vacancies) {
            List<CommercialActivityEntity> acts = commercialActivityRepository.findByVacancyId(v.getId());
            if (!acts.isEmpty()) {
                commercialActivityRepository.deleteAll(acts);
            }
        }
        if (!vacancies.isEmpty()) {
            vacancyRepository.deleteAll(vacancies);
            r.vacancies = vacancies.size();
        }

        // 6) Mantenimiento: cotizaciones (hijas) → tickets → presupuestos.
        // V66 — borramos también archivos físicos de las cotizaciones (PDFs de
        // presupuesto del proveedor) y de las fotos adjuntas al ticket. Antes
        // esos archivos quedaban huérfanos en /uploads cuando se purgaba al dueño.
        List<MaintenanceTicketEntity> tickets = maintenanceTicketRepository.findByOwnerId(ownerId);
        for (MaintenanceTicketEntity t : tickets) {
            List<MaintenanceQuoteEntity> quotes = maintenanceQuoteRepository.findByTicketId(t.getId());
            for (MaintenanceQuoteEntity q : quotes) {
                safeDeleteFile(q.getEvidenceFileId(), r);
            }
            if (!quotes.isEmpty()) {
                maintenanceQuoteRepository.deleteAll(quotes);
            }
            // Fotos del ticket (jsonb array de paths).
            String photosJson = t.getPhotoFileIdsJson();
            if (photosJson != null && !photosJson.isBlank()) {
                try {
                    com.fasterxml.jackson.databind.ObjectMapper om =
                            new com.fasterxml.jackson.databind.ObjectMapper();
                    String[] paths = om.readValue(photosJson, String[].class);
                    for (String p : paths) safeDeleteFile(p, r);
                } catch (Exception ex) {
                    log.warn("[OWNER-CASCADE] no pude parsear photoFileIds del ticket {}: {}",
                            t.getId(), ex.getMessage());
                }
            }
        }
        if (!tickets.isEmpty()) {
            maintenanceTicketRepository.deleteAll(tickets);
            r.tickets = tickets.size();
        }
        List<MaintenanceBudgetEntity> budgets = maintenanceBudgetRepository.findByOwnerIdOrderBySubmittedAtDesc(ownerId);
        for (MaintenanceBudgetEntity b : budgets) {
            safeDeleteFile(b.getFileUrl(), r);
        }
        if (!budgets.isEmpty()) {
            maintenanceBudgetRepository.deleteAll(budgets);
            r.budgets = budgets.size();
        }

        // 7) Egresos y movimientos del inmueble.
        // V66 — borrar los archivos físicos asociados al egreso antes del delete
        // de la fila (budget_file_id, payment_proof_file_id, evidence_file_id).
        List<ExpenseEntity> expenses = expenseRepository.findByOwnerId(ownerId);
        for (ExpenseEntity e : expenses) {
            safeDeleteFile(e.getEvidenceFileId(), r);
            safeDeleteFile(e.getBudgetFileId(), r);
            safeDeleteFile(e.getPaymentProofFileId(), r);
        }
        if (!expenses.isEmpty()) {
            expenseRepository.deleteAll(expenses);
            r.expenses = expenses.size();
        }
        List<PropertyMovementEntity> movements = propertyMovementRepository.findByOwnerIdOrderByOccurredAtDesc(ownerId);
        if (!movements.isEmpty()) {
            propertyMovementRepository.deleteAll(movements);
        }

        // 8) Inmuebles: archivos por cada property (borrados también de storage),
        //    unidades y properties.
        List<PropertyEntity> properties = propertyRepository.findByOwnerId(ownerId);
        for (PropertyEntity p : properties) {
            List<PropertyFileEntity> pfiles = propertyFileRepository.findByPropertyId(p.getId());
            for (PropertyFileEntity pf : pfiles) {
                safeDeleteFile(pf.getFilePath(), r);
            }
            if (!pfiles.isEmpty()) {
                propertyFileRepository.deleteAll(pfiles);
            }
        }
        List<UnitEntity> units = unitRepository.findByOwnerId(ownerId);
        if (!units.isEmpty()) {
            unitRepository.deleteAll(units);
            r.units = units.size();
        }
        if (!properties.isEmpty()) {
            propertyRepository.deleteAll(properties);
            r.properties = properties.size();
        }

        // 9) Expedientes de arrendatarios del dueño y snapshots históricos.
        //    Nota: a estas alturas ya no existen invoices ni agreements que los referencien.
        if (!ownerProfiles.isEmpty()) {
            tenantProfileRepository.deleteAll(ownerProfiles);
            r.tenantProfiles = ownerProfiles.size();
        }
        List<TenantArchiveSnapshotEntity> snaps =
                tenantArchiveSnapshotRepository.findByOwnerIdOrderByArchivedAtDesc(ownerId);
        if (!snaps.isEmpty()) {
            tenantArchiveSnapshotRepository.deleteAll(snaps);
            r.archiveSnapshots = snaps.size();
        }

        // 10) Plataforma / permisos / memberships del dueño.
        platformProviderAssignmentRepository.deleteByOwnerId(ownerId);
        List<PermissionGrantEntity> grants = permissionGrantRepository.findByOwnerId(ownerId);
        if (!grants.isEmpty()) {
            permissionGrantRepository.deleteAll(grants);
        }
        if (!ownerMemberships.isEmpty()) {
            ownerMembershipRepository.deleteAll(ownerMemberships);
            r.memberships = ownerMemberships.size();
        }

        // 11) Notificaciones y action tasks cuyo owner_id apunta al eliminado.
        //    Se barre por todo el repo porque no hay findByOwnerId; volumen bajo
        //    (y en este endpoint estamos en una acción admin poco frecuente).
        List<NotificationEntity> ownerNotifs = notificationRepository.findAll().stream()
                .filter(n -> ownerId.equals(n.getOwnerId()))
                .collect(Collectors.toList());
        if (!ownerNotifs.isEmpty()) {
            notificationRepository.deleteAll(ownerNotifs);
        }
        List<ActionTaskEntity> ownerTasks = actionTaskRepository.findAll().stream()
                .filter(t -> ownerId.equals(t.getOwnerId()))
                .collect(Collectors.toList());
        if (!ownerTasks.isEmpty()) {
            actionTaskRepository.deleteAll(ownerTasks);
        }

        // 12) Audit events del dueño. Regla explícita del operador: "todo registro del
        //     dueño se elimina". El audit del evento SUPERADMIN_USER_DELETE que emite
        //     el controller sobrevive porque se persiste después de este método
        //     dentro de la misma transacción (no tiene owner_id del eliminado).
        auditEventRepository.deleteByOwnerId(ownerId);

        // 13) User del dueño (hard-delete): libera el email para poder re-registrarlo.
        //     También se revocan sus sesiones y se limpian preferencias/notificaciones
        //     asociadas al propio user.
        hardDeleteUserLocal(ownerId);

        // 14) Cascada opcional sobre usuarios relacionados.
        for (String uid : candidateRelatedUserIds) {
            Optional<UserEntity> uo = userRepository.findById(uid);
            if (uo.isEmpty()) continue;
            UserEntity u = uo.get();
            if (u.getRole() == Role.SUPER_ADMIN) {
                r.relatedUsersKept++;
                continue;
            }

            // ¿Tiene todavía algún contexto operativo con OTRO dueño?
            //   · Membership (staff: property admin, accountant, agente, proveedor).
            //   · Expediente / tenant_profile con otro dueño.
            //   · Asignación de proveedor de plataforma con otro dueño.
            // Si sí → conservar; si no → hard-delete.
            boolean hasOtherMembership = !ownerMembershipRepository.findByUserId(uid).isEmpty();
            boolean hasOtherProfile = tenantProfileRepository.findByUserId(uid).stream()
                    .anyMatch(tp -> !ownerId.equals(tp.getOwnerId()));
            boolean hasOtherProviderAssignment =
                    platformProviderAssignmentRepository.findByProviderId(uid).stream()
                            .anyMatch(a -> !ownerId.equals(a.getOwnerId()));

            if (hasOtherMembership || hasOtherProfile || hasOtherProviderAssignment) {
                // Si el campo informativo users.owner_id apuntaba al dueño eliminado,
                // lo nulificamos para que la próxima vez que inicie sesión el backend
                // le resuelva un contexto operativo válido desde sus memberships /
                // profiles / asignaciones actuales (resolveOwnerId() ya hace ese
                // fallback). Dejarlo apuntando a un owner_id huérfano rompería
                // lecturas que usan ese atajo (p.ej. notificaciones dirigidas).
                if (ownerId.equals(u.getOwnerId())) {
                    u.setOwnerId(null);
                    userRepository.save(u);
                }
                r.relatedUsersKept++;
                continue;
            }

            hardDeleteUserLocal(uid);
            r.relatedUsersDeleted++;
        }

        log.warn("[CASCADE-DELETE] done ownerId={} props={} leases={} invoices={} agreements={} "
                        + "profiles={} memberships={} relatedDeleted={} relatedKept={} "
                        + "filesDeleted={} filesMissing={}",
                ownerId, r.properties, r.leases, r.invoices, r.agreements,
                r.tenantProfiles, r.memberships, r.relatedUsersDeleted, r.relatedUsersKept,
                r.filesDeleted, r.filesMissing);
        return r;
    }

    /**
     * Hard-delete de un user NO-OWNER (tenant, staff, agente, proveedor).
     *
     * Se usa cuando el SUPERADMIN elimina una cuenta desde "Gestión Global de
     * Cuentas" (GlobalSearchManager) o cuando se purgan cuentas basura desde la
     * migración. La diferencia con `hardDeleteOwner` es que aquí NO hay universo
     * de data del owner a borrar — solo las referencias personales del user:
     *
     *   · Si es TENANT: sus tenant_profiles, los invoices/payments/convenios/
     *     transfer proofs ligados a esos profiles, leases en los que aparece
     *     como tenant_id, snapshots archivados donde figura.
     *   · Si es STAFF: sus memberships, permission_grants emitidos, asignaciones
     *     de proveedor plataforma, maintenance_quotes / action_tasks / commercial
     *     activities en las que aparezca como actor.
     *   · Común: notifications, preferences, refresh_token_sessions del user.
     *
     * Nunca se llama para role=OWNER (usar hardDeleteOwner para eso). Tampoco
     * borra SUPER_ADMIN — es invariante del sistema.
     */
    @Transactional
    public void hardDeleteNonOwnerUser(String userId) {
        if (userId == null || userId.isBlank()) return;
        Optional<UserEntity> uo = userRepository.findById(userId);
        if (uo.isEmpty()) return;
        UserEntity u = uo.get();
        if (u.getRole() == Role.SUPER_ADMIN) {
            throw new IllegalStateException("SUPER_ADMIN no se elimina por esta vía.");
        }
        if (u.getRole() == Role.OWNER) {
            throw new IllegalStateException(
                    "Para role=OWNER usa hardDeleteOwner para borrar también su universo operativo.");
        }

        // Si es TENANT, barrer su línea financiera antes de borrar profiles.
        if (u.getRole() == Role.TENANT) {
            List<TenantProfileEntity> profiles = tenantProfileRepository.findByUserId(userId);
            Set<String> profileIds = profiles.stream()
                    .map(TenantProfileEntity::getId)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toSet());

            if (!profileIds.isEmpty()) {
                // PaymentAgreementRepository no tiene findByTenantProfileId; los cazamos
                // via findByOwnerId del owner de cada profile y filtramos por profile_id.
                Set<String> ownerScope = profiles.stream()
                        .map(TenantProfileEntity::getOwnerId)
                        .filter(Objects::nonNull)
                        .collect(Collectors.toSet());
                for (String oid : ownerScope) {
                    // Convenios
                    List<PaymentAgreementEntity> scoped = paymentAgreementRepository.findByOwnerId(oid).stream()
                            .filter(a -> a.getTenantProfileId() != null
                                    && profileIds.contains(a.getTenantProfileId()))
                            .collect(Collectors.toList());
                    for (PaymentAgreementEntity a : scoped) {
                        List<AgreementInstallmentEntity> inst =
                                agreementInstallmentRepository.findByAgreementId(a.getId());
                        if (!inst.isEmpty()) agreementInstallmentRepository.deleteAll(inst);
                    }
                    if (!scoped.isEmpty()) paymentAgreementRepository.deleteAll(scoped);

                    // Comprobantes (CEP + transfer proofs).
                    List<TransferProofSubmission> proofs =
                            transferProofSubmissionRepository.findByOwnerId(oid).stream()
                                    .filter(p -> p.getTenantProfileId() != null
                                            && profileIds.contains(p.getTenantProfileId()))
                                    .collect(Collectors.toList());
                    for (TransferProofSubmission p : proofs) {
                        List<CepValidationAttempt> atts =
                                cepValidationAttemptRepository.findByTransferProofId(p.getId());
                        if (!atts.isEmpty()) cepValidationAttemptRepository.deleteAll(atts);
                    }
                    if (!proofs.isEmpty()) transferProofSubmissionRepository.deleteAll(proofs);

                    // Pagos e invoices ligados al profile.
                    List<PaymentEntity> pays = paymentRepository.findByOwnerId(oid).stream()
                            .filter(p -> p.getTenantProfileId() != null
                                    && profileIds.contains(p.getTenantProfileId()))
                            .collect(Collectors.toList());
                    if (!pays.isEmpty()) paymentRepository.deleteAll(pays);

                    List<InvoiceEntity> invs = invoiceRepository.findByOwnerId(oid).stream()
                            .filter(i -> i.getTenantProfileId() != null
                                    && profileIds.contains(i.getTenantProfileId()))
                            .collect(Collectors.toList());
                    if (!invs.isEmpty()) invoiceRepository.deleteAll(invs);
                }
            }

            // Leases donde el tenant aparece como tenant_id. Se barren por owner del lease.
            for (TenantProfileEntity p : profiles) {
                if (p.getOwnerId() == null) continue;
                List<LeaseEntity> tenantLeases = leaseRepository.findByOwnerId(p.getOwnerId()).stream()
                        .filter(l -> l.getTenant() != null && userId.equals(l.getTenant().getId()))
                        .collect(Collectors.toList());
                for (LeaseEntity l : tenantLeases) {
                    List<LeaseFileEntity> files = leaseFileRepository.findByLeaseId(l.getId());
                    if (!files.isEmpty()) leaseFileRepository.deleteAll(files);
                }
                if (!tenantLeases.isEmpty()) leaseRepository.deleteAll(tenantLeases);
            }

            // Snapshots archivados del tenant.
            for (TenantProfileEntity p : profiles) {
                if (p.getOwnerId() == null) continue;
                List<TenantArchiveSnapshotEntity> snaps = tenantArchiveSnapshotRepository
                        .findByOwnerIdOrderByArchivedAtDesc(p.getOwnerId()).stream()
                        .filter(s -> userId.equals(s.getTenantUserId()))
                        .collect(Collectors.toList());
                if (!snaps.isEmpty()) tenantArchiveSnapshotRepository.deleteAll(snaps);
            }

            if (!profiles.isEmpty()) tenantProfileRepository.deleteAll(profiles);
        }

        // Staff common: membership propio, permission grants propios, provider assignments.
        List<OwnerMembershipEntity> userMemberships = ownerMembershipRepository.findByUserId(userId);
        if (!userMemberships.isEmpty()) ownerMembershipRepository.deleteAll(userMemberships);

        hardDeleteUserLocal(userId);
    }

    /**
     * Hard-delete de un user incluyendo dependencias personales (sesiones,
     * notificaciones, preferencias, action tasks). NO toca datos operativos
     * ligados a owner_id — esos ya los borró la cascada de dueño antes.
     *
     * Si por alguna razón el user referenciado aún no existe, es no-op.
     */
    private void hardDeleteUserLocal(String userId) {
        if (userId == null || userId.isBlank()) return;

        refreshTokenSessionRepository.deleteByUserId(userId);

        List<NotificationPreferenceEntity> prefs = notificationPreferenceRepository.findByUserId(userId);
        if (!prefs.isEmpty()) {
            notificationPreferenceRepository.deleteAll(prefs);
        }
        List<NotificationEntity> notifs = notificationRepository.findByUserIdOrderByCreatedAtDesc(userId);
        if (!notifs.isEmpty()) {
            notificationRepository.deleteAll(notifs);
        }
        List<ActionTaskEntity> tasks = actionTaskRepository.findByUserIdOrderByCreatedAtDesc(userId);
        if (!tasks.isEmpty()) {
            actionTaskRepository.deleteAll(tasks);
        }

        // platform_provider_assignments.provider_id → users(id) sin ON DELETE CASCADE.
        // Limpiarlas antes del delete del user para no romper la FK.
        platformProviderAssignmentRepository.deleteByProviderId(userId);

        // Permission grants emitidas al user (acceso cross-owner otorgado a staff).
        List<PermissionGrantEntity> userGrants = permissionGrantRepository.findByUserId(userId);
        if (!userGrants.isEmpty()) {
            permissionGrantRepository.deleteAll(userGrants);
        }

        userRepository.findById(userId).ifPresent(userRepository::delete);
    }

    // ─── Staff / provider lifecycle (activity-aware delete + username tombstone) ──

    /**
     * Resultado de {@link #deleteOrTombstoneStaffOrProvider(String)}:
     *  · {@code hardDeleted=true}  → el user se borró físicamente.
     *  · {@code hardDeleted=false} → hubo actividad histórica (cotizaciones,
     *                                tickets, egresos, movimientos...) que no
     *                                podemos perder sin romper auditoría. Se
     *                                tombstoneó: la fila sigue existiendo pero
     *                                se wipearon contactos/MFA/password y se
     *                                renombró el username a un placeholder
     *                                determinista para liberar el original.
     *
     * <p>V54 — {@code originalContactEmail} reemplaza al antiguo
     * {@code originalEmail}; {@code tombstoneUsername} sustituye al antiguo
     * {@code tombstoneEmail}. El único campo único en users es {@code username}
     * (V48); email y phone son libres desde V54.</p>
     */
    public record StaffDeletionOutcome(boolean hardDeleted,
                                        String originalContactEmail,
                                        String tombstoneUsername) {}

    /**
     * Decide entre hard-delete y tombstone según si el user (staff / provider /
     * agente) tiene actividad histórica que romper no es aceptable (cotizaciones,
     * tickets, egresos, movimientos...).
     *
     * <p>V54: email y phone ya no son únicos en DB, así que "liberarlos" es no-op
     * — cualquier nueva cuenta puede usarlos. Lo único que seguimos liberando es
     * el {@code username} (único en V48) vía {@code usernameService.tombstoneUsername}.
     * El wipe de contactEmail/contactPhone al tombstonear es por privacidad (PII
     * del user dado de baja), no por unicidad.</p>
     *
     * No se puede llamar con role=OWNER, SUPER_ADMIN ni TENANT:
     *   · OWNER    → usar {@link #hardDeleteOwner(String)}.
     *   · TENANT   → flujo de archivado de expediente antes (snapshot).
     *   · SUPER_ADMIN → invariante de sistema, nunca se borra por esta vía.
     */
    @Transactional
    public StaffDeletionOutcome deleteOrTombstoneStaffOrProvider(String userId) {
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("userId requerido.");
        }
        UserEntity u = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalStateException("Usuario no encontrado: " + userId));
        Role r = u.getRole();
        if (r == Role.SUPER_ADMIN) {
            throw new IllegalStateException("SUPER_ADMIN no se elimina por esta vía.");
        }
        if (r == Role.OWNER) {
            throw new IllegalStateException(
                    "Para role=OWNER usa hardDeleteOwner para barrer también su universo operativo.");
        }
        if (r == Role.TENANT) {
            throw new IllegalStateException(
                    "Para role=TENANT usa el flujo de archivado (snapshot) antes de dar de baja.");
        }

        String originalContactEmail = u.getContactEmail();
        String originalUsername = u.getLoginUsername();

        if (!hasBlockingStaffActivity(userId)) {
            hardDeleteNonOwnerUser(userId);
            log.warn("[STAFF-LIFECYCLE] HARD_DELETED userId={} originalUsername={} originalContactEmail={}",
                    userId, originalUsername, originalContactEmail);
            return new StaffDeletionOutcome(true, originalContactEmail, null);
        }

        // Ruta con actividad: tombstone del username + wipe de PII. Se preservan
        // tablas donde el userId aparece como actor/provider/assignee para mantener
        // el trail (maintenance_quotes.provider_id, expenses.provider_user_id,
        // property_movements.actor_user_id, etc.).
        tombstoneStaffOrProviderInternal(u);
        String tombstoneUsername = u.getLoginUsername();
        log.warn("[STAFF-LIFECYCLE] TOMBSTONED userId={} originalUsername={} tombstoneUsername={}",
                userId, originalUsername, tombstoneUsername);
        return new StaffDeletionOutcome(false, originalContactEmail, tombstoneUsername);
    }

    /**
     * V54 — tombstone del user:
     *  · Libera el username (vía {@code usernameService.tombstoneUsername}) —
     *    es el único campo único en DB.
     *  · Borra PII personal (contactEmail, contactPhone, phone, MFA).
     *  · Scramblea la password (aún si se filtra la fila cifrada, no sirve).
     *  · Borra memberships, permisos, sesiones, activation tokens,
     *    notificaciones, tasks y preferencias.
     *  · Desactiva (no borra) platform_provider_assignments para preservar la
     *    referencia histórica desde maintenance_tickets.assigned_provider_id.
     */
    private void tombstoneStaffOrProviderInternal(UserEntity u) {
        u.setContactEmail(null);
        u.setContactPhone(null);
        u.setContactCountryCode(null);
        u.setPhone(null);
        u.setActive(false);
        if (u.getDeletedAt() == null) {
            u.setDeletedAt(LocalDateTime.now());
        }
        u.setMfaEnabled(false);
        u.setMfaSecret(null);
        u.setMustChangePassword(true);
        if (u.getPermissions() != null) {
            u.getPermissions().clear();
        } else {
            u.setPermissions(new HashSet<>());
        }
        // Scramble password: BCrypt sobre random (el plaintext nunca sale de este
        // frame). Aun si alguien capturara la columna encriptada, no es útil.
        u.setPassword(passwordEncoder.encode(UUID.randomUUID() + "-" + UUID.randomUUID()));
        userRepository.save(u);

        // V54 — liberar username original para reuso. Es el único campo único
        // en users (V48); email y phone ya eran libres. La fila persiste con
        // placeholder determinista para preservar integridad referencial.
        usernameService.tombstoneUsername(u);

        // Revocar todas las sesiones refresh activas.
        refreshTokenSessionRepository.deleteByUserId(u.getId());

        // Invalidar cualquier link de activación pendiente: al tombstonear la
        // cuenta ya no es accionable, cualquier URL previa que siga en un
        // inbox deja de servir de inmediato.
        try {
            userActivationTokenRepository.deleteByUserId(u.getId());
        } catch (Exception e) {
            log.warn("[CASCADE] No se pudieron eliminar activation tokens del user {}: {}",
                    u.getId(), e.getMessage());
        }

        // Borrar notificaciones y preferencias personales (son datos personales PII
        // del user; con su baja pierden razón de persistir). Los audit_events quedan
        // intactos porque son la bitácora del SUPER_ADMIN y del sistema.
        List<NotificationPreferenceEntity> prefs = notificationPreferenceRepository.findByUserId(u.getId());
        if (!prefs.isEmpty()) {
            notificationPreferenceRepository.deleteAll(prefs);
        }
        List<NotificationEntity> notifs = notificationRepository.findByUserIdOrderByCreatedAtDesc(u.getId());
        if (!notifs.isEmpty()) {
            notificationRepository.deleteAll(notifs);
        }
        List<ActionTaskEntity> tasks = actionTaskRepository.findByUserIdOrderByCreatedAtDesc(u.getId());
        if (!tasks.isEmpty()) {
            actionTaskRepository.deleteAll(tasks);
        }

        // Cortar acceso cross-owner: memberships y permission_grants emitidos al user.
        List<OwnerMembershipEntity> userMemberships = ownerMembershipRepository.findByUserId(u.getId());
        if (!userMemberships.isEmpty()) {
            ownerMembershipRepository.deleteAll(userMemberships);
        }
        List<PermissionGrantEntity> userGrants = permissionGrantRepository.findByUserId(u.getId());
        if (!userGrants.isEmpty()) {
            permissionGrantRepository.deleteAll(userGrants);
        }

        // Desactivar (no borrar) asignaciones platform_provider_assignments: las
        // filas se referencian desde tickets históricos y cotizaciones. Activas=false
        // garantiza que el routing automático ya no lo asigne.
        List<PlatformProviderAssignmentEntity> assignments =
                platformProviderAssignmentRepository.findByProviderId(u.getId());
        for (PlatformProviderAssignmentEntity a : assignments) {
            if (a.isActive()) {
                a.setActive(false);
                platformProviderAssignmentRepository.save(a);
            }
        }
    }

    /**
     * Devuelve {@code true} si el user tiene al menos una referencia "dura" en
     * tablas operativas que hacen que un hard-delete rompa auditoría. Es defensivo:
     * cada consulta se envuelve en try/catch (si una tabla no existiera en algún
     * entorno de test/branch, devuelve 0 para esa tabla — se prefiere un falso
     * negativo ahí, porque ya hay otras tablas cubriendo el caso).
     *
     * Tablas verificadas (columnas que referencian users.id por valor, sin FK
     * cascade, cuya pérdida rompería el trail):
     * <ul>
     *   <li>maintenance_quotes.provider_id</li>
     *   <li>maintenance_tickets.assigned_provider_id</li>
     *   <li>expenses.provider_user_id</li>
     *   <li>maintenance_budgets.provider_user_id / submitted_by_user_id / decided_by_user_id</li>
     *   <li>commercial_activities.agent_user_id</li>
     *   <li>property_movements.actor_user_id</li>
     *   <li>manual_payment_reminders_sent.actor_user_id</li>
     *   <li>payment_reminders_sent.recipient_user_id</li>
     *   <li>tenant_archive_snapshots.archived_by_user_id</li>
     * </ul>
     *
     * Se omiten audit_events.actor_id a propósito: casi todo user tendría ≥1
     * audit por su creación, haría que NINGÚN staff fuera hard-deletable.
     */
    private boolean hasBlockingStaffActivity(String userId) {
        String[][] refs = new String[][] {
                { "maintenance_quotes",             "provider_id" },
                { "maintenance_tickets",            "assigned_provider_id" },
                { "expenses",                       "provider_user_id" },
                { "maintenance_budgets",            "provider_user_id" },
                { "maintenance_budgets",            "submitted_by_user_id" },
                { "maintenance_budgets",            "decided_by_user_id" },
                { "commercial_activities",          "agent_user_id" },
                { "property_movements",             "actor_user_id" },
                { "manual_payment_reminders_sent",  "actor_user_id" },
                { "payment_reminders_sent",         "recipient_user_id" },
                { "tenant_archive_snapshots",       "archived_by_user_id" }
        };
        for (String[] ref : refs) {
            if (countRefs(ref[0], ref[1], userId) > 0) {
                log.info("[STAFF-LIFECYCLE] user {} has activity in {}.{} → tombstone path",
                        userId, ref[0], ref[1]);
                return true;
            }
        }
        return false;
    }

    private long countRefs(String table, String column, String userId) {
        try {
            Number n = (Number) em.createNativeQuery(
                    "SELECT COUNT(*) FROM " + table + " WHERE " + column + " = :uid")
                    .setParameter("uid", userId)
                    .getSingleResult();
            return n == null ? 0L : n.longValue();
        } catch (Exception e) {
            // Tabla/columna no existe en este entorno (tests, branches). Devolvemos
            // 0 para no bloquear; otras tablas cubren el caso general.
            log.debug("[STAFF-LIFECYCLE] countRefs({}, {}) failed: {}", table, column, e.getMessage());
            return 0L;
        }
    }
}
