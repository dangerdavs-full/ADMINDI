import api from './api';

/**
 * Motivos por los que el inquilino NO es elegible para recibir un recordatorio manual ahora.
 * Emitidos por el backend ({@link com.admindi.backend.service.ManualPaymentReminderService}).
 *   - NO_INVOICE_DUE     → inquilino al corriente o sin facturas activas.
 *   - RATE_LIMIT_REACHED → ya se enviaron 2 recordatorios manuales en las últimas 24 horas.
 *   - IDOR               → defensa: el inquilino no pertenece al dueño activo (no debería pasar
 *                           desde la UI porque la lista solo muestra tus inquilinos).
 */
export type ManualReminderIneligibleReason =
  | 'NO_INVOICE_DUE'
  | 'RATE_LIMIT_REACHED'
  | 'IDOR';

export interface ManualReminderEligibility {
  eligible: boolean;
  reason?: ManualReminderIneligibleReason;
  remainingToday: number;
  invoiceId?: string;
  amount?: number;
  dueDate?: string;
}

export interface ManualReminderResult {
  success: boolean;
  channels: string[];
  remainingToday: number;
  invoiceId: string;
  amount: number;
  dueDate: string;
}

/**
 * Recordatorio manual de pago — Fase B2 de notificaciones.
 *
 * Contrato con el backend:
 *   GET  /api/tenants/{profileId}/manual-reminder-eligibility
 *   POST /api/tenants/{profileId}/send-manual-reminder  body: { password, mfaCode }
 *
 * SUPER_ADMIN está bloqueado server-side; el frontend además oculta el botón por UX.
 *
 * Los errores del POST se propagan como AxiosError con {@code response.status} para que
 * el componente distinga 401 (reauth mala), 403 (IDOR), 409 (sin factura), 429 (rate limit)
 * y muestre el mensaje del servidor en {@code response.data.message}.
 */
export const manualReminderService = {
  checkEligibility: async (tenantProfileId: string): Promise<ManualReminderEligibility> => {
    const res = await api.get<ManualReminderEligibility>(
      `/tenants/${tenantProfileId}/manual-reminder-eligibility`
    );
    return res.data;
  },

  sendManualReminder: async (
    tenantProfileId: string,
    password: string,
    mfaCode: string
  ): Promise<ManualReminderResult> => {
    const res = await api.post<ManualReminderResult>(
      `/tenants/${tenantProfileId}/send-manual-reminder`,
      { password, mfaCode: mfaCode || null }
    );
    return res.data;
  },
};
