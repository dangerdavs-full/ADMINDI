import api from './api';
import type { InvoiceDTO } from './ledgerService';
import type { ReportingPeriodBoundsDTO } from './ownerAccountingService';

export interface PropertyDTO {
  id?: string;
  organizationId?: string;
  name: string;
  address: string;
  type?: string;
  predial?: string;
  description?: string;
  status:
    | 'AVAILABLE'
    | 'OCCUPIED'
    | 'MAINTENANCE'
    | 'DELETED'
    | 'PENDING_RENT'
    | 'PROSPECT_PROPOSED'
    | 'AWAITING_CONTRACT';
  active?: boolean;
  unitCount?: number;
  createdAt?: string;
}

export interface PropertyFileDTO {
  id: string;
  propertyId: string;
  category: string;
  fileName: string;
  contentType: string;
  sizeBytes: number;
  uploadedAt: string;
  downloadUrl: string;
  uploadedBy?: string;
  uploaderRole?: string;
  label?: string;
  note?: string;
}

export interface PropertyMovementDTO {
  id: string;
  propertyId: string;
  resourceType?: string;
  resourceId?: string;
  actorUserId?: string;
  actorRole?: string;
  eventType: string;
  title: string;
  description?: string;
  occurredAt: string;
  metadataJson?: string;
  attachmentFileId?: string;
}

export interface PropertyMonthlyReportDTO {
  propertyId: string;
  monthYear: string;
  expectedRent: number;
  collected: number;
  outstanding: number;
  creditBalance: number;
  partialPaymentsCount: number;
  activeAgreements: number;
  breachedAgreements: number;
  deferredAmount: number;
  maintenanceTickets: number;
  maintenanceCost: number;
  commercialActivities: number;
  newFilesCount: number;
  alerts: string[];
}

export interface PropertyAnnualReportDTO {
  propertyId: string;
  year: number;
  expectedAnnual: number;
  collectedAnnual: number;
  outstandingAnnual: number;
  expensesAnnual: number;
  agreementsHistoric: number;
  monthsWithVacancy: number;
  maintenanceTicketsYear: number;
  commercialActivitiesYear: number;
}

/** Vacancias ligadas al inmueble (comercial). */
export interface VacancyListItemDTO {
  id: string;
  ownerId?: string;
  propertyId: string;
  openedAt?: string;
  closedAt?: string | null;
  status?: string;
  assignedAgentId?: string | null;
  notes?: string | null;
}

export const propertyService = {
  createProperty: async (data: PropertyDTO): Promise<PropertyDTO> => {
    const res = await api.post('/properties', data);
    return res.data;
  },

  getMyProperties: async (): Promise<PropertyDTO[]> => {
    const res = await api.get('/properties');
    return res.data;
  },

  getPropertyDetail: async (id: string): Promise<PropertyDTO> => {
    const res = await api.get(`/properties/${id}`);
    return res.data;
  },

  updateProperty: async (id: string, data: PropertyDTO): Promise<PropertyDTO> => {
    const res = await api.put(`/properties/${id}`, data);
    return res.data;
  },

  deleteProperty: async (id: string, payload: { password: string; mfaCode?: string }): Promise<void> => {
    await api.delete(`/properties/${id}`, { data: payload });
  },

  /**
   * V66 — Preview de impacto del hard-delete. Devuelve contadores de lo que
   * se borrará para que el modal de confirmación sea honesto con el dueño.
   * Solo OWNER.
   */
  getDeleteImpact: async (id: string): Promise<{
    propertyId: string;
    leases: number;
    tenantProfiles: number;
    invoices: number;
    payments: number;
    transferProofs: number;
    maintenanceTickets: number;
    expenses: number;
    propertyFiles: number;
    units: number;
  }> => {
    const res = await api.get(`/properties/${id}/delete-impact`);
    return res.data;
  },

  /**
   * @deprecated Use {@link approvalRequestService.requestPropertyDelete} instead — the
   * backend endpoint now requires double reauth (password + MFA + reason). Staff calling
   * this stub without credentials would receive a 401 / validation error.
   */
  requestDeleteProperty: async (): Promise<never> => {
    throw new Error(
      'propertyService.requestDeleteProperty está deprecado. Usa approvalRequestService.requestPropertyDelete con password, MFA y motivo.',
    );
  },

  uploadFile: async (
    propertyId: string,
    category: string,
    file: File,
    opts?: { label?: string; note?: string }
  ): Promise<PropertyFileDTO> => {
    const formData = new FormData();
    formData.append('file', file);
    const params = new URLSearchParams({ category });
    if (opts?.label) params.set('label', opts.label);
    if (opts?.note) params.set('note', opts.note);
    const res = await api.post(`/properties/${propertyId}/files?${params.toString()}`, formData, {
      headers: { 'Content-Type': 'multipart/form-data' }
    });
    return res.data;
  },

  getFiles: async (propertyId: string): Promise<PropertyFileDTO[]> => {
    const res = await api.get(`/properties/${propertyId}/files`);
    return res.data;
  },

  /**
   * Hard-delete a property file. Restricted to OWNER / SUPER_ADMIN and gated by reauth
   * (password + MFA) on the backend. Staff must go through
   * {@code approvalRequestService.requestPropertyFileDelete} instead.
   *
   * Axios's {@code delete} method sends a body as the second positional arg's
   * {@code data} property, not the first — easy to get wrong, so encapsulated here.
   */
  deleteFile: async (fileId: string, password: string, mfaCode?: string | null): Promise<void> => {
    await api.delete(`/files/${fileId}`, {
      data: { password, mfaCode: mfaCode ?? null },
    });
  },

  getTimeline: async (propertyId: string): Promise<PropertyMovementDTO[]> => {
    const res = await api.get(`/properties/${propertyId}/timeline`);
    return res.data;
  },

  getPropertyInvoices: async (propertyId: string): Promise<InvoiceDTO[]> => {
    const res = await api.get(`/properties/${propertyId}/invoices`);
    return res.data;
  },

  getMonthlyReport: async (propertyId: string, monthYear: string): Promise<PropertyMonthlyReportDTO> => {
    const res = await api.get(`/properties/${propertyId}/reports/monthly`, { params: { monthYear } });
    return res.data;
  },

  getAnnualReport: async (propertyId: string, year: number): Promise<PropertyAnnualReportDTO> => {
    const res = await api.get(`/properties/${propertyId}/reports/annual`, { params: { year } });
    return res.data;
  },

  getReportingPeriodBounds: async (propertyId: string): Promise<ReportingPeriodBoundsDTO> => {
    const res = await api.get(`/properties/${propertyId}/reporting-period-bounds`);
    return res.data;
  },

  listVacanciesByProperty: async (propertyId: string): Promise<VacancyListItemDTO[]> => {
    const res = await api.get(`/vacancies/by-property/${propertyId}`);
    return res.data;
  },
};
