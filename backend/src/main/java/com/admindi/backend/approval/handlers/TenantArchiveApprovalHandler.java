package com.admindi.backend.approval.handlers;

import com.admindi.backend.approval.ApprovalPayloadReader;
import com.admindi.backend.approval.ApprovalTaskHandler;
import com.admindi.backend.constants.ActionTaskEventType;
import com.admindi.backend.model.ActionTaskEntity;
import com.admindi.backend.model.UserEntity;
import com.admindi.backend.repository.ActionTaskRepository;
import com.admindi.backend.repository.UserRepository;
import com.admindi.backend.service.AuditService;
import com.admindi.backend.service.DomainEventDispatcher;
import com.admindi.backend.service.TenantExpedienteArchiveService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Approval handler for {@link ActionTaskEventType#TENANT_ARCHIVE_REQUESTED}.
 *
 * <p>The staff member initiates the archive with their own password + MFA via
 * {@code /api/approval-requests/tenant-profile/{profileId}/archive}; the owner lands on
 * this handler when they hit {@code /api/tasks/{id}/approve}.
 *
 * <h3>On approve</h3>
 * <ol>
 *   <li>Validates task ownership (<code>task.ownerId == current owner</code>).</li>
 *   <li>Delegates to {@link TenantExpedienteArchiveService#archiveOperational(String, String, String)}
 *       passing the owner's password+MFA. That service handles reauth, multi-owner/multi-property
 *       semantics, payment-history retention policy (purge vs snapshot) and all side effects
 *       (lease termination, invoice voiding, vacancy opening, movement ledger, post-baja task).</li>
 *   <li>Marks the task RESOLVED and emits an audit event linking the archive back to
 *       the approval request for traceability.</li>
 * </ol>
 *
 * <h3>On reject</h3>
 * Marks the task DISMISSED, records the owner's optional reason in audit, and dispatches
 * a notification back to the initiator via {@link DomainEventDispatcher}.
 */
@Component
public class TenantArchiveApprovalHandler implements ApprovalTaskHandler {

    private final TenantExpedienteArchiveService archiveService;
    private final ActionTaskRepository taskRepo;
    private final UserRepository userRepo;
    private final AuditService auditService;
    private final DomainEventDispatcher dispatcher;
    private final ApprovalPayloadReader payloadReader;

    public TenantArchiveApprovalHandler(TenantExpedienteArchiveService archiveService,
                                        ActionTaskRepository taskRepo,
                                        UserRepository userRepo,
                                        AuditService auditService,
                                        DomainEventDispatcher dispatcher,
                                        ApprovalPayloadReader payloadReader) {
        this.archiveService = archiveService;
        this.taskRepo = taskRepo;
        this.userRepo = userRepo;
        this.auditService = auditService;
        this.dispatcher = dispatcher;
        this.payloadReader = payloadReader;
    }

    @Override
    public String getEventType() {
        return ActionTaskEventType.TENANT_ARCHIVE_REQUESTED;
    }

    @Override
    public void onApprove(ActionTaskEntity task, String ownerPassword, String ownerMfaCode) {
        UserEntity approver = resolveAuthenticatedUser();
        assertTaskOwnedByApprover(task, approver);

        String tenantProfileId = task.getResourceId();
        if (tenantProfileId == null || tenantProfileId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "La tarea no tiene resourceId de tenant profile.");
        }

        // archiveOperational performs its own reauth (password+MFA), IDOR guard, retention
        // policy (purge without history vs snapshot with history), and side effects.
        archiveService.archiveOperational(tenantProfileId, ownerPassword, ownerMfaCode);

        task.setStatus("RESOLVED");
        task.setResolvedAt(LocalDateTime.now());
        taskRepo.save(task);

        auditService.logEvent(
                approver.getLoginUsername(),
                approver.getRole() != null ? approver.getRole().name() : null,
                "TENANT_ARCHIVE_APPROVED",
                "ActionTask",
                task.getId(),
                task.getOwnerId(),
                null,
                String.format("{\"tenantProfileId\":\"%s\"}", tenantProfileId),
                null,
                null);

        dispatcher.dispatch(
                "TENANT_ARCHIVE_APPROVED",
                "Solicitud de archivo de inquilino aprobada",
                "El dueño aprobó la baja operativa solicitada.",
                task.getOwnerId(),
                approver.getLoginUsername(),
                resolveRecipients(task),
                null);
    }

    @Override
    public void onReject(ActionTaskEntity task, String rejectionReason) {
        UserEntity approver = resolveAuthenticatedUser();
        assertTaskOwnedByApprover(task, approver);

        task.setStatus("DISMISSED");
        task.setResolvedAt(LocalDateTime.now());
        taskRepo.save(task);

        String reason = rejectionReason != null ? rejectionReason.trim() : null;
        auditService.logEvent(
                approver.getLoginUsername(),
                approver.getRole() != null ? approver.getRole().name() : null,
                "TENANT_ARCHIVE_REJECTED",
                "ActionTask",
                task.getId(),
                task.getOwnerId(),
                null,
                reason != null && !reason.isBlank()
                        ? String.format("{\"reason\":%s}", jsonString(reason))
                        : null,
                null,
                null);

        dispatcher.dispatch(
                "TENANT_ARCHIVE_REJECTED",
                "Solicitud de archivo de inquilino rechazada",
                reason != null && !reason.isBlank() ? "Motivo: " + reason : null,
                task.getOwnerId(),
                approver.getLoginUsername(),
                resolveRecipients(task),
                null);
    }

    /**
     * Route the approved/rejected notification to the staff who initiated the request.
     * Falls back to the owner when the payload is missing (legacy tasks) so the audit chain
     * stays complete.
     */
    private List<String> resolveRecipients(ActionTaskEntity task) {
        return payloadReader.initiatorUserId(task)
                .map(List::of)
                .orElseGet(() -> List.of(task.getOwnerId()));
    }

    private UserEntity resolveAuthenticatedUser() {
        String email = SecurityContextHolder.getContext().getAuthentication().getName();
        return userRepo.findByLoginIdentifier(email)
                .orElseThrow(() -> new RuntimeException("Usuario autenticado no existe."));
    }

    private void assertTaskOwnedByApprover(ActionTaskEntity task, UserEntity approver) {
        if (!approver.getId().equals(task.getOwnerId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Solo el titular de la organización puede aprobar o rechazar esta solicitud.");
        }
    }

    /** Minimal JSON string escape so audit payload doesn't break on quotes / backslashes / newlines. */
    private static String jsonString(String raw) {
        StringBuilder out = new StringBuilder("\"");
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) out.append(String.format("\\u%04x", (int) c));
                    else out.append(c);
                }
            }
        }
        return out.append('"').toString();
    }
}
