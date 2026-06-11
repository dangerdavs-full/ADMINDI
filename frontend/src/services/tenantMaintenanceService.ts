import api from './api';

/**
 * Servicio del inquilino para reportar y dar seguimiento a tickets de
 * mantenimiento. El flujo es:
 *  1. `uploadFile` por cada foto del problema (claim a nombre del inquilino).
 *  2. `createTicket` con los fileIds — el backend valida que los subió él mismo.
 *  3. `listMyTickets` / `getTicketDetail` / `getTicketQuotes` para seguimiento.
 *
 * Autorización: todos los endpoints exigen rol TENANT en el backend y aplican
 * IDOR guards sobre el tenantProfile correspondiente.
 */

export type MaintenanceTicketStatus =
  | 'AWAITING_OWNER_AUTH'
  | 'AWAITING_PROVIDER_ACCEPT'
  | 'ACCEPTED'
  | 'QUOTED'
  | 'APPROVED'
  | 'COMPLETED'
  | 'CANCELLED'
  | 'REJECTED_BY_OWNER'
  | 'OPEN';

export type MaintenanceUrgency = 'LOW' | 'NORMAL' | 'HIGH' | 'CRITICAL';

export interface TenantMaintenanceTicketDTO {
  id: string;
  ownerId: string;
  propertyId: string;
  tenantProfileId?: string | null;
  title: string;
  description?: string | null;
  urgency?: MaintenanceUrgency | string;
  status: MaintenanceTicketStatus | string;
  assignedProviderId?: string | null;
  providerAcceptedAt?: string | null;
  awaitingOwnerAuth?: boolean | null;
  authorizedAt?: string | null;
  rejectionReason?: string | null;
  photoFileIdsJson?: string | null;
  createdAt?: string;
  resolvedAt?: string | null;
}

export interface TenantMaintenanceQuoteDTO {
  id: string;
  ticketId: string;
  providerId?: string;
  amount: number;
  description?: string | null;
  visitNotes?: string | null;
  evidenceFileId?: string | null;
  status: 'SUBMITTED' | 'APPROVED' | 'REJECTED' | string;
  submittedAt?: string;
  approvedAt?: string | null;
}

export interface CreateTicketPayload {
  propertyId?: string;
  tenantProfileId: string;
  title: string;
  description?: string;
  urgency?: MaintenanceUrgency;
  photoFileIds?: string[];
}

export const tenantMaintenanceService = {
  /** Sube una foto del problema y devuelve el fileId opaco (URL interna). */
  uploadFile: async (file: File, category = 'maintenance-photo'): Promise<string> => {
    const fd = new FormData();
    fd.append('file', file);
    fd.append('category', category);
    const res = await api.post('/tenant/workflow/maintenance/files/upload', fd, {
      headers: { 'Content-Type': 'multipart/form-data' },
    });
    return res.data.fileId as string;
  },

  /** Crea el ticket con fotos ya subidas. El backend valida que las fotos sean del inquilino. */
  createTicket: async (payload: CreateTicketPayload): Promise<TenantMaintenanceTicketDTO> => {
    const res = await api.post('/tenant/workflow/maintenance/tickets', payload);
    return res.data;
  },

  /** Lista los tickets del inquilino. Opcionalmente filtra por tenantProfileId. */
  listMyTickets: async (tenantProfileId?: string): Promise<TenantMaintenanceTicketDTO[]> => {
    const params = tenantProfileId ? { tenantProfileId } : undefined;
    const res = await api.get('/tenant/workflow/maintenance/tickets/mine', { params });
    return res.data;
  },

  /** Detalle de un ticket (el backend valida que pertenezca al inquilino autenticado). */
  getTicketDetail: async (ticketId: string): Promise<TenantMaintenanceTicketDTO> => {
    const res = await api.get(`/tenant/workflow/maintenance/tickets/${ticketId}`);
    return res.data;
  },

  /** Cotizaciones del ticket (para que el inquilino vea avance; el monto lo decide el dueño). */
  getTicketQuotes: async (ticketId: string): Promise<TenantMaintenanceQuoteDTO[]> => {
    const res = await api.get(`/tenant/workflow/maintenance/tickets/${ticketId}/quotes`);
    return res.data;
  },
};
