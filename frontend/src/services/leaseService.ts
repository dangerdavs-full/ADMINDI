import api from './api';

export interface LeaseDTO {
  id?: string;
  /** Creacion por inmueble (sin unitId): contrato pivota en propertyId */
  propertyId?: string;
  unitId?: string;
  unitName?: string;
  propertyName?: string;
  tenantId: string;
  tenantName?: string;
  tenantEmail?: string;
  startDate: string;
  endDate: string;
  monthlyRent: number;
  depositAmount: number;
  paymentDay: number;
  status?: 'ACTIVE' | 'TERMINATED' | 'EXPIRED';
  documentUrl?: string;
  documentFileName?: string;
  documentContentType?: string;
}

/**
 * Helper legado para saber si un contrato TIENE PDF adjunto. Ya no construimos
 * una URL pública — todos los contratos se sirven vía el endpoint autorizado
 * `/api/secure-files/lease-document/{leaseId}` consumido por axios.
 *
 * Los call sites ahora usan `openSecureFile('lease-document', leaseId)` del
 * módulo `secureFileService` en lugar de `window.open(href)`.
 */
export function hasLeaseDocument(documentUrl?: string): boolean {
  return !!(documentUrl && documentUrl.trim().length > 0);
}

export const leaseService = {
  createLease: async (data: Partial<LeaseDTO>, contractPdf?: File | null): Promise<LeaseDTO> => {
    if (contractPdf) {
      const fd = new FormData();
      if (data.unitId) fd.append('unitId', data.unitId);
      if (data.propertyId) fd.append('propertyId', data.propertyId);
      fd.append('tenantId', data.tenantId!);
      fd.append('startDate', String(data.startDate));
      fd.append('endDate', String(data.endDate));
      fd.append('monthlyRent', String(data.monthlyRent ?? 0));
      fd.append('depositAmount', String(data.depositAmount ?? 0));
      fd.append('paymentDay', String(data.paymentDay ?? 1));
      if (data.documentUrl) fd.append('documentUrl', data.documentUrl);
      fd.append('document', contractPdf);
      const res = await api.post('/leases', fd);
      return res.data;
    }
    const res = await api.post('/leases', data);
    return res.data;
  },

  getMyLeases: async (): Promise<LeaseDTO[]> => {
    const res = await api.get('/leases');
    return res.data;
  },

  terminateLease: async (id: string): Promise<LeaseDTO> => {
    const res = await api.put(`/leases/${id}/terminate`);
    return res.data;
  }
};
