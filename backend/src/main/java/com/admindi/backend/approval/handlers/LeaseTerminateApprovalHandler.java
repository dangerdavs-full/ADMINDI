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
import com.admindi.backend.service.LeaseService;
import com.admindi.backend.service.ReauthService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Approval handler for {@link ActionTaskEventType#LEASE_TERMINATE_REQUESTED}.
 *
 * <p>Unlike {@code TenantExpedienteArchiveService}, {@link LeaseService#terminateLease}
 * does not accept a reauth pair — it relies on the caller's session identity. This handler
 * therefore <b>performs the owner reauth itself</b> (password + MFA via {@link ReauthService})
 * before invoking the domain method. The ownerId assertion inside {@code terminateLease}
 * (IDOR guard) continues to apply, providing defense in depth.
 *
 * <p>Side effects of terminating (status → TERMINATED, unit vacant, property occupancy
 * refreshed, vacancy opened, movement ledger) are all handled by the underlying service.
 */
@Component
public class LeaseTerminateApprovalHandler implements ApprovalTaskHandler {

    private final LeaseService leaseService;
    private final ReauthService reauthService;
    private final ActionTaskRepository taskRepo;
    private final UserRepository userRepo;
    private final AuditService auditService;
    private final DomainEventDispatcher dispatcher;
    private final ApprovalPayloadReader payloadReader;

    public LeaseTerminateApprovalHandler(LeaseService leaseService,
                                         ReauthService reauthService,
                                         ActionTaskRepository taskRepo,
                                         UserRepository userRepo,
                                         AuditService auditService,
                                         DomainEventDispatcher dispatcher,
                                         ApprovalPayloadReader payloadReader) {
        this.leaseService = leaseService;
        this.reauthService = reauthService;
        this.taskRepo = taskRepo;
        this.userRepo = userRepo;
        this.auditService = auditService;
        this.dispatcher = dispatcher;
        this.payloadReader = payloadReader;
    }

    @Override
    public String getEventType() {
        return ActionTaskEventType.LEASE_TERMINATE_REQUESTED;
    }

    @Override
    public void onApprove(ActionTaskEntity task, String ownerPassword, String ownerMfaCode) {
        UserEntity approver = resolveAuthenticatedUser();
        assertTaskOwnedByApprover(task, approver);

        String leaseId = task.getResourceId();
        if (leaseId == null || leaseId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "La tarea no tiene resourceId de lease.");
        }

        // Owner reauth (password + MFA). The operation label is audited by ReauthService.
        reauthService.verifyReauthForUser(approver, ownerPassword, ownerMfaCode, "LEASE_TERMINATE_APPROVE");

        // IDOR guard lives inside LeaseService.terminateLease (ownerId match).
        leaseService.terminateLease(leaseId);

        task.setStatus("RESOLVED");
        task.setResolvedAt(LocalDateTime.now());
        taskRepo.save(task);

        auditService.logEvent(
                approver.getLoginUsername(),
                approver.getRole() != null ? approver.getRole().name() : null,
                "LEASE_TERMINATE_APPROVED",
                "ActionTask",
                task.getId(),
                task.getOwnerId(),
                null,
                String.format("{\"leaseId\":\"%s\"}", leaseId),
                null,
                null);

        dispatcher.dispatch(
                "LEASE_TERMINATE_APPROVED",
                "Solicitud de terminación de contrato aprobada",
                "El dueño aprobó la terminación del contrato.",
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
                "LEASE_TERMINATE_REJECTED",
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
                "LEASE_TERMINATE_REJECTED",
                "Solicitud de terminación de contrato rechazada",
                reason != null && !reason.isBlank() ? "Motivo: " + reason : null,
                task.getOwnerId(),
                approver.getLoginUsername(),
                resolveRecipients(task),
                null);
    }

    /** Notify the staff who initiated the request; fall back to owner for legacy tasks. */
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
