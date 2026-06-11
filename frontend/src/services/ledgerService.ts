import api from './api';

export interface InvoiceDTO {
  id: string;
  tenantName: string;
  tenantEmail: string;
  monthYear: string;
  issueDate: string;
  dueDate: string;
  baseAmount: number;
  appliedLateFee: number;
  totalAmount: number;
  // Settlement accounting
  paidAmount: number;
  outstandingAmount: number;
  creditBalance: number;
  settlementStatus: 'UNPAID' | 'PARTIALLY_PAID' | 'PAID' | 'OVERPAID';
  status: 'PENDING' | 'PAID' | 'LATE' | 'PARTIALLY_PAID';
  paidDate?: string;
  paymentReference?: string;
  proofOfPaymentUrl?: string;
  tenantUploadDate?: string;
  leaseId?: string;
  propertyId?: string;
  shortfallReason?: string;
  shortfallDescription?: string;
  promisedCompletionDate?: string;
  agreementSummaryStatus?: string;
}

export type ShortfallReason =
  | 'PARTIAL_SAME_MONTH'
  | 'PARTIAL_NEXT_MONTH'
  | 'REQUESTING_AGREEMENT'
  | 'BANK_ISSUE'
  | 'OTHER';

export interface ShortfallSubmitResultDTO {
  invoice: InvoiceDTO;
  agreementRequired: boolean;
  message: string;
}

export const ledgerService = {
  getOrgInvoices: async (): Promise<InvoiceDTO[]> => {
    const res = await api.get('/ledger/org');
    return res.data;
  },

  getTenantInvoices: async (tenantProfileId?: string): Promise<InvoiceDTO[]> => {
    const res = await api.get('/ledger/tenant', {
      params: tenantProfileId ? { tenantProfileId } : undefined,
    });
    return res.data;
  },

  uploadProof: async (invoiceId: string, paymentReference: string, file: File): Promise<void> => {
    const formData = new FormData();
    formData.append('paymentReference', paymentReference);
    formData.append('file', file);

    await api.post(`/ledger/${invoiceId}/upload-proof`, formData, {
      headers: { 'Content-Type': 'multipart/form-data' }
    });
  },

  submitShortfallReason: async (
    invoiceId: string,
    body: {
      shortfallReason: ShortfallReason;
      shortfallDescription?: string;
      promisedCompletionDate?: string;
    }
  ): Promise<ShortfallSubmitResultDTO> => {
    const res = await api.post(`/payments/${invoiceId}/shortfall-reason`, body);
    return res.data;
  },
};
