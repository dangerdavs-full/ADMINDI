import api from './api';

export interface GlobalMetricsDTO {
  totalProperties: number;
  occupiedProperties: number;
  expectedMonthlyIncome: number;
  collectedMonthlyIncome: number;
  delinquentTenants: number;
}

export interface PropertyMetricsDTO {
  propertyId: string;
  status: string;
  currentTenantName: string;
  monthlyRent: number;
  lifetimeCollected: number;
  totalInvoices: number;
  paidInvoices: number;
  lateInvoices: number;
}

export interface InvoiceHistoryDTO {
  invoiceId: string;
  monthYear: string;
  paidDate: string | null;
  amountCollected: number;
  tenantName: string;
  status: string;
  paymentReference: string | null;
  paymentNotes: string | null;
}

export const metricsService = {
  getGlobalMetrics: async (): Promise<GlobalMetricsDTO> => {
    const res = await api.get('/metrics/global');
    return res.data;
  },

  getPropertyMetrics: async (propertyId: string): Promise<PropertyMetricsDTO> => {
    const res = await api.get(`/metrics/property/${propertyId}`);
    return res.data;
  },

  /** Recibos / cobranza por inmueble (solo financiero). Historial operativo: timeline en la ficha del inmueble. */
  listPropertyInvoiceFinancialHistory: async (propertyId: string): Promise<InvoiceHistoryDTO[]> => {
    const res = await api.get(`/metrics/property/${propertyId}/invoice-financial-history`);
    return res.data;
  },

  /** @deprecated Prefer listPropertyInvoiceFinancialHistory — nombre alineado al dominio (solo cobranza). */
  getPropertyHistory: async (propertyId: string): Promise<InvoiceHistoryDTO[]> => {
    const res = await api.get(`/metrics/property/${propertyId}/invoice-financial-history`);
    return res.data;
  },
};
