import api from './api';

/** Alineado a backend TenantExpedienteSummaryDTO — expediente completo para vista dueño/admin. */
export interface TenantExpedienteSummaryDTO {
  tenantProfileId?: string;
  tenantName?: string;
  tenantEmail?: string;
  tenantPhone?: string;
  organizationName?: string;
  propertyId?: string;
  propertyName?: string;
  propertyAddress?: string;
  rentAmount?: number;
  paymentDay?: number;
  leaseId?: string;
  leaseStatus?: string;
  leaseStartDate?: string;
  leaseEndDate?: string;
  leaseDocumentUrl?: string;
  leaseDocumentFileName?: string;
}

export interface TenantDTO {
  id?: string;
  userId?: string;
  organizationId?: string;
  name: string;
  /** V51 — identificador de login (case-sensitive). Obligatorio en altas nuevas. */
  username?: string;
  /** V51 — email obligatorio en altas nuevas (contacto + recuperación). */
  email?: string;
  phone: string;
  propertyId: string;
  rentAmount: number;
  paymentDay: number;
  hasLateFee: boolean;
  lateFeeType: 'PERCENTAGE' | 'FIXED_AMOUNT';
  lateFeeValue: number;
  gracePeriodDays: number;
  tempPassword?: string;
  /** Alta integral (ISO yyyy-MM-dd) */
  leaseStartDate?: string;
  leaseEndDate?: string;
  depositAmount?: number;
  leaseId?: string;
  leaseStatus?: string;
}

export const tenantService = {
  /**
   * Alta integral: perfil + lease ACTIVO. Con PDF opcional usa multipart.
   */
  createTenant: async (data: TenantDTO, contractPdf?: File | null): Promise<TenantDTO> => {
    if (contractPdf && contractPdf.size > 0) {
      const fd = new FormData();
      fd.append('name', data.name);
      if (data.username) fd.append('username', data.username);
      if (data.email) fd.append('email', data.email);
      fd.append('phone', data.phone);
      fd.append('propertyId', data.propertyId);
      fd.append('rentAmount', String(data.rentAmount ?? 0));
      fd.append('paymentDay', String(data.paymentDay ?? 1));
      fd.append('leaseStartDate', data.leaseStartDate!);
      fd.append('leaseEndDate', data.leaseEndDate!);
      if (data.depositAmount != null) {
        fd.append('depositAmount', String(data.depositAmount));
      }
      fd.append('hasLateFee', String(data.hasLateFee));
      fd.append('lateFeeType', data.lateFeeType || 'FIXED_AMOUNT');
      fd.append('lateFeeValue', String(data.lateFeeValue ?? 0));
      fd.append('gracePeriodDays', String(data.gracePeriodDays ?? 0));
      fd.append('contractPdf', contractPdf);
      const res = await api.post<TenantDTO>('/tenants', fd);
      return res.data;
    }
    const res = await api.post<TenantDTO>('/tenants', data, {
      headers: { 'Content-Type': 'application/json' },
    });
    return res.data;
  },

  getMyTenants: async (): Promise<TenantDTO[]> => {
    const res = await api.get<TenantDTO[]>('/tenants');
    return res.data;
  },

  updateTenant: async (id: string, data: TenantDTO): Promise<TenantDTO> => {
    const res = await api.put<TenantDTO>(`/tenants/${id}`, data);
    return res.data;
  },

  /**
   * Baja operativa (archivo) del expediente. El backend exige reautenticación:
   * password obligatorio; mfaCode requerido solo si el usuario actual tiene MFA habilitado.
   */
  deleteTenant: async (id: string, password: string, mfaCode?: string): Promise<void> => {
    await api.delete(`/tenants/${id}`, {
      data: { password, mfaCode: mfaCode || null },
    });
  },

  getExpedienteSummary: async (tenantProfileId: string): Promise<TenantExpedienteSummaryDTO> => {
    const res = await api.get<TenantExpedienteSummaryDTO>(`/tenants/${tenantProfileId}/expediente-summary`);
    return res.data;
  },
};
