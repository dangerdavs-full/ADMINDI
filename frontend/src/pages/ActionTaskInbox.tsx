import React, { useEffect, useMemo, useState } from 'react';
import { Inbox, CheckCircle2, XCircle, Clock, AlertTriangle, Archive, FileX, UserCircle, FileMinus } from 'lucide-react';
import {
  actionTaskService,
  ActionTaskDTO,
  EVENT_PROPERTY_DELETE_REQUESTED,
  EVENT_PROPERTY_FILE_DELETE_REQUESTED,
  EVENT_TENANT_ARCHIVE_REQUESTED,
  EVENT_LEASE_TERMINATE_REQUESTED,
  isApprovalEventType,
  parseActionTaskPayload,
  ApprovalTaskPayload,
} from '../services/actionTaskService';
import { ReauthConfirmModal } from '../components/modals/ReauthConfirmModal';
import { ApprovalRejectModal } from '../components/modals/ApprovalRejectModal';

/** UI metadata per approval eventType — icon, accent and copy. */
interface ApprovalUiMeta {
  label: string;
  approveLabel: string;
  icon: React.ComponentType<{ className?: string }>;
  accent: 'rose' | 'indigo' | 'emerald';
  approveDescription: (task: ActionTaskDTO, payload: ApprovalTaskPayload | null) => string;
  rejectDescription: (task: ActionTaskDTO, payload: ApprovalTaskPayload | null) => string;
}

const APPROVAL_UI: Record<string, ApprovalUiMeta> = {
  [EVENT_PROPERTY_DELETE_REQUESTED]: {
    label: 'Eliminación de inmueble',
    approveLabel: 'Aprobar eliminación',
    icon: AlertTriangle,
    accent: 'rose',
    approveDescription: (task) =>
      `Al aprobar, el inmueble "${task.title.replace(/^Solicitud de eliminación:\s*/i, '')}" ` +
      `pasará a baja lógica. Esta acción queda auditada con tu identidad.`,
    rejectDescription: (task) =>
      `Rechazarás la solicitud de eliminación de "${task.title.replace(/^Solicitud de eliminación:\s*/i, '')}". ` +
      `El inmueble permanece operativo.`,
  },
  [EVENT_TENANT_ARCHIVE_REQUESTED]: {
    label: 'Archivo de expediente',
    approveLabel: 'Aprobar archivo',
    icon: Archive,
    accent: 'indigo',
    approveDescription: () =>
      'Al aprobar se archivará el expediente operacional del inquilino (contratos ' +
      'finalizados, inmueble liberado, historial preservado). Esta acción queda auditada.',
    rejectDescription: () =>
      'Rechazarás la solicitud de archivo del expediente. El inquilino sigue activo.',
  },
  [EVENT_LEASE_TERMINATE_REQUESTED]: {
    label: 'Terminación de contrato',
    approveLabel: 'Aprobar terminación',
    icon: FileX,
    accent: 'rose',
    approveDescription: () =>
      'Al aprobar se dará por terminado el contrato. El inmueble quedará disponible ' +
      'para reasignación y el historial del contrato se conservará. Acción auditada.',
    rejectDescription: () =>
      'Rechazarás la solicitud de terminación. El contrato sigue vigente.',
  },
  [EVENT_PROPERTY_FILE_DELETE_REQUESTED]: {
    label: 'Eliminación de archivo',
    approveLabel: 'Aprobar eliminación',
    icon: FileMinus,
    accent: 'rose',
    // Pull the file + property names from the payload so the owner sees exactly
    // *which* file is about to be wiped (category + filename + property).
    approveDescription: (_task, payload) => {
      const fileName = (payload?.fileName as string) || 'archivo';
      const category = (payload?.category as string) || '';
      const propertyName = (payload?.propertyName as string) || 'inmueble';
      const cat = category ? ` (${category})` : '';
      return `Al aprobar se eliminará físicamente el archivo "${fileName}"${cat} del inmueble "${propertyName}". ` +
        `Esta acción borra el archivo del storage y no es reversible. Acción auditada.`;
    },
    rejectDescription: (_task, payload) => {
      const fileName = (payload?.fileName as string) || 'archivo';
      return `Rechazarás la solicitud de eliminación del archivo "${fileName}". El archivo permanece.`;
    },
  },
};

const getApprovalMeta = (eventType: string): ApprovalUiMeta | null =>
  APPROVAL_UI[eventType] ?? null;

/** Any informational task the owner can "mark as reviewed" from the inbox (with reauth). */
const isAcknowledgeable = (task: ActionTaskDTO) =>
  task.status === 'OPEN' && !isApprovalEventType(task.eventType);

export const ActionTaskInbox: React.FC = () => {
  const [tasks, setTasks] = useState<ActionTaskDTO[]>([]);
  const [filter, setFilter] = useState<'OPEN' | 'ALL'>('OPEN');
  const [loading, setLoading] = useState(true);
  const [approveTarget, setApproveTarget] = useState<ActionTaskDTO | null>(null);
  const [rejectTarget, setRejectTarget] = useState<ActionTaskDTO | null>(null);
  const [ackTarget, setAckTarget] = useState<ActionTaskDTO | null>(null);

  const fetchTasks = async () => {
    setLoading(true);
    try {
      const data = await actionTaskService.getMyTasks(filter);
      setTasks(data);
    } catch (err) {
      console.error('Error fetching tasks', err);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => { fetchTasks(); /* eslint-disable-next-line react-hooks/exhaustive-deps */ }, [filter]);

  const handleApprove = async (password: string, mfaCode: string) => {
    if (!approveTarget) return;
    await actionTaskService.approveTask(approveTarget.id, password, mfaCode);
    setApproveTarget(null);
    await fetchTasks();
  };

  const handleReject = async (reason: string | undefined) => {
    if (!rejectTarget) return;
    await actionTaskService.rejectTask(rejectTarget.id, reason);
    setRejectTarget(null);
    await fetchTasks();
  };

  const handleAcknowledge = async (password: string, mfaCode: string) => {
    if (!ackTarget) return;
    await actionTaskService.acknowledgeTask(ackTarget.id, password, mfaCode);
    setAckTarget(null);
    await fetchTasks();
  };

  const statusBadge = (status: string) => {
    switch (status) {
      case 'OPEN': return <span className="inline-flex items-center gap-1 px-2.5 py-1 rounded-full text-xs font-bold bg-amber-100 text-amber-700 border border-amber-200"><Clock className="w-3 h-3" /> Pendiente</span>;
      case 'RESOLVED': return <span className="inline-flex items-center gap-1 px-2.5 py-1 rounded-full text-xs font-bold bg-emerald-100 text-emerald-700 border border-emerald-200"><CheckCircle2 className="w-3 h-3" /> Aprobado</span>;
      case 'DISMISSED': return <span className="inline-flex items-center gap-1 px-2.5 py-1 rounded-full text-xs font-bold bg-slate-100 text-slate-500 border border-slate-200"><XCircle className="w-3 h-3" /> Rechazado</span>;
      default: return <span className="text-xs text-slate-400">{status}</span>;
    }
  };

  const approveMeta = useMemo(
    () => (approveTarget ? getApprovalMeta(approveTarget.eventType) : null),
    [approveTarget],
  );
  const rejectMeta = useMemo(
    () => (rejectTarget ? getApprovalMeta(rejectTarget.eventType) : null),
    [rejectTarget],
  );

  return (
    <div className="space-y-6 animate-in fade-in slide-in-from-bottom-4 duration-500">
      {/* Header */}
      <div className="flex items-center justify-between">
        <div>
          <h2 className="text-xl font-bold text-slate-800 flex items-center gap-2">
            <Inbox className="w-6 h-6 text-indigo-500" /> Tareas Pendientes
          </h2>
          <p className="text-sm text-slate-500 mt-1">Solicitudes que requieren tu aprobación o rechazo.</p>
        </div>
        <div className="flex gap-2">
          <button
            onClick={() => setFilter('OPEN')}
            className={`px-4 py-2 rounded-lg text-sm font-bold transition-colors ${filter === 'OPEN' ? 'bg-amber-100 text-amber-700' : 'text-slate-500 hover:bg-slate-100'}`}
          >Pendientes</button>
          <button
            onClick={() => setFilter('ALL')}
            className={`px-4 py-2 rounded-lg text-sm font-bold transition-colors ${filter === 'ALL' ? 'bg-slate-200 text-slate-700' : 'text-slate-500 hover:bg-slate-100'}`}
          >Todas</button>
        </div>
      </div>

      {/* Tasks */}
      {loading ? (
        <div className="flex items-center justify-center p-12">
          <div className="animate-spin w-8 h-8 border-4 border-indigo-500 border-t-transparent rounded-full" />
        </div>
      ) : tasks.length === 0 ? (
        <div className="p-16 text-center bg-white rounded-2xl border border-dashed border-slate-300">
          <div className="w-16 h-16 bg-emerald-50 rounded-full flex items-center justify-center mx-auto mb-4 border border-emerald-200">
            <CheckCircle2 className="w-8 h-8 text-emerald-400" />
          </div>
          <h4 className="font-semibold text-slate-700 mb-1">Sin pendientes</h4>
          <p className="text-slate-500 text-sm">No hay tareas que requieran tu atención.</p>
        </div>
      ) : (
        <div className="space-y-3">
          {tasks.map((task) => {
            const meta = getApprovalMeta(task.eventType);
            const payload = parseActionTaskPayload(task);
            const Icon = meta?.icon ?? AlertTriangle;
            const iconAccent = meta?.accent === 'indigo'
              ? 'bg-indigo-100 text-indigo-600'
              : meta?.accent === 'emerald'
                ? 'bg-emerald-100 text-emerald-600'
                : meta ? 'bg-rose-100 text-rose-600' : 'bg-slate-100 text-slate-500';

            return (
              <div key={task.id} className="bg-white rounded-xl border border-slate-200 p-5 shadow-sm hover:shadow-md transition-shadow">
                <div className="flex items-start justify-between gap-4">
                  <div className="flex items-start gap-4 flex-1 min-w-0">
                    <div className={`w-10 h-10 rounded-xl flex items-center justify-center shrink-0 ${iconAccent}`}>
                      <Icon className="w-5 h-5" />
                    </div>
                    <div className="min-w-0 flex-1">
                      <div className="flex items-center gap-2 flex-wrap">
                        <h4 className="font-bold text-slate-800 text-sm">{task.title}</h4>
                        {meta && (
                          <span className="text-[10px] font-bold uppercase tracking-wide px-2 py-0.5 rounded-full bg-slate-100 text-slate-600">
                            {meta.label}
                          </span>
                        )}
                      </div>
                      <p className="text-xs text-slate-500 mt-1 line-clamp-2">{task.description}</p>
                      {payload?.reason && (
                        <div className="mt-2 text-xs text-slate-600 bg-slate-50 border border-slate-200 rounded-lg px-3 py-2">
                          <span className="font-semibold text-slate-700">Motivo del solicitante:</span> {payload.reason}
                        </div>
                      )}
                      <div className="flex items-center gap-3 mt-2 flex-wrap">
                        {statusBadge(task.status)}
                        <span className="text-xs text-slate-400">
                          {new Date(task.createdAt).toLocaleDateString('es-MX', { day: 'numeric', month: 'short', year: 'numeric', hour: '2-digit', minute: '2-digit' })}
                        </span>
                        {payload?.initiatedByEmail && (
                          <span className="inline-flex items-center gap-1 text-xs text-slate-500">
                            <UserCircle className="w-3.5 h-3.5" />
                            <span className="font-medium">{payload.initiatedByEmail}</span>
                            {payload.initiatedByRole && (
                              <span className="text-slate-400">· {payload.initiatedByRole}</span>
                            )}
                          </span>
                        )}
                      </div>
                    </div>
                  </div>

                  {task.status === 'OPEN' && meta && (
                    <div className="flex flex-col items-end gap-2 shrink-0">
                      <div className="flex items-center gap-2">
                        <button
                          type="button"
                          onClick={() => setApproveTarget(task)}
                          className="flex items-center gap-1.5 px-4 py-2 text-sm font-bold text-white bg-emerald-600 hover:bg-emerald-700 rounded-lg transition-colors shadow-sm"
                        >
                          <CheckCircle2 className="w-4 h-4" /> {meta.approveLabel}
                        </button>
                        <button
                          type="button"
                          onClick={() => setRejectTarget(task)}
                          className="flex items-center gap-1.5 px-4 py-2 text-sm font-bold text-slate-600 bg-slate-100 hover:bg-slate-200 rounded-lg transition-colors"
                        >
                          <XCircle className="w-4 h-4" /> Rechazar
                        </button>
                      </div>
                      <span className="text-[10px] text-slate-400 max-w-[240px] text-right leading-tight">
                        Requiere contraseña y MFA del titular para aprobar.
                      </span>
                    </div>
                  )}
                  {task.status === 'OPEN' && !meta && isAcknowledgeable(task) && (
                    <div className="flex flex-col items-end gap-2 shrink-0">
                      <button
                        type="button"
                        onClick={() => setAckTarget(task)}
                        className="flex items-center gap-1.5 px-4 py-2 text-sm font-bold text-white bg-indigo-600 hover:bg-indigo-700 rounded-lg transition-colors shadow-sm"
                      >
                        <CheckCircle2 className="w-4 h-4" /> Marcar como revisado
                      </button>
                      <span className="text-[10px] text-slate-400 max-w-[220px] text-right leading-tight">
                        Requiere contraseña y MFA. Queda registrado en auditoría.
                      </span>
                    </div>
                  )}
                </div>
              </div>
            );
          })}
        </div>
      )}

      {/* Reauth modal — aprueba cualquier approval-tracked task */}
      <ReauthConfirmModal
        isOpen={approveTarget !== null}
        onClose={() => setApproveTarget(null)}
        onConfirm={handleApprove}
        title={approveMeta?.approveLabel ?? 'Aprobar solicitud'}
        description={approveTarget && approveMeta
          ? approveMeta.approveDescription(approveTarget, parseActionTaskPayload(approveTarget))
          : 'Esta acción requiere reautenticación del titular.'}
        confirmLabel={approveMeta?.approveLabel ?? 'Aprobar'}
        accent={approveMeta?.accent === 'indigo' ? 'indigo' : approveMeta?.accent === 'emerald' ? 'emerald' : 'rose'}
      />

      {/* Reject modal — razón opcional */}
      <ApprovalRejectModal
        isOpen={rejectTarget !== null}
        onClose={() => setRejectTarget(null)}
        onConfirm={handleReject}
        title={rejectMeta ? `Rechazar: ${rejectMeta.label}` : 'Rechazar solicitud'}
        description={rejectTarget && rejectMeta
          ? rejectMeta.rejectDescription(rejectTarget, parseActionTaskPayload(rejectTarget))
          : 'Rechazarás esta solicitud. Puedes incluir un motivo opcional.'}
      />

      {/* Reauth modal para tareas informativas (acknowledge) */}
      <ReauthConfirmModal
        isOpen={ackTarget !== null}
        onClose={() => setAckTarget(null)}
        onConfirm={handleAcknowledge}
        title="Marcar como revisado"
        description={ackTarget
          ? `Confirmarás que revisaste: "${ackTarget.title}". La tarea quedará resuelta y auditada con tu identidad.`
          : ''}
        confirmLabel="Confirmar revisión"
        accent="indigo"
      />
    </div>
  );
};
