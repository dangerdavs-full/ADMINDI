import React, { useCallback, useEffect, useMemo, useState } from 'react';
import { Bell, Loader2, Lock, Save } from 'lucide-react';
import { useAuth } from '../context/AuthContext';
import {
  notificationPreferenceService,
  NotificationPreferenceDTO,
  NotificationCatalog,
  NotificationCatalogChannel,
  NotificationCatalogEvent,
  NotificationAudience,
} from '../services/notificationPreferenceService';

function keyOf(p: Pick<NotificationPreferenceDTO, 'eventType' | 'channel'>) {
  return `${p.eventType}|${p.channel}`;
}

/**
 * Decide si un evento aplica para el rol del usuario.
 *  - Si el evento NO declara audience (o está vacía), se muestra por defecto
 *    (defensivo — nunca ocultamos por ausencia de metadato).
 *  - Si la declara, solo se muestra cuando el rol del usuario está incluido.
 *  - SUPER_ADMIN ve todo por regla, aunque el evento no lo declare
 *    explícitamente — es una puerta de escape operativa.
 */
function eventVisibleForRole(ev: NotificationCatalogEvent, role: string | null | undefined): boolean {
  if (role === 'SUPER_ADMIN') return true;
  if (!ev.audience || ev.audience.length === 0) return true;
  if (!role) return false;
  return ev.audience.includes(role as NotificationAudience);
}

interface PanelProps {
  /**
   * Si se pasa, el panel se renderiza sin el wrapper "max-w-5xl" externo.
   * Usado cuando el panel vive dentro de un modal o contenedor ya acotado.
   */
  embedded?: boolean;
}

export const NotificationPreferencesPanel: React.FC<PanelProps> = ({ embedded = false }) => {
  const { user } = useAuth();
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState('');
  const [savedFlash, setSavedFlash] = useState(false);
  const [map, setMap] = useState<Record<string, boolean>>({});
  const [catalog, setCatalog] = useState<NotificationCatalog | null>(null);

  const rawEvents: NotificationCatalogEvent[] = catalog?.events ?? [];
  const channels: NotificationCatalogChannel[] = catalog?.channels ?? [];

  // Filtrado por audiencia del rol efectivo del usuario.
  const events = useMemo(
    () => rawEvents.filter((ev) => eventVisibleForRole(ev, user?.role)),
    [rawEvents, user?.role]
  );

  // Agrupación por `group` preservando orden de aparición (primer evento del grupo define posición).
  const eventsByGroup = useMemo(() => {
    const groups = new Map<string, NotificationCatalogEvent[]>();
    for (const ev of events) {
      const g = ev.group || 'General';
      const list = groups.get(g) ?? [];
      list.push(ev);
      groups.set(g, list);
    }
    return Array.from(groups.entries());
  }, [events]);

  const matrix = useMemo(
    () =>
      events.flatMap((ev) =>
        channels.map((ch) => ({ eventType: ev.eventType, channel: ch.id }))
      ),
    [events, channels]
  );

  const load = useCallback(async () => {
    setLoading(true);
    setError('');
    try {
      const [cat, rows] = await Promise.all([
        notificationPreferenceService.catalog(),
        notificationPreferenceService.list(),
      ]);
      setCatalog(cat);
      const m: Record<string, boolean> = {};
      for (const r of rows) {
        m[keyOf(r)] = r.enabled;
      }
      setMap(m);
    } catch (e: unknown) {
      setError('No se pudieron cargar las preferencias.');
      console.error(e);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  const channelMeta = useMemo(() => {
    const m: Record<string, NotificationCatalogChannel> = {};
    for (const c of channels) m[c.id] = c;
    return m;
  }, [channels]);

  const isEnabled = (eventType: string, channel: string): boolean => {
    const meta = channelMeta[channel];
    if (meta && meta.mandatory) return true;
    const v = map[keyOf({ eventType, channel })];
    return v === undefined ? true : v;
  };

  const isEditable = (channel: string): boolean => {
    const meta = channelMeta[channel];
    return meta ? meta.editable : true;
  };

  const toggle = (eventType: string, channel: string) => {
    if (!isEditable(channel)) return;
    const k = keyOf({ eventType, channel });
    setMap((prev) => ({ ...prev, [k]: !isEnabled(eventType, channel) }));
  };

  /** Acciones masivas por canal aplicadas a los eventos visibles. */
  const setChannelForAll = (channel: string, value: boolean) => {
    if (!isEditable(channel)) return;
    setMap((prev) => {
      const next = { ...prev };
      for (const ev of events) {
        next[keyOf({ eventType: ev.eventType, channel })] = value;
      }
      return next;
    });
  };

  const handleSave = async () => {
    setSaving(true);
    setError('');
    setSavedFlash(false);
    try {
      const preferences: NotificationPreferenceDTO[] = matrix.map(({ eventType, channel }) => ({
        eventType,
        channel,
        enabled: isEnabled(eventType, channel),
      }));
      const saved = await notificationPreferenceService.upsert(preferences);
      const m: Record<string, boolean> = {};
      for (const r of saved) {
        m[keyOf(r)] = r.enabled;
      }
      setMap(m);
      setSavedFlash(true);
      setTimeout(() => setSavedFlash(false), 2500);
    } catch (e: unknown) {
      const anyErr = e as { response?: { data?: { message?: string } } };
      const msg = anyErr?.response?.data?.message;
      setError(msg ? `Error al guardar: ${msg}` : 'Error al guardar. Verifique sesión y permisos.');
      console.error(e);
    } finally {
      setSaving(false);
    }
  };

  if (loading) {
    return (
      <div className="flex items-center justify-center p-16 text-slate-500 gap-2">
        <Loader2 className="w-6 h-6 animate-spin" /> Cargando preferencias…
      </div>
    );
  }

  const wrapperCls = embedded
    ? 'space-y-5'
    : 'space-y-6 animate-in fade-in slide-in-from-bottom-4 duration-500 max-w-5xl';

  return (
    <div className={wrapperCls}>
      {!embedded && (
        <div>
          <h2 className="text-xl font-bold text-slate-800 flex items-center gap-2">
            <Bell className="w-6 h-6 text-indigo-500" /> Preferencias de notificación
          </h2>
          <p className="text-sm text-slate-500 mt-1">
            El canal <strong>En app</strong> es obligatorio y no puede apagarse. Los canales de
            Email y WhatsApp son opcionales por evento.
          </p>
        </div>
      )}

      {error && (
        <div className="rounded-xl border border-rose-200 bg-rose-50 px-4 py-3 text-sm text-rose-800">{error}</div>
      )}
      {savedFlash && !error && (
        <div className="rounded-xl border border-emerald-200 bg-emerald-50 px-4 py-3 text-sm text-emerald-800">
          Preferencias guardadas.
        </div>
      )}

      {events.length === 0 ? (
        <div className="rounded-2xl border border-slate-200 bg-white p-8 text-sm text-slate-500">
          No hay eventos configurables para tu rol.
        </div>
      ) : (
        <div className="bg-white rounded-2xl border border-slate-200 shadow-sm overflow-hidden">
          {/* Quick actions por canal: encender/apagar todos los eventos visibles */}
          <div className="px-4 py-3 border-b border-slate-100 bg-slate-50 flex flex-wrap gap-2 items-center">
            <span className="text-xs font-bold text-slate-500 uppercase mr-2">Acciones rápidas:</span>
            {channels
              .filter((ch) => ch.editable)
              .map((ch) => (
                <div key={ch.id} className="inline-flex items-center gap-1 rounded-lg border border-slate-200 bg-white px-2 py-1">
                  <span className="text-xs font-semibold text-slate-600">{ch.label}:</span>
                  <button
                    type="button"
                    onClick={() => setChannelForAll(ch.id, true)}
                    className="text-[11px] font-bold text-emerald-700 hover:text-emerald-900 px-1"
                  >
                    activar todos
                  </button>
                  <span className="text-slate-300">·</span>
                  <button
                    type="button"
                    onClick={() => setChannelForAll(ch.id, false)}
                    className="text-[11px] font-bold text-rose-700 hover:text-rose-900 px-1"
                  >
                    apagar todos
                  </button>
                </div>
              ))}
          </div>

          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead className="bg-slate-50 border-b border-slate-200 sticky top-0 z-[1]">
                <tr>
                  <th className="text-left px-4 py-3 font-semibold text-slate-700">Evento</th>
                  {channels.map((ch) => (
                    <th
                      key={ch.id}
                      className="text-center px-3 py-3 font-semibold text-slate-600 w-32"
                    >
                      <div className="flex flex-col items-center gap-0.5">
                        <span className="inline-flex items-center gap-1">
                          {ch.label}
                          {ch.mandatory && <Lock className="w-3 h-3 text-slate-400" />}
                        </span>
                        {ch.mandatory && (
                          <span className="text-[10px] font-normal text-slate-400">Obligatorio</span>
                        )}
                      </div>
                    </th>
                  ))}
                </tr>
              </thead>
              <tbody>
                {eventsByGroup.map(([groupName, groupEvents]) => (
                  <React.Fragment key={groupName}>
                    <tr className="bg-indigo-50/50">
                      <td colSpan={1 + channels.length} className="px-4 py-2 text-[11px] font-bold text-indigo-700 uppercase tracking-wider">
                        {groupName}
                      </td>
                    </tr>
                    {groupEvents.map((ev) => (
                      <tr key={ev.eventType} className="border-b border-slate-100 hover:bg-slate-50/80">
                        <td className="px-4 py-2.5">
                          <div className="font-semibold text-slate-800">{ev.label}</div>
                          <div className="font-mono text-[11px] text-slate-400">{ev.eventType}</div>
                        </td>
                        {channels.map((ch) => {
                          const checked = isEnabled(ev.eventType, ch.id);
                          const editable = isEditable(ch.id);
                          return (
                            <td key={ch.id} className="text-center py-2">
                              <input
                                type="checkbox"
                                className="w-4 h-4 rounded border-slate-300 text-indigo-600 focus:ring-indigo-500 disabled:cursor-not-allowed disabled:opacity-70"
                                checked={checked}
                                disabled={!editable}
                                readOnly={!editable}
                                onChange={() => toggle(ev.eventType, ch.id)}
                                aria-label={`${ev.eventType} ${ch.id}`}
                                title={editable ? undefined : 'Canal obligatorio, no editable'}
                              />
                            </td>
                          );
                        })}
                      </tr>
                    ))}
                  </React.Fragment>
                ))}
              </tbody>
            </table>
          </div>
        </div>
      )}

      <div className="flex justify-end">
        <button
          type="button"
          onClick={() => void handleSave()}
          disabled={saving || events.length === 0}
          className="inline-flex items-center gap-2 px-5 py-2.5 rounded-xl bg-indigo-600 text-white text-sm font-bold hover:bg-indigo-700 disabled:opacity-60 shadow-sm"
        >
          {saving ? <Loader2 className="w-4 h-4 animate-spin" /> : <Save className="w-4 h-4" />}
          Guardar preferencias
        </button>
      </div>
    </div>
  );
};
