import api from './api';

/**
 * Event type constants mirrored from backend
 * {@code com.admindi.backend.constants.ActionTaskEventType}.
 * Keep in sync with that file — the frontend inbox relies on these strings to route
 * approve / reject / acknowledge flows.
 */
export const EVENT_PROPERTY_DELETE_REQUESTED = 'PROPERTY_DELETE_REQUESTED';
export const EVENT_PROPERTY_FILE_DELETE_REQUESTED = 'PROPERTY_FILE_DELETE_REQUESTED';
export const EVENT_TENANT_ARCHIVE_REQUESTED = 'TENANT_ARCHIVE_REQUESTED';
export const EVENT_LEASE_TERMINATE_REQUESTED = 'LEASE_TERMINATE_REQUESTED';
export const EVENT_TEAM_MANAGE_REQUESTED = 'TEAM_MANAGE_REQUESTED';

/**
 * Set of eventTypes the owner inbox approves/rejects via POST /api/tasks/{id}/{approve,reject}.
 * Every entry here must have a corresponding `ApprovalTaskHandler` registered in backend
 * {@code ApprovalTaskRegistry}.
 */
export const APPROVAL_EVENT_TYPES: ReadonlySet<string> = new Set([
  EVENT_PROPERTY_DELETE_REQUESTED,
  EVENT_PROPERTY_FILE_DELETE_REQUESTED,
  EVENT_TENANT_ARCHIVE_REQUESTED,
  EVENT_LEASE_TERMINATE_REQUESTED,
  // EVENT_TEAM_MANAGE_REQUESTED: handler pending — omit until backend phase lands.
]);

/** Parsed payload shape produced by {@code ApprovalRequestService} in backend. */
export interface ApprovalTaskPayload {
  initiatedByUserId?: string;
  initiatedByEmail?: string;
  initiatedByRole?: string;
  initiatedAt?: string;
  reason?: string;
  // Domain-specific fields (propertyId, propertyName, tenantProfileId, leaseId, ...)
  [k: string]: unknown;
}

export interface ActionTaskDTO {
  id: string;
  userId: string;
  ownerId: string;
  eventType: string;
  title: string;
  description: string;
  status: string;
  resourceType: string;
  resourceId: string;
  /** JSON string persisted by backend. Use {@link parseActionTaskPayload} to decode. */
  payload?: string | null;
  createdAt: string;
  resolvedAt?: string;
}

/** Safe-parse the task payload. Returns null on missing/invalid JSON. */
export const parseActionTaskPayload = (task: Pick<ActionTaskDTO, 'payload'>): ApprovalTaskPayload | null => {
  if (!task.payload) return null;
  try {
    return JSON.parse(task.payload) as ApprovalTaskPayload;
  } catch {
    return null;
  }
};

/** True if this eventType is handled by the generic approve/reject routes. */
export const isApprovalEventType = (eventType: string): boolean =>
  APPROVAL_EVENT_TYPES.has(eventType);

export const actionTaskService = {
  getMyTasks: async (status?: string): Promise<ActionTaskDTO[]> => {
    const params = status ? `?status=${status}` : '?status=OPEN';
    const res = await api.get(`/tasks${params}`);
    return res.data;
  },

  getOpenCount: async (): Promise<number> => {
    const res = await api.get('/tasks/open-count');
    return res.data.openCount;
  },

  /**
   * Owner approves any approval-tracked task (PROPERTY_DELETE_REQUESTED,
   * TENANT_ARCHIVE_REQUESTED, LEASE_TERMINATE_REQUESTED, …). Backend resolves the
   * handler dynamically via {@code ApprovalTaskRegistry}.
   */
  approveTask: async (taskId: string, password: string, mfaCode: string): Promise<void> => {
    await api.post(`/tasks/${taskId}/approve`, { password, mfaCode: mfaCode || null });
  },

  /**
   * Owner rejects an approval-tracked task. Optional {@code reason} is surfaced in the
   * audit trail and may be forwarded to the initiating staff by future handlers.
   */
  rejectTask: async (taskId: string, reason?: string): Promise<void> => {
    await api.post(`/tasks/${taskId}/reject`, reason ? { reason } : {});
  },

  /**
   * Marca como revisada una tarea informacional (p. ej. TENANT_EXPEDIENTE_ARCHIVED).
   * No aplica a eventos de aprobación (PROPERTY_DELETE_REQUESTED, TENANT_ARCHIVE_REQUESTED,
   * LEASE_TERMINATE_REQUESTED) — esos usan approve/reject.
   * Requiere reautenticación (MFA + contraseña) del titular de la sesión.
   */
  acknowledgeTask: async (taskId: string, password: string, mfaCode: string): Promise<void> => {
    await api.post(`/tasks/${taskId}/acknowledge`, { password, mfaCode: mfaCode || null });
  }
};
