import api from './api';

export type TransferProofStatus =
  | 'RECEIVED'
  | 'INCOMPLETE_DATA'
  | 'VALIDATED'
  | 'REJECTED'
  | 'REJECTED_BY_CEP'
  // V57 — estados de pago en efectivo
  | 'PENDING_OWNER_VALIDATION'
  | 'VALIDATED_BY_OWNER'
  | 'REJECTED_BY_OWNER'
  | 'EXPIRED_AWAITING_OWNER';

export interface TransferProofDTO {
  id: string;
  invoiceId: string;
  tenantName?: string;
  tenantEmail?: string;
  monthYear?: string;
  fileUrl?: string;
  cepXmlAvailable?: boolean;
  cepPdfAvailable?: boolean;
  claveRastreo?: string;
  bankEmitter?: string;
  accountReceiver?: string;
  amount?: number;
  transferDate?: string;
  status: TransferProofStatus;
  rejectionReason?: string;
  missingFields?: string;
  submittedAt?: string;
  reviewedAt?: string;
  reviewedBy?: string;
  // V57 — sistema de tipos e intentos
  paymentType?: 'SPEI' | 'CASH';
  attemptNumber?: number;
  attemptsRemaining?: number;
  expiresAt?: string;
  hoursRemaining?: number;
  ownerValidationNotes?: string;
}

export interface PaymentDTO {
  id: string;
  invoiceId: string;
  tenantName?: string;
  tenantEmail?: string;
  monthYear?: string;
  amount: number;
  appliedAmount?: number;
  unappliedAmount?: number;
  paymentMethod: string;
  gatewayReference?: string;
  status: string;
  paidAt?: string;
  confirmedBy?: string;
  confirmedAt?: string;
  notes?: string;
  createdAt?: string;
  // V56 — clasificación IA (opcional, backend la genera async tras la confirmación)
  aiCategory?: string | null;
  aiCfdiUse?: string | null;
  aiTaxDeductible?: boolean | null;
  aiConfidence?: number | null;
  aiReviewedByUser?: boolean;
}

export interface MpPreference {
  preferenceId: string;
  checkoutUrl: string;
  sandboxUrl: string;
  invoiceId: string;
  amount: string;
}

/**
 * V56 — resultado del OCR de un comprobante SPEI con Claude Vision.
 * Los campos `null` significan "no legible": el inquilino debe capturarlos
 * manualmente antes de confirmar.
 */
export interface ReceiptOcrResult {
  ok: boolean;
  claveRastreo: string | null;
  amount: string | null;
  transferDate: string | null;
  bankEmitter: string | null;
  accountReceiver: string | null;
  beneficiaryName: string | null;
  rfcBeneficiary: string | null;
  confidence: number;
  errorMessage: string | null;
}

export const paymentService = {
  // --- Transfer Proofs (SPEI) ---

  /**
   * Submit a transfer proof with banking details.
   *
   * V58 — La cuenta receptora NO se manda: el backend la obtiene de
   * {@code owner.clabe}. El parámetro {@code captureMethod} indica si el
   * inquilino usó foto + IA (AI_OCR, 6/mes) o captura manual (ilimitado).
   */
  submitTransferProof: async (
    invoiceId: string,
    data: { claveRastreo?: string; bankEmitter?: string; amount?: number; transferDate?: string; captureMethod?: 'AI_OCR' | 'MANUAL' },
    file?: File
  ): Promise<TransferProofDTO> => {
    const formData = new FormData();
    formData.append('invoiceId', invoiceId);
    if (data.claveRastreo) formData.append('claveRastreo', data.claveRastreo);
    if (data.bankEmitter) formData.append('bankEmitter', data.bankEmitter);
    if (data.amount != null) formData.append('amount', String(data.amount));
    if (data.transferDate) formData.append('transferDate', data.transferDate);
    formData.append('captureMethod', data.captureMethod || 'MANUAL');
    if (file) formData.append('file', file);

    const res = await api.post('/payments/proofs', formData, {
      headers: { 'Content-Type': 'multipart/form-data' }
    });
    return res.data;
  },

  /** Complete missing data for a proof (CEP revalidation) */
  completeProofData: async (
    proofId: string,
    data: { claveRastreo?: string; bankEmitter?: string; accountReceiver?: string; amount?: number; transferDate?: string }
  ): Promise<TransferProofDTO> => {
    const res = await api.post(`/payments/proofs/${proofId}/complete-data`, data);
    return res.data;
  },

  /**
   * V56 — extrae datos de un comprobante SPEI con Claude Vision.
   * El usuario revisa los datos antes de enviar {@link submitTransferProof}.
   * Si `ok=false`, el inquilino debe capturar los datos manualmente.
   */
  extractReceiptWithAi: async (file: File): Promise<ReceiptOcrResult> => {
    const formData = new FormData();
    formData.append('file', file);
    const res = await api.post('/payments/proofs/ocr', formData, {
      headers: { 'Content-Type': 'multipart/form-data' }
    });
    return res.data;
  },

  /** Get all proofs (Owner/Admin/Accountant — read-only trazabilidad) */
  getAllProofs: async (): Promise<TransferProofDTO[]> => {
    const res = await api.get('/payments/proofs');
    return res.data;
  },

  /**
   * V65 — Histórico de comprobantes filtrado por propiedad o expediente.
   * Devuelve SOLO estados terminales (validados, rechazados, expirados) —
   * útil para el expediente del inmueble y del inquilino.
   */
  getProofsHistory: async (filters: {
    propertyId?: string;
    tenantProfileId?: string;
  } = {}): Promise<TransferProofDTO[]> => {
    const params: Record<string, string> = {};
    if (filters.propertyId) params.propertyId = filters.propertyId;
    if (filters.tenantProfileId) params.tenantProfileId = filters.tenantProfileId;
    const res = await api.get('/payments/proofs/history', { params });
    return res.data;
  },

  /** Owner override: approve/reject a proof (normal flow for CASH, exceptional for SPEI) */
  overrideProof: async (proofId: string, approve: boolean, rejectionReason?: string): Promise<void> => {
    await api.post(`/payments/proofs/${proofId}/override`, { approve, rejectionReason });
  },

  /**
   * V57 — Pago en efectivo: el inquilino sube comprobante, solo se valida
   * monto vía OCR, el dueño aprueba/rechaza en 120h.
   */
  submitCashProof: async (
    invoiceId: string,
    amount: number,
    file: File,
    tenantNote?: string
  ): Promise<TransferProofDTO> => {
    const formData = new FormData();
    formData.append('invoiceId', invoiceId);
    formData.append('amount', String(amount));
    formData.append('file', file);
    if (tenantNote) formData.append('tenantNote', tenantNote);

    const res = await api.post('/payments/proofs/cash', formData, {
      headers: { 'Content-Type': 'multipart/form-data' }
    });
    return res.data;
  },

  /**
   * V59 — Lista de comprobantes PENDING_OWNER_VALIDATION del dueño actual.
   *
   * Incluye dos tipos:
   *  - CASH (flujo normal de pago en efectivo).
   *  - SPEI que cayó a validación manual porque Banxico estaba caído al
   *    momento del submit, o porque el dueño no tiene CLABE configurada.
   *
   * El DTO lleva `paymentType` para que la UI distinga tag/copy y `hoursRemaining`
   * para el timer regresivo.
   */
  getPendingProofs: async (): Promise<TransferProofDTO[]> => {
    const res = await api.get('/payments/proofs/pending');
    return res.data;
  },

  /**
   * Alias back-compat. Antes solo devolvía CASH; desde V59 el backend mapea
   * este endpoint al generalizado para que código existente siga funcionando
   * sin tocar nada más. Prefiere {@link getPendingProofs} en código nuevo.
   */
  getPendingCashProofs: async (): Promise<TransferProofDTO[]> => {
    const res = await api.get('/payments/proofs/pending');
    return res.data;
  },

  // --- Payments ---

  /** Get payment history */
  getPayments: async (monthYear?: string): Promise<PaymentDTO[]> => {
    const params = monthYear ? `?monthYear=${monthYear}` : '';
    const res = await api.get(`/payments${params}`);
    return res.data;
  },

  // --- Mercado Pago ---

  /** Create a Mercado Pago checkout preference (amount omitted = full outstanding balance) */
  createMPPreference: async (
    invoiceId: string,
    tenantEmail?: string,
    tenantProfileId?: string,
    amount?: number,
  ): Promise<MpPreference> => {
    const body: Record<string, string> = { invoiceId };
    if (tenantEmail) body.tenantEmail = tenantEmail;
    if (tenantProfileId) body.tenantProfileId = tenantProfileId;
    if (amount != null) body.amount = String(amount);
    const res = await api.post('/integrations/mercadopago/create-preference', body);
    return res.data;
  },

  /** Whether Mercado Pago API credentials are configured on the server */
  getMPStatus: async (): Promise<{ integratorConfigured: boolean; mode: string }> => {
    const res = await api.get('/integrations/mercadopago/status');
    return res.data;
  },

  /** Inquilino: ¿el dueño vinculó MP para esta factura? */
  getMPCheckoutStatus: async (
    invoiceId: string,
  ): Promise<{ ownerMpConnected: boolean; canPayWithMp: boolean }> => {
    const res = await api.get(`/integrations/mercadopago/checkout-status/${invoiceId}`);
    return res.data;
  },

  /** Check MP payment status for an invoice */
  getMPPaymentStatus: async (invoiceId: string): Promise<{ invoiceId: string; invoiceStatus: string; paid: string }> => {
    const res = await api.get(`/integrations/mercadopago/payment-status/${invoiceId}`);
    return res.data;
  },
};
