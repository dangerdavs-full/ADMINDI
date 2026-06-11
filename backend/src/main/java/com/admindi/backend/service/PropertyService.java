package com.admindi.backend.service;

import com.admindi.backend.approval.ApprovalPayloadReader;
import com.admindi.backend.approval.ApprovalRequestService;
import com.admindi.backend.approval.ApprovalRequestService.ApprovalRequestInput;
import com.admindi.backend.constants.ActionTaskEventType;
import com.admindi.backend.dto.PropertyDTO;
import com.admindi.backend.model.*;
import com.admindi.backend.repository.*;
import com.admindi.backend.security.TenantContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class PropertyService {

    private final PropertyRepository propertyRepository;
    private final UserRepository userRepository;
    private final UnitRepository unitRepository;
    private final LeaseRepository leaseRepository;
    private final ActionTaskRepository actionTaskRepository;
    private final DomainEventDispatcher dispatcher;
    private final ReauthService reauthService;
    private final LeaseService leaseService;
    private final ApprovalRequestService approvalRequestService;
    private final ApprovalPayloadReader payloadReader;
    private final PropertyCascadeDeletionService propertyCascade;

    @Autowired
    public PropertyService(PropertyRepository propertyRepository, UserRepository userRepository,
                           UnitRepository unitRepository, LeaseRepository leaseRepository,
                           ActionTaskRepository actionTaskRepository,
                           DomainEventDispatcher dispatcher,
                           ReauthService reauthService, LeaseService leaseService,
                           @org.springframework.context.annotation.Lazy ApprovalRequestService approvalRequestService,
                           ApprovalPayloadReader payloadReader,
                           PropertyCascadeDeletionService propertyCascade) {
        this.propertyRepository = propertyRepository;
        this.userRepository = userRepository;
        this.unitRepository = unitRepository;
        this.leaseRepository = leaseRepository;
        this.actionTaskRepository = actionTaskRepository;
        this.dispatcher = dispatcher;
        this.reauthService = reauthService;
        this.leaseService = leaseService;
        this.approvalRequestService = approvalRequestService;
        this.payloadReader = payloadReader;
        this.propertyCascade = propertyCascade;
    }

    /**
     * V66 — Resolver el rol del actor autenticado (owner vs staff). Se usa en
     * los flujos de eliminación para enforzar que solo el OWNER pueda disparar
     * el hard-delete total (el PROPERTY_ADMIN puede solicitarlo pero no
     * ejecutarlo).
     */
    private Role resolveActorRole() {
        String username = SecurityContextHolder.getContext().getAuthentication().getName();
        return userRepository.findByLoginIdentifier(username)
                .map(UserEntity::getRole)
                .orElse(null);
    }

    /**
     * V66 — Impact summary del hard-delete para el modal de confirmación del
     * dueño. Delega en el servicio de cascada (read-only).
     */
    public Map<String, Object> previewDeleteImpact(String propertyId) {
        String ownerId = resolveOwnerId();
        PropertyEntity entity = propertyRepository.findById(propertyId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Inmueble no encontrado."));
        if (!entity.getOwnerId().equals(ownerId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Aislamiento IDOR.");
        }
        return propertyCascade.previewImpact(propertyId);
    }

    /**
     * Defensa-en-profundidad: antes de devolver el DTO de un inmueble sincronizamos
     * {@code properties.status} con la fuente de verdad operacional (contratos ACTIVE).
     * Si algún flujo histórico dejó el inmueble pegado en OCCUPIED sin lease ACTIVE,
     * esto lo corrige de forma idempotente en cada lectura. Respeta MAINTENANCE y DELETED.
     */
    private void reconcileOccupancy(PropertyEntity entity) {
        if (entity == null) return;
        if (entity.getStatus() == PropertyStatus.DELETED || entity.getStatus() == PropertyStatus.MAINTENANCE) {
            return;
        }
        try {
            leaseService.refreshPropertyOccupancyIncludingProfiles(entity.getOwnerId(), entity.getId());
        } catch (Exception ignored) {
            // La reconciliación es un nice-to-have en el read path; nunca debe romper la lectura.
        }
    }

    private String resolveOwnerId() {
        return TenantContext.resolveOwnerId(userRepository);
    }

    private String resolveActorEmail() {
        return SecurityContextHolder.getContext().getAuthentication().getName();
    }

    public PropertyDTO createProperty(PropertyDTO dto) {
        String ownerId = resolveOwnerId();
        String actor = resolveActorEmail();

        PropertyEntity entity = new PropertyEntity(ownerId, dto.getName(), dto.getAddress(), dto.getStatus());
        entity.setType(dto.getType());
        entity.setPredial(dto.getPredial());
        entity.setDescription(dto.getDescription());

        PropertyEntity saved = propertyRepository.save(entity);

        dispatcher.dispatch("PROPERTY_CREATED",
                "Inmueble creado: " + saved.getName(),
                "Dirección: " + saved.getAddress(),
                ownerId, actor, null);

        return mapToDTO(saved);
    }

    public List<PropertyDTO> getMyProperties() {
        String ownerId = resolveOwnerId();
        List<PropertyEntity> properties = propertyRepository.findByOwnerIdAndActiveTrue(ownerId);
        for (PropertyEntity p : properties) {
            reconcileOccupancy(p);
        }
        // Releemos tras posibles updates para que el DTO refleje el status reconciliado.
        return propertyRepository.findByOwnerIdAndActiveTrue(ownerId).stream()
                .map(this::mapToDTO)
                .collect(Collectors.toList());
    }

    public PropertyDTO getPropertyDetail(String id) {
        String ownerId = resolveOwnerId();
        PropertyEntity entity = propertyRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Inmueble no encontrado."));
        if (!entity.getOwnerId().equals(ownerId)) {
            throw new RuntimeException("Aislamiento IDOR: propiedad de otro dueño.");
        }
        reconcileOccupancy(entity);
        PropertyEntity fresh = propertyRepository.findById(id).orElse(entity);
        return mapToDTO(fresh);
    }

    public PropertyDTO updateProperty(String id, PropertyDTO dto) {
        String ownerId = resolveOwnerId();
        String actor = resolveActorEmail();
        PropertyEntity entity = propertyRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Inmueble no encontrado."));

        if (!entity.getOwnerId().equals(ownerId)) {
            throw new RuntimeException("Aislamiento IDOR: propiedad de otro dueño.");
        }

        entity.setName(dto.getName());
        entity.setAddress(dto.getAddress());
        entity.setType(dto.getType());
        entity.setPredial(dto.getPredial());
        entity.setDescription(dto.getDescription());
        if (dto.getStatus() != null) entity.setStatus(dto.getStatus());

        PropertyEntity updated = propertyRepository.save(entity);

        dispatcher.dispatch("PROPERTY_UPDATED",
                "Inmueble actualizado: " + updated.getName(),
                null, ownerId, actor, null);

        return mapToDTO(updated);
    }

    /**
     * Safety check: block deletion if Property has an active Lease.
     */
    private void assertNoActiveLease(String propertyId) {
        boolean hasActive = leaseRepository.existsByOwnerIdAndProperty_IdAndStatus(
                resolveOwnerId(), propertyId, LeaseStatus.ACTIVE);
        if (hasActive) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "No se puede eliminar el inmueble: tiene un arrendamiento activo. Archive el expediente primero.");
        }
    }

    /**
     * V66 — Hard delete con reauth — exclusivo del OWNER.
     *
     * <p>Regla operativa decidida por el dueño: borrar un inmueble debe
     * eliminar TODA su contabilidad (tickets, cotizaciones, egresos,
     * comprobantes, facturas, pagos, archivos físicos en disco). Esto evita
     * "expedientes fantasma" tras el borrado y respeta el requisito
     * "si eliminas un inmueble se eliminan los archivos".</p>
     *
     * <p>Guards de seguridad:</p>
     * <ul>
     *   <li>Solo el usuario con rol {@code OWNER} puede invocarlo; el
     *       {@code PROPERTY_ADMIN} debe usar el flujo de aprobación
     *       ({@link #requestDeleteProperty}).</li>
     *   <li>Reauth estándar (password + MFA) antes de ejecutar.</li>
     *   <li>No se puede borrar un inmueble con contrato ACTIVE.</li>
     * </ul>
     */
    public void softDeleteWithReauth(String id, String password, String mfaCode) {
        reauthService.verifyReauth(password, mfaCode, "PROPERTY_DELETE_DIRECT");

        String ownerId = resolveOwnerId();
        String actor = resolveActorEmail();
        Role actorRole = resolveActorRole();

        PropertyEntity entity = propertyRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Inmueble no encontrado."));

        if (!entity.getOwnerId().equals(ownerId)) {
            throw new RuntimeException("Aislamiento IDOR.");
        }

        // V66 — solo el OWNER puede ejecutar; staff (PROPERTY_ADMIN) debe
        // usar requestDeleteProperty → aprobación del dueño.
        if (actorRole != Role.OWNER) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Eliminar un inmueble es acción exclusiva del dueño. El administrador de inmuebles debe solicitar la aprobación.");
        }

        assertNoActiveLease(id);

        String propertyName = entity.getName();
        String propertyAddress = entity.getAddress();

        // V66 — Cascada total: archivos físicos + DB. Dentro de transacción.
        propertyCascade.hardDeleteProperty(id);

        dispatcher.dispatch("PROPERTY_DELETED",
                "Inmueble eliminado: " + propertyName,
                "Se borró toda la información del inmueble (" + (propertyAddress != null ? propertyAddress : "") + ") y sus archivos.",
                ownerId, actor);
    }

    /**
     * Staff requests property deletion — creates an ActionTask for the OWNER.
     *
     * <p>Since Fase 2 of the approval framework, this method enforces the <b>double reauth
     * policy</b>: the staff member must supply their own password + MFA here to prove
     * their identity before the request reaches the owner's inbox. The owner later supplies
     * their own reauth at {@code /api/tasks/{id}/approve}. See
     * {@link ApprovalRequestService}.
     *
     * <p>Legacy behavior (staff-initiated delete request without staff reauth) has been
     * removed. Frontend callers of {@code POST /api/properties/{id}/request-delete} must
     * now POST a body {@code {password, mfaCode, reason?}}.
     */
    public String requestDeleteProperty(String propertyId, String staffPassword, String staffMfaCode, String reason) {
        String ownerId = resolveOwnerId();
        PropertyEntity entity = propertyRepository.findById(propertyId)
                .orElseThrow(() -> new RuntimeException("Inmueble no encontrado."));

        if (!entity.getOwnerId().equals(ownerId)) {
            throw new RuntimeException("Aislamiento IDOR.");
        }

        Map<String, Object> context = new LinkedHashMap<>();
        context.put("propertyId", propertyId);
        context.put("propertyName", entity.getName());
        context.put("propertyAddress", entity.getAddress());

        ActionTaskEntity task = approvalRequestService.submit(new ApprovalRequestInput(
                ActionTaskEventType.PROPERTY_DELETE_REQUESTED,
                ownerId,
                "PROPERTY",
                propertyId,
                "Solicitud de eliminación: " + entity.getName(),
                "Se solicita eliminar el inmueble '" + entity.getName() + "' ("
                        + entity.getAddress() + ").",
                reason,
                context,
                staffPassword,
                staffMfaCode));

        return task.getId();
    }

    /**
     * OWNER approves property deletion from ActionTask.
     * Requires reauth (password + MFA if enabled). Blocks if property has active lease.
     */
    public void approveDeleteWithReauth(String taskId, String password, String mfaCode) {
        String ownerId = resolveOwnerId();
        String actor = resolveActorEmail();

        ActionTaskEntity task = actionTaskRepository.findById(taskId)
                .orElseThrow(() -> new RuntimeException("Tarea no encontrada."));

        if (!"OPEN".equalsIgnoreCase(task.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "La tarea ya fue procesada. Estado actual: " + task.getStatus() + ".");
        }

        if (!"PROPERTY_DELETE_REQUESTED".equals(task.getEventType())) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Solo se pueden aprobar solicitudes de eliminación de inmueble (eventType=PROPERTY_DELETE_REQUESTED).");
        }
        if (!task.getOwnerId().equals(ownerId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Solo el titular de la organización puede aprobar esta solicitud.");
        }

        // V66 — guard de rol: aunque el task haya sido creado por staff, solo
        // el dueño (OWNER) puede aprobar y ejecutar el hard-delete. Staff con
        // rol distinto que llegue a este endpoint recibe 403 explícito.
        Role actorRole = resolveActorRole();
        if (actorRole != Role.OWNER) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Solo el dueño puede aprobar la eliminación de un inmueble.");
        }

        assertNoActiveLease(task.getResourceId());

        PropertyEntity entity = propertyRepository.findById(task.getResourceId())
                .orElseThrow(() -> new RuntimeException("Inmueble no encontrado."));

        reauthService.verifyReauth(password, mfaCode, "PROPERTY_DELETE_APPROVE");

        String propertyName = entity.getName();
        String propertyId = entity.getId();

        // V66 — Cascada total en lugar de soft delete.
        propertyCascade.hardDeleteProperty(propertyId);

        task.setStatus("RESOLVED");
        task.setResolvedAt(LocalDateTime.now());
        actionTaskRepository.save(task);

        // Notify the staff member who initiated the request (falls back to the owner when
        // the task has no payload — e.g. tasks created before the approval framework).
        // V66 — la entidad ya no existe tras el hard-delete; usamos las variables
        // capturadas (propertyName, propertyId) para armar la notificación.
        List<String> recipients = recipientsForApprovalOutcome(task, ownerId);
        dispatcher.dispatch("PROPERTY_DELETE_APPROVED",
                "Eliminación aprobada con reauth: " + propertyName,
                null, ownerId, actor, recipients);
    }

    /**
     * OWNER rejects property deletion from ActionTask. Accepts an optional {@code reason}
     * that is persisted (inside the notification body) and forwarded to the initiating
     * staff member so they know why the request was turned down.
     */
    public void rejectDeleteProperty(String taskId, String reason) {
        String ownerId = resolveOwnerId();
        String actor = resolveActorEmail();

        ActionTaskEntity task = actionTaskRepository.findById(taskId)
                .orElseThrow(() -> new RuntimeException("Tarea no encontrada."));

        if (!"OPEN".equalsIgnoreCase(task.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "La tarea ya fue procesada. Estado actual: " + task.getStatus() + ".");
        }

        if (!"PROPERTY_DELETE_REQUESTED".equals(task.getEventType())) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Solo se pueden rechazar solicitudes de eliminación de inmueble (eventType=PROPERTY_DELETE_REQUESTED).");
        }
        if (!task.getOwnerId().equals(ownerId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Solo el titular de la organización puede rechazar esta solicitud.");
        }

        task.setStatus("DISMISSED");
        task.setResolvedAt(LocalDateTime.now());
        actionTaskRepository.save(task);

        String trimmedReason = (reason != null && !reason.isBlank()) ? reason.trim() : null;
        String body = trimmedReason != null ? "Motivo: " + trimmedReason : null;

        List<String> recipients = recipientsForApprovalOutcome(task, ownerId);
        dispatcher.dispatch("PROPERTY_DELETE_REJECTED",
                "Eliminación rechazada para inmueble",
                body, ownerId, actor, recipients, null);
    }

    /**
     * Backward-compatible overload for pre-Fase 2 callers (legacy REST endpoint). Forwards
     * to the reason-aware variant with a null reason.
     */
    public void rejectDeleteProperty(String taskId) {
        rejectDeleteProperty(taskId, null);
    }

    /**
     * Resolves the notification recipients for the outcome (APPROVED/REJECTED) of an
     * approval task. Preference order:
     *
     * <ol>
     *   <li>The staff member who initiated the request (from the task payload).</li>
     *   <li>The owner themselves, as fallback for legacy tasks without a structured payload.
     *       This guarantees that in the worst case the audit trail is preserved and at
     *       least the owner's in-app inbox shows the closure.</li>
     * </ol>
     */
    private List<String> recipientsForApprovalOutcome(ActionTaskEntity task, String ownerIdFallback) {
        return payloadReader.initiatorUserId(task)
                .map(List::of)
                .orElseGet(() -> List.of(ownerIdFallback));
    }

    private PropertyDTO mapToDTO(PropertyEntity entity) {
        int units = unitRepository.countByPropertyId(entity.getId());
        return new PropertyDTO(
                entity.getId(),
                entity.getOwnerId(),
                entity.getName(),
                entity.getAddress(),
                entity.getType(),
                entity.getPredial(),
                entity.getDescription(),
                entity.getStatus(),
                entity.isActive(),
                units,
                entity.getCreatedAt()
        );
    }
}
