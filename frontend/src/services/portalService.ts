import api from './api';

export interface TenantPortalDTO {
  tenantName: string;
  tenantEmail: string;
  tenantPhone: string;
  organizationName: string;
  propertyName: string;
  propertyAddress: string;
  rentAmount: number;
  paymentDay: number;
}

export const portalService = {
  getMyStatus: async (): Promise<TenantPortalDTO> => {
    const res = await api.get('/portal/my-status');
    return res.data;
  }
};
