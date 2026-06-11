package com.admindi.backend.approval.handlers;

import com.admindi.backend.approval.ApprovalTaskHandler;
import com.admindi.backend.constants.ActionTaskEventType;
import com.admindi.backend.model.ActionTaskEntity;
import com.admindi.backend.service.PropertyService;
import org.springframework.stereotype.Component;

/**
 * Approval handler for {@link ActionTaskEventType#PROPERTY_DELETE_REQUESTED}.
 *
 * <p>This is the first concrete handler in the generic approval framework. It wraps the
 * pre-existing {@link PropertyService#approveDeleteWithReauth(String, String, String)} and
 * {@link PropertyService#rejectDeleteProperty(String)} flows without altering their
 * semantics, so the owner experience for property-deletion requests is unchanged. The
 * refactor is purely structural: instead of the controller hard-coding the dispatch, it
 * now looks up this bean from {@link com.admindi.backend.approval.ApprovalTaskRegistry}
 * by eventType.
 *
 * <p>Why reuse instead of re-implementing:
 * <ul>
 *   <li>{@code approveDeleteWithReauth} already performs reauth (password + MFA), blocks
 *       on active leases, soft-deletes the property, marks the task RESOLVED and emits
 *       {@code PROPERTY_DELETE_APPROVED} notifications targeted at the initiating staff.</li>
 *   <li>{@code rejectDeleteProperty(taskId, reason)} marks the task DISMISSED, dispatches
 *       {@code PROPERTY_DELETE_REJECTED} with the owner's optional motive, and routes the
 *       notification to the initiating staff so they learn why the request was turned down.</li>
 * </ul>
 * Both methods also re-verify ownership and task state internally, so no check is lost by
 * routing through this handler.
 */
@Component
public class PropertyDeleteApprovalHandler implements ApprovalTaskHandler {

    private final PropertyService propertyService;

    public PropertyDeleteApprovalHandler(PropertyService propertyService) {
        this.propertyService = propertyService;
    }

    @Override
    public String getEventType() {
        return ActionTaskEventType.PROPERTY_DELETE_REQUESTED;
    }

    @Override
    public void onApprove(ActionTaskEntity task, String ownerPassword, String ownerMfaCode) {
        propertyService.approveDeleteWithReauth(task.getId(), ownerPassword, ownerMfaCode);
    }

    @Override
    public void onReject(ActionTaskEntity task, String rejectionReason) {
        propertyService.rejectDeleteProperty(task.getId(), rejectionReason);
    }
}
