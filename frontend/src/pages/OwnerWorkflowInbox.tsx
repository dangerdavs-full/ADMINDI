import React, { useCallback, useEffect, useMemo, useState } from 'react';
import {
  Wrench,
  CheckCircle2,
  XCircle,
  DollarSign,
  UserCheck,
  Loader2,
  AlertCircle,
  FileText,
  Upload,
  HomeIcon,
  RefreshCw,
  Receipt,
  Clock3,
  Landmark,
  Copy,
} from 'lucide-react';
import {
  ownerWorkflowService,
  MaintenanceTicketDTO,
  MaintenanceQuoteDTO,
  ProspectSubmissionDTO,
  AgentCommissionDTO,
} from '../services/ownerWorkflowService';
import { ownerTeamService, OwnerProviderLink } from '../services/ownerTeamService';
import { propertyService, PropertyDTO } from '../services/propertyService';
import { paymentService } from '../services/paymentService';
import { openFileAttachment, describeSecureFileError } from '../services/secureFileService';
import { RentProofsPanel } from './RentProofsPanel';
import { banxicoInstitutionService, BanxicoInstitution } from '../services/banxicoInstitutionService';

type InboxTab = 'RENT_PROOFS' | 'MAINT_AUTH' | 'MAINT_QUOTE' | 'MAINT_PAY' | 'MAINT_PAY_CONFIRM' | 'PROSPECTS' | 'COMMISSIONS';

const TABS: Array<{ id: InboxTab; label: string; icon: React.ReactNode }> = [
  { id: 'RENT_PROOFS', label: 'Comprobantes de renta', icon: <Receipt className="w-4 h-4" /> },
  { id: 'MAINT_AUTH', label: 'Autorizar mantenimiento', icon: <Wrench className="w-4 h-4" /> },
  { id: 'MAINT_QUOTE', label: 'Aprobar cotizaciones', icon: <FileText className="w-4 h-4" /> },
  { id: 'MAINT_PAY', label: 'Pagar proveedor', icon: <DollarSign className="w-4 h-4" /> },
  { id: 'MAINT_PAY_CONFIRM', label: 'Esperando confirmación', icon: <Clock3 className="w-4 h-4" /> },
  { id: 'PROSPECTS', label: 'Prospectos de inquilino', icon: <UserCheck className="w-4 h-4" /> },
  { id: 'COMMISSIONS', label: 'Comisiones de agente', icon: <HomeIcon className="w-4 h-4" /> },
];

const currency = (n?: number | null) =>
  n == null ? '—' : n.toLocaleString('es-MX', { style: 'currency', currency: 'MXN' });

// ─── Helpers UI ──────────────────────────────────────────────────────────────

const EmptyState: React.FC<{ icon: React.ReactNode; title: string; subtitle: string }> = ({ icon, title, subtitle }) => (
  <div className="border-2 border-dashed border-slate-200 rounded-xl py-10 text-center">
    <div className="inline-flex w-12 h-12 bg-slate-50 rounded-full items-center justify-center text-slate-400 mb-3">{icon}</div>
    <p className="text-sm font-medium text-slate-700">{title}</p>
    <p className="text-xs text-slate-500 mt-1">{subtitle}</p>
  </div>
);

const LoadingBlock: React.FC = () => (
  <div className="flex items-center gap-2 text-sm text-slate-500 justify-center py-10">
    <Loader2 className="w-4 h-4 animate-spin" /> Cargando…
  </div>
);

const ErrorBlock: React.FC<{ msg: string }> = ({ msg }) => (
  <div className="p-3 rounded-lg bg-red-50 border border-red-200 text-sm text-red-700 flex gap-2 items-start">
    <AlertCircle className="w-4 h-4 flex-shrink-0 mt-0.5" /> <span>{msg}</span>
  </div>
);

// ─── Tab: Autorizar tickets de mantenimiento ────────────────────────────────

const MaintenanceAuthTab: React.FC<{ onChanged: () => void }> = ({ onChanged }) => {
  const [tickets, setTickets] = useState<MaintenanceTicketDTO[]>([]);
  const [properties, setProperties] = useState<PropertyDTO[]>([]);
  const [providers, setProviders] = useState<OwnerProviderLink[]>([]);
  const [loading, setLoading] = useState(true);
  const [busyId, setBusyId] = useState<string | null>(null);
  const [err, setErr] = useState<string | null>(null);
  // Selector de provider por ticket (undefined = cadena por prioridades).
  const [providerByTicket, setProviderByTicket] = useState<Record<string, string>>({});

  const propertyById = useMemo(() => {
    const m = new Map<string, PropertyDTO>();
    properties.forEach(p => p.id && m.set(p.id, p));
    return m;
  }, [properties]);

  const load = useCallback(async () => {
    setLoading(true); setErr(null);
    try {
      const [t, props, links] = await Promise.all([
        ownerWorkflowService.getPendingAuthTickets(),
        propertyService.getMyProperties(),
        ownerTeamService.getProviderLinks(),
      ]);
      setTickets(t);
      setProperties(props);
      setProviders(links.filter(l => l.providerType === 'MAINTENANCE_PROVIDER' && l.assignmentActive));
    } catch (e: any) {
      setErr(e?.response?.data?.message ?? 'No pude cargar los tickets.');
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => { load(); }, [load]);

  const authorize = async (t: MaintenanceTicketDTO) => {
    const chosen = providerByTicket[t.id];
    const txt = chosen
      ? `Autorizar y asignar directamente al proveedor seleccionado. El proveedor recibirá la notificación con 72h para aceptar.`
      : `Autorizar y enviar al primer proveedor de tu cadena de prioridades. 72h por proveedor.`;
    if (!window.confirm(txt)) return;
    setBusyId(t.id);
    try {
      await ownerWorkflowService.authorizeTicket(t.id, chosen);
      await load();
      onChanged();
    } catch (e: any) {
      alert(e?.response?.data?.message ?? 'Error al autorizar.');
    } finally {
      setBusyId(null);
    }
  };

  const reject = async (t: MaintenanceTicketDTO) => {
    const reason = window.prompt('Motivo para rechazar (se avisa al inquilino):');
    if (reason == null || reason.trim() === '') return;
    setBusyId(t.id);
    try {
      await ownerWorkflowService.rejectTicket(t.id, reason);
      await load();
      onChanged();
    } catch (e: any) {
      alert(e?.response?.data?.message ?? 'Error al rechazar.');
    } finally {
      setBusyId(null);
    }
  };

  if (loading) return <LoadingBlock />;
  if (err) return <ErrorBlock msg={err} />;
  if (tickets.length === 0) {
    return (
      <EmptyState
        icon={<Wrench className="w-5 h-5" />}
        title="Sin mantenimientos pendientes"
        subtitle="Cuando un inquilino abra un ticket aparecerá aquí para que lo autorices."
      />
    );
  }

  return (
    <ul className="space-y-3">
      {tickets.map(t => {
        const prop = propertyById.get(t.propertyId);
        const photos = (() => {
          try { return t.photoFileIdsJson ? (JSON.parse(t.photoFileIdsJson) as string[]) : []; }
          catch { return []; }
        })();
        const busy = busyId === t.id;
        return (
          <li key={t.id} className="bg-white border border-slate-200 rounded-xl p-4">
            <div className="flex items-start justify-between gap-4">
              <div className="min-w-0 flex-1">
                <div className="flex items-center gap-2">
                  <span className="px-2 py-0.5 rounded-md bg-amber-100 text-amber-800 text-[10px] font-bold uppercase tracking-wide">
                    {t.urgency ?? 'Normal'}
                  </span>
                  <span className="text-xs text-slate-500">
                    {t.createdAt ? new Date(t.createdAt).toLocaleString() : ''}
                  </span>
                </div>
                <h4 className="mt-1 font-semibold text-slate-900">{t.title}</h4>
                <p className="text-sm text-slate-600 mt-0.5">{prop?.name ?? t.propertyId}</p>
                {t.description && (
                  <p className="text-sm text-slate-700 mt-2 whitespace-pre-wrap">{t.description}</p>
                )}
                {photos.length > 0 && (
                  <div className="mt-2">
                    <p className="text-xs text-slate-500 mb-1">Fotos adjuntas ({photos.length}):</p>
                    <div className="flex flex-wrap gap-2">
                      {photos.map((fid, i) => (
                        <button
                          key={fid}
                          type="button"
                          onClick={async () => {
                            try { await openFileAttachment(fid); }
                            catch (err) { alert(describeSecureFileError(err)); }
                          }}
                          className="inline-flex items-center gap-1.5 px-2.5 py-1 rounded-md border border-slate-200 bg-white hover:bg-slate-50 text-xs font-medium text-slate-700"
                        >
                          <FileText className="w-3.5 h-3.5 text-amber-600" /> Foto {i + 1}
                        </button>
                      ))}
                    </div>
                  </div>
                )}
              </div>
            </div>

            <div className="mt-3 pt-3 border-t border-slate-100 flex flex-col sm:flex-row sm:items-end gap-3">
              <div className="flex-1">
                <label className="block text-xs font-semibold text-slate-600 uppercase tracking-wide mb-1">
                  Proveedor (opcional)
                </label>
                <select
                  value={providerByTicket[t.id] ?? ''}
                  onChange={e => setProviderByTicket(prev => ({ ...prev, [t.id]: e.target.value }))}
                  disabled={busy}
                  className="w-full px-3 py-1.5 text-sm rounded-lg border border-slate-200 bg-white"
                >
                  <option value="">Usar cadena por prioridades</option>
                  {providers.map(p => (
                    <option key={p.providerUserId} value={p.providerUserId}>{p.name}</option>
                  ))}
                </select>
              </div>
              <div className="flex gap-2">
                <button
                  onClick={() => reject(t)}
                  disabled={busy}
                  className="inline-flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-sm font-medium border border-red-200 text-red-700 hover:bg-red-50 disabled:opacity-40"
                >
                  <XCircle className="w-4 h-4" /> Rechazar
                </button>
                <button
                  onClick={() => authorize(t)}
                  disabled={busy}
                  className="inline-flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-sm font-semibold bg-emerald-600 text-white hover:bg-emerald-700 disabled:opacity-40"
                >
                  {busy ? <Loader2 className="w-4 h-4 animate-spin" /> : <CheckCircle2 className="w-4 h-4" />} Autorizar
                </button>
              </div>
            </div>
          </li>
        );
      })}
    </ul>
  );
};

// ─── Tab: Aprobar cotizaciones ─────────────────────────────────────────────

const QuoteApprovalTab: React.FC<{ onChanged: () => void }> = ({ onChanged }) => {
  const [quotes, setQuotes] = useState<MaintenanceQuoteDTO[]>([]);
  const [tickets, setTickets] = useState<Map<string, MaintenanceTicketDTO>>(new Map());
  const [loading, setLoading] = useState(true);
  const [busyId, setBusyId] = useState<string | null>(null);
  const [err, setErr] = useState<string | null>(null);

  const load = useCallback(async () => {
    setLoading(true); setErr(null);
    try {
      const q = await ownerWorkflowService.getPendingApprovalQuotes();
      setQuotes(q);
      // Cargar contexto (tickets relacionados) en paralelo.
      const uniqTicketIds = Array.from(new Set(q.map(x => x.ticketId)));
      const tMap = new Map<string, MaintenanceTicketDTO>();
      const all = await ownerWorkflowService.getAllTickets();
      all.filter(t => uniqTicketIds.includes(t.id)).forEach(t => tMap.set(t.id, t));
      setTickets(tMap);
    } catch (e: any) {
      setErr(e?.response?.data?.message ?? 'No pude cargar las cotizaciones.');
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => { load(); }, [load]);

  const approve = async (q: MaintenanceQuoteDTO) => {
    if (!window.confirm(`Aprobar cotización por ${currency(q.amount)}?`)) return;
    setBusyId(q.id);
    try {
      await ownerWorkflowService.approveQuote(q.id);
      await load();
      onChanged();
    } catch (e: any) {
      alert(e?.response?.data?.message ?? 'Error al aprobar.');
    } finally { setBusyId(null); }
  };

  const reject = async (q: MaintenanceQuoteDTO) => {
    const reason = window.prompt('Motivo del rechazo (el proveedor lo verá):');
    if (!reason) return;
    setBusyId(q.id);
    try {
      await ownerWorkflowService.rejectQuote(q.id, reason);
      await load();
      onChanged();
    } catch (e: any) {
      alert(e?.response?.data?.message ?? 'Error al rechazar.');
    } finally { setBusyId(null); }
  };

  if (loading) return <LoadingBlock />;
  if (err) return <ErrorBlock msg={err} />;
  if (quotes.length === 0) {
    return (
      <EmptyState
        icon={<FileText className="w-5 h-5" />}
        title="Sin cotizaciones por aprobar"
        subtitle="Aparecerán aquí cuando el proveedor envíe su cotización."
      />
    );
  }

  return (
    <ul className="space-y-3">
      {quotes.map(q => {
        const t = tickets.get(q.ticketId);
        const busy = busyId === q.id;
        return (
          <li key={q.id} className="bg-white border border-slate-200 rounded-xl p-4">
            <div className="flex items-start justify-between gap-4">
              <div className="min-w-0 flex-1">
                <h4 className="font-semibold text-slate-900">{t?.title ?? q.ticketId}</h4>
                <p className="text-2xl font-bold text-emerald-600 mt-1">{currency(q.amount)}</p>
                {q.description && (
                  <p className="text-sm text-slate-700 mt-2 whitespace-pre-wrap">{q.description}</p>
                )}
                {q.evidenceFileId && (
                  <button
                    type="button"
                    onClick={async () => {
                      try { await openFileAttachment(q.evidenceFileId!); }
                      catch (err) { alert(describeSecureFileError(err)); }
                    }}
                    className="mt-2 inline-flex items-center gap-1.5 px-3 py-1.5 rounded-lg border border-indigo-200 bg-indigo-50 hover:bg-indigo-100 text-sm font-medium text-indigo-700"
                  >
                    <FileText className="w-4 h-4" /> Ver cotización adjunta (PDF/imagen)
                  </button>
                )}
                <p className="text-xs text-slate-500 mt-2">
                  Enviada {q.submittedAt ? new Date(q.submittedAt).toLocaleString() : '—'}
                </p>
              </div>
              <div className="flex gap-2 flex-shrink-0">
                <button onClick={() => reject(q)} disabled={busy}
                  className="inline-flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-sm font-medium border border-red-200 text-red-700 hover:bg-red-50 disabled:opacity-40">
                  <XCircle className="w-4 h-4" /> Rechazar
                </button>
                <button onClick={() => approve(q)} disabled={busy}
                  className="inline-flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-sm font-semibold bg-emerald-600 text-white hover:bg-emerald-700 disabled:opacity-40">
                  {busy ? <Loader2 className="w-4 h-4 animate-spin" /> : <CheckCircle2 className="w-4 h-4" />} Aprobar
                </button>
              </div>
            </div>
          </li>
        );
      })}
    </ul>
  );
};

// ─── Tab: Pagar al proveedor (SPEI) ────────────────────────────────────────

// V63 — datos bancarios del proveedor asignado (los muestra el tab de pago
// para que el dueño cotege antes de transferir).
interface ProviderBankPreview {
  providerName: string;
  accountActive: boolean;
  clabeMasked?: string;
  bankName?: string;
  accountHolder?: string;
  validationStatus?: string;
}

const TicketPayTab: React.FC<{ onChanged: () => void }> = ({ onChanged }) => {
  const [tickets, setTickets] = useState<MaintenanceTicketDTO[]>([]);
  const [quotesByTicket, setQuotesByTicket] = useState<Record<string, MaintenanceQuoteDTO[]>>({});
  const [bankByTicket, setBankByTicket] = useState<Record<string, ProviderBankPreview>>({});
  const [loading, setLoading] = useState(true);
  const [busyId, setBusyId] = useState<string | null>(null);
  const [err, setErr] = useState<string | null>(null);
  const [form, setForm] = useState<Record<string, { amount: string; file: File | null }>>({});

  const load = useCallback(async () => {
    setLoading(true); setErr(null);
    try {
      const t = await ownerWorkflowService.getReadyToPayTickets();
      setTickets(t);
      const entries = await Promise.all(t.map(async x => [x.id, await ownerWorkflowService.getTicketQuotes(x.id)] as const));
      const map: Record<string, MaintenanceQuoteDTO[]> = {};
      entries.forEach(([id, qs]) => { map[id] = qs; });
      setQuotesByTicket(map);
      // V63 — bank preview por ticket. Falla silenciosamente si el provider
      // aún no tiene datos (el dueño verá el chip "sin CLABE registrada").
      const banks: Record<string, ProviderBankPreview> = {};
      await Promise.all(t.map(async tk => {
        try {
          banks[tk.id] = await ownerWorkflowService.getProviderBankPreview(tk.id);
        } catch { /* deja el slot vacío */ }
      }));
      setBankByTicket(banks);
      // Pre-llenamos el monto con la cotización aprobada.
      const pre: Record<string, { amount: string; file: File | null }> = {};
      t.forEach(tk => {
        const approved = (map[tk.id] ?? []).find(q => q.status === 'APPROVED');
        pre[tk.id] = { amount: approved ? String(approved.amount) : '', file: null };
      });
      setForm(pre);
    } catch (e: any) {
      setErr(e?.response?.data?.message ?? 'No pude cargar los tickets.');
    } finally { setLoading(false); }
  }, []);

  useEffect(() => { load(); }, [load]);

  const pay = async (t: MaintenanceTicketDTO) => {
    const st = form[t.id];
    const bank = bankByTicket[t.id];
    if (!bank?.accountActive) {
      alert('El proveedor aún no tiene CLABE + banco + titular registrados. No puedes pagarle hasta que complete su onboarding.');
      return;
    }
    if (!st?.file) { alert('Sube el comprobante SPEI (PDF o imagen).'); return; }
    const n = Number(st.amount);
    if (!st.amount || Number.isNaN(n) || n <= 0) { alert('Monto pagado inválido.'); return; }
    // V63 — confirmación severa antes de enviar SPEI: recordar a quién va.
    const destino = `${bank.accountHolder ?? bank.providerName} — ${bank.bankName ?? '(banco)'} — CLABE ${bank.clabeMasked ?? '****'}`;
    if (!window.confirm(
      `Vas a registrar una transferencia SPEI por ${currency(n)} a:\n\n${destino}\n\n`
      + `El proveedor tendrá que confirmar en su panel que efectivamente recibió el dinero antes de que el ticket quede cerrado contablemente.\n\n`
      + `¿Continuar?`)) return;
    setBusyId(t.id);
    try {
      const fileId = await ownerWorkflowService.uploadFile(st.file, 'spei-proof');
      await ownerWorkflowService.payAndCloseTicket(t.id, n, fileId);
      await load();
      onChanged();
    } catch (e: any) {
      alert(e?.response?.data?.message ?? 'Error al registrar el pago.');
    } finally { setBusyId(null); }
  };

  const copy = async (text?: string) => {
    if (!text) return;
    try { await navigator.clipboard.writeText(text); } catch { /* noop */ }
  };

  if (loading) return <LoadingBlock />;
  if (err) return <ErrorBlock msg={err} />;
  if (tickets.length === 0) {
    return (
      <EmptyState
        icon={<DollarSign className="w-5 h-5" />}
        title="Sin pagos pendientes"
        subtitle="Los tickets con cotización aprobada aparecerán aquí para pagar con SPEI."
      />
    );
  }

  return (
    <ul className="space-y-3">
      {tickets.map(t => {
        const approved = (quotesByTicket[t.id] ?? []).find(q => q.status === 'APPROVED');
        const st = form[t.id] ?? { amount: '', file: null };
        const busy = busyId === t.id;
        const bank = bankByTicket[t.id];
        const discountNote = t.platformDiscountAmount
          ? `Crédito plataforma: ${currency(t.platformDiscountAmount)} (${((t.platformDiscountPct ?? 0) * 100).toFixed(0)}%)`
          : null;
        return (
          <li key={t.id} className="bg-white border border-slate-200 rounded-xl p-4">
            <div>
              <h4 className="font-semibold text-slate-900">{t.title}</h4>
              <p className="text-xs text-slate-500">Cotización aprobada: <strong>{currency(approved?.amount)}</strong></p>
              {discountNote && <p className="text-xs text-indigo-600 mt-1">{discountNote}</p>}
              {t.rejectionReason && t.rejectionReason.startsWith('[DISPUTA PAGO]') && (
                <div className="mt-2 p-2 rounded-lg bg-rose-50 border border-rose-200 text-xs text-rose-700">
                  <strong>El proveedor disputó tu pago anterior:</strong> {t.rejectionReason.replace('[DISPUTA PAGO] ', '')}
                  <br />
                  <span className="text-[10px] text-rose-600 mt-1 inline-block">Revisa tu estado de cuenta y registra el pago nuevamente cuando verifiques que el SPEI salió correctamente.</span>
                </div>
              )}
            </div>

            {/* V63 — preview del destino antes de transferir */}
            <div className="mt-3 rounded-xl border border-indigo-100 bg-indigo-50/60 p-3">
              <div className="flex items-center gap-2 mb-1">
                <Landmark className="w-4 h-4 text-indigo-600" />
                <p className="text-xs font-bold text-indigo-900 uppercase tracking-wide">Destino de la transferencia</p>
              </div>
              {bank?.accountActive ? (
                <div className="grid grid-cols-1 sm:grid-cols-3 gap-2 text-sm">
                  <div>
                    <p className="text-[10px] font-bold text-slate-500 uppercase">Titular</p>
                    <p className="font-semibold text-slate-800 truncate">{bank.accountHolder || bank.providerName}</p>
                  </div>
                  <div>
                    <p className="text-[10px] font-bold text-slate-500 uppercase">Banco</p>
                    <p className="font-semibold text-slate-800 truncate">{bank.bankName || '—'}</p>
                  </div>
                  <div>
                    <p className="text-[10px] font-bold text-slate-500 uppercase flex items-center gap-1">
                      CLABE
                      <button
                        type="button"
                        onClick={() => copy(bank.clabeMasked)}
                        className="text-slate-400 hover:text-slate-700"
                        title="Copiar CLABE enmascarada"
                      >
                        <Copy className="w-3 h-3" />
                      </button>
                    </p>
                    <p className="font-mono font-semibold text-slate-800 truncate">{bank.clabeMasked || '—'}</p>
                  </div>
                </div>
              ) : (
                <p className="text-xs text-amber-800 bg-amber-50 border border-amber-200 rounded p-2">
                  Este proveedor aún no ha registrado su CLABE / banco / titular. No puedes pagarle hasta que complete su onboarding.
                </p>
              )}
            </div>

            <div className="mt-3 pt-3 border-t border-slate-100 grid grid-cols-1 sm:grid-cols-[1fr_1fr_auto] gap-3 items-end">
              <div>
                <label className="block text-xs font-semibold text-slate-600 uppercase tracking-wide mb-1">Monto pagado</label>
                <input
                  type="number" step="0.01" min={0}
                  value={st.amount}
                  onChange={e => setForm(prev => ({ ...prev, [t.id]: { ...st, amount: e.target.value } }))}
                  disabled={busy || !bank?.accountActive}
                  className="w-full px-3 py-1.5 text-sm rounded-lg border border-slate-200 disabled:bg-slate-50"
                />
              </div>
              <div>
                <label className="block text-xs font-semibold text-slate-600 uppercase tracking-wide mb-1">Comprobante SPEI</label>
                <input
                  type="file" accept="application/pdf,image/*"
                  onChange={e => setForm(prev => ({ ...prev, [t.id]: { ...st, file: e.target.files?.[0] ?? null } }))}
                  disabled={busy || !bank?.accountActive}
                  className="w-full text-xs disabled:opacity-50"
                />
              </div>
              <button
                onClick={() => pay(t)}
                disabled={busy || !bank?.accountActive}
                className="inline-flex items-center gap-1.5 px-3 py-2 rounded-lg text-sm font-semibold bg-indigo-600 text-white hover:bg-indigo-700 disabled:opacity-40 disabled:cursor-not-allowed">
                {busy ? <Loader2 className="w-4 h-4 animate-spin" /> : <Upload className="w-4 h-4" />} Registrar pago
              </button>
            </div>
            <p className="text-[11px] text-slate-400 mt-2">
              El ticket quedará pendiente hasta que el proveedor confirme que recibió el SPEI.
            </p>
          </li>
        );
      })}
    </ul>
  );
};

// ─── V63 — Tab: Pagos esperando confirmación del proveedor ─────────────────
//
// Muestra tickets que el dueño ya pagó (subió SPEI) pero que están bloqueados
// hasta que el proveedor confirme o dispute. Si el proveedor disputa, aparece
// el motivo para que el dueño entienda qué pasó y vuelva a intentar en el tab
// MAINT_PAY.

const PaymentAwaitingConfirmationTab: React.FC<{ onChanged: () => void }> = () => {
  const [tickets, setTickets] = useState<MaintenanceTicketDTO[]>([]);
  const [loading, setLoading] = useState(true);
  const [err, setErr] = useState<string | null>(null);

  const load = useCallback(async () => {
    setLoading(true); setErr(null);
    try {
      setTickets(await ownerWorkflowService.getAwaitingPaymentConfirmation());
    } catch (e: any) {
      setErr(e?.response?.data?.message ?? 'No pude cargar los tickets.');
    } finally { setLoading(false); }
  }, []);

  useEffect(() => { load(); }, [load]);

  if (loading) return <LoadingBlock />;
  if (err) return <ErrorBlock msg={err} />;
  if (tickets.length === 0) {
    return (
      <EmptyState
        icon={<Clock3 className="w-5 h-5" />}
        title="Nada pendiente de confirmación"
        subtitle="Cuando registres un pago SPEI el proveedor lo verá en su panel y deberá confirmar o disputar."
      />
    );
  }

  return (
    <ul className="space-y-3">
      {tickets.map(t => (
        <li key={t.id} className="bg-white border border-amber-200 rounded-xl p-4">
          <div className="flex items-start justify-between gap-3 flex-wrap">
            <div className="flex-1 min-w-0">
              <h4 className="font-semibold text-slate-900">{t.title}</h4>
              <p className="text-xs text-slate-500 mt-0.5">
                Esperando que el proveedor confirme haber recibido el SPEI.
              </p>
              {t.rejectionReason && t.rejectionReason.startsWith('[DISPUTA PAGO]') && (
                <div className="mt-2 p-2 rounded-lg bg-rose-50 border border-rose-200 text-xs text-rose-700">
                  <strong>Disputa previa:</strong> {t.rejectionReason.replace('[DISPUTA PAGO] ', '')}
                </div>
              )}
            </div>
            <span className="inline-flex items-center gap-1 px-2 py-1 rounded-lg text-xs font-bold bg-amber-100 text-amber-800">
              <Clock3 className="w-3 h-3" /> Esperando confirmación
            </span>
          </div>
        </li>
      ))}
    </ul>
  );
};

// ─── Tab: Prospectos ───────────────────────────────────────────────────────

const ProspectsTab: React.FC<{ onChanged: () => void }> = ({ onChanged }) => {
  const [prospects, setProspects] = useState<ProspectSubmissionDTO[]>([]);
  const [loading, setLoading] = useState(true);
  const [busyId, setBusyId] = useState<string | null>(null);
  const [err, setErr] = useState<string | null>(null);

  const load = useCallback(async () => {
    setLoading(true); setErr(null);
    try {
      setProspects(await ownerWorkflowService.getPendingProspects());
    } catch (e: any) {
      setErr(e?.response?.data?.message ?? 'No pude cargar los prospectos.');
    } finally { setLoading(false); }
  }, []);
  useEffect(() => { load(); }, [load]);

  const accept = async (p: ProspectSubmissionDTO) => {
    if (!window.confirm(
      `Aceptar prospecto "${p.prospectName}".\n\nDespués deberás crear el expediente (contrato) desde Arrendatarios > Nuevo. La comisión quedará en PENDIENTE hasta el pago.`
    )) return;
    setBusyId(p.id);
    try {
      await ownerWorkflowService.acceptProspect(p.id);
      await load();
      onChanged();
    } catch (e: any) {
      alert(e?.response?.data?.message ?? 'Error al aceptar.');
    } finally { setBusyId(null); }
  };

  const reject = async (p: ProspectSubmissionDTO) => {
    const reason = window.prompt('Motivo del rechazo (se avisa al agente):');
    if (!reason) return;
    setBusyId(p.id);
    try {
      await ownerWorkflowService.rejectProspect(p.id, reason);
      await load();
      onChanged();
    } catch (e: any) {
      alert(e?.response?.data?.message ?? 'Error al rechazar.');
    } finally { setBusyId(null); }
  };

  if (loading) return <LoadingBlock />;
  if (err) return <ErrorBlock msg={err} />;
  if (prospects.length === 0) {
    return (
      <EmptyState
        icon={<UserCheck className="w-5 h-5" />}
        title="Sin prospectos pendientes"
        subtitle="Cuando tu agente inmobiliario proponga un inquilino aparecerá aquí para decidir."
      />
    );
  }

  return (
    <ul className="space-y-3">
      {prospects.map(p => {
        const busy = busyId === p.id;
        return (
          <li key={p.id} className="bg-white border border-slate-200 rounded-xl p-4">
            <div>
              <h4 className="font-semibold text-slate-900">{p.prospectName}</h4>
              <p className="text-sm text-slate-600 mt-0.5">
                {p.prospectEmail ?? '—'} · {p.prospectPhone ?? '—'}
              </p>
              {p.notes && <p className="text-sm text-slate-700 mt-2 whitespace-pre-wrap">{p.notes}</p>}
              <p className="text-xs text-slate-500 mt-2">
                Enviado {p.submittedAt ? new Date(p.submittedAt).toLocaleString() : '—'}
                {p.lastReminderAt && ` · Último recordatorio ${new Date(p.lastReminderAt).toLocaleDateString()}`}
              </p>
            </div>
            <div className="mt-3 pt-3 border-t border-slate-100 flex gap-2 justify-end">
              <button onClick={() => reject(p)} disabled={busy}
                className="inline-flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-sm font-medium border border-red-200 text-red-700 hover:bg-red-50 disabled:opacity-40">
                <XCircle className="w-4 h-4" /> Rechazar
              </button>
              <button onClick={() => accept(p)} disabled={busy}
                className="inline-flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-sm font-semibold bg-emerald-600 text-white hover:bg-emerald-700 disabled:opacity-40">
                {busy ? <Loader2 className="w-4 h-4 animate-spin" /> : <CheckCircle2 className="w-4 h-4" />} Aceptar
              </button>
            </div>
          </li>
        );
      })}
    </ul>
  );
};

// ─── Tab: Comisiones ───────────────────────────────────────────────────────

const CommissionsTab: React.FC<{ onChanged: () => void }> = ({ onChanged }) => {
  const [invoices, setInvoices] = useState<AgentCommissionDTO[]>([]);
  const [loading, setLoading] = useState(true);
  const [busyId, setBusyId] = useState<string | null>(null);
  const [err, setErr] = useState<string | null>(null);
  const [emitterBanks, setEmitterBanks] = useState<BanxicoInstitution[]>([]);
  const [form, setForm] = useState<Record<string, {
    file: File | null; amount: string; claveRastreo: string; bankEmitter: string;
  }>>({});

  const load = useCallback(async () => {
    setLoading(true); setErr(null);
    try {
      const all = await ownerWorkflowService.getCommissions();
      // Mostrar sólo las accionables: PENDING_PAYMENT o FAILED (reintento).
      const actionable = all.filter(i => ['PENDING_PAYMENT', 'FAILED'].includes(i.status));
      setInvoices(actionable);
      const pre: typeof form = {};
      actionable.forEach(i => {
        pre[i.id] = { file: null, amount: String(i.amount), claveRastreo: '', bankEmitter: '' };
      });
      setForm(pre);
    } catch (e: any) {
      setErr(e?.response?.data?.message ?? 'No pude cargar las comisiones.');
    } finally { setLoading(false); }
  }, []);

  useEffect(() => { load(); }, [load]);
  useEffect(() => {
    banxicoInstitutionService.getCatalog()
      .then((catalog) => setEmitterBanks(catalog.emitters || []))
      .catch(() => setEmitterBanks([]));
  }, []);

  const submit = async (inv: AgentCommissionDTO) => {
    const st = form[inv.id];
    if (!st?.file) { alert('Adjunta el comprobante SPEI.'); return; }
    if (!st.claveRastreo.trim()) { alert('La clave de rastreo es obligatoria.'); return; }
    if (!st.bankEmitter.trim()) { alert('Selecciona el banco emisor del catálogo Banxico.'); return; }
    const amount = Number(st.amount);
    if (Number.isNaN(amount) || amount <= 0) { alert('Monto inválido.'); return; }
    setBusyId(inv.id);
    try {
      const fileId = await ownerWorkflowService.uploadFile(st.file, 'spei-commission');
      await ownerWorkflowService.submitSpeiProof(inv.id, {
        proofFileId: fileId,
        declaredAmount: amount,
        claveRastreo: st.claveRastreo.trim(),
        bankEmitter: st.bankEmitter.trim(),
      });
      await load();
      onChanged();
    } catch (e: any) {
      alert(e?.response?.data?.message ?? 'Error al enviar el comprobante.');
    } finally { setBusyId(null); }
  };

  const voidIt = async (inv: AgentCommissionDTO) => {
    const reason = window.prompt('Motivo para anular la comisión:');
    if (!reason) return;
    setBusyId(inv.id);
    try {
      await ownerWorkflowService.voidCommission(inv.id, reason);
      await load();
      onChanged();
    } catch (e: any) {
      alert(e?.response?.data?.message ?? 'Error al anular.');
    } finally { setBusyId(null); }
  };

  if (loading) return <LoadingBlock />;
  if (err) return <ErrorBlock msg={err} />;
  if (invoices.length === 0) {
    return (
      <EmptyState
        icon={<HomeIcon className="w-5 h-5" />}
        title="Sin comisiones por pagar"
        subtitle="Aparecerán aquí cuando confirmes un contrato propuesto por tu agente."
      />
    );
  }

  return (
    <ul className="space-y-3">
      {invoices.map(inv => {
        const st = form[inv.id] ?? { file: null, amount: '', claveRastreo: '', bankEmitter: '' };
        const busy = busyId === inv.id;
        return (
          <li key={inv.id} className="bg-white border border-slate-200 rounded-xl p-4">
            <div className="flex items-start justify-between gap-4">
              <div>
                <p className="text-2xl font-bold text-slate-900">{currency(inv.amount)}</p>
                <p className="text-xs text-slate-500 mt-0.5">
                  Agente {inv.agentSource ?? '—'} · Estado: <span className="font-semibold">{inv.status}</span>
                </p>
                {(inv.validationAttempts ?? 0) > 0 && (
                  <p className="text-xs text-red-600 mt-1">
                    Intentos fallidos de validación: {inv.validationAttempts}
                  </p>
                )}
              </div>
              <button onClick={() => voidIt(inv)} disabled={busy}
                className="text-xs text-slate-400 hover:text-red-600">Anular</button>
            </div>

            <div className="mt-3 pt-3 border-t border-slate-100 grid grid-cols-1 sm:grid-cols-2 gap-3">
              <div>
                <label className="block text-xs font-semibold text-slate-600 uppercase tracking-wide mb-1">Monto declarado</label>
                <input type="number" step="0.01" min={0} value={st.amount}
                  onChange={e => setForm(prev => ({ ...prev, [inv.id]: { ...st, amount: e.target.value } }))}
                  disabled={busy}
                  className="w-full px-3 py-1.5 text-sm rounded-lg border border-slate-200" />
              </div>
              <div>
                <label className="block text-xs font-semibold text-slate-600 uppercase tracking-wide mb-1">Clave de rastreo</label>
                <input type="text" value={st.claveRastreo}
                  onChange={e => setForm(prev => ({ ...prev, [inv.id]: { ...st, claveRastreo: e.target.value } }))}
                  disabled={busy}
                  className="w-full px-3 py-1.5 text-sm rounded-lg border border-slate-200" />
              </div>
              <div>
                <label className="block text-xs font-semibold text-slate-600 uppercase tracking-wide mb-1">Banco emisor</label>
                <select value={st.bankEmitter}
                  onChange={e => setForm(prev => ({ ...prev, [inv.id]: { ...st, bankEmitter: e.target.value } }))}
                  disabled={busy}
                  className="w-full px-3 py-1.5 text-sm rounded-lg border border-slate-200 bg-white">
                  <option value="">Selecciona banco Banxico</option>
                  {emitterBanks.map((bank) => (
                    <option key={bank.code} value={bank.name}>{bank.name}</option>
                  ))}
                </select>
              </div>
              <div>
                <label className="block text-xs font-semibold text-slate-600 uppercase tracking-wide mb-1">Comprobante SPEI</label>
                <input type="file" accept="application/pdf,image/*"
                  onChange={e => setForm(prev => ({ ...prev, [inv.id]: { ...st, file: e.target.files?.[0] ?? null } }))}
                  disabled={busy} className="w-full text-xs" />
              </div>
            </div>
            <div className="mt-3 flex justify-end">
              <button onClick={() => submit(inv)} disabled={busy}
                className="inline-flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-sm font-semibold bg-indigo-600 text-white hover:bg-indigo-700 disabled:opacity-40">
                {busy ? <Loader2 className="w-4 h-4 animate-spin" /> : <Upload className="w-4 h-4" />} Enviar comprobante
              </button>
            </div>
          </li>
        );
      })}
    </ul>
  );
};

// ─── Página principal ─────────────────────────────────────────────────────

export const OwnerWorkflowInbox: React.FC = () => {
  const [tab, setTab] = useState<InboxTab>('RENT_PROOFS');
  const [counts, setCounts] = useState<Record<InboxTab, number>>({
    RENT_PROOFS: 0, MAINT_AUTH: 0, MAINT_QUOTE: 0, MAINT_PAY: 0, MAINT_PAY_CONFIRM: 0,
    PROSPECTS: 0, COMMISSIONS: 0,
  });
  const [refreshKey, setRefreshKey] = useState(0);

  const refreshCounts = useCallback(async () => {
    try {
      const [rentProofs, authT, quotes, payT, payConfT, prospects, commissions] = await Promise.all([
        paymentService.getPendingCashProofs().catch(() => []),
        ownerWorkflowService.getPendingAuthTickets().catch(() => []),
        ownerWorkflowService.getPendingApprovalQuotes().catch(() => []),
        ownerWorkflowService.getReadyToPayTickets().catch(() => []),
        ownerWorkflowService.getAwaitingPaymentConfirmation().catch(() => []),
        ownerWorkflowService.getPendingProspects().catch(() => []),
        ownerWorkflowService.getCommissions().catch(() => []),
      ]);
      setCounts({
        RENT_PROOFS: rentProofs.length,
        MAINT_AUTH: authT.length,
        MAINT_QUOTE: quotes.length,
        MAINT_PAY: payT.length,
        MAINT_PAY_CONFIRM: payConfT.length,
        PROSPECTS: prospects.length,
        COMMISSIONS: commissions.filter(i => ['PENDING_PAYMENT', 'FAILED'].includes(i.status)).length,
      });
    } catch { /* noop */ }
  }, []);

  useEffect(() => { refreshCounts(); }, [refreshCounts, refreshKey]);

  const onChanged = () => setRefreshKey(k => k + 1);

  return (
    <div className="space-y-6 animate-in fade-in duration-300">
      <div className="flex items-start justify-between gap-4">
        <div>
          <h2 className="text-2xl font-bold text-slate-900">Bandeja de decisiones</h2>
          <p className="text-sm text-slate-500 mt-1">
            Todo lo que requiere tu autorización: mantenimiento, cotizaciones, prospectos y pagos de comisión.
          </p>
        </div>
        <button onClick={onChanged}
          className="inline-flex items-center gap-1.5 px-3 py-1.5 text-sm font-medium rounded-lg border border-slate-200 text-slate-600 hover:bg-slate-50">
          <RefreshCw className="w-4 h-4" /> Actualizar
        </button>
      </div>

      <div className="flex flex-wrap gap-2 border-b border-slate-200 pb-0">
        {TABS.map(tb => {
          const active = tab === tb.id;
          const cnt = counts[tb.id];
          return (
            <button key={tb.id} onClick={() => setTab(tb.id)}
              className={`inline-flex items-center gap-1.5 px-3 py-2 text-sm font-semibold border-b-2 -mb-px transition-colors ${
                active ? 'border-indigo-500 text-indigo-600' : 'border-transparent text-slate-500 hover:text-slate-700'
              }`}>
              {tb.icon} {tb.label}
              {cnt > 0 && (
                <span className={`inline-flex items-center justify-center min-w-[20px] h-5 px-1.5 rounded-full text-[10px] font-bold ${
                  active ? 'bg-indigo-100 text-indigo-700' : 'bg-slate-200 text-slate-700'
                }`}>{cnt}</span>
              )}
            </button>
          );
        })}
      </div>

      <div key={refreshKey}>
        {tab === 'RENT_PROOFS' && <RentProofsPanel onChange={onChanged} />}
        {tab === 'MAINT_AUTH' && <MaintenanceAuthTab onChanged={onChanged} />}
        {tab === 'MAINT_QUOTE' && <QuoteApprovalTab onChanged={onChanged} />}
        {tab === 'MAINT_PAY' && <TicketPayTab onChanged={onChanged} />}
        {tab === 'MAINT_PAY_CONFIRM' && <PaymentAwaitingConfirmationTab onChanged={onChanged} />}
        {tab === 'PROSPECTS' && <ProspectsTab onChanged={onChanged} />}
        {tab === 'COMMISSIONS' && <CommissionsTab onChanged={onChanged} />}
      </div>
    </div>
  );
};
