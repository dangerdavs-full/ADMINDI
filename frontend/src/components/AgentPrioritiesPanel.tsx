import React, { useEffect, useMemo, useState } from 'react';
import { ArrowUp, ArrowDown, Trash2, Plus, HomeIcon, Wrench, AlertCircle, Loader2 } from 'lucide-react';
import {
  agentWorkflowService,
  AgentFlowType,
  OwnerAgentPriorityDTO,
} from '../services/agentWorkflowService';
import { ownerTeamService, OwnerProviderLink } from '../services/ownerTeamService';

interface Props {
  /**
   * Flujo a priorizar. Un panel = un flujo. Si quieres ambos, renderiza dos
   * instancias (ver OwnerTeamHub).
   */
  flowType: AgentFlowType;
}

const FLOW_META: Record<AgentFlowType, { title: string; subtitle: string; providerType: string; icon: React.ReactNode }> = {
  VACANCY: {
    title: 'Agentes inmobiliarios',
    subtitle:
      'Orden en que se notificarán cuando actives "Buscar inquilino" en un inmueble disponible. El primero tiene 72h para aceptar antes de pasar al siguiente.',
    providerType: 'REAL_ESTATE_AGENT',
    icon: <HomeIcon className="w-4 h-4" />,
  },
  MAINTENANCE: {
    title: 'Proveedores de mantenimiento',
    subtitle:
      'Orden para tickets de mantenimiento cuando autorizas el flujo por cadena (vs. elegir a uno específico). 72h por eslabón.',
    providerType: 'MAINTENANCE_PROVIDER',
    icon: <Wrench className="w-4 h-4" />,
  },
};

export const AgentPrioritiesPanel: React.FC<Props> = ({ flowType }) => {
  const meta = FLOW_META[flowType];
  const [priorities, setPriorities] = useState<OwnerAgentPriorityDTO[]>([]);
  const [links, setLinks] = useState<OwnerProviderLink[]>([]);
  const [loading, setLoading] = useState(true);
  const [busyId, setBusyId] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [addingOpen, setAddingOpen] = useState(false);

  const fetch = async () => {
    setLoading(true);
    setError(null);
    try {
      const [prios, allLinks] = await Promise.all([
        agentWorkflowService.listPriorities(flowType),
        ownerTeamService.getProviderLinks(),
      ]);
      setPriorities(prios.sort((a, b) => a.priorityOrder - b.priorityOrder));
      setLinks(allLinks.filter(l => l.providerType === meta.providerType && l.assignmentActive));
    } catch (e) {
      setError('No pude cargar las prioridades.');
      console.error(e);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => { fetch(); /* eslint-disable-next-line react-hooks/exhaustive-deps */ }, [flowType]);

  // Mapa rápido userId → link (para pintar nombre/email en la lista priorizada).
  const linkById = useMemo(() => {
    const m = new Map<string, OwnerProviderLink>();
    links.forEach(l => m.set(l.providerUserId, l));
    return m;
  }, [links]);

  // Agentes vinculados que aún NO están priorizados (candidatos a "Agregar").
  const availableToAdd = useMemo(() => {
    const priorityIds = new Set(priorities.map(p => p.agentUserId));
    return links.filter(l => !priorityIds.has(l.providerUserId));
  }, [links, priorities]);

  const move = async (agentUserId: string, delta: -1 | 1) => {
    setBusyId(agentUserId);
    setError(null);
    try {
      const updated = await agentWorkflowService.movePriority(flowType, agentUserId, delta);
      setPriorities(updated.sort((a, b) => a.priorityOrder - b.priorityOrder));
    } catch (e: any) {
      setError(e?.response?.data?.error ?? 'No se pudo mover.');
    } finally {
      setBusyId(null);
    }
  };

  const remove = async (agentUserId: string) => {
    const remaining = priorities.filter(p => p.agentUserId !== agentUserId).map(p => p.agentUserId);
    setBusyId(agentUserId);
    setError(null);
    try {
      const updated = await agentWorkflowService.replacePriorities(flowType, remaining);
      setPriorities(updated.sort((a, b) => a.priorityOrder - b.priorityOrder));
    } catch (e: any) {
      setError(e?.response?.data?.error ?? 'No se pudo quitar.');
    } finally {
      setBusyId(null);
    }
  };

  const add = async (agentUserId: string) => {
    const next = [...priorities.map(p => p.agentUserId), agentUserId];
    setBusyId(agentUserId);
    setError(null);
    try {
      const updated = await agentWorkflowService.replacePriorities(flowType, next);
      setPriorities(updated.sort((a, b) => a.priorityOrder - b.priorityOrder));
      setAddingOpen(false);
    } catch (e: any) {
      setError(e?.response?.data?.error ?? 'No se pudo agregar.');
    } finally {
      setBusyId(null);
    }
  };

  return (
    <div className="bg-white border border-slate-200 rounded-xl p-5">
      <div className="flex items-start justify-between gap-4 mb-4">
        <div className="flex gap-2">
          <div className="w-8 h-8 rounded-lg bg-indigo-50 text-indigo-600 flex items-center justify-center">
            {meta.icon}
          </div>
          <div>
            <h3 className="font-semibold text-slate-900">{meta.title}</h3>
            <p className="text-xs text-slate-500 max-w-xl mt-0.5">{meta.subtitle}</p>
          </div>
        </div>
      </div>

      {error && (
        <div className="mb-3 flex gap-2 p-2 rounded-lg bg-red-50 border border-red-200 text-sm text-red-700">
          <AlertCircle className="w-4 h-4 flex-shrink-0 mt-0.5" />
          <span>{error}</span>
        </div>
      )}

      {loading ? (
        <div className="flex items-center gap-2 text-sm text-slate-500 py-6 justify-center">
          <Loader2 className="w-4 h-4 animate-spin" /> Cargando…
        </div>
      ) : (
        <>
          {priorities.length === 0 ? (
            <div className="border-2 border-dashed border-slate-200 rounded-lg py-8 text-center">
              <p className="text-sm text-slate-500">
                Aún no tienes prioridades configuradas.
              </p>
              <p className="text-xs text-slate-400 mt-1">
                {availableToAdd.length > 0
                  ? 'Agrega al menos uno para poder notificarlos desde tus inmuebles.'
                  : 'Primero vincula un agente en "Equipo y proveedores".'}
              </p>
            </div>
          ) : (
            <ul className="space-y-2">
              {priorities.map((p, idx) => {
                const link = linkById.get(p.agentUserId);
                const isFirst = idx === 0;
                const isLast = idx === priorities.length - 1;
                const busy = busyId === p.agentUserId;
                return (
                  <li
                    key={p.id}
                    className="flex items-center gap-3 p-3 rounded-lg bg-slate-50 border border-slate-200"
                  >
                    <span className="flex-shrink-0 w-7 h-7 rounded-full bg-indigo-600 text-white text-xs font-bold flex items-center justify-center">
                      {idx + 1}
                    </span>
                    <div className="flex-1 min-w-0">
                      <p className="text-sm font-medium text-slate-900 truncate">
                        {link?.name ?? 'Agente desvinculado'}
                      </p>
                      <p className="text-xs text-slate-500 truncate">
                        {link?.email ?? p.agentUserId}
                      </p>
                    </div>
                    <div className="flex items-center gap-1">
                      <button
                        onClick={() => move(p.agentUserId, -1)}
                        disabled={isFirst || busy}
                        title="Subir"
                        className="p-1.5 rounded-md hover:bg-white disabled:opacity-30 disabled:cursor-not-allowed text-slate-600 border border-slate-200"
                      >
                        <ArrowUp className="w-3.5 h-3.5" />
                      </button>
                      <button
                        onClick={() => move(p.agentUserId, 1)}
                        disabled={isLast || busy}
                        title="Bajar"
                        className="p-1.5 rounded-md hover:bg-white disabled:opacity-30 disabled:cursor-not-allowed text-slate-600 border border-slate-200"
                      >
                        <ArrowDown className="w-3.5 h-3.5" />
                      </button>
                      <button
                        onClick={() => remove(p.agentUserId)}
                        disabled={busy}
                        title="Quitar de la cadena"
                        className="p-1.5 rounded-md hover:bg-red-50 disabled:opacity-30 text-red-600 border border-red-100"
                      >
                        <Trash2 className="w-3.5 h-3.5" />
                      </button>
                    </div>
                  </li>
                );
              })}
            </ul>
          )}

          <div className="mt-3">
            {addingOpen ? (
              <div className="border border-slate-200 rounded-lg p-3">
                <p className="text-xs font-semibold text-slate-600 uppercase tracking-wide mb-2">
                  Agregar a la cadena
                </p>
                {availableToAdd.length === 0 ? (
                  <p className="text-sm text-slate-500">
                    No hay más {flowType === 'VACANCY' ? 'agentes inmobiliarios' : 'proveedores de mantenimiento'} vinculados.
                    Vincula más desde "Equipo y proveedores".
                  </p>
                ) : (
                  <ul className="space-y-1">
                    {availableToAdd.map(l => (
                      <li key={l.providerUserId}>
                        <button
                          onClick={() => add(l.providerUserId)}
                          disabled={busyId === l.providerUserId}
                          className="w-full flex items-center justify-between p-2 rounded-md hover:bg-indigo-50 text-left disabled:opacity-50"
                        >
                          <span>
                            <span className="text-sm font-medium text-slate-900">{l.name}</span>
                            <span className="text-xs text-slate-500 ml-2">{l.email}</span>
                          </span>
                          <Plus className="w-4 h-4 text-indigo-600" />
                        </button>
                      </li>
                    ))}
                  </ul>
                )}
                <button
                  onClick={() => setAddingOpen(false)}
                  className="mt-2 text-xs text-slate-500 hover:text-slate-700"
                >
                  Cerrar
                </button>
              </div>
            ) : (
              <button
                onClick={() => setAddingOpen(true)}
                disabled={availableToAdd.length === 0}
                className="inline-flex items-center gap-1.5 px-3 py-1.5 text-sm font-medium rounded-lg border border-indigo-200 text-indigo-700 hover:bg-indigo-50 disabled:opacity-40 disabled:cursor-not-allowed"
              >
                <Plus className="w-4 h-4" />
                Agregar {flowType === 'VACANCY' ? 'agente' : 'proveedor'}
              </button>
            )}
          </div>
        </>
      )}
    </div>
  );
};
