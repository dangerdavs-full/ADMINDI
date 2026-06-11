package com.admindi.backend.constants;

/**
 * Centralized registry of {@code ActionTaskEntity.eventType} values that drive the
 * owner-approval inbox. These strings are persisted in {@code action_tasks.event_type}
 * and referenced across the approval framework ({@link com.admindi.backend.approval.ApprovalTaskHandler}
 * implementations, controller dispatch, frontend inbox filters).
 *
 * Only event types listed here participate in the generic approve/reject pipeline
 * exposed by {@code POST /api/tasks/{id}/approve} and {@code /reject}. Informational
 * tasks (e.g. TENANT_EXPEDIENTE_ARCHIVED post-baja) are handled via {@code /acknowledge}
 * and are deliberately not included here.
 */
public final class ActionTaskEventType {

    private ActionTaskEventType() {}

    /** Staff (e.g. Property Admin) requests the owner to approve deletion of a property. */
    public static final String PROPERTY_DELETE_REQUESTED = "PROPERTY_DELETE_REQUESTED";

    /** Staff requests the owner to approve archive/purge of a tenant expedient. */
    public static final String EXPEDIENT_DELETE_REQUESTED = "EXPEDIENT_DELETE_REQUESTED";

    /** Staff requests the owner to approve full tenant archive across the organization. */
    public static final String TENANT_ARCHIVE_REQUESTED = "TENANT_ARCHIVE_REQUESTED";

    /** Staff requests the owner to approve termination of an active lease. */
    public static final String LEASE_TERMINATE_REQUESTED = "LEASE_TERMINATE_REQUESTED";

    /** Staff requests the owner to approve a team-management change (add/remove staff or role change). */
    public static final String TEAM_MANAGE_REQUESTED = "TEAM_MANAGE_REQUESTED";

    /** Staff requests the owner to approve deletion of a property file (photo, plan, document). */
    public static final String PROPERTY_FILE_DELETE_REQUESTED = "PROPERTY_FILE_DELETE_REQUESTED";

    /**
     * V52 — Staff requests the owner to approve putting a property back on the rental market
     * (triggers the real-estate agent chain via {@code VacancyAgentOrchestrationService}).
     * OWNER puede ejecutar directo sin pasar por approval; PROPERTY_ADMIN debe pedir esta
     * autorización al dueño.
     */
    public static final String VACANCY_START_REQUESTED = "VACANCY_START_REQUESTED";
}
