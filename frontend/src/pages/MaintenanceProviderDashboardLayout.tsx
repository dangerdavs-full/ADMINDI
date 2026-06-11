import React, { useEffect, useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useAuth } from '../context/AuthContext';
import {
  LogOut, HardHat, Bell, X, Building2, MapPin, Phone, Mail, Clock,
  CheckCircle2, XCircle, Landmark, Loader2, RefreshCw, FileText,
  Wrench, Upload,
} from 'lucide-react';
import {
  maintenanceProviderService,
  type ProviderTicketDTO,
  type MaintenanceQuoteDTO,
} from '../services/maintenanceProviderService';
import { BankAccountTab } from './RealEstateAgentDashboardLayout';
import { NotificationPreferencesPanel } from './NotificationPreferencesPanel';
import NotificationHistoryPanel from './NotificationHistoryPanel';
import { AgentOnboardingWizard } from '../components/AgentOnboardingWizard';
import { agentBankAccountService } from '../services/agentBankAccountService';
import { openFileAttachment, describeSecureFileError } from '../services/secureFileService';

type ProviderTab = 'INVITES' | 'ACTIVE' | 'PAYMENT_CONFIRM' | 'BANK';

const STATUS_LABELS: Record<string, { text: string; cls: string }> = {
  AWAITING_PROVIDER_ACCEPT: { text: 'Esperando tu aceptación', cls: 'bg-amber-100 text-amber-700' },
  ACCEPTED: { text: 'Aceptado — sube cotización', cls: 'bg-blue-100 text-blue-700' },
  QUOTED: { text: 'Cotización enviada', cls: 'bg-violet-100 text-violet-700' },
  APPROVED: { text: 'Aprobada por dueño — ejecuta', cls: 'bg-teal-100 text-teal-700' },
  AWAITING_PROVIDER_CONFIRM: { text: 'Confirma recepción del pago', cls: 'bg-amber-100 text-amber-800' },
  COMPLETED: { text: 'Completado', cls: 'bg-emerald-100 text-emerald-700' },
  CANCELLED: { text: 'Cancelado', cls: 'bg-slate-100 text-slate-600' },
  REJECTED_BY_OWNER: { text: 'Rechazado por dueño', cls: 'bg-rose-100 text-rose-700' },
};

const URGENCY_LABELS: Record<string, { text: string; cls: string }> = {
  LOW: { text: 'Baja', cls: 'bg-slate-100 text-slate-600' },
  NORMAL: { text: 'Normal', cls: 'bg-blue-100 text-blue-700' },
  HIGH: { text: 'Alta', cls: 'bg-orange-100 text-orange-700' },
  CRITICAL: { text: 'Crítica', cls: 'bg-rose-100 text-rose-700' },
};

const fmtDate = (iso?: string | null) =>
  iso ? new Date(iso).toLocaleString('es-MX', { dateStyle: 'short', timeStyle: 'short' }) : '—';

const fmtMoney = (n?: number | null) =>
  typeof n === 'number' ? `$${n.toLocaleString('es-MX', { minimumFractionDigits: 2 })}` : '—';

// ══════════════════════════════════════════════════════════════════════
// Tab: Invitaciones pendientes (72h para aceptar)
// ══════════════════════════════════════════════════════════════════════
const InvitesTab: React.FC<{ onChanged: () => void }> = ({ onChanged }) => {
  const [items, setItems] = useState<ProviderTicketDTO[]>([]);
  const [loading, setLoading] = useState(true);
  const [busyId, setBusyId] = useState<string | null>(null);
  const [rejectModal, setRejectModal] = useState<{ id: string; reason: string } | null>(null);

  const load = async () => {
    setLoading(true);
    try { setItems(await maintenanceProviderService.getInvitations()); } finally { setLoading(false); }
  };
  useEffect(() => { load(); }, []);

  const accept = async (id: string) => {
    // #region agent log
    fetch('http://127.0.0.1:7682/ingest/3f2253a5-d387-4dcd-a5a3-22aec5e3ff2b',{method:'POST',headers:{'Content-Type':'application/json','X-Debug-Session-Id':'93290f'},body:JSON.stringify({sessionId:'93290f',hypothesisId:'ACCEPT',location:'MaintenanceProviderDashboardLayout.tsx:accept',message:'accept:click',data:{ticketId:id},timestamp:Date.now()})}).catch(()=>{});
    // #endregion
    setBusyId(id);
    try {
      await maintenanceProviderService.acceptTicket(id);
      // #region agent log
      fetch('http://127.0.0.1:7682/ingest/3f2253a5-d387-4dcd-a5a3-22aec5e3ff2b',{method:'POST',headers:{'Content-Type':'application/json','X-Debug-Session-Id':'93290f'},body:JSON.stringify({sessionId:'93290f',hypothesisId:'ACCEPT',location:'MaintenanceProviderDashboardLayout.tsx:accept',message:'accept:success',data:{ticketId:id},timestamp:Date.now()})}).catch(()=>{});
      // #endregion
      await load();
      onChanged();
    } catch (e: unknown) {
      const err = e as { response?: { status?: number; data?: { message?: string } } };
      const status = err?.response?.status;
      const msg = err?.response?.data?.message;
      // #region agent log
      fetch('http://127.0.0.1:7682/ingest/3f2253a5-d387-4dcd-a5a3-22aec5e3ff2b',{method:'POST',headers:{'Content-Type':'application/json','X-Debug-Session-Id':'93290f'},body:JSON.stringify({sessionId:'93290f',hypothesisId:'ACCEPT',location:'MaintenanceProviderDashboardLayout.tsx:accept',message:'accept:failed',data:{ticketId:id,httpStatus:status,errorMessage:msg||null},timestamp:Date.now()})}).catch(()=>{});
      // #endregion
      alert(msg || `No se pudo aceptar el ticket${status ? ` (HTTP ${status})` : ''}.`);
    } finally { setBusyId(null); }
  };
  const doReject = async () => {
    if (!rejectModal) return;
    // #region agent log
    fetch('http://127.0.0.1:7682/ingest/3f2253a5-d387-4dcd-a5a3-22aec5e3ff2b',{method:'POST',headers:{'Content-Type':'application/json','X-Debug-Session-Id':'93290f'},body:JSON.stringify({sessionId:'93290f',hypothesisId:'REJECT',location:'MaintenanceProviderDashboardLayout.tsx:doReject',message:'reject:click',data:{ticketId:rejectModal.id,hasReason:!!rejectModal.reason.trim()},timestamp:Date.now()})}).catch(()=>{});
    // #endregion
    setBusyId(rejectModal.id);
    try {
      await maintenanceProviderService.rejectTicket(rejectModal.id, rejectModal.reason.trim() || undefined);
      // #region agent log
      fetch('http://127.0.0.1:7682/ingest/3f2253a5-d387-4dcd-a5a3-22aec5e3ff2b',{method:'POST',headers:{'Content-Type':'application/json','X-Debug-Session-Id':'93290f'},body:JSON.stringify({sessionId:'93290f',hypothesisId:'REJECT',location:'MaintenanceProviderDashboardLayout.tsx:doReject',message:'reject:success',data:{ticketId:rejectModal.id},timestamp:Date.now()})}).catch(()=>{});
      // #endregion
      setRejectModal(null);
      await load();
      onChanged();
    } catch (e: unknown) {
      const err = e as { response?: { status?: number; data?: { message?: string } } };
      const status = err?.response?.status;
      const msg = err?.response?.data?.message;
      // #region agent log
      fetch('http://127.0.0.1:7682/ingest/3f2253a5-d387-4dcd-a5a3-22aec5e3ff2b',{method:'POST',headers:{'Content-Type':'application/json','X-Debug-Session-Id':'93290f'},body:JSON.stringify({sessionId:'93290f',hypothesisId:'REJECT',location:'MaintenanceProviderDashboardLayout.tsx:doReject',message:'reject:failed',data:{ticketId:rejectModal.id,httpStatus:status,errorMessage:msg||null},timestamp:Date.now()})}).catch(()=>{});
      // #endregion
      alert(msg || `No se pudo rechazar${status ? ` (HTTP ${status})` : ''}.`);
    } finally { setBusyId(null); }
  };

  if (loading) return <div className="animate-pulse h-48 bg-slate-100 rounded-2xl" />;
  if (items.length === 0) {
    return (
      <div className="bg-white rounded-2xl border border-slate-200 p-8 text-center text-slate-500">
        <Bell className="w-10 h-10 mx-auto text-slate-300 mb-3" />
        <p className="text-sm">No tienes invitaciones pendientes.</p>
      </div>
    );
  }

  return (
    <>
      <div className="space-y-4">
        {items.map((t) => {
          const urg = URGENCY_LABELS[t.urgency] || { text: t.urgency, cls: 'bg-slate-100 text-slate-600' };
          return (
            <div key={t.id} className="bg-white rounded-2xl border border-amber-200 p-5 shadow-sm">
              <div className="flex items-start justify-between gap-4 mb-3">
                <div className="flex items-start gap-3">
                  <div className="w-10 h-10 rounded-xl bg-amber-100 text-amber-600 flex items-center justify-center shrink-0">
                    <Wrench className="w-5 h-5" />
                  </div>
                  <div>
                    <p className="font-bold text-slate-800">{t.title}</p>
                    <p className="text-xs text-slate-500 flex items-center gap-1 mt-0.5">
                      <Building2 className="w-3 h-3" /> {t.propertyName || t.propertyId}
                      <MapPin className="w-3 h-3 ml-2" /> {t.propertyAddress || '—'}
                    </p>
                  </div>
                </div>
                <div className="text-right flex flex-col gap-1 items-end">
                  <span className="inline-flex items-center gap-1 px-2 py-1 rounded-lg text-xs font-bold bg-amber-100 text-amber-700">
                    <Clock className="w-3 h-3" /> Vence en {t.expiresInHours ?? '?'}h
                  </span>
                  <span className={`inline-flex items-center px-2 py-0.5 rounded-lg text-xs font-bold ${urg.cls}`}>Urgencia: {urg.text}</span>
                </div>
              </div>

              {t.description && (
                <p className="text-sm text-slate-600 border-t border-slate-100 pt-3 whitespace-pre-wrap">{t.description}</p>
              )}

              <div className="grid grid-cols-1 md:grid-cols-3 gap-3 pt-3 mt-3 border-t border-slate-100 text-sm">
                <div>
                  <p className="text-[10px] font-bold uppercase text-slate-400">Dueño</p>
                  <p className="font-semibold text-slate-700">{t.ownerName || '—'}</p>
                </div>
                <div>
                  <p className="text-[10px] font-bold uppercase text-slate-400">Contacto</p>
                  <p className="text-slate-600 text-xs flex items-center gap-1">
                    <Mail className="w-3 h-3" /> {t.ownerEmail || '—'}
                  </p>
                  <p className="text-slate-600 text-xs flex items-center gap-1">
                    <Phone className="w-3 h-3" /> {t.ownerPhone || '—'}
                  </p>
                </div>
                <div>
                  <p className="text-[10px] font-bold uppercase text-slate-400">Turno</p>
                  <p className="font-semibold text-slate-700">#{t.invitation?.priorityOrder}</p>
                </div>
              </div>

              <div className="flex gap-3 pt-4">
                <button
                  type="button"
                  onClick={() => accept(t.id)}
                  disabled={busyId === t.id}
                  className="flex-1 py-2.5 bg-emerald-600 hover:bg-emerald-700 text-white font-bold rounded-xl text-sm disabled:opacity-60 flex items-center justify-center gap-2"
                >
                  {busyId === t.id ? <Loader2 className="w-4 h-4 animate-spin" /> : <CheckCircle2 className="w-4 h-4" />} Aceptar
                </button>
                <button
                  type="button"
                  onClick={() => setRejectModal({ id: t.id, reason: '' })}
                  disabled={busyId === t.id}
                  className="px-4 py-2.5 bg-white border border-rose-200 text-rose-600 hover:bg-rose-50 font-bold rounded-xl text-sm disabled:opacity-60 flex items-center gap-2"
                >
                  <XCircle className="w-4 h-4" /> Rechazar
                </button>
              </div>
            </div>
          );
        })}
      </div>

      {rejectModal && (
        <ModalShell title="Rechazar ticket" onClose={() => setRejectModal(null)}>
          <p className="text-sm text-slate-600 mb-3">
            Si rechazas, el dueño lo verá y el ticket pasa al siguiente proveedor de su lista.
          </p>
          <textarea
            className="w-full border border-slate-300 rounded-lg p-2 text-sm min-h-[80px]"
            placeholder="Motivo (opcional)"
            value={rejectModal.reason}
            onChange={(e) => setRejectModal({ ...rejectModal, reason: e.target.value })}
          />
          <div className="flex gap-2 mt-3">
            <button type="button" onClick={() => setRejectModal(null)} className="flex-1 py-2 text-sm text-slate-500 font-semibold">Cancelar</button>
            <button
              type="button"
              onClick={doReject}
              disabled={busyId === rejectModal.id}
              className="flex-1 py-2 bg-rose-600 hover:bg-rose-700 text-white font-bold rounded-lg text-sm disabled:opacity-60"
            >
              Confirmar rechazo
            </button>
          </div>
        </ModalShell>
      )}
    </>
  );
};

// ══════════════════════════════════════════════════════════════════════
// Tab: Tickets activos (aceptados, cotizaciones, aprobados)
// ══════════════════════════════════════════════════════════════════════
const ActiveTicketsTab: React.FC<{ onChanged: () => void }> = ({ onChanged }) => {
  const [items, setItems] = useState<ProviderTicketDTO[]>([]);
  const [loading, setLoading] = useState(true);
  const [quotesByTicket, setQuotesByTicket] = useState<Record<string, MaintenanceQuoteDTO[]>>({});
  const [quoteModal, setQuoteModal] = useState<ProviderTicketDTO | null>(null);
  // V67 — fix: tickets asignados directamente por el dueño quedan en
  // AWAITING_PROVIDER_ACCEPT pero no estaban en la cadena de invitaciones,
  // por lo que aparecían en "Mis tickets" sin botones para aceptar/rechazar.
  // Añadimos los mismos controles que están en InvitesTab para cubrir
  // también esa ruta de asignación.
  const [busyId, setBusyId] = useState<string | null>(null);
  const [rejectModal, setRejectModal] = useState<{ id: string; reason: string } | null>(null);
  // V67 — modal para cancelar ticket aceptado (duplicado / ya resuelto / etc.)
  const [cancelModal, setCancelModal] = useState<{ id: string; title: string; reason: string } | null>(null);

  const load = async () => {
    setLoading(true);
    try {
      const mine = await maintenanceProviderService.getMyTickets();
      setItems(mine);
      const quotes: Record<string, MaintenanceQuoteDTO[]> = {};
      for (const t of mine) {
        if (t.status === 'QUOTED' || t.status === 'APPROVED' || t.status === 'COMPLETED') {
          try {
            quotes[t.id] = await maintenanceProviderService.getTicketQuotes(t.id);
          } catch { /* ignore individual failures */ }
        }
      }
      setQuotesByTicket(quotes);
    } finally { setLoading(false); }
  };
  useEffect(() => { load(); }, []);

  const acceptDirect = async (id: string) => {
    // #region agent log
    fetch('http://127.0.0.1:7682/ingest/3f2253a5-d387-4dcd-a5a3-22aec5e3ff2b',{method:'POST',headers:{'Content-Type':'application/json','X-Debug-Session-Id':'93290f'},body:JSON.stringify({sessionId:'93290f',hypothesisId:'ACCEPT',location:'MaintenanceProviderDashboardLayout.tsx:ActiveTicketsTab.acceptDirect',message:'accept:click_from_active_tab',data:{ticketId:id},timestamp:Date.now()})}).catch(()=>{});
    // #endregion
    setBusyId(id);
    try {
      await maintenanceProviderService.acceptTicket(id);
      await load();
      onChanged();
    } catch (e: unknown) {
      const err = e as { response?: { status?: number; data?: { message?: string } } };
      const status = err?.response?.status;
      const msg = err?.response?.data?.message;
      // #region agent log
      fetch('http://127.0.0.1:7682/ingest/3f2253a5-d387-4dcd-a5a3-22aec5e3ff2b',{method:'POST',headers:{'Content-Type':'application/json','X-Debug-Session-Id':'93290f'},body:JSON.stringify({sessionId:'93290f',hypothesisId:'ACCEPT',location:'MaintenanceProviderDashboardLayout.tsx:ActiveTicketsTab.acceptDirect',message:'accept:failed_from_active_tab',data:{ticketId:id,httpStatus:status,errorMessage:msg||null},timestamp:Date.now()})}).catch(()=>{});
      // #endregion
      alert(msg || `No se pudo aceptar el ticket${status ? ` (HTTP ${status})` : ''}.`);
    } finally { setBusyId(null); }
  };

  const doRejectDirect = async () => {
    if (!rejectModal) return;
    // #region agent log
    fetch('http://127.0.0.1:7682/ingest/3f2253a5-d387-4dcd-a5a3-22aec5e3ff2b',{method:'POST',headers:{'Content-Type':'application/json','X-Debug-Session-Id':'93290f'},body:JSON.stringify({sessionId:'93290f',hypothesisId:'REJECT',location:'MaintenanceProviderDashboardLayout.tsx:ActiveTicketsTab.doRejectDirect',message:'reject:click_from_active_tab',data:{ticketId:rejectModal.id,hasReason:!!rejectModal.reason.trim()},timestamp:Date.now()})}).catch(()=>{});
    // #endregion
    setBusyId(rejectModal.id);
    try {
      await maintenanceProviderService.rejectTicket(rejectModal.id, rejectModal.reason.trim() || undefined);
      setRejectModal(null);
      await load();
      onChanged();
    } catch (e: unknown) {
      const err = e as { response?: { status?: number; data?: { message?: string } } };
      const status = err?.response?.status;
      const msg = err?.response?.data?.message;
      // #region agent log
      fetch('http://127.0.0.1:7682/ingest/3f2253a5-d387-4dcd-a5a3-22aec5e3ff2b',{method:'POST',headers:{'Content-Type':'application/json','X-Debug-Session-Id':'93290f'},body:JSON.stringify({sessionId:'93290f',hypothesisId:'REJECT',location:'MaintenanceProviderDashboardLayout.tsx:ActiveTicketsTab.doRejectDirect',message:'reject:failed_from_active_tab',data:{ticketId:rejectModal.id,httpStatus:status,errorMessage:msg||null},timestamp:Date.now()})}).catch(()=>{});
      // #endregion
      alert(msg || `No se pudo rechazar${status ? ` (HTTP ${status})` : ''}.`);
    } finally { setBusyId(null); }
  };

  const doCancel = async () => {
    if (!cancelModal) return;
    const reason = cancelModal.reason.trim();
    if (reason.length < 5) {
      alert('Describe el motivo de la cancelación (mínimo 5 caracteres). Esta razón se compartirá con el dueño y el inquilino.');
      return;
    }
    setBusyId(cancelModal.id);
    try {
      await maintenanceProviderService.cancelTicket(cancelModal.id, reason);
      setCancelModal(null);
      await load();
      onChanged();
    } catch (e: unknown) {
      const err = e as { response?: { status?: number; data?: { message?: string } } };
      const status = err?.response?.status;
      const msg = err?.response?.data?.message;
      alert(msg || `No se pudo cancelar${status ? ` (HTTP ${status})` : ''}.`);
    } finally { setBusyId(null); }
  };

  // V67 — sólo es cancelable mientras no haya compromiso económico (ACCEPTED,
  // QUOTED). Coincide con la regla del backend providerCancel.
  const isCancelable = (status: string) =>
    status === 'AWAITING_PROVIDER_ACCEPT'
    || status === 'ACCEPTED'
    || status === 'QUOTED';

  if (loading) return <div className="animate-pulse h-48 bg-slate-100 rounded-2xl" />;
  if (items.length === 0) {
    return (
      <div className="bg-white rounded-2xl border border-slate-200 p-8 text-center text-slate-500">
        <Wrench className="w-10 h-10 mx-auto text-slate-300 mb-3" />
        <p className="text-sm">No tienes tickets activos.</p>
        <p className="text-xs mt-1">Acepta una invitación para empezar.</p>
      </div>
    );
  }

  return (
    <>
      <div className="space-y-4">
        {items.map((t) => {
          const label = STATUS_LABELS[t.status] || { text: t.status, cls: 'bg-slate-100 text-slate-700' };
          const quotes = quotesByTicket[t.id] || [];
          const urg = URGENCY_LABELS[t.urgency] || { text: t.urgency, cls: 'bg-slate-100 text-slate-600' };
          return (
            <div key={t.id} className="bg-white rounded-2xl border border-slate-200 p-5 shadow-sm">
              <div className="flex items-start justify-between gap-4 mb-3">
                <div className="flex items-start gap-3">
                  <div className="w-10 h-10 rounded-xl bg-orange-100 text-orange-600 flex items-center justify-center shrink-0">
                    <Wrench className="w-5 h-5" />
                  </div>
                  <div>
                    <p className="font-bold text-slate-800">{t.title}</p>
                    <p className="text-xs text-slate-500 flex items-center gap-1 mt-0.5">
                      <Building2 className="w-3 h-3" /> {t.propertyName || t.propertyId} · {t.propertyAddress || ''}
                    </p>
                  </div>
                </div>
                <div className="text-right flex flex-col gap-1 items-end">
                  <span className={`inline-flex items-center px-2 py-1 rounded-lg text-xs font-bold ${label.cls}`}>{label.text}</span>
                  <span className={`inline-flex items-center px-2 py-0.5 rounded-lg text-[10px] font-bold ${urg.cls}`}>{urg.text}</span>
                </div>
              </div>

              {t.description && (
                <p className="text-sm text-slate-600 border-t border-slate-100 pt-3 whitespace-pre-wrap">{t.description}</p>
              )}

              <div className="grid grid-cols-1 md:grid-cols-3 gap-3 pt-3 mt-3 border-t border-slate-100 text-sm">
                <div>
                  <p className="text-[10px] font-bold uppercase text-slate-400">Dueño</p>
                  <p className="font-semibold text-slate-700">{t.ownerName || '—'}</p>
                </div>
                <div>
                  <p className="text-[10px] font-bold uppercase text-slate-400">Abierto</p>
                  <p className="font-medium text-slate-700">{fmtDate(t.createdAt)}</p>
                </div>
                <div>
                  <p className="text-[10px] font-bold uppercase text-slate-400">Aceptado</p>
                  <p className="font-medium text-slate-700">{fmtDate(t.providerAcceptedAt)}</p>
                </div>
              </div>

              {quotes.length > 0 && (
                <div className="mt-3 pt-3 border-t border-slate-100 space-y-1">
                  <p className="text-[10px] font-bold uppercase text-slate-400">Cotizaciones</p>
                  {quotes.map((q) => (
                    <div key={q.id} className="flex items-center justify-between bg-slate-50 rounded-lg px-3 py-2 text-sm">
                      <div>
                        <p className="font-semibold text-slate-800">{fmtMoney(Number(q.amount))}</p>
                        {q.description && <p className="text-xs text-slate-500">{q.description}</p>}
                      </div>
                      <span className={`px-2 py-0.5 rounded-md text-[10px] font-bold ${
                        q.status === 'APPROVED' ? 'bg-emerald-100 text-emerald-700' :
                        q.status === 'REJECTED' ? 'bg-rose-100 text-rose-700' :
                        'bg-amber-100 text-amber-700'
                      }`}>{q.status}</span>
                    </div>
                  ))}
                </div>
              )}

              <div className="flex gap-2 pt-4 flex-wrap">
                {t.status === 'AWAITING_PROVIDER_ACCEPT' && (
                  <>
                    <button
                      type="button"
                      disabled={busyId === t.id}
                      onClick={() => acceptDirect(t.id)}
                      className="px-4 py-2 bg-emerald-600 hover:bg-emerald-700 disabled:opacity-60 text-white font-bold rounded-xl text-sm flex items-center gap-2"
                    >
                      {busyId === t.id ? <Loader2 className="w-4 h-4 animate-spin" /> : <CheckCircle2 className="w-4 h-4" />} Aceptar
                    </button>
                    <button
                      type="button"
                      disabled={busyId === t.id}
                      onClick={() => setRejectModal({ id: t.id, reason: '' })}
                      className="px-4 py-2 bg-white border border-rose-200 hover:bg-rose-50 disabled:opacity-60 text-rose-700 font-bold rounded-xl text-sm flex items-center gap-2"
                    >
                      <XCircle className="w-4 h-4" /> Rechazar
                    </button>
                  </>
                )}
                {t.status === 'ACCEPTED' && (
                  <button
                    type="button"
                    onClick={() => setQuoteModal(t)}
                    className="px-4 py-2 bg-violet-600 hover:bg-violet-700 text-white font-bold rounded-xl text-sm flex items-center gap-2"
                  >
                    <FileText className="w-4 h-4" /> Enviar cotización
                  </button>
                )}
                {t.status === 'QUOTED' && (
                  <span className="text-sm text-slate-500 italic">Esperando aprobación del dueño…</span>
                )}
                {t.status === 'APPROVED' && (
                  <span className="text-sm text-teal-700 font-semibold flex items-center gap-1">
                    <CheckCircle2 className="w-4 h-4" /> Dueño aprobó — procede con el trabajo. El dueño registrará el pago al finalizar.
                  </span>
                )}
                {t.status === 'COMPLETED' && (
                  <span className="text-sm text-emerald-700 font-semibold flex items-center gap-1">
                    <CheckCircle2 className="w-4 h-4" /> Ticket cerrado. Revisa tu comisión en Mis cuentas si aplica.
                  </span>
                )}
                {/* V67 — el proveedor puede cancelar mientras no haya
                    compromiso económico (ACCEPTED, QUOTED). El backend
                    valida defensivamente el estado y rechaza si ya hay pago. */}
                {isCancelable(t.status) && (
                  <button
                    type="button"
                    disabled={busyId === t.id}
                    onClick={() => setCancelModal({ id: t.id, title: t.title, reason: '' })}
                    title="Cancelar este ticket (ej. duplicado, ya resuelto, fuera de tu oficio)"
                    className="px-4 py-2 bg-white border border-slate-300 hover:bg-slate-50 disabled:opacity-60 text-slate-700 font-bold rounded-xl text-sm flex items-center gap-2"
                  >
                    <XCircle className="w-4 h-4 text-slate-400" /> Cancelar ticket
                  </button>
                )}
              </div>
            </div>
          );
        })}
      </div>

      {quoteModal && (
        <QuoteModal ticket={quoteModal} onClose={() => setQuoteModal(null)} onDone={() => { setQuoteModal(null); load(); onChanged(); }} />
      )}

      {rejectModal && (
        <ModalShell title="Rechazar ticket" onClose={() => setRejectModal(null)}>
          <textarea
            value={rejectModal.reason}
            onChange={(e) => setRejectModal({ ...rejectModal, reason: e.target.value })}
            placeholder="Motivo opcional (se comparte con el dueño)"
            className="w-full rounded-xl border border-slate-300 p-3 text-sm min-h-[80px]"
          />
          <div className="flex gap-2 justify-end mt-3">
            <button type="button" onClick={() => setRejectModal(null)} className="px-4 py-2 text-sm text-slate-600 hover:bg-slate-100 rounded-lg">Cancelar</button>
            <button type="button" disabled={busyId === rejectModal.id} onClick={doRejectDirect} className="px-4 py-2 text-sm font-bold text-white bg-rose-600 hover:bg-rose-700 disabled:opacity-60 rounded-lg">
              {busyId === rejectModal.id ? 'Rechazando…' : 'Rechazar ticket'}
            </button>
          </div>
        </ModalShell>
      )}

      {cancelModal && (
        <ModalShell title="Cancelar ticket" onClose={() => setCancelModal(null)}>
          <p className="text-sm text-slate-700 mb-2">
            Cancelarás <strong>"{cancelModal.title}"</strong>. El dueño y el inquilino serán notificados con tu motivo.
          </p>
          <p className="text-xs text-slate-500 mb-3">
            Usa esto cuando detectas que el ticket es duplicado, que el inquilino ya resolvió por su cuenta, o que el caso no corresponde a tu oficio. Si ya hay un pago en curso, no podrás cancelar — comunícalo al dueño directamente.
          </p>
          <textarea
            value={cancelModal.reason}
            onChange={(e) => setCancelModal({ ...cancelModal, reason: e.target.value })}
            placeholder="Motivo (mínimo 5 caracteres) — ej. ticket duplicado del #123, problema ya resuelto por el inquilino, etc."
            className="w-full rounded-xl border border-slate-300 p-3 text-sm min-h-[100px]"
          />
          <div className="flex gap-2 justify-end mt-3">
            <button type="button" onClick={() => setCancelModal(null)} className="px-4 py-2 text-sm text-slate-600 hover:bg-slate-100 rounded-lg">Cerrar</button>
            <button
              type="button"
              disabled={busyId === cancelModal.id || cancelModal.reason.trim().length < 5}
              onClick={doCancel}
              className="px-4 py-2 text-sm font-bold text-white bg-slate-700 hover:bg-slate-800 disabled:opacity-60 rounded-lg"
            >
              {busyId === cancelModal.id ? 'Cancelando…' : 'Cancelar ticket'}
            </button>
          </div>
        </ModalShell>
      )}
    </>
  );
};

const QuoteModal: React.FC<{ ticket: ProviderTicketDTO; onClose: () => void; onDone: () => void }> = ({ ticket, onClose, onDone }) => {
  const [form, setForm] = useState({ amount: '', description: '' });
  const [file, setFile] = useState<File | null>(null);
  const [busy, setBusy] = useState(false);
  const submit = async () => {
    const amount = Number(form.amount);
    if (!amount || amount <= 0) { alert('Monto inválido.'); return; }
    setBusy(true);
    try {
      let evidenceFileId: string | undefined;
      if (file) evidenceFileId = await maintenanceProviderService.uploadFile(file, 'quote-evidence');
      await maintenanceProviderService.submitQuote(ticket.id, {
        amount,
        description: form.description.trim() || undefined,
        evidenceFileId,
      });
      onDone();
    } catch (e: unknown) {
      const msg = (e as { response?: { data?: { message?: string } } })?.response?.data?.message;
      alert(msg || 'Error al enviar cotización.');
    } finally { setBusy(false); }
  };
  const input = 'w-full border border-slate-300 rounded-lg p-2 text-sm';
  return (
    <ModalShell title={`Cotización de ${ticket.title}`} onClose={onClose}>
      <div className="space-y-3">
        <div>
          <label className="text-xs font-semibold text-slate-600 block mb-1">Monto propuesto (MXN) *</label>
          <input className={input} type="number" step="0.01" value={form.amount} onChange={(e) => setForm((f) => ({ ...f, amount: e.target.value }))} />
        </div>
        <div>
          <label className="text-xs font-semibold text-slate-600 block mb-1">Detalle del trabajo</label>
          <textarea className={input + ' min-h-[80px]'} placeholder="Incluye materiales, mano de obra, tiempo estimado…" value={form.description} onChange={(e) => setForm((f) => ({ ...f, description: e.target.value }))} />
        </div>
        <div>
          <label className="text-xs font-semibold text-slate-600 block mb-1">Evidencia (PDF o imagen, opcional)</label>
          <input type="file" accept=".pdf,image/*" onChange={(e) => setFile(e.target.files?.[0] || null)} className="text-xs w-full" />
        </div>
      </div>
      <div className="flex gap-2 mt-4">
        <button type="button" onClick={onClose} className="flex-1 py-2 text-sm text-slate-500 font-semibold">Cancelar</button>
        <button type="button" onClick={submit} disabled={busy} className="flex-1 py-2 bg-violet-600 hover:bg-violet-700 text-white font-bold rounded-lg text-sm disabled:opacity-60 flex items-center justify-center gap-2">
          {busy ? <Loader2 className="w-4 h-4 animate-spin" /> : <Upload className="w-4 h-4" />} Enviar cotización
        </button>
      </div>
    </ModalShell>
  );
};

// ══════════════════════════════════════════════════════════════════════
// V63 — Tab: Confirmar pagos recibidos
// ══════════════════════════════════════════════════════════════════════
//
// El proveedor ve tickets donde el dueño YA subió su comprobante SPEI y
// está esperando que él confirme haberlo recibido (o dispute). Confirmar
// cierra el ticket y marca el expense del dueño como PAID. Disputar vuelve
// el ticket a APPROVED para que el dueño corrija y reintente.
const PaymentConfirmationTab: React.FC<{ onChanged: () => void }> = ({ onChanged }) => {
  const [items, setItems] = useState<ProviderTicketDTO[]>([]);
  const [proofByTicket, setProofByTicket] = useState<Record<string, string | null>>({});
  const [loading, setLoading] = useState(true);
  const [busyId, setBusyId] = useState<string | null>(null);
  const [disputeModal, setDisputeModal] = useState<{ id: string; title: string; reason: string } | null>(null);

  const load = async () => {
    setLoading(true);
    try {
      const tickets = await maintenanceProviderService.getAwaitingPaymentConfirmation();
      setItems(tickets);
      // Cargamos el fileId del comprobante SPEI por ticket en paralelo.
      // Si alguno falla (rara vez), simplemente no mostramos botón.
      const proofs: Record<string, string | null> = {};
      await Promise.all(tickets.map(async t => {
        try { proofs[t.id] = await maintenanceProviderService.getPaymentProofFileId(t.id); }
        catch { proofs[t.id] = null; }
      }));
      setProofByTicket(proofs);
    } finally { setLoading(false); }
  };
  useEffect(() => { load(); }, []);

  const openProof = async (fileId: string) => {
    try { await openFileAttachment(fileId); }
    catch (err) { alert(describeSecureFileError(err)); }
  };

  const confirm = async (t: ProviderTicketDTO) => {
    if (!window.confirm(
      `Confirmar que recibiste el pago por "${t.title}".\n\n`
      + `Con esto se cierra el ticket y el dueño puede cerrar su contabilidad. `
      + `Si aún no ves el SPEI en tu banco, mejor disputa en lugar de confirmar.`)) return;
    setBusyId(t.id);
    try {
      await maintenanceProviderService.confirmPaymentReceived(t.id);
      await load();
      onChanged();
    } catch (e: unknown) {
      const msg = (e as { response?: { data?: { message?: string } } })?.response?.data?.message;
      alert(msg || 'No se pudo confirmar el pago.');
    } finally { setBusyId(null); }
  };

  const doDispute = async () => {
    if (!disputeModal) return;
    const reason = disputeModal.reason.trim();
    if (reason.length < 5) { alert('Describe el motivo (mínimo 5 caracteres).'); return; }
    setBusyId(disputeModal.id);
    try {
      await maintenanceProviderService.disputePayment(disputeModal.id, reason);
      setDisputeModal(null);
      await load();
      onChanged();
    } catch (e: unknown) {
      const msg = (e as { response?: { data?: { message?: string } } })?.response?.data?.message;
      alert(msg || 'No se pudo registrar la disputa.');
    } finally { setBusyId(null); }
  };

  if (loading) return <div className="animate-pulse h-48 bg-slate-100 rounded-2xl" />;
  if (items.length === 0) {
    return (
      <div className="bg-white rounded-2xl border border-slate-200 p-8 text-center text-slate-500">
        <CheckCircle2 className="w-10 h-10 mx-auto text-slate-300 mb-3" />
        <p className="text-sm">No tienes pagos esperando tu confirmación.</p>
      </div>
    );
  }

  return (
    <>
      <div className="space-y-4">
        {items.map(t => {
          // V63 — el comprobante SPEI del dueño vive en expenses.payment_proof_file_id,
          // no en el ticket. Lo obtuvimos en `load()` via getPaymentProofFileId.
          const proofFileId = proofByTicket[t.id];
          const busy = busyId === t.id;
          return (
            <div key={t.id} className="bg-white rounded-2xl border border-amber-200 p-5 shadow-sm">
              <div className="flex items-start justify-between gap-4 mb-3">
                <div className="flex items-start gap-3">
                  <div className="w-10 h-10 rounded-xl bg-amber-100 text-amber-600 flex items-center justify-center shrink-0">
                    <Landmark className="w-5 h-5" />
                  </div>
                  <div>
                    <p className="font-bold text-slate-800">{t.title}</p>
                    <p className="text-xs text-slate-500 flex items-center gap-1 mt-0.5">
                      <Building2 className="w-3 h-3" /> {t.propertyName || t.propertyId}
                    </p>
                  </div>
                </div>
                <span className="inline-flex items-center gap-1 px-2 py-1 rounded-lg text-xs font-bold bg-amber-100 text-amber-800">
                  <Clock className="w-3 h-3" /> Pendiente confirmar
                </span>
              </div>

              <div className="p-3 rounded-lg bg-slate-50 border border-slate-100 text-sm text-slate-700 mb-3">
                El dueño registró un pago SPEI para este ticket. <strong>Revisa tu cuenta bancaria</strong> y confirma si el monto ya llegó. Si no lo ves o el monto está mal, elige "No lo recibí" y el dueño volverá a intentarlo.
              </div>

              {proofFileId ? (
                <div className="mb-3">
                  <button
                    type="button"
                    onClick={() => openProof(proofFileId)}
                    className="inline-flex items-center gap-1.5 px-3 py-1.5 rounded-lg border border-indigo-200 bg-indigo-50 hover:bg-indigo-100 text-xs font-semibold text-indigo-700"
                  >
                    <FileText className="w-3.5 h-3.5" /> Ver comprobante SPEI del dueño
                  </button>
                </div>
              ) : (
                <p className="mb-3 text-xs text-amber-700 bg-amber-50 border border-amber-200 rounded p-2">
                  El dueño aún no adjuntó un comprobante visible. Puedes confirmar o disputar cuando corresponda.
                </p>
              )}

              <div className="flex items-center gap-2 flex-wrap">
                <button
                  type="button"
                  onClick={() => setDisputeModal({ id: t.id, title: t.title, reason: '' })}
                  disabled={busy}
                  className="inline-flex items-center gap-1.5 px-3 py-2 rounded-lg text-sm font-medium border border-rose-200 text-rose-700 hover:bg-rose-50 disabled:opacity-40"
                >
                  <XCircle className="w-4 h-4" /> No lo recibí / disputar
                </button>
                <button
                  type="button"
                  onClick={() => confirm(t)}
                  disabled={busy}
                  className="inline-flex items-center gap-1.5 px-3 py-2 rounded-lg text-sm font-semibold bg-emerald-600 text-white hover:bg-emerald-700 disabled:opacity-40"
                >
                  {busy ? <Loader2 className="w-4 h-4 animate-spin" /> : <CheckCircle2 className="w-4 h-4" />} Ya lo recibí — confirmar
                </button>
              </div>
            </div>
          );
        })}
      </div>

      {disputeModal && (
        <ModalShell title={`Disputar pago — ${disputeModal.title}`} onClose={() => setDisputeModal(null)}>
          <p className="text-sm text-slate-600 mb-3">
            Cuéntale al dueño qué pasó (por ejemplo: "aún no veo el SPEI en mi cuenta", "el monto es menor",
            "la CLABE no corresponde"). El ticket volverá a "Por pagar" en su panel para que lo reintente.
          </p>
          <textarea
            className="w-full text-sm p-2.5 border border-slate-300 rounded-lg focus:ring-2 focus:ring-rose-500 outline-none min-h-[88px]"
            placeholder="Motivo de la disputa..."
            maxLength={500}
            value={disputeModal.reason}
            onChange={e => setDisputeModal({ ...disputeModal, reason: e.target.value })}
          />
          <div className="flex gap-2 pt-3">
            <button type="button" onClick={() => setDisputeModal(null)} className="flex-1 py-2 text-sm text-slate-500 font-semibold">
              Cancelar
            </button>
            <button
              type="button"
              onClick={doDispute}
              disabled={busyId === disputeModal.id || disputeModal.reason.trim().length < 5}
              className="flex-1 py-2 bg-rose-600 hover:bg-rose-700 text-white font-bold rounded-lg text-sm disabled:opacity-50 flex items-center justify-center gap-2"
            >
              {busyId === disputeModal.id ? <Loader2 className="w-4 h-4 animate-spin" /> : <XCircle className="w-4 h-4" />}
              Confirmar disputa
            </button>
          </div>
        </ModalShell>
      )}
    </>
  );
};

// ── Modal shell ─────────────────────────────────────────────────────────────
const ModalShell: React.FC<{ title: string; onClose: () => void; children: React.ReactNode }> = ({ title, onClose, children }) => (
  <div className="fixed inset-0 z-[120] flex items-center justify-center p-4">
    <div className="absolute inset-0 bg-slate-900/70 backdrop-blur-sm" onClick={onClose} />
    <div className="relative bg-white rounded-2xl w-full max-w-md shadow-2xl border border-slate-200">
      <div className="px-5 py-3 border-b border-slate-100 flex items-center justify-between">
        <h3 className="font-bold text-slate-800">{title}</h3>
        <button type="button" onClick={onClose} className="p-1.5 text-slate-400 hover:text-slate-700 rounded-lg">
          <X className="w-4 h-4" />
        </button>
      </div>
      <div className="p-5">{children}</div>
    </div>
  </div>
);

// ══════════════════════════════════════════════════════════════════════
// Layout principal
// ══════════════════════════════════════════════════════════════════════
export const MaintenanceProviderDashboardLayout: React.FC = () => {
  const navigate = useNavigate();
  const { user, logout } = useAuth();
  const [tab, setTab] = useState<ProviderTab>('INVITES');
  const [refreshKey, setRefreshKey] = useState(0);
  const [prefsOpen, setPrefsOpen] = useState(false);
  const [prefsTab, setPrefsTab] = useState<'PREFS' | 'HISTORY'>('PREFS');
  // V63 — Onboarding bancario obligatorio. Al primer login el wizard bloquea
  // la UI hasta que el agente capture CLABE + banco + titular. Se consulta
  // /bank-account/status en cada mount/refresh para decidir.
  const [onboardingRequired, setOnboardingRequired] = useState<boolean | null>(null);

  const handleLogout = async () => { await logout(); navigate('/login'); };
  const onChanged = () => setRefreshKey((k) => k + 1);

  useEffect(() => {
    let cancelled = false;
    agentBankAccountService.getStatus('MAINTENANCE_PROVIDER')
      .then(s => {
        if (!cancelled) {
          setOnboardingRequired(!s.complete);
          // #region agent log
          fetch('http://127.0.0.1:7682/ingest/3f2253a5-d387-4dcd-a5a3-22aec5e3ff2b',{method:'POST',headers:{'Content-Type':'application/json','X-Debug-Session-Id':'93290f'},body:JSON.stringify({sessionId:'93290f',hypothesisId:'STATUS',location:'MaintenanceProviderDashboardLayout.tsx:useEffect',message:'onboarding:status',data:{complete:s.complete,accountActive:s.accountActive,onboardingRequired:!s.complete},timestamp:Date.now()})}).catch(()=>{});
          // #endregion
        }
      })
      .catch(() => {
        if (!cancelled) {
          setOnboardingRequired(true);
          // #region agent log
          fetch('http://127.0.0.1:7682/ingest/3f2253a5-d387-4dcd-a5a3-22aec5e3ff2b',{method:'POST',headers:{'Content-Type':'application/json','X-Debug-Session-Id':'93290f'},body:JSON.stringify({sessionId:'93290f',hypothesisId:'STATUS',location:'MaintenanceProviderDashboardLayout.tsx:useEffect',message:'onboarding:status_fetch_failed',data:{onboardingRequired:true},timestamp:Date.now()})}).catch(()=>{});
          // #endregion
        }
      }); // conservador
    return () => { cancelled = true; };
  }, [refreshKey]);

  const tabs = useMemo(() => ([
    { key: 'INVITES' as const, label: 'Invitaciones', icon: Bell },
    { key: 'ACTIVE' as const, label: 'Mis tickets', icon: Wrench },
    { key: 'PAYMENT_CONFIRM' as const, label: 'Confirmar pagos', icon: CheckCircle2 },
    { key: 'BANK' as const, label: 'Cuenta bancaria', icon: Landmark },
  ]), []);

  return (
    <div className="min-h-screen bg-slate-50 font-sans flex flex-col">
      <header className="bg-slate-900 text-white border-b border-slate-800">
        <div className="max-w-6xl mx-auto px-4 sm:px-6 lg:px-8 h-16 flex items-center justify-between">
          <div className="flex items-center gap-3">
            <div className="w-8 h-8 rounded-lg bg-orange-500/20 text-orange-300 flex items-center justify-center">
              <HardHat className="w-5 h-5" />
            </div>
            <span className="font-bold tracking-wide text-lg">Portal Proveedor de Mantenimiento</span>
          </div>
          <div className="flex items-center gap-3 flex-wrap justify-end">
            <span className="text-sm font-medium text-slate-300 hidden sm:block">{user?.name}</span>
            <button type="button" onClick={onChanged} className="px-2.5 py-1.5 rounded-lg text-sm text-orange-200 hover:bg-slate-800" title="Recargar">
              <RefreshCw className="w-4 h-4" />
            </button>
            <button type="button" onClick={() => setPrefsOpen(true)} className="flex items-center gap-2 px-3 py-1.5 rounded-lg text-sm font-bold text-orange-300 hover:bg-slate-800">
              <Bell className="w-4 h-4" /> <span className="hidden sm:inline">Notificaciones</span>
            </button>
            <button type="button" onClick={() => void handleLogout()} className="flex items-center gap-2 px-3 py-1.5 rounded-lg text-sm font-bold text-rose-400 hover:bg-slate-800">
              <LogOut className="w-4 h-4" /> <span className="hidden sm:inline">Salir</span>
            </button>
          </div>
        </div>
      </header>

      <nav className="bg-white border-b border-slate-200 sticky top-0 z-10">
        <div className="max-w-6xl mx-auto px-4 sm:px-6 lg:px-8 flex gap-1 overflow-x-auto">
          {tabs.map((t) => {
            const Icon = t.icon;
            return (
              <button
                key={t.key}
                type="button"
                onClick={() => setTab(t.key)}
                className={`flex items-center gap-2 px-4 py-3 text-sm font-bold border-b-2 whitespace-nowrap ${
                  tab === t.key ? 'text-orange-700 border-orange-500' : 'text-slate-500 border-transparent hover:text-slate-700'
                }`}
              >
                <Icon className="w-4 h-4" /> {t.label}
              </button>
            );
          })}
        </div>
      </nav>

      <main className="flex-1 max-w-6xl mx-auto px-4 sm:px-6 lg:px-8 py-6 w-full">
        <div key={`${tab}-${refreshKey}`}>
          {tab === 'INVITES' && <InvitesTab onChanged={onChanged} />}
          {tab === 'ACTIVE' && <ActiveTicketsTab onChanged={onChanged} />}
          {tab === 'PAYMENT_CONFIRM' && <PaymentConfirmationTab onChanged={onChanged} />}
          {tab === 'BANK' && <BankAccountTab service={maintenanceProviderService} />}
        </div>
      </main>

      {onboardingRequired && (
        <AgentOnboardingWizard
          role="MAINTENANCE_PROVIDER"
          blocking
          onCompleted={() => {
            setOnboardingRequired(false);
            onChanged();
          }}
        />
      )}

      {prefsOpen && (
        <div className="fixed inset-0 z-[140] flex items-center justify-center p-4">
          <div className="absolute inset-0 bg-slate-900/70 backdrop-blur-sm" onClick={() => setPrefsOpen(false)} />
          <div className="relative bg-white rounded-2xl w-full max-w-3xl max-h-[90vh] shadow-2xl border border-slate-200 overflow-hidden flex flex-col">
            <div className="px-6 pt-4 pb-0 border-b border-slate-100 bg-slate-50 flex items-center justify-between gap-4">
              <div>
                <h3 className="text-lg font-bold text-slate-800 flex items-center gap-2"><Bell className="w-5 h-5 text-orange-500" /> Mis notificaciones</h3>
                <div className="flex gap-2 mt-2">
                  <button type="button" onClick={() => setPrefsTab('PREFS')} className={`px-3 py-1.5 text-sm font-semibold border-b-2 -mb-px ${prefsTab === 'PREFS' ? 'text-orange-600 border-orange-500' : 'text-slate-500 border-transparent hover:text-slate-700'}`}>
                    Preferencias
                  </button>
                  <button type="button" onClick={() => setPrefsTab('HISTORY')} className={`px-3 py-1.5 text-sm font-semibold border-b-2 -mb-px ${prefsTab === 'HISTORY' ? 'text-orange-600 border-orange-500' : 'text-slate-500 border-transparent hover:text-slate-700'}`}>
                    Historial
                  </button>
                </div>
              </div>
              <button type="button" onClick={() => setPrefsOpen(false)} className="p-1.5 rounded-lg text-slate-400 hover:text-slate-700 hover:bg-slate-100">
                <X className="w-5 h-5" />
              </button>
            </div>
            <div className="p-6 overflow-y-auto">
              {prefsTab === 'PREFS' ? <NotificationPreferencesPanel embedded /> : <NotificationHistoryPanel mode="me" embedded />}
            </div>
          </div>
        </div>
      )}
    </div>
  );
};
