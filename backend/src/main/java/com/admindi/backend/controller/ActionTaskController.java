package com.admindi.backend.controller;

import com.admindi.backend.approval.ApprovalTaskHandler;
import com.admindi.backend.approval.ApprovalTaskRegistry;
import com.admindi.backend.model.ActionTaskEntity;
import com.admindi.backend.model.UserEntity;
import com.admindi.backend.repository.ActionTaskRepository;
import com.admindi.backend.repository.UserRepository;
import com.admindi.backend.service.AuditService;
import com.admindi.backend.service.ReauthService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Owner inbox for action tasks. Handles three task categories:
 *
 * <ul>
 *   <li><b>Approval tasks</b> — sensitive staff-initiated actions that need the owner's
 *       explicit approval (property delete, tenant archive, lease terminate, team manage, …).
 *       These are driven by {@link ApprovalTaskRegistry}: each eventType has a dedicated
 *       {@link ApprovalTaskHandler} that owns the reauth + domain effect. Routes:
 *       {@code POST /{id}/approve} and {@code /reject}.</li>
 *   <li><b>Informational tasks</b> — post-action notifications that require the owner's
 *       awareness but have no undo (e.g. {@code TENANT_EXPEDIENTE_ARCHIVED} post-baja).
 *       Routes: {@code POST /{id}/acknowledge}. Not supported for any eventType that has
 *       an approval handler registered.</li>
 *   <li><b>Legacy generic status</b> — {@code PUT /{id}/status} is kept for backward
 *       compatibility with older dashboards; new code should prefer the typed endpoints.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/tasks")
public class ActionTaskController {

    private final ActionTaskRepository taskRepo;
    private final UserRepository userRepo;
    private final ApprovalTaskRegistry approvalRegistry;
    private final ReauthService reauthService;
    private final AuditService auditService;

    public ActionTaskController(ActionTaskRepository taskRepo,
                                UserRepository userRepo,
                                ApprovalTaskRegistry approvalRegistry,
                                ReauthService reauthService,
                                AuditService auditService) {
        this.taskRepo = taskRepo;
        this.userRepo = userRepo;
        this.approvalRegistry = approvalRegistry;
        this.reauthService = reauthService;
        this.auditService = auditService;
    }

    @GetMapping
    @PreAuthorize("hasRole('OWNER')")
    public ResponseEntity<List<ActionTaskEntity>> getMyTasks(
            @RequestParam(required = false) String status) {
        String userId = resolveUserId();
        if (status != null && !status.isBlank() && !"ALL".equalsIgnoreCase(status)) {
            return ResponseEntity.ok(taskRepo.findByUserIdAndStatusOrderByCreatedAtDesc(userId, status.toUpperCase()));
        }
        return ResponseEntity.ok(taskRepo.findByUserIdOrderByCreatedAtDesc(userId));
    }

    @GetMapping("/open-count")
    @PreAuthorize("hasRole('OWNER')")
    public ResponseEntity<Map<String, Long>> getOpenCount() {
        String userId = resolveUserId();
        long count = taskRepo.countByUserIdAndStatus(userId, "OPEN");
        return ResponseEntity.ok(Map.of("openCount", count));
    }

    /**
     * Owner approves a sensitive action that a staff member initiated. Requires password +
     * MFA (owner's own credentials — not the staff member's). The concrete effect is
     * delegated to the {@link ApprovalTaskHandler} registered for the task's eventType.
     */
    @PostMapping("/{id}/approve")
    @PreAuthorize("hasRole('OWNER')")
    public ResponseEntity<Void> approveTask(
            @PathVariable String id,
            @RequestBody PropertyController.ReauthRequest reauth) {
        String userId = resolveUserId();
        ActionTaskEntity task = loadOpenAssignedTask(id, userId);
        ApprovalTaskHandler handler = approvalRegistry.resolveHandler(task.getEventType());
        handler.onApprove(task, reauth.getPassword(), reauth.getMfaCode());
        return ResponseEntity.ok().build();
    }

    /**
     * Owner rejects a sensitive action request. Body accepts an optional {@code reason}
     * to be recorded in the audit trail. Handlers may ignore the reason for simple flows
     * (property delete) or surface it to the initiating staff for richer workflows.
     */
    @PostMapping("/{id}/reject")
    @PreAuthorize("hasRole('OWNER')")
    public ResponseEntity<Void> rejectTask(
            @PathVariable String id,
            @RequestBody(required = false) Map<String, String> body) {
        String userId = resolveUserId();
        ActionTaskEntity task = loadOpenAssignedTask(id, userId);
        ApprovalTaskHandler handler = approvalRegistry.resolveHandler(task.getEventType());
        String reason = body != null ? body.get("reason") : null;
        handler.onReject(task, reason);
        return ResponseEntity.ok().build();
    }

    /**
     * Generic "acknowledge / mark as reviewed" for informational tasks that require owner
     * attention but have no undo workflow (e.g. {@code TENANT_EXPEDIENTE_ARCHIVED} post-baja,
     * vacancy follow-ups). Requires MFA + password reauthentication — even informational
     * sign-offs must prove the session belongs to the titular account.
     *
     * <p>Not allowed for any eventType that has a registered {@link ApprovalTaskHandler}:
     * those must use /approve or /reject because they trigger the actual action flow with
     * a domain-specific outcome.
     */
    @PostMapping("/{id}/acknowledge")
    @PreAuthorize("hasRole('OWNER')")
    public ResponseEntity<Void> acknowledgeTask(
            @PathVariable String id,
            @RequestBody PropertyController.ReauthRequest reauth) {
        String userId = resolveUserId();
        ActionTaskEntity task = loadOpenAssignedTask(id, userId);

        if (approvalRegistry.supports(task.getEventType())) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Las tareas de tipo '" + task.getEventType() + "' requieren aprobación o rechazo explícito "
                            + "(/approve o /reject), no acknowledge.");
        }

        reauthService.verifyReauth(reauth.getPassword(), reauth.getMfaCode(), "TASK_ACKNOWLEDGE");

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        UserEntity actor = userRepo.findByLoginIdentifier(auth.getName()).orElseThrow();

        task.setStatus("RESOLVED");
        task.setResolvedAt(LocalDateTime.now());
        taskRepo.save(task);

        auditService.logEvent(
                actor.getLoginUsername(),
                actor.getRole() != null ? actor.getRole().name() : null,
                "OWNER_TASK_ACKNOWLEDGED",
                "ActionTask",
                task.getId(),
                task.getOwnerId(),
                null,
                String.format("{\"eventType\":\"%s\",\"resourceType\":\"%s\",\"resourceId\":\"%s\"}",
                        task.getEventType(), task.getResourceType(), task.getResourceId()),
                null,
                null);
        return ResponseEntity.ok().build();
    }

    /**
     * Load a task and enforce two invariants shared by approve / reject / acknowledge:
     * the caller owns the task (task.userId == caller) and the task is still OPEN. The
     * eventType-specific validation (whether /approve or /acknowledge is appropriate) is
     * performed by each endpoint.
     */
    private ActionTaskEntity loadOpenAssignedTask(String taskId, String callerUserId) {
        ActionTaskEntity task = taskRepo.findById(taskId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Tarea no encontrada."));
        if (!callerUserId.equals(task.getUserId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "No autorizado: esta tarea está asignada a otro usuario.");
        }
        if (!"OPEN".equalsIgnoreCase(task.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "La tarea ya no está pendiente. Estado actual: " + task.getStatus() + ".");
        }
        return task;
    }

    @PutMapping("/{id}/status")
    @PreAuthorize("hasRole('OWNER')")
    public ResponseEntity<ActionTaskEntity> updateStatus(
            @PathVariable String id,
            @RequestBody Map<String, String> body) {
        String userId = resolveUserId();
        ActionTaskEntity task = taskRepo.findById(id)
                .orElseThrow(() -> new RuntimeException("Task not found"));
        if (!task.getUserId().equals(userId)) {
            return ResponseEntity.status(403).build();
        }

        String newStatus = body.get("status");
        if (newStatus == null || !List.of("OPEN", "IN_PROGRESS", "RESOLVED", "DISMISSED").contains(newStatus.toUpperCase())) {
            return ResponseEntity.badRequest().build();
        }

        task.setStatus(newStatus.toUpperCase());
        if ("RESOLVED".equals(newStatus.toUpperCase()) || "DISMISSED".equals(newStatus.toUpperCase())) {
            task.setResolvedAt(LocalDateTime.now());
        }
        taskRepo.save(task);
        return ResponseEntity.ok(task);
    }

    private String resolveUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        UserEntity user = userRepo.findByLoginIdentifier(auth.getName())
                .orElseThrow(() -> new RuntimeException("User not found"));
        return user.getId();
    }
}
