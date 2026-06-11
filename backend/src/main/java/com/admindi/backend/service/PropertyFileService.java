package com.admindi.backend.service;

import com.admindi.backend.approval.ApprovalPayloadReader;
import com.admindi.backend.approval.ApprovalRequestService;
import com.admindi.backend.approval.ApprovalRequestService.ApprovalRequestInput;
import com.admindi.backend.constants.ActionTaskEventType;
import com.admindi.backend.dto.PropertyFileDTO;
import com.admindi.backend.model.ActionTaskEntity;
import com.admindi.backend.model.PropertyEntity;
import com.admindi.backend.model.PropertyFileEntity;
import com.admindi.backend.model.PropertyMovementEventType;
import com.admindi.backend.model.UserEntity;
import com.admindi.backend.repository.PropertyFileRepository;
import com.admindi.backend.repository.PropertyRepository;
import com.admindi.backend.repository.UserRepository;
import com.admindi.backend.security.TenantContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class PropertyFileService {

    private static final long MAX_FILE_SIZE = 10 * 1024 * 1024; // 10MB
    private static final Set<String> ALLOWED_TYPES = Set.of(
            "image/jpeg", "image/png", "image/webp", "image/gif",
            "application/pdf"
    );

    private static final Set<String> ALLOWED_LABELS = Set.of(
            "BASELINE", "UPDATE", "BEFORE", "AFTER", "VISIT", "MAINTENANCE",
            "VACANCY", "AGREEMENT_EVIDENCE",
            // V57 — comprobantes de pago (SPEI validado por CEP o CASH aprobado por el dueño)
            "PAYMENT_PROOF"
    );

    private final PropertyFileRepository fileRepo;
    private final PropertyRepository propertyRepo;
    private final UserRepository userRepo;
    private final FileStorageService storageService;
    private final DomainEventDispatcher dispatcher;
    private final PropertyMovementService propertyMovementService;
    private final ReauthService reauthService;
    private final AuditService auditService;
    private final ApprovalRequestService approvalRequestService;
    private final ApprovalPayloadReader payloadReader;

    @Autowired
    public PropertyFileService(PropertyFileRepository fileRepo, PropertyRepository propertyRepo,
                               UserRepository userRepo, FileStorageService storageService,
                               DomainEventDispatcher dispatcher,
                               PropertyMovementService propertyMovementService,
                               ReauthService reauthService,
                               AuditService auditService,
                               @org.springframework.context.annotation.Lazy ApprovalRequestService approvalRequestService,
                               ApprovalPayloadReader payloadReader) {
        this.fileRepo = fileRepo;
        this.propertyRepo = propertyRepo;
        this.userRepo = userRepo;
        this.storageService = storageService;
        this.dispatcher = dispatcher;
        this.propertyMovementService = propertyMovementService;
        this.reauthService = reauthService;
        this.auditService = auditService;
        this.approvalRequestService = approvalRequestService;
        this.payloadReader = payloadReader;
    }

    private String resolveOwnerId() {
        return TenantContext.resolveOwnerId(userRepo);
    }

    public PropertyFileDTO uploadFile(String propertyId, String category, MultipartFile file,
                                      String label, String note) {
        String ownerId = resolveOwnerId();
        String actor = SecurityContextHolder.getContext().getAuthentication().getName();
        UserEntity actorUser = userRepo.findByLoginIdentifier(actor).orElse(null);

        // Validate property ownership
        PropertyEntity property = propertyRepo.findById(propertyId)
                .orElseThrow(() -> new RuntimeException("Inmueble no encontrado."));
        if (!property.getOwnerId().equals(ownerId)) {
            throw new RuntimeException("IDOR: Inmueble de otro dueño.");
        }

        // Validate file
        if (file.isEmpty()) throw new RuntimeException("Archivo vacío.");
        if (file.getSize() > MAX_FILE_SIZE) throw new RuntimeException("Archivo excede 10MB.");
        String contentType = file.getContentType();
        if (contentType == null || !ALLOWED_TYPES.contains(contentType)) {
            throw new RuntimeException("Tipo de archivo no permitido. Solo: JPEG, PNG, WEBP, GIF, PDF.");
        }

        // Validate category
        if (!"PHOTO".equals(category) && !"PLAN".equals(category) && !"OTHER".equals(category)) {
            throw new RuntimeException("Categoría inválida. Use: PHOTO, PLAN, OTHER.");
        }

        // Store file
        String storagePath = storageService.store(file, "properties/" + propertyId + "/" + category.toLowerCase());

        // Save metadata
        PropertyFileEntity entity = new PropertyFileEntity();
        entity.setId(UUID.randomUUID().toString());
        entity.setPropertyId(propertyId);
        entity.setCategory(category);
        entity.setFileName(file.getOriginalFilename());
        entity.setFilePath(storagePath);
        entity.setContentType(contentType);
        entity.setSizeBytes(file.getSize());
        entity.setUploadedBy(actor);
        if (actorUser != null && actorUser.getRole() != null) {
            entity.setUploaderRole(actorUser.getRole().name());
        }
        if (label != null && !label.isBlank()) {
            String normalized = label.trim().toUpperCase(Locale.ROOT);
            if (!ALLOWED_LABELS.contains(normalized)) {
                throw new RuntimeException("Invalid label. Allowed: " + ALLOWED_LABELS);
            }
            entity.setLabel(normalized);
        }
        if (note != null && !note.isBlank()) {
            entity.setNote(note.trim());
        }
        PropertyFileEntity saved = fileRepo.save(entity);

        dispatcher.dispatch("PROPERTY_FILE_UPLOADED",
                "Archivo subido: " + file.getOriginalFilename() + " (" + category + ")",
                "Inmueble: " + property.getName(),
                ownerId, actor, null);

        UserEntity uploader = userRepo.findByLoginIdentifier(actor).orElse(null);
        String actorUserId = uploader != null ? uploader.getId() : null;
        String actorRole = uploader != null ? uploader.getRole().name() : null;
        propertyMovementService.record(ownerId, propertyId, "PROPERTY_FILE", saved.getId(),
                actorUserId, actorRole, PropertyMovementEventType.PROPERTY_FILE_UPLOADED,
                "Nueva evidencia / archivo",
                file.getOriginalFilename() + " (" + category + ")",
                saved.getUploadedAt(),
                "{\"category\":\"" + category + "\",\"fileName\":\"" + escapeJson(file.getOriginalFilename()) + "\"}",
                saved.getId());

        return mapToDTO(saved);
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    public List<PropertyFileDTO> getFiles(String propertyId) {
        String ownerId = resolveOwnerId();
        PropertyEntity property = propertyRepo.findById(propertyId)
                .orElseThrow(() -> new RuntimeException("Inmueble no encontrado."));
        if (!property.getOwnerId().equals(ownerId)) {
            throw new RuntimeException("IDOR.");
        }
        return fileRepo.findByPropertyId(propertyId).stream()
                .map(this::mapToDTO)
                .collect(Collectors.toList());
    }

    public PropertyFileEntity getFileEntity(String fileId) {
        return fileRepo.findById(fileId)
                .orElseThrow(() -> new RuntimeException("Archivo no encontrado."));
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Destructive operations — hard delete with reauth (direct) or approval (staff)
    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * Owner / SUPER_ADMIN deletes a file directly, proving possession with password + MFA.
     * Uploading a file is reversible (re-upload) but deletion is not — the blob is wiped
     * from the storage layer and the metadata row is dropped — so every delete goes
     * through reauth regardless of category. Staff members (even with PROPERTY_DELETE
     * authority) are routed through {@link #requestFileDelete} instead.
     *
     * <p>Idempotency: the method is <em>not</em> idempotent. A second call with the same
     * fileId returns 404. That's deliberate — pretending the file still exists would mask
     * accidental double deletes.
     */
    public void deleteFileWithReauth(String fileId, String password, String mfaCode) {
        PropertyFileEntity entity = loadFileOrThrow(fileId);
        PropertyEntity property = loadPropertyOrThrow(entity.getPropertyId());
        String ownerId = resolveOwnerId();
        assertOwnership(property, ownerId);

        String actorEmail = SecurityContextHolder.getContext().getAuthentication().getName();
        reauthService.verifyReauth(password, mfaCode, "PROPERTY_FILE_DELETE");

        performPhysicalDeletion(entity, property, actorEmail);

        auditService.logEvent(
                actorEmail,
                actorRoleFromContext(actorEmail),
                "PROPERTY_FILE_DELETED_DIRECT",
                "PropertyFile",
                entity.getId(),
                ownerId,
                null,
                String.format("{\"propertyId\":\"%s\",\"category\":\"%s\",\"fileName\":\"%s\"}",
                        property.getId(), entity.getCategory(), escapeJson(entity.getFileName())),
                null,
                null);

        // Notify the owner themselves so the audit chain is visible in the inbox.
        dispatcher.dispatch("PROPERTY_FILE_DELETED",
                "Archivo eliminado: " + entity.getFileName(),
                "Inmueble: " + property.getName() + " (" + entity.getCategory() + ")",
                ownerId, actorEmail, List.of(ownerId), null);
    }

    /**
     * Staff-initiated request for the owner to approve deleting a property file. Mirrors
     * the {@code PROPERTY_DELETE_REQUESTED} flow but at the file granularity. Requires the
     * staff member's password and MFA (double-reauth policy).
     *
     * @return the id of the created ActionTask so the caller can link to the inbox entry.
     */
    public String requestFileDelete(String fileId, String password, String mfaCode, String reason) {
        PropertyFileEntity entity = loadFileOrThrow(fileId);
        PropertyEntity property = loadPropertyOrThrow(entity.getPropertyId());
        // IDOR: staff can only request deletion for files under their current owner context.
        String ownerId = resolveOwnerId();
        assertOwnership(property, ownerId);

        Map<String, Object> context = new LinkedHashMap<>();
        context.put("fileId", entity.getId());
        context.put("propertyId", property.getId());
        context.put("propertyName", property.getName());
        context.put("category", entity.getCategory());
        context.put("fileName", entity.getFileName());

        ActionTaskEntity task = approvalRequestService.submit(new ApprovalRequestInput(
                ActionTaskEventType.PROPERTY_FILE_DELETE_REQUESTED,
                ownerId,
                "PROPERTY_FILE",
                entity.getId(),
                "Solicitud para eliminar archivo: " + entity.getFileName(),
                "Se solicita eliminar el archivo \"" + entity.getFileName() + "\" ("
                        + entity.getCategory() + ") del inmueble \"" + property.getName() + "\". "
                        + "Esta acción borra el archivo físico y no es reversible.",
                reason,
                context,
                password,
                mfaCode));

        return task.getId();
    }

    /**
     * Owner approves an open {@code PROPERTY_FILE_DELETE_REQUESTED} task from the
     * {@link com.admindi.backend.approval.handlers.PropertyFileDeleteApprovalHandler}.
     * Performs its own reauth (password + MFA) and then runs the physical deletion.
     *
     * <p>The caller (the approval handler) has already validated that the task is OPEN
     * and assigned to this owner, but we still re-verify ownership of the underlying
     * file as a last-mile IDOR defense in case the property ownership was changed
     * between the request and the approval.
     */
    public void approveFileDeletion(ActionTaskEntity task, String password, String mfaCode) {
        if (!ActionTaskEventType.PROPERTY_FILE_DELETE_REQUESTED.equals(task.getEventType())) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Tarea no es PROPERTY_FILE_DELETE_REQUESTED.");
        }
        String fileId = task.getResourceId();
        if (fileId == null || fileId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "La tarea no tiene resourceId del archivo.");
        }

        PropertyFileEntity entity = loadFileOrThrow(fileId);
        PropertyEntity property = loadPropertyOrThrow(entity.getPropertyId());
        String ownerId = task.getOwnerId();
        if (!property.getOwnerId().equals(ownerId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "El archivo pertenece a un inmueble de otra organización.");
        }

        String approverEmail = SecurityContextHolder.getContext().getAuthentication().getName();
        reauthService.verifyReauth(password, mfaCode, "PROPERTY_FILE_DELETE_APPROVE");

        performPhysicalDeletion(entity, property, approverEmail);

        auditService.logEvent(
                approverEmail,
                actorRoleFromContext(approverEmail),
                "PROPERTY_FILE_DELETE_APPROVED",
                "ActionTask",
                task.getId(),
                ownerId,
                null,
                String.format("{\"fileId\":\"%s\",\"propertyId\":\"%s\",\"fileName\":\"%s\"}",
                        entity.getId(), property.getId(), escapeJson(entity.getFileName())),
                null,
                null);

        // Notify the staff who originally requested the deletion.
        List<String> recipients = payloadReader.initiatorUserId(task)
                .map(List::of)
                .orElseGet(() -> List.of(ownerId));
        dispatcher.dispatch("PROPERTY_FILE_DELETE_APPROVED",
                "Eliminación de archivo aprobada",
                entity.getFileName() + " (" + entity.getCategory() + ") — inmueble "
                        + property.getName(),
                ownerId, approverEmail, recipients, null);
    }

    /**
     * Persist the rejection of a file-deletion task. Unlike the approve path this is a
     * pure status update (no side effects on the file itself), so we let the handler
     * flip the task state and just record the audit trail here.
     */
    public void recordFileDeletionRejection(ActionTaskEntity task, String reason) {
        String approverEmail = SecurityContextHolder.getContext().getAuthentication().getName();
        String trimmed = (reason != null && !reason.isBlank()) ? reason.trim() : null;

        auditService.logEvent(
                approverEmail,
                actorRoleFromContext(approverEmail),
                "PROPERTY_FILE_DELETE_REJECTED",
                "ActionTask",
                task.getId(),
                task.getOwnerId(),
                null,
                trimmed != null
                        ? String.format("{\"reason\":\"%s\",\"fileId\":\"%s\"}",
                                escapeJson(trimmed), task.getResourceId())
                        : String.format("{\"fileId\":\"%s\"}", task.getResourceId()),
                null,
                null);

        List<String> recipients = payloadReader.initiatorUserId(task)
                .map(List::of)
                .orElseGet(() -> List.of(task.getOwnerId()));
        dispatcher.dispatch("PROPERTY_FILE_DELETE_REJECTED",
                "Eliminación de archivo rechazada",
                trimmed != null ? "Motivo: " + trimmed : null,
                task.getOwnerId(), approverEmail, recipients, null);
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Internal helpers
    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * Runs the actual physical deletion: blob out of storage, row out of DB, plus the
     * movement-ledger entry so the property's timeline shows the file was removed. Kept
     * as a single method so direct-owner and approval flows have identical semantics.
     */
    private void performPhysicalDeletion(PropertyFileEntity entity, PropertyEntity property,
                                         String actorEmail) {
        storageService.delete(entity.getFilePath());
        fileRepo.delete(entity);

        UserEntity actor = userRepo.findByLoginIdentifier(actorEmail).orElse(null);
        String actorUserId = actor != null ? actor.getId() : null;
        String actorRole = (actor != null && actor.getRole() != null) ? actor.getRole().name() : null;
        propertyMovementService.record(
                property.getOwnerId(),
                property.getId(),
                "PROPERTY_FILE",
                entity.getId(),
                actorUserId,
                actorRole,
                PropertyMovementEventType.PROPERTY_FILE_DELETED,
                "Archivo eliminado",
                entity.getFileName() + " (" + entity.getCategory() + ")",
                java.time.LocalDateTime.now(),
                String.format("{\"deleted\":true,\"category\":\"%s\",\"fileName\":\"%s\"}",
                        entity.getCategory(), escapeJson(entity.getFileName())),
                entity.getId());
    }

    private PropertyFileEntity loadFileOrThrow(String fileId) {
        return fileRepo.findById(fileId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Archivo no encontrado."));
    }

    private PropertyEntity loadPropertyOrThrow(String propertyId) {
        return propertyRepo.findById(propertyId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Inmueble no encontrado."));
    }

    private void assertOwnership(PropertyEntity property, String ownerId) {
        if (!property.getOwnerId().equals(ownerId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "IDOR: el archivo pertenece a otra organización.");
        }
    }

    private String actorRoleFromContext(String email) {
        return userRepo.findByLoginIdentifier(email)
                .map(u -> u.getRole() != null ? u.getRole().name() : null)
                .orElse(null);
    }

    /**
     * @deprecated Use {@link #deleteFileWithReauth(String, String, String)} (owner) or
     *             {@link #requestFileDelete(String, String, String, String)} (staff).
     *             Kept only to surface explicit errors at the old call sites during the
     *             cutover; the {@link PropertyFileEntity} hard-delete without reauth was
     *             a leakage vector (contracts/deeds under OTHER category).
     */
    @Deprecated
    public void deleteFile(String fileId) {
        throw new ResponseStatusException(HttpStatus.GONE,
                "deleteFile(fileId) deprecado. Usa deleteFileWithReauth (owner) o "
                        + "requestFileDelete (staff) para cumplir la política de password+MFA.");
    }

    private PropertyFileDTO mapToDTO(PropertyFileEntity e) {
        PropertyFileDTO dto = new PropertyFileDTO();
        dto.setId(e.getId());
        dto.setPropertyId(e.getPropertyId());
        dto.setCategory(e.getCategory());
        dto.setFileName(e.getFileName());
        dto.setContentType(e.getContentType());
        dto.setSizeBytes(e.getSizeBytes());
        dto.setUploadedAt(e.getUploadedAt());
        dto.setDownloadUrl("/api/files/" + e.getId() + "/download");
        dto.setUploadedBy(e.getUploadedBy());
        dto.setUploaderRole(e.getUploaderRole());
        dto.setLabel(e.getLabel());
        dto.setNote(e.getNote());
        return dto;
    }
}
