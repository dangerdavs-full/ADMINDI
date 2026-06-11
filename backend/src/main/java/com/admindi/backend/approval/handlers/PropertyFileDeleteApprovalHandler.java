package com.admindi.backend.approval.handlers;

import com.admindi.backend.approval.ApprovalTaskHandler;
import com.admindi.backend.constants.ActionTaskEventType;
import com.admindi.backend.model.ActionTaskEntity;
import com.admindi.backend.repository.ActionTaskRepository;
import com.admindi.backend.service.PropertyFileService;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * Approval handler for {@link ActionTaskEventType#PROPERTY_FILE_DELETE_REQUESTED}.
 *
 * <p>Thin orchestrator: the owner's reauth, the physical file deletion, the audit trail
 * and the recipient-routed notification all live in {@link PropertyFileService}. This
 * class exists so the generic approve/reject routing in
 * {@link com.admindi.backend.approval.ApprovalTaskRegistry} has a concrete bean to call.
 *
 * <h3>Why the status flip lives here instead of in the service</h3>
 * The service-level helpers can be called from two entry points (direct owner delete vs
 * approved task), and only the "approved task" path should flip the task state. Keeping
 * the {@code task.setStatus("RESOLVED")} here preserves that invariant — direct deletes
 * never touch the action-task table.
 */
@Component
public class PropertyFileDeleteApprovalHandler implements ApprovalTaskHandler {

    private final PropertyFileService propertyFileService;
    private final ActionTaskRepository taskRepo;

    public PropertyFileDeleteApprovalHandler(PropertyFileService propertyFileService,
                                             ActionTaskRepository taskRepo) {
        this.propertyFileService = propertyFileService;
        this.taskRepo = taskRepo;
    }

    @Override
    public String getEventType() {
        return ActionTaskEventType.PROPERTY_FILE_DELETE_REQUESTED;
    }

    @Override
    public void onApprove(ActionTaskEntity task, String ownerPassword, String ownerMfaCode) {
        propertyFileService.approveFileDeletion(task, ownerPassword, ownerMfaCode);

        task.setStatus("RESOLVED");
        task.setResolvedAt(LocalDateTime.now());
        taskRepo.save(task);
    }

    @Override
    public void onReject(ActionTaskEntity task, String rejectionReason) {
        propertyFileService.recordFileDeletionRejection(task, rejectionReason);

        task.setStatus("DISMISSED");
        task.setResolvedAt(LocalDateTime.now());
        taskRepo.save(task);
    }
}
