import api from './api';

/**
 * Historial de notificaciones (Bloque C de notificaciones).
 *
 * Contrato con el backend ({@code NotificationHistoryController}):
 *   GET  /api/notifications/history/tenant/{tenantProfileId}?month=YYYY-MM
 *   GET  /api/notifications/history/owner?month=YYYY-MM&channel=&outcome=&tenantUserId=
 *   GET  /api/notifications/history/me?month=YYYY-MM
 *   POST /api/notifications/retry/{auditEventId}  body: { password, mfaCode }
 *
 * El backend aplica hard-limit de 3 meses: si se consulta un mes fuera del rango
 * [maxOf(ownerMin, hoy-2) .. hoy] devuelve 400. El frontend debe respetar los
 * {@code minMonth} y {@code maxMonth} que vienen en la respuesta para bloquear el
 * selector de mes.
 *
 * SUPER_ADMIN NO accede a estos endpoints (@PreAuthorize server-side los excluye).
 * El frontend también oculta los botones para ese rol.
 */

export type NotificationChannel = 'EMAIL' | 'WHATSAPP';
export type NotificationOutcome = 'SENT' | 'FAILED' | 'SKIPPED';

export interface NotificationHistoryEntry {
  id: string;
  timestamp: string;            // ISO-8601 del backend (LocalDateTime)
  channel: NotificationChannel;
  outcome: NotificationOutcome;
  eventType: string;            // p.ej. "MANUAL_PAYMENT_REMINDER"
  recipientUserId: string;
  recipientName: string | null;
  recipientEmail: string | null; // ya enmascarado por el backend
  recipientPhone: string | null; // ya enmascarado por el backend
  actorEmail: string;            // "SYSTEM" si fue cron
  detail: string | null;
}

export interface NotificationHistoryPage {
  entries: NotificationHistoryEntry[];
  totalCount: number;
  sentCount: number;
  failedCount: number;
  skippedCount: number;
  monthYear: string;            // "YYYY-MM" efectivamente consultado
  minMonth: string;             // límite inferior del selector de mes
  maxMonth: string;             // límite superior del selector de mes (normalmente "hoy")
}

export interface RetryRequest {
  password: string;
  mfaCode: string | null;
}

export interface RetryResult {
  success: boolean;
  channels: string[];
  remainingToday: number;
  invoiceId: string;
  amount: number;
  dueDate: string;
}

export interface OwnerHistoryFilters {
  month: string;
  channel?: NotificationChannel;
  outcome?: NotificationOutcome;
  tenantUserId?: string;
}

export const notificationHistoryService = {
  /**
   * C3 — historial de notificaciones enviadas a un inquilino específico.
   */
  listForTenant: async (
    tenantProfileId: string,
    month: string
  ): Promise<NotificationHistoryPage> => {
    const res = await api.get<NotificationHistoryPage>(
      `/notifications/history/tenant/${tenantProfileId}`,
      { params: { month } }
    );
    return res.data;
  },

  /**
   * C4 — panel global del dueño/admin, con filtros combinables.
   */
  listForOwner: async (
    filters: OwnerHistoryFilters
  ): Promise<NotificationHistoryPage> => {
    const params: Record<string, string> = { month: filters.month };
    if (filters.channel) params.channel = filters.channel;
    if (filters.outcome) params.outcome = filters.outcome;
    if (filters.tenantUserId) params.tenantUserId = filters.tenantUserId;
    const res = await api.get<NotificationHistoryPage>(
      `/notifications/history/owner`,
      { params }
    );
    return res.data;
  },

  /**
   * C6 — inquilino viendo sus propios eventos (solo exitosos).
   */
  listForMe: async (month: string): Promise<NotificationHistoryPage> => {
    const res = await api.get<NotificationHistoryPage>(
      `/notifications/history/me`,
      { params: { month } }
    );
    return res.data;
  },

  /**
   * C5 — reintento de un evento fallido. Solo disponible para MANUAL_PAYMENT_REMINDER.
   */
  retry: async (
    auditEventId: string,
    password: string,
    mfaCode: string
  ): Promise<RetryResult> => {
    const res = await api.post<RetryResult>(
      `/notifications/retry/${auditEventId}`,
      { password, mfaCode: mfaCode || null }
    );
    return res.data;
  },
};
