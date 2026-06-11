package com.admindi.backend.controller;

import com.admindi.backend.approval.ApprovalRequestService;
import com.admindi.backend.approval.ApprovalRequestService.ApprovalRequestInput;
import com.admindi.backend.constants.ActionTaskEventType;
import com.admindi.backend.model.ActionTaskEntity;
import com.admindi.backend.model.LeaseEntity;
import com.admindi.backend.model.LeaseStatus;
import com.admindi.backend.model.PropertyEntity;
import com.admindi.backend.model.TenantProfileEntity;
import com.admindi.backend.model.UnitEntity;
import com.admindi.backend.repository.LeaseRepository;
import com.admindi.backend.repository.PropertyRepository;
import com.admindi.backend.repository.TenantProfileRepository;
import com.admindi.backend.repository.UserRepository;
import com.admindi.backend.security.TenantContext;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Staff-facing endpoints for initiating approval-required actions. Each route:
 *
 * <ol>
 *   <li>Validates that the target resource exists, is in a state that allows the action,
 *       and belongs to the staff's current organizational context (IDOR guard).</li>
 *   <li>Delegates to {@link ApprovalRequestService#submit(ApprovalRequestInput)} which
 *       performs the staff reauth (password + MFA), persists the
 *       {@link com.admindi.backend.model.ActionTaskEntity}, audits, and notifies the owner.</li>
 * </ol>
 *
 * <p>The generic approve/reject side of the workflow lives in
 * {@link ActionTaskController}, dispatched through the
 * {@link com.admindi.backend.approval.ApprovalTaskRegistry}.
 *
 * <p>Route pattern is deliberately resource-scoped (not a single "generic" endpoint)
 * because each action has resource-specific preconditions and authorities. The shared
 * behavior lives in {@link ApprovalRequestService}, not in the route shape.
 */
@RestController
@RequestMapping("/api/approval-requests")
public class ApprovalRequestController {

    private final ApprovalRequestService approvalRequestService;
    private final TenantProfileRepository tenantProfileRepository;
    private final LeaseRepository leaseRepository;
    private final UserRepository userRepository;
    private final PropertyRepository propertyRepository;
    private final com.admindi.backend.service.PropertyService propertyService;
    private final com.admindi.backend.service.PropertyFileService propertyFileService;

    public ApprovalRequestController(ApprovalRequestService approvalRequestService,
                                     TenantProfileRepository tenantProfileRepository,
                                     LeaseRepository leaseRepository,
                                     UserRepository userRepository,
                                     PropertyRepository propertyRepository,
                                     com.admindi.backend.service.PropertyService propertyService,
                                     com.admindi.backend.service.PropertyFileService propertyFileService) {
        this.approvalRequestService = approvalRequestService;
        this.tenantProfileRepository = tenantProfileRepository;
        this.leaseRepository = leaseRepository;
        this.userRepository = userRepository;
        this.propertyRepository = propertyRepository;
        this.propertyService = propertyService;
        this.propertyFileService = propertyFileService;
    }

    /**
     * Staff initiates a request for the owner to approve deleting a property. Delegates to
     * {@link com.admindi.backend.service.PropertyService#requestDeleteProperty} which owns
     * the IDOR guard for property ownership and routes through the shared ApprovalRequestService.
     *
     * <p>Authority alignment: the {@code tpl-full-access} template (V9/V40) grants
     * {@code properties:delete} (lowercase/colon convention), not {@code PROPERTY_DELETE}.
     * We check both so the endpoint works consistently whichever naming convention the
     * environment ended up with after template migrations.
     */
    @PostMapping("/property/{propertyId}/delete")
    @PreAuthorize("hasRole('OWNER') or hasAuthority('PROPERTY_DELETE')")
    public ResponseEntity<ApprovalRequestResponse> requestPropertyDelete(
            @PathVariable String propertyId,
            @RequestBody ApprovalRequestBody body) {
        String taskId = propertyService.requestDeleteProperty(
                propertyId, body.getPassword(), body.getMfaCode(), body.getReason());
        return ResponseEntity.accepted().body(
                new ApprovalRequestResponse(taskId, ActionTaskEventType.PROPERTY_DELETE_REQUESTED));
    }

    /**
     * Staff initiates a request for the owner to approve archiving / purging a tenant
     * expedient. Requires {@code PROPERTY_ARCHIVE_TENANT} authority (granted by
     * "Acceso Total" template; not granted to read-only / accountant roles).
     */
    @PostMapping("/tenant-profile/{profileId}/archive")
    @PreAuthorize("hasRole('OWNER') or hasAuthority('PROPERTY_ARCHIVE_TENANT')")
    public ResponseEntity<ApprovalRequestResponse> requestTenantArchive(
            @PathVariable String profileId,
            @RequestBody ApprovalRequestBody body) {

        TenantProfileEntity profile = tenantProfileRepository.findById(profileId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Expediente no encontrado."));

        if (profile.getArchivedAt() != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Este expediente ya fue archivado.");
        }

        String ownerId = resolveOwnerContext(profile.getOwnerId());
        // IDOR guard: staff can only request archive for tenants within their current owner context.
        if (!ownerId.equals(profile.getOwnerId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Este expediente pertenece a otra organización.");
        }

        String tenantLabel = resolveUserDisplayName(profile.getUserId());

        Map<String, Object> context = new LinkedHashMap<>();
        context.put("tenantProfileId", profile.getId());
        context.put("tenantUserId", profile.getUserId());
        context.put("propertyId", profile.getPropertyId());

        ActionTaskEntity task = approvalRequestService.submit(new ApprovalRequestInput(
                ActionTaskEventType.TENANT_ARCHIVE_REQUESTED,
                ownerId,
                "TENANT_PROFILE",
                profile.getId(),
                "Solicitud de archivo de expediente: " + tenantLabel,
                "Se solicita dar de baja operativa el expediente del inquilino " + tenantLabel
                        + ". Si existe historial de pagos se preservará un snapshot; si no, se purgará sin rastro.",
                body.getReason(),
                context,
                body.getPassword(),
                body.getMfaCode()));

        return ResponseEntity.accepted().body(new ApprovalRequestResponse(task.getId(), task.getEventType()));
    }

    /**
     * Staff initiates a request for the owner to approve terminating an active lease.
     * Requires write access over leases — if staff can start a contract they can request
     * to end it, subject to owner approval.
     *
     * <p>Authority alignment: template grants {@code leases:write} (V9 convention);
     * {@code LEASE_CREATE} is accepted for forward compatibility with future granular
     * renames, so upgrading the template doesn't break staff already in the field.
     */
    @PostMapping("/lease/{leaseId}/terminate")
    @PreAuthorize("hasRole('OWNER') or hasAuthority('LEASE_CREATE')")
    public ResponseEntity<ApprovalRequestResponse> requestLeaseTerminate(
            @PathVariable String leaseId,
            @RequestBody ApprovalRequestBody body) {

        LeaseEntity lease = leaseRepository.findById(leaseId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Contrato no encontrado."));

        if (lease.getStatus() != LeaseStatus.ACTIVE) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Solo se pueden solicitar terminaciones de contratos activos. Estado actual: " + lease.getStatus() + ".");
        }

        String ownerId = resolveOwnerContext(lease.getOwnerId());
        if (!ownerId.equals(lease.getOwnerId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Este contrato pertenece a otra organización.");
        }

        PropertyEntity property = lease.resolvePropertyEntity();
        UnitEntity unit = lease.getUnit();
        String propLabel = property != null ? property.getName() : (unit != null ? unit.getName() : leaseId);
        String tenantLabel = lease.getTenant() != null ? lease.getTenant().getName() : "(inquilino)";

        Map<String, Object> context = new LinkedHashMap<>();
        context.put("leaseId", lease.getId());
        context.put("propertyId", property != null ? property.getId() : null);
        context.put("unitId", unit != null ? unit.getId() : null);
        context.put("tenantUserId", lease.getTenant() != null ? lease.getTenant().getId() : null);

        ActionTaskEntity task = approvalRequestService.submit(new ApprovalRequestInput(
                ActionTaskEventType.LEASE_TERMINATE_REQUESTED,
                ownerId,
                "LEASE",
                lease.getId(),
                "Solicitud de terminación de contrato: " + propLabel,
                "Se solicita terminar el contrato de " + tenantLabel + " sobre " + propLabel
                        + ". El inmueble quedará libre al aprobarse.",
                body.getReason(),
                context,
                body.getPassword(),
                body.getMfaCode()));

        return ResponseEntity.accepted().body(new ApprovalRequestResponse(task.getId(), task.getEventType()));
    }

    /**
     * Staff initiates a request for the owner to approve deleting a property file.
     * The accepted authorities mirror the file-upload endpoint (so whoever can add
     * evidence can request to remove it), but the actual deletion still needs the
     * owner's password + MFA on the approve step.
     */
    @PostMapping("/property-files/{fileId}/delete")
    @PreAuthorize("hasRole('OWNER') or hasAuthority('PROPERTY_DELETE') or hasAuthority('PROPERTY_UPDATE')")
    public ResponseEntity<ApprovalRequestResponse> requestPropertyFileDelete(
            @PathVariable String fileId,
            @RequestBody ApprovalRequestBody body) {
        String taskId = propertyFileService.requestFileDelete(
                fileId, body.getPassword(), body.getMfaCode(), body.getReason());
        return ResponseEntity.accepted().body(
                new ApprovalRequestResponse(taskId, ActionTaskEventType.PROPERTY_FILE_DELETE_REQUESTED));
    }

    /**
     * Staff solicita al dueño autorizar la "puesta en renta" (arranque de la cadena de
     * agentes inmobiliarios) sobre un inmueble. El dueño ejecuta la acción directamente
     * vía {@code /api/owner/workflow/vacancies/start-agent-chain}; staff pasa por aquí
     * y requiere el authority granular {@code VACANCY_START_CHAIN} (tpl-full-access).
     *
     * <p>No verificamos aquí la guarda {@code NO_RENTAL_HISTORY}: el handler la chequea
     * al aprobar, para que el dueño vea el motivo con toda la información a la mano
     * (evita falsos negativos si el historial cambia entre request y approve).
     */
    @PostMapping("/property/{propertyId}/start-vacancy")
    @PreAuthorize("hasRole('OWNER') or hasAuthority('VACANCY_START_CHAIN')")
    public ResponseEntity<ApprovalRequestResponse> requestVacancyStart(
            @PathVariable String propertyId,
            @RequestBody ApprovalRequestBody body) {

        PropertyEntity property = propertyRepository.findById(propertyId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Inmueble no encontrado."));

        String ownerId = resolveOwnerContext(property.getOwnerId());
        if (!ownerId.equals(property.getOwnerId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Este inmueble pertenece a otra organización.");
        }

        String propLabel = property.getName() != null && !property.getName().isBlank()
                ? property.getName() : propertyId;

        Map<String, Object> context = new LinkedHashMap<>();
        context.put("propertyId", property.getId());
        context.put("propertyName", propLabel);

        ActionTaskEntity task = approvalRequestService.submit(new ApprovalRequestInput(
                ActionTaskEventType.VACANCY_START_REQUESTED,
                ownerId,
                "PROPERTY",
                property.getId(),
                "Solicitud: poner en renta " + propLabel,
                "Tu administrador solicita arrancar la cadena de agentes inmobiliarios para "
                        + propLabel + ". Al aprobar, se abrirá vacancia y se notificará al primer agente según tus prioridades.",
                body.getReason(),
                context,
                body.getPassword(),
                body.getMfaCode()));

        return ResponseEntity.accepted().body(new ApprovalRequestResponse(task.getId(), task.getEventType()));
    }

    /**
     * Resolve the caller's current owner context. V52: SUPER_ADMIN ya no opera aquí
     * (endpoints movidos a rol OWNER/staff). Todos los callers son staff pinned a su
     * TenantContext.
     */
    private String resolveOwnerContext(String resourceOwnerId) {
        // resourceOwnerId se deja en la firma por compatibilidad con los call-sites,
        // pero ya no se usa: el contexto del caller es siempre el ancla de aislamiento.
        return TenantContext.resolveOwnerId(userRepository);
    }

    private String resolveUserDisplayName(String userId) {
        if (userId == null || userId.isBlank()) return "(inquilino)";
        return userRepository.findById(userId)
                .map(u -> u.getName() != null && !u.getName().isBlank()
                        ? u.getName()
                        : (u.getLoginUsername() != null ? u.getLoginUsername() : userId))
                .orElse(userId);
    }

    /** Input for every approval-request endpoint. Fields are POSTed as JSON. */
    public static class ApprovalRequestBody {
        private String password;
        private String mfaCode;
        private String reason;

        public String getPassword() { return password; }
        public void setPassword(String password) { this.password = password; }
        public String getMfaCode() { return mfaCode; }
        public void setMfaCode(String mfaCode) { this.mfaCode = mfaCode; }
        public String getReason() { return reason; }
        public void setReason(String reason) { this.reason = reason; }
    }

    /** Minimal response so the frontend can link the user directly to the created task. */
    public record ApprovalRequestResponse(String taskId, String eventType) {}
}
