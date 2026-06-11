import api from './api';

/**
 * Tipo de flujo para prioridades de agentes.
 * - VACANCY: agentes inmobiliarios (REAL_ESTATE_AGENT) para comercializar vacancias.
 * - MAINTENANCE: proveedores de mantenimiento (MAINTENANCE_PROVIDER) para tickets.
 */
export type AgentFlowType = 'VACANCY' | 'MAINTENANCE';

export interface OwnerAgentPriorityDTO {
  id: string;
  ownerId: string;
  agentUserId: string;
  flowType: AgentFlowType;
  priorityOrder: number;
  active: boolean;
  createdAt?: string;
  updatedAt?: string;
}

export interface VacancyChainStateDTO {
  id: string;
  propertyId: string;
  ownerId: string;
  assignedAgentId?: string | null;
  chainState?: string | null;
  currentPriorityOrder?: number | null;
  openedAt?: string;
  closedAt?: string | null;
  status?: string;
}

export interface StartChainErrorDTO {
  error: string;
  hint?: string;
}

export const agentWorkflowService = {
  listPriorities: async (flowType: AgentFlowType): Promise<OwnerAgentPriorityDTO[]> => {
    const res = await api.get(`/owner/agent-priorities/${flowType}`);
    return res.data;
  },

  replacePriorities: async (flowType: AgentFlowType, agentUserIds: string[]): Promise<OwnerAgentPriorityDTO[]> => {
    const res = await api.put(`/owner/agent-priorities/${flowType}`, { agentUserIds });
    return res.data;
  },

  movePriority: async (flowType: AgentFlowType, agentUserId: string, delta: -1 | 1): Promise<OwnerAgentPriorityDTO[]> => {
    const res = await api.post(`/owner/agent-priorities/${flowType}/move`, { agentUserId, delta });
    return res.data;
  },

  /**
   * Arranca manualmente la cadena de notificaciones a agentes inmobiliarios
   * para un inmueble que está AVAILABLE. El backend abre vacancia si no existe
   * y notifica al primer agente de la cadena.
   *
   * Si no hay prioridades configuradas devuelve 409 con {error, hint}; el caller
   * debe mostrar un modal invitando al owner a configurar al menos un agente.
   */
  startAgentChainForProperty: async (propertyId: string): Promise<VacancyChainStateDTO> => {
    const res = await api.post('/owner/workflow/vacancies/start-agent-chain', { propertyId });
    return res.data;
  },
};
