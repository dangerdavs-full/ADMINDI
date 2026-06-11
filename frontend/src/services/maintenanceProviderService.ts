import api from './api';
import type { AgentBankAccountDTO } from './realEstateAgentService';

/** Ticket visto desde el portal del proveedor de mantenimiento. */
export interface ProviderTicketDTO {
  id: string;
  title: string;
  description?: string | null;
  urgency: 'LOW' | 'NORMAL' | 'HIGH' | 'CRITICAL' | string;
  status: string;
  createdAt: string;
  providerAcceptedAt?: string | null;
  resolvedAt?: string | null;
  photoFileIdsJson?: string | null;
  propertyId: string;
  propertyName?: string | null;
  propertyAddress?: string | null;
  ownerId: string;
  ownerName?: string | null;
  ownerEmail?: string | null;
  ownerPhone?: string | null;
  invitation?: {
    linkId: string;
    notifiedAt: string;
    expiresAt: string;
    priorityOrder: number;
  };
  expiresInHours?: number | null;
}

export interface MaintenanceQuoteDTO {
  id: string;
  ticketId: string;
  providerId?: string | null;
  amount: number;
  description?: string | null;
  evidenceFileId?: string | null;
  status: 'SUBMITTED' | 'APPROVED' | 'REJECTED' | string;
  submittedAt: string;
  approvedAt?: string | null;
}

export const maintenanceProviderService = {
  // ── Upload evidencia ───────────────────────────────────────────────────
  uploadFile: async (file: File, category: string): Promise<string> => {
    const fd = new FormData();
    fd.append('file', file);
    fd.append('category', category);
    const res = await api.post('/maintenance-provider/files/upload', fd, {
      headers: { 'Content-Type': 'multipart/form-data' },
    });
    return res.data.fileId as string;
  },

  // ── Tickets ────────────────────────────────────────────────────────────
  getInvitations: async (): Promise<ProviderTicketDTO[]> => {
    const res = await api.get('/maintenance-provider/tickets/invitations');
    return res.data;
  },
  getMyTickets: async (): Promise<ProviderTicketDTO[]> => {
    const res = await api.get('/maintenance-provider/tickets');
    return res.data;
  },
  getTicketQuotes: async (ticketId: string): Promise<MaintenanceQuoteDTO[]> => {
    const res = await api.get(`/maintenance-provider/tickets/${ticketId}/quotes`);
    return res.data;
  },
  acceptTicket: async (ticketId: string): Promise<unknown> => {
    const res = await api.post(`/maintenance-provider/tickets/${ticketId}/accept`);
    return res.data;
  },
  rejectTicket: async (ticketId: string, reason?: string): Promise<unknown> => {
    const res = await api.post(`/maintenance-provider/tickets/${ticketId}/reject`, { reason });
    return res.data;
  },
  /**
   * V67 — Cancelar un ticket ya aceptado (duplicado, ya resuelto, fuera de
   * oficio, etc.). El backend rechaza si el ticket ya tiene compromiso de
   * pago (status APPROVED o posterior).
   */
  cancelTicket: async (ticketId: string, reason: string): Promise<unknown> => {
    const res = await api.post(`/maintenance-provider/tickets/${ticketId}/cancel`, { reason });
    return res.data;
  },
  submitQuote: async (
    ticketId: string,
    payload: { amount: number; description?: string; evidenceFileId?: string }
  ): Promise<MaintenanceQuoteDTO> => {
    const res = await api.post(`/maintenance-provider/tickets/${ticketId}/quote`, payload);
    return res.data;
  },

  // ── V63 — Confirmación bidireccional del pago ────────────────────────────

  /** Tickets con SPEI registrado por el dueño esperando que el proveedor confirme o dispute. */
  getAwaitingPaymentConfirmation: async (): Promise<ProviderTicketDTO[]> => {
    const res = await api.get('/maintenance-provider/tickets/awaiting-payment-confirmation');
    return res.data;
  },

  /**
   * El proveedor confirma haber recibido el SPEI. Cierra el ticket como
   * COMPLETED y marca el expense como PAID en contabilidad.
   */
  confirmPaymentReceived: async (ticketId: string): Promise<ProviderTicketDTO> => {
    const res = await api.post(`/maintenance-provider/tickets/${ticketId}/payment-confirm`);
    return res.data;
  },

  /**
   * El proveedor disputa el pago (no lo ve en su cuenta, monto incorrecto,
   * etc.). El ticket regresa a APPROVED para que el dueño vuelva a intentar.
   */
  disputePayment: async (ticketId: string, reason: string): Promise<ProviderTicketDTO> => {
    const res = await api.post(`/maintenance-provider/tickets/${ticketId}/payment-dispute`, { reason });
    return res.data;
  },

  /**
   * Devuelve el fileId del comprobante SPEI subido por el dueño para este
   * ticket. El provider lo usa junto con openFileAttachment para verlo antes
   * de confirmar / disputar. Puede retornar `fileId: null` si aún no hay.
   */
  getPaymentProofFileId: async (ticketId: string): Promise<string | null> => {
    const res = await api.get(`/maintenance-provider/tickets/${ticketId}/payment-proof-id`);
    return res.data?.fileId ?? null;
  },

  // ── CLABE ──────────────────────────────────────────────────────────────
  getBankAccount: async (): Promise<AgentBankAccountDTO | null> => {
    const res = await api.get('/maintenance-provider/bank-account', {
      validateStatus: (s) => s === 200 || s === 204,
    });
    if (res.status === 204) return null;
    return res.data;
  },
  upsertBankAccount: async (payload: {
    clabe: string;
    bankName?: string;
    accountHolder?: string;
  }): Promise<AgentBankAccountDTO> => {
    const res = await api.put('/maintenance-provider/bank-account', payload);
    return res.data;
  },
};
