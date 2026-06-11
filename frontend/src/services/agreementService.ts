import api from './api';

export interface AgreementInstallmentDTO {
  id: string;
  agreementId: string;
  dueDate: string;
  amount: number;
  status: 'PENDING' | 'PAID' | 'LATE' | 'CANCELLED';
  paidAt?: string;
  paymentId?: string;
}

export interface PaymentAgreementDTO {
  id: string;
  ownerId: string;
  tenantProfileId: string;
  tenantName?: string;
  tenantEmail?: string;
  leaseId?: string;
  invoiceId?: string;
  monthYear?: string;
  requestedAmount: number;
  approvedAmount?: number;
  deferredAmount?: number;
  reason?: string;
  description?: string;
  evidenceFileUrl?: string;
  status: 'REQUESTED' | 'APPROVED' | 'REJECTED' | 'ACTIVE' | 'COMPLETED' | 'BREACHED' | 'CANCELLED';
  createdAt?: string;
  approvedAt?: string;
  approvedBy?: string;
  rejectedAt?: string;
  rejectedBy?: string;
  rejectionReason?: string;
  installments?: AgreementInstallmentDTO[];
}

export const agreementService = {
  /** Tenant: request an agreement */
  requestAgreement: async (invoiceId: string, requestedAmount: number, reason?: string, description?: string): Promise<PaymentAgreementDTO> => {
    const res = await api.post('/agreements', { invoiceId, requestedAmount, reason, description });
    return res.data;
  },

  /** Tenant: get my agreements */
  getMyAgreements: async (tenantProfileId?: string): Promise<PaymentAgreementDTO[]> => {
    const res = await api.get('/agreements/mine', {
      params: tenantProfileId ? { tenantProfileId } : undefined,
    });
    return res.data;
  },

  /** Owner: get pending agreements */
  getPendingAgreements: async (): Promise<PaymentAgreementDTO[]> => {
    const res = await api.get('/agreements/pending');
    return res.data;
  },

  /** Owner/Admin/Accountant: get all agreements */
  getAllAgreements: async (): Promise<PaymentAgreementDTO[]> => {
    const res = await api.get('/agreements');
    return res.data;
  },

  /** V67 — Owner/Admin: convenios de un inquilino específico. */
  getAgreementsByTenant: async (tenantProfileId: string): Promise<PaymentAgreementDTO[]> => {
    const res = await api.get(`/agreements/tenant/${tenantProfileId}`);
    return res.data;
  },

  /** Owner: approve agreement */
  approveAgreement: async (
    id: string,
    approvedAmount: number,
    installments?: { dueDate: string; amount: number }[]
  ): Promise<PaymentAgreementDTO> => {
    const res = await api.post(`/agreements/${id}/approve`, { approvedAmount, installments });
    return res.data;
  },

  /** Owner: reject agreement */
  rejectAgreement: async (id: string, rejectionReason?: string): Promise<PaymentAgreementDTO> => {
    const res = await api.post(`/agreements/${id}/reject`, { rejectionReason });
    return res.data;
  },
};
