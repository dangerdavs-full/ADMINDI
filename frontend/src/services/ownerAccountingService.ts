import api from './api';

export interface ReceivableLineDTO {
  invoiceId: string;
  propertyId?: string;
  propertyName?: string;
  tenantName: string;
  monthYear: string;
  expectedRent: number;
  paidAmount: number;
  outstanding: number;
  status: string;
  settlementStatus: string;
  shortfallReason?: string;
  promisedCompletionDate?: string;
  agreementSummaryStatus?: string;
  balanceDriver?: string;
}

export interface ExpenseLineDTO {
  id: string;
  propertyId: string;
  propertyName?: string;
  amount: number;
  status: string;
  type: string;
  description?: string;
  linkedResourceType?: string;
  linkedResourceId?: string;
}

export interface ReportingPeriodBoundsDTO {
  minMonthYear: string;
  maxMonthYear: string;
  minYear: number;
  maxYear: number;
}

export interface OwnerAccountingSummaryDTO {
  expectedIncome: number;
  collectedIncome: number;
  outstandingIncome: number;
  overpaidCredits: number;
  approvedExpenses: number;
  paidExpenses: number;
  pendingExpenses: number;
  /**
   * V64 — Crédito 15% que la plataforma absorbió este mes (ahorro al dueño).
   * Los egresos ya vienen netos; este campo permite comunicar el beneficio
   * como línea independiente en la UI.
   */
  platformSavings?: number;
  lateFeeAccrued: number;
  activeAgreementsCount: number;
  breachedAgreementsCount: number;
  delinquentTenantsCount: number;
  propertiesWithIssuesCount: number;
  receivables: ReceivableLineDTO[];
  expenses: ExpenseLineDTO[];
  alerts: string[];
}

export interface ReconciliationReport {
  propertiesScanned: number;
  propertiesUpdated: number;
  ghostInvoicesVoided: number;
  orphanLeasesTerminated: number;
}

export const ownerAccountingService = {
  getReportingPeriodBounds: async (): Promise<ReportingPeriodBoundsDTO> => {
    const res = await api.get('/owner/reporting-period-bounds');
    return res.data;
  },

  getSummary: async (monthYear: string): Promise<OwnerAccountingSummaryDTO> => {
    const res = await api.get('/owner/accounting-summary', { params: { monthYear } });
    return res.data;
  },

  /**
   * Reconciliación idempotente: recalcula ocupación real de cada inmueble y anula facturas
   * fantasma (abiertas contra expedientes archivados o huérfanos). Soluciona estados heredados
   * de versiones previas del cron o de bajas parciales. No elimina historial PAID.
   */
  reconcile: async (): Promise<ReconciliationReport> => {
    const res = await api.post('/accounting/reconcile');
    return res.data;
  },
};
