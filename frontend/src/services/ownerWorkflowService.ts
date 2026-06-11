import api from './api';

// ─── Tipos ──────────────────────────────────────────────────────────────────

export interface MaintenanceTicketDTO {
  id: string;
  ownerId: string;
  propertyId: string;
  title: string;
  description?: string;
  urgency?: string;
  status: string;
  awaitingOwnerAuth?: boolean;
  authorizedAt?: string | null;
  authorizedBy?: string | null;
  ownerChosenProviderId?: string | null;
  assignedProviderId?: string | null;
  rejectionReason?: string | null;
  platformDiscountPct?: number | null;
  platformDiscountAmount?: number | null;
  photoFileIdsJson?: string | null;
  createdAt?: string;
  updatedAt?: string;
}

export interface MaintenanceQuoteDTO {
  id: string;
  ticketId: string;
  providerId?: string;
  amount: number;
  description?: string;
  evidenceFileId?: string;
  status: 'SUBMITTED' | 'APPROVED' | 'REJECTED';
  submittedAt?: string;
  approvedAt?: string | null;
}

export interface ProspectSubmissionDTO {
  id: string;
  vacancyId: string;
  ownerId: string;
  agentUserId: string;
  prospectName: string;
  prospectPhone?: string;
  prospectEmail?: string;
  notes?: string;
  status: string; // PENDING | ACCEPTED | REJECTED | EXPIRED
  submittedAt?: string;
  decidedAt?: string | null;
  rejectionReason?: string | null;
  lastReminderAt?: string | null;
}

export interface AgentCommissionDTO {
  id: string;
  ownerId: string;
  agentUserId: string;
  vacancyId?: string;
  leaseId?: string | null;
  amount: number;
  platformRevenueShare?: number;
  agentSource?: string; // PLATFORM | PRIVATE
  status: string;       // PENDING_PAYMENT | SUBMITTED_PROOF | VALIDATING | PAID | FAILED | VOIDED
  speiProofFileId?: string | null;
  claveRastreo?: string | null;
  bankEmitter?: string | null;
  declaredAmount?: number | null;
  validationAttempts?: number;
  paidAt?: string | null;
  voidedAt?: string | null;
  voidReason?: string | null;
  createdAt?: string;
  linkedExpenseId?: string | null;
}

// ─── API ────────────────────────────────────────────────────────────────────

export const ownerWorkflowService = {
  /** Sube un archivo genérico (SPEI proof, evidencia). Devuelve fileId. */
  uploadFile: async (file: File, category: string): Promise<string> => {
    const fd = new FormData();
    fd.append('file', file);
    fd.append('category', category);
    const res = await api.post('/owner/workflow/files/upload', fd, {
      headers: { 'Content-Type': 'multipart/form-data' },
    });
    return res.data.fileId as string;
  },

  // Mantenimiento
  getPendingAuthTickets: async (): Promise<MaintenanceTicketDTO[]> => {
    const res = await api.get('/owner/workflow/maintenance/tickets/pending-authorization');
    return res.data;
  },
  getPendingApprovalQuotes: async (): Promise<MaintenanceQuoteDTO[]> => {
    const res = await api.get('/owner/workflow/maintenance/quotes/pending-approval');
    return res.data;
  },
  getReadyToPayTickets: async (): Promise<MaintenanceTicketDTO[]> => {
    const res = await api.get('/owner/workflow/maintenance/tickets/ready-to-pay');
    return res.data;
  },
  getAllTickets: async (): Promise<MaintenanceTicketDTO[]> => {
    const res = await api.get('/owner/workflow/maintenance/tickets');
    return res.data;
  },
  getTicketQuotes: async (ticketId: string): Promise<MaintenanceQuoteDTO[]> => {
    const res = await api.get(`/owner/workflow/maintenance/tickets/${ticketId}/quotes`);
    return res.data;
  },
  authorizeTicket: async (ticketId: string, providerUserId?: string): Promise<MaintenanceTicketDTO> => {
    const res = await api.post(`/owner/workflow/maintenance/tickets/${ticketId}/authorize`,
      providerUserId ? { providerUserId } : {});
    return res.data;
  },
  rejectTicket: async (ticketId: string, reason: string): Promise<MaintenanceTicketDTO> => {
    const res = await api.post(`/owner/workflow/maintenance/tickets/${ticketId}/reject`, { reason });
    return res.data;
  },
  approveQuote: async (quoteId: string): Promise<MaintenanceTicketDTO> => {
    const res = await api.post(`/owner/workflow/maintenance/quotes/${quoteId}/approve`);
    return res.data;
  },
  rejectQuote: async (quoteId: string, reason: string): Promise<MaintenanceQuoteDTO> => {
    const res = await api.post(`/owner/workflow/maintenance/quotes/${quoteId}/reject`, { reason });
    return res.data;
  },
  payAndCloseTicket: async (
    ticketId: string,
    paidAmount: number,
    speiProofFileId: string
  ): Promise<MaintenanceTicketDTO> => {
    const res = await api.post(`/owner/workflow/maintenance/tickets/${ticketId}/pay-and-close`, {
      paidAmount,
      speiProofFileId,
    });
    return res.data;
  },

  /** V63 — tickets con SPEI registrado esperando confirmación del proveedor. */
  getAwaitingPaymentConfirmation: async (): Promise<MaintenanceTicketDTO[]> => {
    const res = await api.get('/owner/workflow/maintenance/tickets/awaiting-payment-confirmation');
    return res.data;
  },

  /**
   * V65 — resumen consolidado de pendientes para el dashboard. Agrupa
   * mantenimiento, cotizaciones, pagos, prospectos y comisiones en un solo
   * endpoint. Los comprobantes de renta se piden en paralelo con su propio
   * servicio porque viven fuera del workflow.
   */
  getPendingSummary: async (): Promise<{
    total: number;
    maintAuth: number;
    maintQuote: number;
    maintPay: number;
    maintPayConfirm: number;
    prospects: number;
    commissions: number;
  }> => {
    const res = await api.get('/owner/workflow/pending-summary');
    return res.data;
  },

  /**
   * V63 — Preview de los datos bancarios del proveedor asignado al ticket,
   * para que el dueño vea a quién va a transferir ANTES de subir el SPEI.
   * Devuelve CLABE enmascarada + banco + titular.
   */
  getProviderBankPreview: async (ticketId: string): Promise<{
    providerUserId: string;
    providerName: string;
    accountActive: boolean;
    clabeMasked?: string;
    bankName?: string;
    accountHolder?: string;
    validationStatus?: string;
  }> => {
    const res = await api.get(`/owner/workflow/maintenance/tickets/${ticketId}/provider-bank-preview`);
    return res.data;
  },

  // Prospectos
  getPendingProspects: async (): Promise<ProspectSubmissionDTO[]> => {
    const res = await api.get('/owner/workflow/prospects/pending');
    return res.data;
  },
  acceptProspect: async (prospectId: string): Promise<ProspectSubmissionDTO> => {
    const res = await api.post(`/owner/workflow/prospects/${prospectId}/accept`);
    return res.data;
  },
  rejectProspect: async (prospectId: string, reason: string): Promise<ProspectSubmissionDTO> => {
    const res = await api.post(`/owner/workflow/prospects/${prospectId}/reject`, { reason });
    return res.data;
  },

  // Comisiones
  getCommissions: async (): Promise<AgentCommissionDTO[]> => {
    const res = await api.get('/owner/workflow/commissions');
    return res.data;
  },
  submitSpeiProof: async (invoiceId: string, payload: {
    proofFileId: string;
    declaredAmount: number;
    claveRastreo: string;
    bankEmitter: string;
  }): Promise<AgentCommissionDTO> => {
    const res = await api.post(`/owner/workflow/commissions/${invoiceId}/spei-proof`, payload);
    return res.data;
  },
  voidCommission: async (invoiceId: string, reason: string): Promise<AgentCommissionDTO> => {
    const res = await api.post(`/owner/workflow/commissions/${invoiceId}/void`, { reason });
    return res.data;
  },
};
