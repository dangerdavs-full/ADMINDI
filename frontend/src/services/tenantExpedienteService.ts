import api from './api';

export interface TenantExpedienteListItem {
  tenantProfileId: string;
  propertyId?: string;
  propertyName?: string;
  propertyAddress?: string;
  rentAmount: number;
  paymentDay: number;
  leaseId?: string;
  leaseStatus?: string;
}

export interface TenantExpedienteSummary {
  tenantProfileId: string;
  tenantName: string;
  tenantEmail: string;
  tenantPhone?: string;
  organizationName: string;
  propertyId?: string;
  propertyName?: string;
  propertyAddress?: string;
  rentAmount: number;
  paymentDay: number;
  leaseId?: string;
  leaseStatus?: string;
  leaseStartDate?: string;
  leaseEndDate?: string;
  leaseDocumentUrl?: string;
  leaseDocumentFileName?: string;
}

export const tenantExpedienteService = {
  getExpedientes: async (): Promise<TenantExpedienteListItem[]> => {
    const res = await api.get('/tenant/expedientes');
    return res.data;
  },

  getSummary: async (tenantProfileId: string): Promise<TenantExpedienteSummary> => {
    const res = await api.get(`/tenant/expedientes/${tenantProfileId}/summary`);
    return res.data;
  },
};