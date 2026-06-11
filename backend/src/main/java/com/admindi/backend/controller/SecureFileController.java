package com.admindi.backend.controller;

import com.admindi.backend.model.*;
import com.admindi.backend.repository.*;
import com.admindi.backend.security.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Servir archivos sensibles (contratos, comprobantes SPEI, evidencias de convenios,
 * documentos de inmueble, presupuestos de mantenimiento) con autorización explícita
 * por recurso — no por URL directa al disco.
 *
 * <h2>Por qué existe este controller</h2>
 * <p>La exposición previa vía {@code /uploads/**} como recurso estático (mapeado en
 * {@code WebConfig.addResourceHandlers}) tenía dos problemas graves de seguridad:</p>
 * <ol>
 *   <li><b>IDOR</b>: cualquier usuario autenticado con un JWT válido podía descargar
 *       PDFs de cualquier dueño si adivinaba/copiaba la URL (p. ej. un inquilino
 *       del dueño A descargando el contrato del dueño B). El único control era la
 *       existencia de JWT, no la pertenencia del archivo al contexto del llamante.</li>
 *   <li><b>URLs no-revocables</b>: las URLs {@code /uploads/...} son estables en el
 *       tiempo, por lo que un link filtrado (Slack, email, captura) quedaba accesible
 *       indefinidamente, sin expiración ni posibilidad de invalidar.</li>
 *   <li><b>Browser nav sin Authorization</b>: al abrir un PDF con {@code <a href>}
 *       el navegador no envía el header {@code Authorization: Bearer}, por lo que el
 *       flujo nativo del frontend se apoyaba implícitamente en que el static handler
 *       no fuera filtrado — contradicción con {@code SecurityConfig.anyRequest().authenticated()}.</li>
 * </ol>
 *
 * <h2>Modelo nuevo</h2>
 * <p>Cada tipo de archivo tiene su endpoint tipado (p. ej.
 * {@code /api/secure-files/lease-document/{leaseId}}). El endpoint:</p>
 * <ul>
 *   <li>Recupera el registro de DB.</li>
 *   <li>Verifica que el llamante esté autorizado para leer ese recurso concreto
 *       (match por ownerId del contexto, tenant propietario del expediente,
 *       membership de staff, o SUPER_ADMIN).</li>
 *   <li>Transmite el archivo con {@code Content-Disposition: inline} y el nombre
 *       original para preview natural en el navegador.</li>
 * </ul>
 *
 * <p>El frontend consume estos endpoints con axios + {@code responseType: 'blob'},
 * obtiene el blob en memoria y lo abre con {@code URL.createObjectURL}. Así:</p>
 * <ul>
 *   <li>El header {@code Authorization} viaja en cada request.</li>
 *   <li>El blob URL es efímero y local al tab; no se puede compartir externamente.</li>
 *   <li>Cuando el usuario cierra la pestaña, el objeto queda elegible para GC.</li>
 * </ul>
 *
 * <p>Si en el futuro se migra a S3/GCS, aquí se firmará una URL presigned corta en
 * vez de servir el byte stream — el contrato de autorización de este controller no
 * cambia.</p>
 */
@RestController
public class SecureFileController {

    private static final Logger log = LoggerFactory.getLogger(SecureFileController.class);

    @Autowired private UserRepository userRepository;
    @Autowired private LeaseRepository leaseRepository;
    @Autowired private LeaseFileRepository leaseFileRepository;
    @Autowired private PropertyFileRepository propertyFileRepository;
    @Autowired private PropertyRepository propertyRepository;
    @Autowired private PaymentAgreementRepository paymentAgreementRepository;
    @Autowired private TransferProofSubmissionRepository transferProofSubmissionRepository;
    @Autowired private MaintenanceBudgetRepository maintenanceBudgetRepository;
    @Autowired private TenantProfileRepository tenantProfileRepository;
    @Autowired private OwnerMembershipRepository ownerMembershipRepository;
    @Autowired private PlatformProviderAssignmentRepository platformProviderAssignmentRepository;
    @Autowired private FileUploadClaimRepository fileUploadClaimRepository;
    @Autowired private MaintenanceTicketRepository maintenanceTicketRepository;
    @Autowired private MaintenanceQuoteRepository maintenanceQuoteRepository;
    // V65 — para autorizar archivos consumed_resource_type='EXPENSE'.
    @Autowired private ExpenseRepository expenseRepository;

    // ---------------------------------------------------------------------
    // Endpoints públicos (requieren JWT via SecurityFilterChain)
    // ---------------------------------------------------------------------

    /**
     * Descarga el contrato firmado del lease ({@code leases.document_url}).
     * Autorizado: dueño del lease, inquilino del lease, staff con membership al
     * dueño, o SUPER_ADMIN.
     */
    @GetMapping("/api/secure-files/lease-document/{leaseId}")
    public ResponseEntity<Resource> downloadLeaseDocument(@PathVariable String leaseId) {
        LeaseEntity lease = leaseRepository.findById(leaseId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Contrato no encontrado."));
        UserEntity caller = resolveCaller();
        authorizeLease(lease, caller);
        String path = lease.getDocumentUrl();
        if (path == null || path.isBlank()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Este contrato no tiene PDF adjunto.");
        }
        String fileName = "contrato-" + leaseId + ".pdf";
        return streamFile(path, fileName, "application/pdf", caller, "lease-document", leaseId);
    }

    /**
     * Descarga un anexo de lease ({@code lease_files}). Misma autorización que el
     * lease padre.
     */
    @GetMapping("/api/secure-files/lease-file/{fileId}")
    public ResponseEntity<Resource> downloadLeaseFile(@PathVariable String fileId) {
        LeaseFileEntity file = leaseFileRepository.findById(fileId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Archivo no encontrado."));
        LeaseEntity lease = leaseRepository.findById(file.getLeaseId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Lease del archivo no encontrado."));
        UserEntity caller = resolveCaller();
        authorizeLease(lease, caller);
        return streamFile(file.getFilePath(), file.getFileName(), file.getContentType(),
                caller, "lease-file", fileId);
    }

    /**
     * Descarga un documento de inmueble ({@code property_files}). Autorizado:
     * dueño del inmueble, staff con membership, o SUPER_ADMIN.
     */
    @GetMapping("/api/secure-files/property-file/{fileId}")
    public ResponseEntity<Resource> downloadPropertyFile(@PathVariable String fileId) {
        PropertyFileEntity file = propertyFileRepository.findById(fileId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Archivo no encontrado."));
        PropertyEntity property = propertyRepository.findById(file.getPropertyId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Inmueble del archivo no encontrado."));
        UserEntity caller = resolveCaller();
        authorizeOwnerScope(property.getOwnerId(), caller);
        return streamFile(file.getFilePath(), file.getFileName(), file.getContentType(),
                caller, "property-file", fileId);
    }

    /**
     * Descarga la evidencia firmada de un convenio ({@code payment_agreements.evidence_file_url}).
     * Autorizado: dueño del convenio, inquilino titular, staff con membership, SUPER_ADMIN.
     */
    @GetMapping("/api/secure-files/agreement-evidence/{agreementId}")
    public ResponseEntity<Resource> downloadAgreementEvidence(@PathVariable String agreementId) {
        PaymentAgreementEntity ag = paymentAgreementRepository.findById(agreementId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Convenio no encontrado."));
        UserEntity caller = resolveCaller();
        authorizeAgreement(ag, caller);
        String path = ag.getEvidenceFileUrl();
        if (path == null || path.isBlank()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "El convenio no tiene evidencia adjunta.");
        }
        return streamFile(path, "convenio-" + agreementId + ".pdf", "application/pdf",
                caller, "agreement-evidence", agreementId);
    }

    /**
     * Descarga el comprobante SPEI/CEP ({@code transfer_proof_submissions.file_url}).
     * Autorizado: dueño que valida, inquilino que subió (vía tenant_profile.user_id),
     * staff con membership, SUPER_ADMIN.
     */
    @GetMapping("/api/secure-files/transfer-proof/{proofId}")
    public ResponseEntity<Resource> downloadTransferProof(@PathVariable String proofId) {
        TransferProofSubmission proof = transferProofSubmissionRepository.findById(proofId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Comprobante no encontrado."));
        UserEntity caller = resolveCaller();
        authorizeTransferProof(proof, caller);
        String path = proof.getFileUrl();
        if (path == null || path.isBlank()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Comprobante sin archivo.");
        }
        return streamFile(path, "comprobante-" + proofId, null, caller, "transfer-proof", proofId);
    }

    /**
     * Descarga el CEP oficial XML descargado de Banxico para un comprobante validado.
     */
    @GetMapping("/api/secure-files/transfer-proof-cep-xml/{proofId}")
    public ResponseEntity<Resource> downloadTransferProofCepXml(@PathVariable String proofId) {
        TransferProofSubmission proof = transferProofSubmissionRepository.findById(proofId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Comprobante no encontrado."));
        UserEntity caller = resolveCaller();
        authorizeTransferProof(proof, caller);
        String path = proof.getCepXmlUrl();
        if (path == null || path.isBlank()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Este comprobante aún no tiene CEP XML guardado.");
        }
        return streamFile(path, "cep-banxico-" + proofId + ".xml", "application/xml",
                caller, "transfer-proof-cep-xml", proofId);
    }

    /**
     * Descarga el CEP oficial PDF descargado de Banxico para un comprobante validado.
     */
    @GetMapping("/api/secure-files/transfer-proof-cep-pdf/{proofId}")
    public ResponseEntity<Resource> downloadTransferProofCepPdf(@PathVariable String proofId) {
        TransferProofSubmission proof = transferProofSubmissionRepository.findById(proofId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Comprobante no encontrado."));
        UserEntity caller = resolveCaller();
        authorizeTransferProof(proof, caller);
        String path = proof.getCepPdfUrl();
        if (path == null || path.isBlank()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Este comprobante aún no tiene CEP PDF guardado.");
        }
        return streamFile(path, "cep-banxico-" + proofId + ".pdf", "application/pdf",
                caller, "transfer-proof-cep-pdf", proofId);
    }

    /**
     * Descarga el adjunto de un presupuesto de mantenimiento ({@code maintenance_budgets.file_url}).
     * Autorizado: dueño, proveedor que subió, staff con membership, SUPER_ADMIN.
     */
    @GetMapping("/api/secure-files/maintenance-budget/{budgetId}")
    public ResponseEntity<Resource> downloadMaintenanceBudget(@PathVariable String budgetId) {
        MaintenanceBudgetEntity budget = maintenanceBudgetRepository.findById(budgetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Presupuesto no encontrado."));
        UserEntity caller = resolveCaller();
        authorizeMaintenanceBudget(budget, caller);
        String path = budget.getFileUrl();
        if (path == null || path.isBlank()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Presupuesto sin archivo.");
        }
        return streamFile(path, "presupuesto-" + budgetId, null, caller, "maintenance-budget", budgetId);
    }

    /**
     * V61 — Descarga genérica de un adjunto registrado como {@code file_upload_claims}.
     *
     * <p>Permite servir fotos y PDFs que circulan por el sistema como paths
     * opacos (el {@code fileId} que devuelven los controllers de upload). La
     * autorización se basa en quién consumió el claim:</p>
     * <ul>
     *   <li>{@code MAINTENANCE_TICKET}: owner del ticket, tenant autor, staff
     *       con membership o proveedor asignado.</li>
     *   <li>{@code MAINTENANCE_QUOTE}: owner del ticket padre, proveedor autor,
     *       tenant del ticket, staff.</li>
     *   <li>Claims sin consumir: sólo el uploader o SUPER_ADMIN.</li>
     * </ul>
     *
     * <p>El parámetro viaja como query {@code ?fileId=<URL-encoded-path>} porque
     * los path interno contienen {@code /} que complican {@code @PathVariable}.</p>
     */
    @GetMapping("/api/secure-files/file-attachment")
    public ResponseEntity<Resource> downloadFileAttachment(@RequestParam("fileId") String fileId) {
        if (fileId == null || fileId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "fileId obligatorio.");
        }
        FileUploadClaimEntity claim = fileUploadClaimRepository.findByFilePath(fileId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Archivo no registrado."));
        UserEntity caller = resolveCaller();

        String rt = claim.getConsumedResourceType();
        String rid = claim.getConsumedResourceId();

        // V62 — fallback: si el claim no trae consumed_* (caso de archivos
        // subidos ANTES de que el service cableara markConsumed, o uploads que
        // aún no se asociaron a un recurso), intentamos descubrir el consumer
        // inspeccionando las tablas que referencian el path:
        //  1) maintenance_quotes.evidence_file_id
        //  2) maintenance_tickets.photo_file_ids (jsonb array)
        // Si alguno aparece, autorizamos contra el ticket padre. Si no, caemos
        // al fallback restrictivo de "solo uploader".
        if (rt == null || rid == null) {
            Optional<MaintenanceQuoteEntity> qByFile = maintenanceQuoteRepository
                    .findFirstByEvidenceFileId(claim.getFilePath());
            if (qByFile.isPresent()) {
                rt = "MAINTENANCE_QUOTE";
                rid = qByFile.get().getId();
            } else {
                List<MaintenanceTicketEntity> ticketMatches = maintenanceTicketRepository
                        .findByPhotoFilePath(claim.getFilePath());
                if (!ticketMatches.isEmpty()) {
                    rt = "MAINTENANCE_TICKET";
                    rid = ticketMatches.get(0).getId();
                } else {
                    // V65 — fallback para expenses: buscar el path en las 3 columnas
                    // (evidence_file_id, budget_file_id, payment_proof_file_id).
                    List<ExpenseEntity> expenseMatches = expenseRepository
                            .findByAnyFileReference(claim.getFilePath());
                    if (!expenseMatches.isEmpty()) {
                        rt = "EXPENSE";
                        rid = expenseMatches.get(0).getId();
                    }
                }
            }
        }

        if ("MAINTENANCE_TICKET".equals(rt) && rid != null) {
            MaintenanceTicketEntity ticket = maintenanceTicketRepository.findById(rid)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                            "Ticket asociado al archivo no encontrado."));
            authorizeMaintenanceTicketAccess(ticket, caller);
        } else if ("MAINTENANCE_QUOTE".equals(rt) && rid != null) {
            MaintenanceQuoteEntity quote = maintenanceQuoteRepository.findById(rid)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                            "Cotización asociada al archivo no encontrada."));
            MaintenanceTicketEntity ticket = maintenanceTicketRepository
                    .findById(quote.getTicketId())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                            "Ticket padre del quote no encontrado."));
            authorizeMaintenanceTicketAccess(ticket, caller);
        } else if ("EXPENSE".equals(rt) && rid != null) {
            // V65 — autorización de archivos ligados a un expense.
            // · Owner del expense.
            // · Staff con membership al owner.
            // · Proveedor asignado (si el expense referencia un ticket con
            //   assignedProviderId == caller) — esto habilita que el provider
            //   vea el comprobante SPEI del dueño para confirmar el pago.
            // · SUPER_ADMIN.
            ExpenseEntity expense = expenseRepository.findById(rid)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                            "Egreso asociado al archivo no encontrado."));
            authorizeExpenseAccess(expense, caller);
        } else {
            // Sin consumer registrado — sólo el uploader o SUPER_ADMIN pueden leerlo.
            if (caller.getRole() != Role.SUPER_ADMIN
                    && !caller.getId().equals(claim.getUploaderUserId())) {
                log.warn("[SECURE-FILES] file-attachment acceso denegado caller={} uploader={} path={}",
                        caller.getId(), claim.getUploaderUserId(), fileId);
                throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                        "No tienes acceso a este archivo.");
            }
        }

        return streamFile(claim.getFilePath(),
                "adjunto-" + (claim.getId() != null ? claim.getId() : "file"),
                null, caller, "file-attachment", claim.getId());
    }

    /**
     * Autorización para ver adjuntos de un ticket de mantenimiento. Permite:
     *  - SUPER_ADMIN (global).
     *  - OWNER del ticket.
     *  - PROPERTY_ADMIN con membership al ownerId.
     *  - TENANT autor (via tenant_profile.user_id).
     *  - MAINTENANCE_PROVIDER asignado al ticket.
     */
    private void authorizeMaintenanceTicketAccess(MaintenanceTicketEntity ticket, UserEntity caller) {
        if (caller.getRole() == Role.SUPER_ADMIN) return;
        if (caller.getRole() == Role.OWNER
                && ticket.getOwnerId() != null
                && ticket.getOwnerId().equals(caller.getId())) return;
        if (caller.getRole() == Role.TENANT && ticket.getTenantProfileId() != null) {
            Optional<TenantProfileEntity> tp = tenantProfileRepository.findById(ticket.getTenantProfileId());
            if (tp.isPresent() && caller.getId().equals(tp.get().getUserId())) return;
        }
        if (caller.getRole() == Role.MAINTENANCE_PROVIDER
                && ticket.getAssignedProviderId() != null
                && ticket.getAssignedProviderId().equals(caller.getId())) return;
        if (ticket.getOwnerId() != null && ownerMembershipRepository
                .findByUserIdAndOwnerId(caller.getId(), ticket.getOwnerId()).isPresent()) {
            return;
        }
        log.warn("[SECURE-FILES] maintenance-ticket IDOR rejected caller={} role={} ticket={}",
                caller.getId(), caller.getRole(), ticket.getId());
        throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                "No tienes acceso a este archivo del ticket.");
    }

    /**
     * V65 — autorización para archivos ligados a un expense.
     *
     * <p>Permite:
     *  - SUPER_ADMIN.
     *  - OWNER del expense.
     *  - Staff (PROPERTY_ADMIN / ACCOUNTANT) con membership al owner.
     *  - MAINTENANCE_PROVIDER asignado al ticket padre (cuando el expense
     *    referencia un maintenance_ticket): habilita que el provider vea el
     *    comprobante SPEI que subió el dueño, para poder confirmar el pago.
     * </p>
     */
    private void authorizeExpenseAccess(ExpenseEntity expense, UserEntity caller) {
        if (caller.getRole() == Role.SUPER_ADMIN) return;
        String expenseOwner = expense.getOwnerId();
        if (caller.getRole() == Role.OWNER && expenseOwner != null
                && expenseOwner.equals(caller.getId())) return;
        if (expenseOwner != null && ownerMembershipRepository
                .findByUserIdAndOwnerId(caller.getId(), expenseOwner).isPresent()) {
            return;
        }
        if (caller.getRole() == Role.MAINTENANCE_PROVIDER
                && "MAINTENANCE_TICKET".equals(expense.getLinkedResourceType())
                && expense.getLinkedResourceId() != null) {
            Optional<MaintenanceTicketEntity> tOpt =
                    maintenanceTicketRepository.findById(expense.getLinkedResourceId());
            if (tOpt.isPresent() && caller.getId().equals(tOpt.get().getAssignedProviderId())) {
                return;
            }
        }
        log.warn("[SECURE-FILES] expense IDOR rejected caller={} role={} expense={}",
                caller.getId(), caller.getRole(), expense.getId());
        throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                "No tienes acceso a este archivo del egreso.");
    }

    // ---------------------------------------------------------------------
    // Helpers de autorización
    // ---------------------------------------------------------------------

    /**
     * Resuelve el {@link UserEntity} del llamante a partir del JWT ya validado
     * por {@code JwtAuthenticationFilter}. Nunca retorna null: si el token no
     * resuelve a un user real, tira 401.
     */
    private UserEntity resolveCaller() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getName() == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sin autenticación.");
        }
        return userRepository.findByLoginIdentifier(auth.getName())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Usuario del token no existe."));
    }

    /**
     * Autorización genérica a un universo de dueño. Permitido si:
     *  - caller es SUPER_ADMIN (acceso global).
     *  - caller es el OWNER y matchea el ownerId objetivo.
     *  - caller es staff (PROPERTY_ADMIN / ACCOUNTANT) con membership activa al
     *    ownerId objetivo en {@code owner_memberships}.
     *  - caller es provider (REAL_ESTATE_AGENT / MAINTENANCE_PROVIDER) con
     *    asignación ACTIVA en {@code platform_provider_assignments}. Esta tabla
     *    es la fuente de verdad para vínculos provider↔dueño (el flujo de
     *    provider nunca escribe en {@code owner_memberships}).
     *  - (No se permite solo porque el token traiga ese ownerId en claims: hay
     *    que tener la relación real en DB, para que tokens viejos no abran
     *    archivos de organizaciones a las que el usuario ya no pertenece.)
     */
    private void authorizeOwnerScope(String targetOwnerId, UserEntity caller) {
        if (targetOwnerId == null || targetOwnerId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Recurso sin dueño resoluble.");
        }
        if (caller.getRole() == Role.SUPER_ADMIN) {
            return;
        }
        if (caller.getRole() == Role.OWNER && targetOwnerId.equals(caller.getId())) {
            return;
        }
        if (ownerMembershipRepository.findByUserIdAndOwnerId(caller.getId(), targetOwnerId).isPresent()) {
            return;
        }
        if (caller.getRole() == Role.MAINTENANCE_PROVIDER || caller.getRole() == Role.REAL_ESTATE_AGENT) {
            boolean activeAssignment = platformProviderAssignmentRepository
                    .findByProviderIdAndOwnerId(caller.getId(), targetOwnerId)
                    .map(a -> a.isActive())
                    .orElse(false);
            if (activeAssignment) {
                return;
            }
        }
        // Fallback por JWT ownerId SOLO si el user también tiene ese ownerId en users.owner_id
        // (campo informativo). Evita abrir archivos con un claim manipulado en un token viejo.
        String jwtOwner = TenantContext.getCurrentOwner();
        if (jwtOwner != null && jwtOwner.equals(targetOwnerId)
                && targetOwnerId.equals(caller.getOwnerId())) {
            return;
        }
        log.warn("[SECURE-FILES] IDOR rejected: caller={} role={} targetOwnerId={}",
                caller.getId(), caller.getRole(), targetOwnerId);
        throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                "No tienes acceso a este archivo (distinta organización).");
    }

    /**
     * Autorización sobre un lease. Se expande sobre {@link #authorizeOwnerScope}
     * para incluir el caso del inquilino titular del contrato.
     */
    private void authorizeLease(LeaseEntity lease, UserEntity caller) {
        // Caso tenant: el propio inquilino del contrato siempre puede leer su PDF.
        if (caller.getRole() == Role.TENANT && lease.getTenant() != null
                && caller.getId().equals(lease.getTenant().getId())) {
            return;
        }
        authorizeOwnerScope(lease.getOwnerId(), caller);
    }

    /**
     * Autorización sobre un convenio. Incluye al inquilino titular del expediente
     * asociado.
     */
    private void authorizeAgreement(PaymentAgreementEntity ag, UserEntity caller) {
        if (caller.getRole() == Role.TENANT && ag.getTenantProfileId() != null) {
            Optional<TenantProfileEntity> tpOpt = tenantProfileRepository.findById(ag.getTenantProfileId());
            if (tpOpt.isPresent() && caller.getId().equals(tpOpt.get().getUserId())) {
                return;
            }
        }
        authorizeOwnerScope(ag.getOwnerId(), caller);
    }

    /**
     * Autorización sobre un comprobante de transferencia. Incluye al inquilino
     * que lo subió.
     */
    private void authorizeTransferProof(TransferProofSubmission proof, UserEntity caller) {
        if (caller.getRole() == Role.TENANT && proof.getTenantProfileId() != null) {
            Optional<TenantProfileEntity> tpOpt = tenantProfileRepository.findById(proof.getTenantProfileId());
            if (tpOpt.isPresent() && caller.getId().equals(tpOpt.get().getUserId())) {
                return;
            }
        }
        authorizeOwnerScope(proof.getOwnerId(), caller);
    }

    /**
     * Autorización sobre un presupuesto. Incluye al proveedor que lo subió.
     */
    private void authorizeMaintenanceBudget(MaintenanceBudgetEntity budget, UserEntity caller) {
        if (caller.getRole() == Role.MAINTENANCE_PROVIDER && budget.getProviderUserId() != null
                && caller.getId().equals(budget.getProviderUserId())) {
            return;
        }
        authorizeOwnerScope(budget.getOwnerId(), caller);
    }

    // ---------------------------------------------------------------------
    // Stream del archivo
    // ---------------------------------------------------------------------

    /**
     * Entrega el archivo al cliente. Usa {@code Content-Disposition: inline} para
     * que los PDFs/imágenes se previsualicen nativamente en el navegador; el
     * nombre sugerido se conserva para "Guardar como...".
     *
     * <p>V59.1 — si el caller no proporciona {@code contentType} (porque los
     * endpoints {@code transfer-proof} y {@code maintenance-budget} no guardan
     * el MIME original en DB), lo inferimos del <strong>path real</strong> del
     * archivo (extensión + {@link Files#probeContentType}). De igual manera,
     * si el {@code fileName} sugerido no trae extensión, le pegamos la del
     * archivo de disco. Sin esto el browser recibía {@code application/octet-stream}
     * y un nombre sin extensión: forzaba descarga y el usuario no podía abrir
     * la foto/PDF porque Windows/Mac no sabían con qué programa asociarlo.
     *
     * <p>Adicionalmente se envían headers estrictos:
     *  - {@code Cache-Control: private, no-store}: los documentos son sensibles
     *    y no deben quedar en caches intermediarios ni en disco del browser.
     *  - {@code X-Content-Type-Options: nosniff}: impide que el navegador cambie
     *    el tipo detectado (defensa contra MIME sniffing). Cuando el MIME
     *    provisto es correcto {@code nosniff} no estorba; cuando es
     *    {@code application/octet-stream}, {@code nosniff} es lo que evita que
     *    el browser "ayude" abriendo el archivo como HTML/script/etc.
     *  - {@code X-Download-Options: noopen}: previene que IE/legacy abran el PDF
     *    directamente en contexto del sitio.
     */
    private ResponseEntity<Resource> streamFile(String path, String fileName, String contentType,
                                                UserEntity caller, String kind, String resourceId) {
        String cleanPath = path.startsWith("/") ? path.substring(1) : path;
        Path filePath = Paths.get(cleanPath);
        Resource resource = new FileSystemResource(filePath);
        if (!resource.exists()) {
            log.warn("[SECURE-FILES] file not on disk: kind={} id={} path={}", kind, resourceId, path);
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "El archivo ya no está disponible.");
        }

        // ─── Content-Type: usa el provisto; si no, infiere del archivo ───────
        String resolvedContentType = (contentType != null && !contentType.isBlank())
                ? contentType
                : guessContentTypeFromPath(filePath);
        MediaType media;
        try {
            media = (resolvedContentType != null && !resolvedContentType.isBlank())
                    ? MediaType.parseMediaType(resolvedContentType)
                    : MediaType.APPLICATION_OCTET_STREAM;
        } catch (Exception badType) {
            media = MediaType.APPLICATION_OCTET_STREAM;
        }

        // ─── Filename: garantiza que lleve extensión para que el browser y el
        //     SO puedan abrirlo correctamente al descargar. Si el caller pasó
        //     uno sin extensión (p. ej. "comprobante-<uuid>"), le añadimos la
        //     del archivo real en disco.
        String baseName = (fileName == null || fileName.isBlank())
                ? (kind + "-" + resourceId) : fileName;
        String safeName = ensureExtension(baseName, filePath);

        log.info("[SECURE-FILES] serve kind={} id={} to user={} role={} name={} type={}",
                kind, resourceId, caller.getId(), caller.getRole(), safeName, media);
        return ResponseEntity.ok()
                .contentType(media)
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + safeName + "\"")
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .header("X-Content-Type-Options", "nosniff")
                .header("X-Download-Options", "noopen")
                .body(resource);
    }

    /**
     * Intenta inferir el MIME de un archivo local. Primero usa el probe del
     * JDK (que lee {@code mime.types} del sistema), y si devuelve null o si
     * el probe falla (p. ej. en algunos Windows el mapping de {@code .jpeg}
     * no está registrado), cae a una tabla mínima por extensión suficiente
     * para los tipos que sube el inquilino: fotos y PDFs.
     *
     * @return MIME detectado o {@code null} si no fue posible determinarlo.
     */
    private String guessContentTypeFromPath(Path filePath) {
        if (filePath == null) return null;
        try {
            String probed = Files.probeContentType(filePath);
            if (probed != null && !probed.isBlank()) return probed;
        } catch (Exception ignored) {
            // fall through al mapeo por extensión
        }
        String name = filePath.getFileName() == null ? "" : filePath.getFileName().toString();
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".webp")) return "image/webp";
        if (lower.endsWith(".gif")) return "image/gif";
        if (lower.endsWith(".heic")) return "image/heic";
        if (lower.endsWith(".heif")) return "image/heif";
        if (lower.endsWith(".pdf")) return "application/pdf";
        if (lower.endsWith(".xml")) return "application/xml";
        return null;
    }

    /**
     * Si {@code baseName} no trae extensión, le añade la del archivo real en
     * disco (última componente del path). Si {@code baseName} YA tiene
     * extensión, se respeta tal cual. Previene nombres como
     * "comprobante-abc123" que al descargar quedan sin asociación en el SO.
     */
    private String ensureExtension(String baseName, Path filePath) {
        if (baseName == null || baseName.isBlank()) baseName = "archivo";
        int baseDot = baseName.lastIndexOf('.');
        int baseSlash = Math.max(baseName.lastIndexOf('/'), baseName.lastIndexOf('\\'));
        boolean baseHasExt = baseDot > baseSlash && baseDot < baseName.length() - 1;
        if (baseHasExt) {
            return baseName;
        }
        if (filePath == null || filePath.getFileName() == null) {
            return baseName;
        }
        String diskName = filePath.getFileName().toString();
        int diskDot = diskName.lastIndexOf('.');
        if (diskDot < 0 || diskDot == diskName.length() - 1) {
            return baseName; // el archivo en disco tampoco tiene extensión
        }
        return baseName + diskName.substring(diskDot).toLowerCase(Locale.ROOT);
    }
}
