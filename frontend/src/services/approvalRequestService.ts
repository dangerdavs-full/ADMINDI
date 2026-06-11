import api from './api';

/**
 * Staff-facing service to request sensitive owner-approved actions.
 *
 * Every endpoint requires the staff member to reauth with password + MFA code.
 * The backend (ApprovalRequestController) validates the staff reauth, creates an
 * ActionTaskEntity assigned to the owner, and notifies the owner so they can
 * approve/reject it from their inbox.
 *
 * See {@code ApprovalRequestController} in backend for the full contract.
 */

/**
 * Mirror of backend {@code ApprovalRequestController.ApprovalRequestBody}. Keep the shape
 * identical — Jackson silently drops unknown fields, so adding extras on the frontend
 * gives the illusion of working while the data is discarded. If you need to forward more
 * context to the task payload, extend the backend DTO first and then the type here.
 */
export interface ApprovalRequestBody {
  password: string;
  mfaCode?: string | null;
  reason?: string | null;
}

export interface ApprovalRequestResponse {
  taskId: string;
  eventType: string;
}

const buildRequestPayload = (body: ApprovalRequestBody) => ({
  password: body.password,
  mfaCode: body.mfaCode ?? null,
  reason: body.reason ?? null,
});

export const approvalRequestService = {
  /**
   * POST /api/approval-requests/property/{propertyId}/delete
   * Requires staff role OWNER, SUPER_ADMIN or authority PROPERTY_DELETE.
   */
  requestPropertyDelete: async (
    propertyId: string,
    body: ApprovalRequestBody,
  ): Promise<ApprovalRequestResponse> => {
    const res = await api.post(
      `/approval-requests/property/${propertyId}/delete`,
      buildRequestPayload(body),
    );
    return res.data;
  },

  /**
   * POST /api/approval-requests/tenant-profile/{profileId}/archive
   * Requires staff role OWNER, SUPER_ADMIN or authority PROPERTY_ARCHIVE_TENANT.
   */
  requestTenantArchive: async (
    profileId: string,
    body: ApprovalRequestBody,
  ): Promise<ApprovalRequestResponse> => {
    const res = await api.post(
      `/approval-requests/tenant-profile/${profileId}/archive`,
      buildRequestPayload(body),
    );
    return res.data;
  },

  /**
   * POST /api/approval-requests/lease/{leaseId}/terminate
   * Requires staff role OWNER, SUPER_ADMIN or authority LEASE_CREATE.
   */
  requestLeaseTerminate: async (
    leaseId: string,
    body: ApprovalRequestBody,
  ): Promise<ApprovalRequestResponse> => {
    const res = await api.post(
      `/approval-requests/lease/${leaseId}/terminate`,
      buildRequestPayload(body),
    );
    return res.data;
  },

  /**
   * POST /api/approval-requests/property-files/{fileId}/delete
   * Requires staff role OWNER, SUPER_ADMIN or write/delete authority over properties
   * (same gating as file upload, since whoever can add evidence can ask to remove it).
   */
  requestPropertyFileDelete: async (
    fileId: string,
    body: ApprovalRequestBody,
  ): Promise<ApprovalRequestResponse> => {
    const res = await api.post(
      `/approval-requests/property-files/${fileId}/delete`,
      buildRequestPayload(body),
    );
    return res.data;
  },

  /**
   * POST /api/approval-requests/property/{propertyId}/start-vacancy
   * Staff-only "Poner en renta": el administrador solicita al dueño autorizar el
   * arranque de la cadena de agentes inmobiliarios. Requiere authority
   * {@code VACANCY_START_CHAIN} (tpl-full-access).
   */
  requestVacancyStart: async (
    propertyId: string,
    body: ApprovalRequestBody,
  ): Promise<ApprovalRequestResponse> => {
    const res = await api.post(
      `/approval-requests/property/${propertyId}/start-vacancy`,
      buildRequestPayload(body),
    );
    return res.data;
  },
};

export type ApprovalActionType =
  | 'PROPERTY_DELETE'
  | 'PROPERTY_FILE_DELETE'
  | 'TENANT_ARCHIVE'
  | 'LEASE_TERMINATE'
  | 'VACANCY_START';

/**
 * Roles that can execute sensitive actions directly (with their own reauth, no third-party
 * approval). Anyone else with the required granular permission must go through the
 * approval workflow (POST /api/approval-requests/…).
 *
 * V52 — SUPER_ADMIN queda fuera por diseño: su alcance es la administración de la
 * plataforma (dueños, auditoría, recuperación, archivo), no la operación de datos
 * del dueño. Solo OWNER ejecuta directo.
 */
const DIRECT_EXECUTION_ROLES: ReadonlySet<string> = new Set(['OWNER']);

/** True if the given role can execute sensitive actions directly, skipping the approval flow. */
export const canExecuteDirectly = (role: string | null | undefined): boolean =>
  role != null && DIRECT_EXECUTION_ROLES.has(role);

/** UI-level metadata for each approval flow (title, description, accent colour). */
export const APPROVAL_ACTION_META: Record<
  ApprovalActionType,
  { title: string; headline: string; description: string; accent: 'rose' | 'amber' | 'indigo' }
> = {
  PROPERTY_DELETE: {
    title: 'Solicitar eliminación de inmueble',
    headline: 'Eliminación de inmueble',
    description:
      'El dueño recibirá una solicitud para aprobar la eliminación de este inmueble. ' +
      'Solo procederá cuando el titular confirme con su contraseña y MFA.',
    accent: 'rose',
  },
  TENANT_ARCHIVE: {
    title: 'Solicitar archivo de expediente',
    headline: 'Archivo de expediente de inquilino',
    description:
      'Se archivará el expediente operacional (contratos, saldos al día, historial accesible). ' +
      'El dueño debe aprobar la solicitud antes de que el cambio se aplique.',
    accent: 'amber',
  },
  LEASE_TERMINATE: {
    title: 'Solicitar terminación de contrato',
    headline: 'Terminación de contrato',
    description:
      'Se solicitará al dueño dar por terminado el contrato. La acción solo se ejecuta ' +
      'tras la aprobación con contraseña y MFA del titular.',
    accent: 'indigo',
  },
  PROPERTY_FILE_DELETE: {
    title: 'Solicitar eliminación de archivo',
    headline: 'Eliminación de archivo del inmueble',
    description:
      'La acción borra el archivo físico y es irreversible. El dueño decidirá desde su ' +
      'bandeja y confirmará con su contraseña y MFA antes de que el archivo se elimine.',
    accent: 'rose',
  },
  VACANCY_START: {
    title: 'Solicitar "Poner en renta"',
    headline: 'Arranque de cadena de agentes inmobiliarios',
    description:
      'El dueño recibirá tu solicitud para autorizar el arranque de la cadena de agentes ' +
      'inmobiliarios sobre este inmueble. Al aprobar, se abrirá la vacancia y se notificará ' +
      'al primer agente según las prioridades del dueño.',
    accent: 'indigo',
  },
};
