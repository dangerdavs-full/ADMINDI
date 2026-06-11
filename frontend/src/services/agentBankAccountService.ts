import api from './api';

/**
 * V63 — Servicio compartido entre los dashboards de MAINTENANCE_PROVIDER y
 * REAL_ESTATE_AGENT para gestionar su cuenta bancaria. Ambos roles exponen la
 * misma semántica (obtener, actualizar, consultar estado) con rutas paralelas;
 * este servicio enruta a la correcta según el rol del usuario autenticado.
 *
 * Contrato de estado:
 *  - `complete = true`  → CLABE + banco + titular guardados; el dashboard se
 *    desbloquea.
 *  - `complete = false` → el frontend debe mostrar el wizard bloqueante.
 *
 * Contrato de errores 412:
 *  - Cualquier endpoint operativo (accept ticket, submit quote, accept
 *    vacancy, submit prospect, close contract) responde HTTP 412 con
 *    `reason="BANK_ACCOUNT_REQUIRED"` si el agente no cumple. El frontend
 *    intercepta esa señal y reabre el wizard.
 */

export type AgentRole = 'MAINTENANCE_PROVIDER' | 'REAL_ESTATE_AGENT';

export interface AgentBankAccount {
  id?: string;
  clabe?: string;
  bankName?: string;
  accountHolder?: string;
  validationStatus?: 'PENDING' | 'VALIDATED' | 'FAILED';
  validationAttempts?: number;
  lastValidationError?: string | null;
  validatedAt?: string | null;
}

export interface BankAccountStatus {
  complete: boolean;
  accountActive: boolean;
}

const BASE_BY_ROLE: Record<AgentRole, string> = {
  MAINTENANCE_PROVIDER: '/maintenance-provider',
  REAL_ESTATE_AGENT: '/real-estate-agent',
};

function base(role: AgentRole) {
  return BASE_BY_ROLE[role];
}

export const agentBankAccountService = {
  /** Devuelve `{complete, accountActive}`. Si falla, asumimos `complete=false` para ser conservadores. */
  getStatus: async (role: AgentRole): Promise<BankAccountStatus> => {
    const res = await api.get(`${base(role)}/bank-account/status`);
    return res.data;
  },

  /** GET: cuenta bancaria existente, o `null` si no hay registro. */
  get: async (role: AgentRole): Promise<AgentBankAccount | null> => {
    try {
      const res = await api.get(`${base(role)}/bank-account`);
      if (res.status === 204) return null;
      return res.data;
    } catch (e: any) {
      // El backend devuelve 204 No Content cuando no hay cuenta. Algunos clientes
      // lo tratan como excepción; aquí lo normalizamos a null.
      if (e?.response?.status === 204) return null;
      throw e;
    }
  },

  /** Crea o actualiza la cuenta bancaria. Valida CLABE sintácticamente en backend. */
  upsert: async (role: AgentRole,
                 payload: { clabe: string; bankName?: string; accountHolder: string }
  ): Promise<AgentBankAccount> => {
    const res = await api.put(`${base(role)}/bank-account`, payload);
    return res.data;
  },
};

/** V63 — helper que el frontend usa para detectar respuestas 412 BANK_ACCOUNT_REQUIRED. */
export function isBankAccountRequiredError(err: any): boolean {
  if (!err?.response) return false;
  if (err.response.status !== 412) return false;
  const payload = err.response.data;
  if (!payload) return false;
  const msg = typeof payload === 'string' ? payload : (payload.message || payload.error || '');
  return typeof msg === 'string' && msg.includes('BANK_ACCOUNT_REQUIRED');
}
