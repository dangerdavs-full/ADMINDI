import React, { useState, useEffect } from 'react';
import api from '../services/api';
import { ClipboardList, Search, ChevronLeft, ChevronRight } from 'lucide-react';

interface AuditEvent {
  id: string;
  timestamp: string;
  actorId: string;
  actorRole: string;
  eventType: string;
  resourceType: string;
  resourceId: string;
  ownerId: string;
  oldValues: string | null;
  newValues: string | null;
}

interface AuditPage {
  content: AuditEvent[];
  totalPages: number;
  totalElements: number;
  number: number;
  size: number;
}

const EVENT_TYPE_OPTIONS = [
  '',
  // Owner lifecycle
  'OWNER_CREATED', 'OWNER_CONTACT_UPDATED', 'OWNER_DEACTIVATED', 'OWNER_PURGED',
  // Provider lifecycle
  'PROVIDER_CREATED', 'PROVIDER_UPDATED', 'PROVIDER_DEACTIVATED',
  // Account recovery (no n8n)
  'ADMIN_ACCOUNT_RECOVERY_PASSWORD_RESET', 'ADMIN_ACCOUNT_RECOVERY_MFA_RESET',
  'ADMIN_ACCOUNT_RECOVERY_FULL_RECOVERY', 'FEE_ADMIN_ACCOUNT_RECOVERY',
  // N8N events
  'N8N_OWNER_CREATED_SENT', 'N8N_OWNER_CREATED_SKIPPED', 'N8N_OWNER_CREATED_FAILED',
  'N8N_OWNER_DEACTIVATED_SENT', 'N8N_OWNER_DEACTIVATED_SKIPPED', 'N8N_OWNER_DEACTIVATED_FAILED',
  'N8N_OWNER_CONTACT_UPDATED_SENT', 'N8N_OWNER_CONTACT_UPDATED_SKIPPED', 'N8N_OWNER_CONTACT_UPDATED_FAILED',
];

export const AdminAudit = () => {
    const [events, setEvents] = useState<AuditEvent[]>([]);
    const [loading, setLoading] = useState(false);
    const [page, setPage] = useState(0);
    const [totalPages, setTotalPages] = useState(0);
    const [totalElements, setTotalElements] = useState(0);

    // Filters
    const [eventType, setEventType] = useState('');
    const [actorId, setActorId] = useState('');
    const [ownerId, setOwnerId] = useState('');
    const [expandedId, setExpandedId] = useState<string | null>(null);

    const fetchAudit = async (p = 0) => {
        setLoading(true);
        try {
            const params = new URLSearchParams();
            params.set('page', String(p));
            params.set('size', '15');
            if (eventType) params.set('eventType', eventType);
            if (actorId) params.set('actorId', actorId);
            if (ownerId) params.set('ownerId', ownerId);

            const res = await api.get(`/admin/audit?${params.toString()}`);
            const data: AuditPage = res.data;
            setEvents(data.content);
            setTotalPages(data.totalPages);
            setTotalElements(data.totalElements);
            setPage(data.number);
        } catch {
            setEvents([]);
        }
        setLoading(false);
    };

    useEffect(() => { fetchAudit(0); }, []);

    const handleSearch = () => {
        setPage(0);
        fetchAudit(0);
    };

    const formatTs = (ts: string) => {
        try {
            const d = new Date(ts);
            return d.toLocaleString('es-MX', { dateStyle: 'short', timeStyle: 'medium' });
        } catch { return ts; }
    };

    const getEventBadge = (type: string) => {
        if (type.includes('PURGE')) return 'bg-red-100 text-red-700';
        if (type.includes('CREATED')) return 'bg-emerald-100 text-emerald-700';
        if (type.includes('DEACTIVATED')) return 'bg-orange-100 text-orange-700';
        if (type.includes('UPDATED')) return 'bg-blue-100 text-blue-700';
        if (type.includes('RECOVERY') || type.includes('RESET')) return 'bg-amber-100 text-amber-700';
        if (type.includes('N8N')) return 'bg-purple-100 text-purple-700';
        if (type.includes('FAILED')) return 'bg-red-100 text-red-700';
        if (type.includes('SKIPPED')) return 'bg-slate-100 text-slate-600';
        return 'bg-slate-100 text-slate-600';
    };

    return (
        <div>
            <div className="flex items-center gap-3 mb-6">
                <ClipboardList className="w-7 h-7 text-indigo-600" />
                <h2 className="text-2xl font-bold text-slate-800">Auditoría Administrativa</h2>
                <span className="text-sm bg-indigo-100 text-indigo-700 px-2 py-0.5 rounded-full font-semibold">{totalElements} eventos</span>
            </div>

            {/* Filters */}
            <div className="mb-4 p-4 bg-white border border-slate-200 rounded-xl flex flex-wrap gap-3 items-end">
                <div className="flex-1 min-w-[180px]">
                    <label className="block text-xs font-semibold text-slate-500 mb-1">Tipo de Evento</label>
                    <select value={eventType} onChange={e => setEventType(e.target.value)}
                        className="w-full px-3 py-2 border border-slate-200 rounded-lg text-sm bg-white">
                        <option value="">Todos</option>
                        {EVENT_TYPE_OPTIONS.filter(Boolean).map(t => (
                            <option key={t} value={t}>{t}</option>
                        ))}
                    </select>
                </div>
                <div className="min-w-[160px]">
                    <label className="block text-xs font-semibold text-slate-500 mb-1">Actor (email)</label>
                    <input value={actorId} onChange={e => setActorId(e.target.value)}
                        className="w-full px-3 py-2 border border-slate-200 rounded-lg text-sm"
                        placeholder="admin@..." />
                </div>
                <div className="min-w-[160px]">
                    <label className="block text-xs font-semibold text-slate-500 mb-1">Owner ID</label>
                    <input value={ownerId} onChange={e => setOwnerId(e.target.value)}
                        className="w-full px-3 py-2 border border-slate-200 rounded-lg text-sm"
                        placeholder="uuid..." />
                </div>
                <button onClick={handleSearch}
                    className="px-4 py-2 bg-indigo-600 text-white rounded-lg text-sm font-bold hover:bg-indigo-700 transition-colors flex items-center gap-1.5">
                    <Search className="w-4 h-4" /> Buscar
                </button>
            </div>

            {/* Table */}
            {loading ? (
                <div className="text-center py-12 text-slate-400">Cargando auditoría...</div>
            ) : events.length === 0 ? (
                <div className="text-center py-12 bg-white border border-slate-200 rounded-xl">
                    <ClipboardList className="w-12 h-12 text-slate-300 mx-auto mb-3" />
                    <p className="text-slate-500">No se encontraron eventos de auditoría.</p>
                </div>
            ) : (
                <div className="bg-white rounded-xl border border-slate-200 overflow-hidden">
                    <table className="w-full text-sm">
                        <thead>
                            <tr className="bg-slate-50 text-left text-xs text-slate-500 uppercase">
                                <th className="px-4 py-3">Fecha</th>
                                <th className="px-4 py-3">Evento</th>
                                <th className="px-4 py-3">Actor</th>
                                <th className="px-4 py-3">Rol</th>
                                <th className="px-4 py-3">Recurso</th>
                                <th className="px-4 py-3">Owner</th>
                            </tr>
                        </thead>
                        <tbody>
                            {events.map(ev => (
                                <React.Fragment key={ev.id}>
                                    <tr
                                        className="border-t border-slate-100 hover:bg-slate-50 cursor-pointer transition-colors"
                                        onClick={() => setExpandedId(expandedId === ev.id ? null : ev.id)}>
                                        <td className="px-4 py-2.5 text-slate-600 text-xs font-mono whitespace-nowrap">{formatTs(ev.timestamp)}</td>
                                        <td className="px-4 py-2.5">
                                            <span className={`text-xs px-2 py-0.5 rounded-full font-semibold ${getEventBadge(ev.eventType)}`}>
                                                {ev.eventType}
                                            </span>
                                        </td>
                                        <td className="px-4 py-2.5 text-slate-600 text-xs">{ev.actorId || '—'}</td>
                                        <td className="px-4 py-2.5 text-slate-500 text-xs">{ev.actorRole || '—'}</td>
                                        <td className="px-4 py-2.5 text-slate-500 text-xs">{ev.resourceType || '—'}</td>
                                        <td className="px-4 py-2.5 text-slate-400 text-xs font-mono">{ev.ownerId ? ev.ownerId.slice(0,8) + '...' : '—'}</td>
                                    </tr>
                                    {expandedId === ev.id && (ev.oldValues || ev.newValues) && (
                                        <tr className="bg-slate-50">
                                            <td colSpan={6} className="px-6 py-3">
                                                <div className="grid grid-cols-2 gap-4 text-xs">
                                                    <div>
                                                        <div className="font-bold text-slate-500 mb-1">Old Values</div>
                                                        <pre className="bg-white p-2 rounded border text-slate-600 overflow-auto max-h-32">
                                                            {ev.oldValues ? JSON.stringify(JSON.parse(ev.oldValues), null, 2) : '—'}
                                                        </pre>
                                                    </div>
                                                    <div>
                                                        <div className="font-bold text-slate-500 mb-1">New Values</div>
                                                        <pre className="bg-white p-2 rounded border text-slate-600 overflow-auto max-h-32">
                                                            {ev.newValues ? JSON.stringify(JSON.parse(ev.newValues), null, 2) : '—'}
                                                        </pre>
                                                    </div>
                                                </div>
                                            </td>
                                        </tr>
                                    )}
                                </React.Fragment>
                            ))}
                        </tbody>
                    </table>

                    {/* Pagination */}
                    <div className="px-4 py-3 bg-slate-50 border-t border-slate-200 flex items-center justify-between">
                        <span className="text-xs text-slate-500">
                            Página {page + 1} de {totalPages} ({totalElements} total)
                        </span>
                        <div className="flex gap-1">
                            <button onClick={() => fetchAudit(page - 1)} disabled={page === 0}
                                className="p-1.5 rounded hover:bg-slate-200 disabled:opacity-30 transition-colors">
                                <ChevronLeft className="w-4 h-4" />
                            </button>
                            <button onClick={() => fetchAudit(page + 1)} disabled={page >= totalPages - 1}
                                className="p-1.5 rounded hover:bg-slate-200 disabled:opacity-30 transition-colors">
                                <ChevronRight className="w-4 h-4" />
                            </button>
                        </div>
                    </div>
                </div>
            )}
        </div>
    );
};
