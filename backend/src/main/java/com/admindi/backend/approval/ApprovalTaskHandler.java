package com.admindi.backend.approval;

import com.admindi.backend.model.ActionTaskEntity;

/**
 * Contract for handling the owner's response to an approval-required action task.
 *
 * <p>Each sensitive staff-initiated action (property delete, tenant archive, lease
 * termination, team-management change, etc.) registers one handler keyed by the task's
 * {@code eventType}. The generic {@link com.admindi.backend.controller.ActionTaskController}
 * delegates approve/reject to the matching handler via {@link ApprovalTaskRegistry}.
 *
 * <h3>Security contract</h3>
 * Implementations are responsible for:
 * <ul>
 *   <li>Verifying the caller is the task's titular owner.</li>
 *   <li>Re-authenticating the owner with password and MFA (via {@code ReauthService}) on approve.</li>
 *   <li>Executing the domain-level side effect (delete, archive, terminate, etc.).</li>
 *   <li>Transitioning the task to {@code RESOLVED} (approve) or {@code DISMISSED} (reject).</li>
 *   <li>Emitting audit events and dispatching notifications.</li>
 * </ul>
 *
 * <p>The controller performs basic pre-checks (task exists, status = OPEN, assigned to the
 * caller, eventType supported) but delegates all domain-specific logic to the handler.
 */
public interface ApprovalTaskHandler {

    /**
     * The {@code ActionTaskEntity.eventType} this handler processes. Must match a constant
     * in {@link com.admindi.backend.constants.ActionTaskEventType}.
     */
    String getEventType();

    /**
     * Owner approves the action. Implementation must verify reauth (password + MFA), execute
     * the side effect, and mark the task RESOLVED. Throws {@code ResponseStatusException} or
     * {@code RuntimeException} on authorization / reauth / domain failure.
     *
     * @param task            the task being approved (already validated as OPEN and assigned).
     * @param ownerPassword   owner's password for reauth (required).
     * @param ownerMfaCode    owner's TOTP code (required if owner has MFA enabled).
     */
    void onApprove(ActionTaskEntity task, String ownerPassword, String ownerMfaCode);

    /**
     * Owner rejects the action. Implementation must mark the task DISMISSED and emit audit.
     * Reauth is optional for reject — rejecting a request is non-destructive, so handlers
     * may choose to require it only for high-sensitivity flows.
     *
     * @param task              the task being rejected (already validated as OPEN and assigned).
     * @param rejectionReason   optional human-readable reason provided by the owner (may be null).
     */
    void onReject(ActionTaskEntity task, String rejectionReason);
}
