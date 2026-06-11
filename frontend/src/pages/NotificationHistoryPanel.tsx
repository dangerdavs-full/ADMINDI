import React, { useEffect, useMemo, useState, useCallback } from 'react';
import {
  Bell, Mail, MessageSquare, AlertTriangle, CheckCircle2, AlertCircle,
  RefreshCw, Download, ChevronLeft, ChevronRight, Filter as FilterIcon,
  X as XIcon,
} from 'lucide-react';
import {
  notificationHistoryService,
  NotificationHistoryEntry,
  NotificationHistoryPage,
  NotificationChannel,
  NotificationOutcome,
} from '../services/notificationHistoryService';
import { ReauthConfirmModal } from '../components/modals/ReauthConfirmModal';
import { useAuth } from '../context/AuthContext';

/**
 * Modo de operación del panel de historial.
 *  - 'tenant' → dueño/admin viendo el historial de UN inquilino (tenantProfileId requerido).
 *  - 'owner'  → dueño/admin viendo historial global con filtros.
 *  - 'me'     → inquilino viendo su propio historial (solo exitosos, sin detalle técnico,
 *               sin botón reintentar).
 *
 * El backend ya aplica hard-limit de 3 meses; el selector respeta minMonth/maxMonth que
 * devuelve la API en {@link NotificationHistoryPage}.
 */
export type NotificationHistoryMode = 'tenant' | 'owner' | 'me';

interface Props {
  mode: NotificationHistoryMode;
  tenantProfileId?: string;   // requerido si mode='tenant'
  embedded?: boolean;         // si va dentro de modal o contenedor ajustado
}

function formatMonthLabel(ym: string): string {
  const [y, m] = ym.split('-').map(Number);
  const d = new Date(y, (m || 1) - 1, 1);
  return d.toLocaleDateString('es-MX', { month: 'long', year: 'numeric' });
}

function currentMonth(): string {
  const d = new Date();
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}`;
}

function shiftMonth(ym: string, delta: number): string {
  const [y, m] = ym.split('-').map(Number);
  const dt = new Date(y, (m || 1) - 1 + delta, 1);
  return `${dt.getFullYear()}-${String(dt.getMonth() + 1).padStart(2, '0')}`;
}

function compareMonth(a: string, b: string): number {
  return a < b ? -1 : a > b ? 1 : 0;
}

function formatTimestamp(iso: string): { date: string; time: string } {
  const d = new Date(iso);
  return {
    date: d.toLocaleDateString('es-MX', { day: '2-digit', month: 'short', year: 'numeric' }),
    time: d.toLocaleTimeString('es-MX', { hour: '2-digit', minute: '2-digit' }),
  };
}

function csvEscape(v: string | null | undefined): string {
  if (v == null) return '';
  const s = String(v);
  if (/[",\n;]/.test(s)) return `"${s.replace(/"/g, '""')}"`;
  return s;
}

function buildCsv(entries: NotificationHistoryEntry[]): string {
  const header = [
    'Fecha', 'Hora', 'Canal', 'Resultado', 'Evento',
    'Destinatario', 'Email', 'Telefono', 'Actor', 'Detalle',
  ].join(',');
  const lines = entries.map((e) => {
    const ts = formatTimestamp(e.timestamp);
    return [
      csvEscape(ts.date),
      csvEscape(ts.time),
      csvEscape(e.channel),
      csvEscape(e.outcome),
      csvEscape(e.eventType),
      csvEscape(e.recipientName),
      csvEscape(e.recipientEmail),
      csvEscape(e.recipientPhone),
      csvEscape(e.actorEmail),
      csvEscape(e.detail),
    ].join(',');
  });
  return [header, ...lines].join('\n');
}

function downloadCsv(filename: string, content: string) {
  const blob = new Blob(['\uFEFF' + content], { type: 'text/csv;charset=utf-8;' });
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = filename;
  document.body.appendChild(a);
  a.click();
  document.body.removeChild(a);
  URL.revokeObjectURL(url);
}

const ChannelPill: React.FC<{ channel: NotificationChannel }> = ({ channel }) => {
  if (channel === 'EMAIL') {
    return (
      <span className="inline-flex items-center gap-1 px-2 py-0.5 rounded-full bg-sky-50 text-sky-700 text-xs font-semibold border border-sky-200">
        <Mail className="w-3 h-3" /> Email
      </span>
    );
  }
  return (
    <span className="inline-flex items-center gap-1 px-2 py-0.5 rounded-full bg-emerald-50 text-emerald-700 text-xs font-semibold border border-emerald-200">
      <MessageSquare className="w-3 h-3" /> WhatsApp
    </span>
  );
};

const OutcomePill: React.FC<{ outcome: NotificationOutcome }> = ({ outcome }) => {
  if (outcome === 'SENT') {
    return (
      <span className="inline-flex items-center gap-1 px-2 py-0.5 rounded-full bg-emerald-50 text-emerald-700 text-xs font-semibold border border-emerald-200">
        <CheckCircle2 className="w-3 h-3" /> Enviado
      </span>
    );
  }
  if (outcome === 'FAILED') {
    return (
      <span className="inline-flex items-center gap-1 px-2 py-0.5 rounded-full bg-rose-50 text-rose-700 text-xs font-semibold border border-rose-200">
        <AlertCircle className="w-3 h-3" /> Fallido
      </span>
    );
  }
  return (
    <span className="inline-flex items-center gap-1 px-2 py-0.5 rounded-full bg-amber-50 text-amber-700 text-xs font-semibold border border-amber-200">
      <AlertTriangle className="w-3 h-3" /> Omitido
    </span>
  );
};

export const NotificationHistoryPanel: React.FC<Props> = ({
  mode,
  tenantProfileId,
  embedded = false,
}) => {
  const { user } = useAuth();
  const [month, setMonth] = useState<string>(currentMonth());
  const [page, setPage] = useState<NotificationHistoryPage | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [flash, setFlash] = useState<string | null>(null);

  // Filtros (solo modo owner)
  const [channelFilter, setChannelFilter] = useState<NotificationChannel | ''>('');
  const [outcomeFilter, setOutcomeFilter] = useState<NotificationOutcome | ''>('');
  const [showFilters, setShowFilters] = useState(false);

  // Reintento (solo modo tenant/owner)
  const [retryTarget, setRetryTarget] = useState<NotificationHistoryEntry | null>(null);

  const canRetry = mode !== 'me' && user?.role !== 'SUPER_ADMIN';
  const canExport = mode !== 'me';
  const showDetail = mode !== 'me';
  const showOnlySuccessful = mode === 'me';

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      let data: NotificationHistoryPage;
      if (mode === 'tenant') {
        if (!tenantProfileId) throw new Error('tenantProfileId es requerido en modo tenant');
        data = await notificationHistoryService.listForTenant(tenantProfileId, month);
      } else if (mode === 'owner') {
        data = await notificationHistoryService.listForOwner({
          month,
          channel: channelFilter || undefined,
          outcome: outcomeFilter || undefined,
        });
      } else {
        data = await notificationHistoryService.listForMe(month);
      }
      setPage(data);
    } catch (err: any) {
      const msg = err?.response?.data?.message || err?.message || 'Error al cargar el historial.';
      setError(msg);
      setPage(null);
    } finally {
      setLoading(false);
    }
  }, [mode, tenantProfileId, month, channelFilter, outcomeFilter]);

  useEffect(() => { load(); }, [load]);

  const handleRetryConfirm = async (password: string, mfaCode: string) => {
    if (!retryTarget) return;
    try {
      await notificationHistoryService.retry(retryTarget.id, password, mfaCode);
      setFlash(`Reintento enviado. Destinatario: ${retryTarget.recipientName || 'inquilino'}.`);
      setRetryTarget(null);
      setTimeout(() => setFlash(null), 5000);
      await load();
    } catch (err: any) {
      // La excepción se muestra dentro del modal — relanzamos para que lo capture.
      throw err;
    }
  };

  const goPrev = () => {
    if (!page) return;
    const next = shiftMonth(month, -1);
    if (compareMonth(next, page.minMonth) < 0) return;
    setMonth(next);
  };
  const goNext = () => {
    if (!page) return;
    const next = shiftMonth(month, +1);
    if (compareMonth(next, page.maxMonth) > 0) return;
    setMonth(next);
  };
  const canGoPrev = page ? compareMonth(shiftMonth(month, -1), page.minMonth) >= 0 : false;
  const canGoNext = page ? compareMonth(shiftMonth(month, +1), page.maxMonth) <= 0 : false;

  const handleExport = () => {
    if (!page || page.entries.length === 0) return;
    const scope = mode === 'tenant' ? 'inquilino' : 'cartera';
    downloadCsv(`notificaciones_${scope}_${month}.csv`, buildCsv(page.entries));
  };

  const bannerText = mode === 'me'
    ? 'Aquí verás los avisos que te hemos enviado por email y WhatsApp en los últimos 3 meses. Si necesitas historial anterior contáctanos.'
    : 'Mostramos los últimos 3 meses de actividad de notificaciones. Si necesitas historial anterior, contáctanos.';

  const emptyMessage = mode === 'me'
    ? 'Este mes no te hemos enviado avisos.'
    : mode === 'tenant'
    ? 'Este mes no se han enviado avisos a este inquilino. Las notificaciones automáticas salen en la fecha de vencimiento.'
    : 'Este mes no se han enviado avisos en tu cartera.';

  const displayedEntries = useMemo(() => {
    if (!page) return [] as NotificationHistoryEntry[];
    return showOnlySuccessful ? page.entries.filter((e) => e.outcome === 'SENT') : page.entries;
  }, [page, showOnlySuccessful]);

  return (
    <div className={embedded ? '' : 'bg-white border border-slate-200 rounded-2xl shadow-sm'}>
      <div className="px-5 py-4 border-b border-slate-100 flex flex-wrap gap-3 items-center justify-between">
        <div className="flex items-center gap-3">
          <div className="w-10 h-10 rounded-xl bg-indigo-50 text-indigo-600 flex items-center justify-center">
            <Bell className="w-5 h-5" />
          </div>
          <div>
            <h3 className="text-base font-bold text-slate-800">
              {mode === 'me' ? 'Mis notificaciones' : 'Historial de notificaciones'}
            </h3>
            <p className="text-xs text-slate-500">Email y WhatsApp — últimos 3 meses</p>
          </div>
        </div>

        <div className="flex items-center gap-2">
          <button
            onClick={goPrev}
            disabled={!canGoPrev || loading}
            className="p-2 rounded-lg border border-slate-200 text-slate-600 hover:bg-slate-50 disabled:opacity-40 disabled:cursor-not-allowed"
            aria-label="Mes anterior"
          >
            <ChevronLeft className="w-4 h-4" />
          </button>
          <div className="min-w-[140px] text-center px-3 py-1.5 rounded-lg bg-slate-50 border border-slate-200 text-sm font-semibold text-slate-700 capitalize">
            {formatMonthLabel(month)}
          </div>
          <button
            onClick={goNext}
            disabled={!canGoNext || loading}
            className="p-2 rounded-lg border border-slate-200 text-slate-600 hover:bg-slate-50 disabled:opacity-40 disabled:cursor-not-allowed"
            aria-label="Mes siguiente"
          >
            <ChevronRight className="w-4 h-4" />
          </button>

          {mode === 'owner' && (
            <button
              onClick={() => setShowFilters((s) => !s)}
              className={`p-2 rounded-lg border ${showFilters ? 'bg-indigo-50 border-indigo-200 text-indigo-700' : 'border-slate-200 text-slate-600 hover:bg-slate-50'}`}
              title="Filtros"
            >
              <FilterIcon className="w-4 h-4" />
            </button>
          )}
          {canExport && page && page.entries.length > 0 && (
            <button
              onClick={handleExport}
              className="px-3 py-1.5 text-sm rounded-lg border border-slate-200 text-slate-700 hover:bg-slate-50 flex items-center gap-1.5"
              title="Exportar CSV del mes visible"
            >
              <Download className="w-4 h-4" /> CSV
            </button>
          )}
        </div>
      </div>

      {mode === 'owner' && showFilters && (
        <div className="px-5 py-3 border-b border-slate-100 bg-slate-50/50 flex flex-wrap gap-3 items-center text-sm">
          <div className="flex items-center gap-2">
            <span className="text-xs font-semibold text-slate-500 uppercase tracking-wide">Canal</span>
            <select
              className="rounded-lg border border-slate-200 bg-white px-2 py-1 text-sm"
              value={channelFilter}
              onChange={(e) => setChannelFilter(e.target.value as NotificationChannel | '')}
            >
              <option value="">Todos</option>
              <option value="EMAIL">Email</option>
              <option value="WHATSAPP">WhatsApp</option>
            </select>
          </div>
          <div className="flex items-center gap-2">
            <span className="text-xs font-semibold text-slate-500 uppercase tracking-wide">Resultado</span>
            <select
              className="rounded-lg border border-slate-200 bg-white px-2 py-1 text-sm"
              value={outcomeFilter}
              onChange={(e) => setOutcomeFilter(e.target.value as NotificationOutcome | '')}
            >
              <option value="">Todos</option>
              <option value="SENT">Enviadas</option>
              <option value="FAILED">Fallidas</option>
              <option value="SKIPPED">Omitidas</option>
            </select>
          </div>
          {(channelFilter || outcomeFilter) && (
            <button
              onClick={() => { setChannelFilter(''); setOutcomeFilter(''); }}
              className="ml-auto text-xs font-semibold text-slate-500 hover:text-slate-700 flex items-center gap-1"
            >
              <XIcon className="w-3 h-3" /> Limpiar filtros
            </button>
          )}
        </div>
      )}

      <div className="px-5 py-3 bg-amber-50/40 border-b border-amber-100 text-xs text-amber-800 font-medium">
        {bannerText}
      </div>

      {page && (
        <div className="px-5 py-3 border-b border-slate-100 grid grid-cols-2 sm:grid-cols-4 gap-2 text-center">
          <div className="rounded-lg bg-slate-50 border border-slate-200 px-3 py-2">
            <div className="text-xs text-slate-500 font-semibold">Total</div>
            <div className="text-lg font-bold text-slate-800">{showOnlySuccessful ? page.sentCount : page.totalCount}</div>
          </div>
          <div className="rounded-lg bg-emerald-50 border border-emerald-200 px-3 py-2">
            <div className="text-xs text-emerald-700 font-semibold">Enviadas</div>
            <div className="text-lg font-bold text-emerald-700">{page.sentCount}</div>
          </div>
          {!showOnlySuccessful && (
            <>
              <div className="rounded-lg bg-rose-50 border border-rose-200 px-3 py-2">
                <div className="text-xs text-rose-700 font-semibold">Fallidas</div>
                <div className="text-lg font-bold text-rose-700">{page.failedCount}</div>
              </div>
              <div className="rounded-lg bg-amber-50 border border-amber-200 px-3 py-2">
                <div className="text-xs text-amber-700 font-semibold">Omitidas</div>
                <div className="text-lg font-bold text-amber-700">{page.skippedCount}</div>
              </div>
            </>
          )}
        </div>
      )}

      {flash && (
        <div className="px-5 py-2.5 bg-emerald-50 border-b border-emerald-100 text-sm text-emerald-800 font-medium flex items-center gap-2">
          <CheckCircle2 className="w-4 h-4" /> {flash}
        </div>
      )}

      <div className="p-5">
        {loading && (
          <div className="py-10 text-center text-sm text-slate-500">Cargando historial…</div>
        )}
        {!loading && error && (
          <div className="py-6 bg-rose-50 border border-rose-200 rounded-xl px-4 text-sm text-rose-700">
            {error}
          </div>
        )}
        {!loading && !error && displayedEntries.length === 0 && (
          <div className="py-10 text-center text-sm text-slate-500 max-w-md mx-auto">
            {emptyMessage}
          </div>
        )}
        {!loading && !error && displayedEntries.length > 0 && (
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead>
                <tr className="border-b border-slate-200 text-xs uppercase tracking-wide text-slate-500">
                  <th className="text-left py-2 pr-3 font-semibold">Fecha</th>
                  <th className="text-left py-2 pr-3 font-semibold">Canal</th>
                  <th className="text-left py-2 pr-3 font-semibold">Resultado</th>
                  <th className="text-left py-2 pr-3 font-semibold">Evento</th>
                  {mode !== 'me' && <th className="text-left py-2 pr-3 font-semibold">Destinatario</th>}
                  {mode !== 'me' && <th className="text-left py-2 pr-3 font-semibold">Actor</th>}
                  {showDetail && <th className="text-left py-2 pr-3 font-semibold">Detalle técnico</th>}
                  {canRetry && <th className="text-right py-2 font-semibold">Acción</th>}
                </tr>
              </thead>
              <tbody>
                {displayedEntries.map((e) => {
                  const ts = formatTimestamp(e.timestamp);
                  const retryable = canRetry && e.outcome === 'FAILED' && e.eventType === 'MANUAL_PAYMENT_REMINDER';
                  return (
                    <tr key={e.id} className="border-b border-slate-100 hover:bg-slate-50/50">
                      <td className="py-2.5 pr-3 text-slate-700 whitespace-nowrap">
                        <div className="font-medium">{ts.date}</div>
                        <div className="text-xs text-slate-400">{ts.time}</div>
                      </td>
                      <td className="py-2.5 pr-3"><ChannelPill channel={e.channel} /></td>
                      <td className="py-2.5 pr-3"><OutcomePill outcome={e.outcome} /></td>
                      <td className="py-2.5 pr-3 text-slate-700 text-xs font-mono">{e.eventType}</td>
                      {mode !== 'me' && (
                        <td className="py-2.5 pr-3 text-slate-700">
                          <div className="font-medium">{e.recipientName || '—'}</div>
                          <div className="text-xs text-slate-500">
                            {e.channel === 'EMAIL' ? e.recipientEmail : e.recipientPhone}
                          </div>
                        </td>
                      )}
                      {mode !== 'me' && (
                        <td className="py-2.5 pr-3 text-xs text-slate-500">
                          {e.actorEmail === 'SYSTEM' ? <span className="italic">automático</span> : e.actorEmail}
                        </td>
                      )}
                      {showDetail && (
                        <td className="py-2.5 pr-3 text-xs text-slate-500 max-w-xs">
                          <div className="truncate" title={e.detail || ''}>{e.detail || '—'}</div>
                        </td>
                      )}
                      {canRetry && (
                        <td className="py-2.5 text-right">
                          {retryable ? (
                            <button
                              onClick={() => setRetryTarget(e)}
                              className="inline-flex items-center gap-1 px-2.5 py-1 rounded-lg bg-indigo-50 border border-indigo-200 text-indigo-700 text-xs font-semibold hover:bg-indigo-100"
                            >
                              <RefreshCw className="w-3 h-3" /> Reintentar
                            </button>
                          ) : (
                            <span className="text-xs text-slate-300">—</span>
                          )}
                        </td>
                      )}
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        )}
      </div>

      <ReauthConfirmModal
        isOpen={!!retryTarget}
        onClose={() => setRetryTarget(null)}
        onConfirm={handleRetryConfirm}
        title="Reintentar envío"
        description={`Se volverá a enviar el recordatorio manual a ${retryTarget?.recipientName || 'el inquilino'} por los tres canales (email, WhatsApp e in-app). Esta acción consume uno de los 2 envíos manuales permitidos por día.`}
        confirmLabel="Reenviar"
        accent="emerald"
      />
    </div>
  );
};

export default NotificationHistoryPanel;
