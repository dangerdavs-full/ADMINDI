import api from './api';

/** Vacancia vista desde el portal del agente inmobiliario. */
export interface AgentVacancyDTO {
  id: string;
  status: string;
  chainState?: string | null;
  openedAt?: string;
  notes?: string | null;
  photosUploadedAt?: string | null;
  contractSignedAt?: string | null;
  contractMonths?: number | null;
  contractMonthlyRent?: number | null;
  contractDeposit?: number | null;
  propertyId: string;
  propertyName?: string | null;
  propertyAddress?: string | null;
  ownerId: string;
  ownerName?: string | null;
  ownerEmail?: string | null;
  ownerPhone?: string | null;
  assignedAgentId?: string | null;
  invitation?: {
    linkId: string;
    notifiedAt: string;
    expiresAt: string;
    priorityOrder: number;
  };
  expiresInHours?: number | null;
}

export interface ProspectSubmissionDTO {
  id: string;
  vacancyId: string;
  propertyId: string;
  ownerId: string;
  agentUserId: string;
  prospectName: string;
  prospectPhone?: string | null;
  prospectEmail?: string | null;
  notes?: string | null;
  submittedAt: string;
  ownerDecision: 'PENDING' | 'ACCEPTED' | 'REJECTED';
  decidedAt?: string | null;
  rejectionReason?: string | null;
  lastReminderAt?: string | null;
}

export interface AgentCommissionDTO {
  id: string;
  ownerId: string;
  agentUserId: string;
  agentSource: 'PLATFORM' | 'PRIVATE';
  leaseId?: string | null;
  propertyId: string;
  vacancyId?: string | null;
  monthlyRent: number;
  contractMonths: number;
  commissionPct: number;
  commissionAmount: number;
  status: 'PENDING' | 'AWAITING_SPEI' | 'PENDING_MANUAL_CONFIRM' | 'PAID' | 'VOIDED';
  speiProofFileId?: string | null;
  speiValidationAttempts: number;
  speiLastError?: string | null;
  paidAt?: string | null;
  createdAt: string;
}

export interface AgentBankAccountDTO {
  id: string;
  agentUserId: string;
  clabe: string;
  bankName?: string | null;
  accountHolder?: string | null;
  validationStatus: 'PENDING' | 'VALIDATED' | 'FAILED';
  validationAttempts: number;
  lastValidationError?: string | null;
  validatedAt?: string | null;
  updatedAt?: string;
}

export interface ContractClosureResultDTO {
  commissionInvoiceId: string;
  ownerNotified: boolean;
  vacancyId: string;
}

export const realEstateAgentService = {
  // ── Upload evidencias ───────────────────────────────────────────────────
  uploadFile: async (file: File, category: string): Promise<string> => {
    const fd = new FormData();
    fd.append('file', file);
    fd.append('category', category);
    const res = await api.post('/real-estate-agent/files/upload', fd, {
      headers: { 'Content-Type': 'multipart/form-data' },
    });
    return res.data.fileId as string;
  },

  // ── Vacancias ───────────────────────────────────────────────────────────
  getInvitations: async (): Promise<AgentVacancyDTO[]> => {
    const res = await api.get('/real-estate-agent/vacancies/invitations');
    return res.data;
  },
  getMyVacancies: async (): Promise<AgentVacancyDTO[]> => {
    const res = await api.get('/real-estate-agent/vacancies/mine');
    return res.data;
  },
  acceptVacancy: async (vacancyId: string, reason?: string): Promise<AgentVacancyDTO> => {
    const res = await api.post(`/real-estate-agent/vacancies/${vacancyId}/accept`, { reason });
    return res.data;
  },
  rejectVacancy: async (vacancyId: string, reason?: string): Promise<AgentVacancyDTO> => {
    const res = await api.post(`/real-estate-agent/vacancies/${vacancyId}/reject`, { reason });
    return res.data;
  },
  recordPhotos: async (vacancyId: string, propertyFileIds: string[]): Promise<AgentVacancyDTO> => {
    const res = await api.post(`/real-estate-agent/vacancies/${vacancyId}/photos`, { propertyFileIds });
    return res.data;
  },
  closeContract: async (
    vacancyId: string,
    payload: {
      evidenceFileId: string;
      months: number;
      monthlyRent: number;
      deposit?: number;
      agentSource?: 'PLATFORM' | 'PRIVATE';
      commissionPct?: number;
    }
  ): Promise<ContractClosureResultDTO> => {
    const res = await api.post(`/real-estate-agent/vacancies/${vacancyId}/close`, payload);
    return res.data;
  },

  // ── Prospectos ──────────────────────────────────────────────────────────
  getProspects: async (): Promise<ProspectSubmissionDTO[]> => {
    const res = await api.get('/real-estate-agent/prospects');
    return res.data;
  },
  submitProspect: async (payload: {
    vacancyId: string;
    name: string;
    phone?: string;
    email?: string;
    notes?: string;
  }): Promise<ProspectSubmissionDTO> => {
    const res = await api.post('/real-estate-agent/prospects', payload);
    return res.data;
  },

  // ── Comisiones ──────────────────────────────────────────────────────────
  getCommissions: async (): Promise<AgentCommissionDTO[]> => {
    const res = await api.get('/real-estate-agent/commissions');
    return res.data;
  },
  confirmCommissionReceived: async (invoiceId: string): Promise<AgentCommissionDTO> => {
    const res = await api.post(`/real-estate-agent/commissions/${invoiceId}/confirm-received`);
    return res.data;
  },

  // ── CLABE ───────────────────────────────────────────────────────────────
  getBankAccount: async (): Promise<AgentBankAccountDTO | null> => {
    const res = await api.get('/real-estate-agent/bank-account', { validateStatus: (s) => s === 200 || s === 204 });
    if (res.status === 204) return null;
    return res.data;
  },
  upsertBankAccount: async (payload: {
    clabe: string;
    bankName?: string;
    accountHolder?: string;
  }): Promise<AgentBankAccountDTO> => {
    const res = await api.put('/real-estate-agent/bank-account', payload);
    return res.data;
  },
};
