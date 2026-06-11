import React, { useEffect, useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useAuth } from '../context/AuthContext';
import {
  LogOut, Briefcase, Bell, X, Home, Building2, MapPin, Phone, Mail, Clock,
  CheckCircle2, XCircle, Camera, UserPlus, FileSignature, DollarSign, Landmark,
  AlertCircle, Loader2, Upload, RefreshCw,
} from 'lucide-react';
import {
  realEstateAgentService,
  type AgentVacancyDTO,
  type ProspectSubmissionDTO,
  type AgentCommissionDTO,
  type AgentBankAccountDTO,
} from '../services/realEstateAgentService';
import { NotificationPreferencesPanel } from './NotificationPreferencesPanel';
import NotificationHistoryPanel from './NotificationHistoryPanel';
import { AgentOnboardingWizard } from '../components/AgentOnboardingWizard';
import { agentBankAccountService } from '../services/agentBankAccountService';
import {
  banxicoInstitutionService,
  BanxicoInstitution,
  findBanxicoInstitutionByClabe,
} from '../services/banxicoInstitutionService';

type AgentTab = 'INVITES' | 'ACTIVE' | 'PROSPECTS' | 'COMMISSIONS' | 'BANK';

const STATE_LABELS: Record<string, { text: string; cls: string }> = {
  AWAITING_AGENT: { text: 'Esperando tu respuesta', cls: 'bg-amber-100 text-amber-700' },
  AGENT_ACCEPTED: { text: 'Aceptada — sube fotos', cls: 'bg-blue-100 text-blue-700' },
  PHOTOS_UPLOADED: { text: 'Fotos subidas — prospecta', cls: 'bg-violet-100 text-violet-700' },
  PROSPECT_PROPOSED: { text: 'Prospecto propuesto', cls: 'bg-indigo-100 text-indigo-700' },
  AWAITING_CONTRACT: { text: 'Esperando contrato', cls: 'bg-teal-100 text-teal-700' },
  CONTRACT_SIGNED: { text: 'Contrato firmado', cls: 'bg-emerald-100 text-emerald-700' },
  CLOSED: { text: 'Cerrada', cls: 'bg-slate-100 text-slate-600' },
  CHAIN_EXHAUSTED: { text: 'Cadena agotada', cls: 'bg-rose-100 text-rose-700' },
};

const fmtDate = (iso?: string | null) =>
  iso ? new Date(iso).toLocaleString('es-MX', { dateStyle: 'short', timeStyle: 'short' }) : '—';

const fmtMoney = (n?: number | null) =>
  typeof n === 'number' ? `$${n.toLocaleString('es-MX', { minimumFractionDigits: 2 })}` : '—';

// ══════════════════════════════════════════════════════════════════════
// Tab: Invitaciones pendientes (72h para aceptar/rechazar)
// ══════════════════════════════════════════════════════════════════════
const InvitesTab: React.FC<{ onChanged: () => void }> = ({ onChanged }) => {
  const [items, setItems] = useState<AgentVacancyDTO[]>([]);
  const [loading, setLoading] = useState(true);
  const [busyId, setBusyId] = useState<string | null>(null);
  const [rejectModal, setRejectModal] = useState<{ id: string; reason: string } | null>(null);

  const load = async () => {
    setLoading(true);
    try {
      setItems(await realEstateAgentService.getInvitations());
    } finally {
      setLoading(false);
    }
  };
  useEffect(() => { load(); }, []);

  const accept = async (id: string) => {
    setBusyId(id);
    try {
      await realEstateAgentService.acceptVacancy(id);
      await load();
      onChanged();
    } catch (e: unknown) {
      const msg = (e as { response?: { data?: { message?: string } } })?.response?.data?.message;
      alert(msg || 'No se pudo aceptar la invitación.');
    } finally {
      setBusyId(null);
    }
  };

  const doReject = async () => {
    if (!rejectModal) return;
    setBusyId(rejectModal.id);
    try {
      await realEstateAgentService.rejectVacancy(rejectModal.id, rejectModal.reason.trim() || undefined);
      setRejectModal(null);
      await load();
      onChanged();
    } catch (e: unknown) {
      const msg = (e as { response?: { data?: { message?: string } } })?.response?.data?.message;
      alert(msg || 'No se pudo rechazar la invitación.');
    } finally {
      setBusyId(null);
    }
  };

  if (loading) return <div className="animate-pulse h-48 bg-slate-100 rounded-2xl" />;
  if (items.length === 0) {
    return (
      <div className="bg-white rounded-2xl border border-slate-200 p-8 text-center text-slate-500">
        <Bell className="w-10 h-10 mx-auto text-slate-300 mb-3" />
        <p className="text-sm">No tienes invitaciones pendientes.</p>
        <p className="text-xs mt-1">Cuando un dueño te asigne una vacancia, aparecerá aquí.</p>
      </div>
    );
  }

  return (
    <>
      <div className="space-y-4">
        {items.map((v) => (
          <div key={v.id} className="bg-white rounded-2xl border border-amber-200 p-5 shadow-sm">
            <div className="flex items-start justify-between gap-4 mb-3">
              <div className="flex items-start gap-3">
                <div className="w-10 h-10 rounded-xl bg-amber-100 text-amber-600 flex items-center justify-center shrink-0">
                  <Building2 className="w-5 h-5" />
                </div>
                <div>
                  <p className="font-bold text-slate-800">{v.propertyName || '(sin nombre)'}</p>
                  <p className="text-xs text-slate-500 flex items-center gap-1 mt-0.5">
                    <MapPin className="w-3 h-3" /> {v.propertyAddress || '—'}
                  </p>
                </div>
              </div>
              <div className="text-right">
                <span className="inline-flex items-center gap-1 px-2 py-1 rounded-lg text-xs font-bold bg-amber-100 text-amber-700">
                  <Clock className="w-3 h-3" /> Vence en {v.expiresInHours ?? '?'}h
                </span>
                <p className="text-[10px] text-slate-400 mt-1">Notificada: {fmtDate(v.invitation?.notifiedAt)}</p>
              </div>
            </div>

            <div className="grid grid-cols-1 md:grid-cols-3 gap-3 pb-3 border-b border-slate-100 text-sm">
              <div>
                <p className="text-[10px] font-bold uppercase text-slate-400">Dueño</p>
                <p className="font-semibold text-slate-700">{v.ownerName || '—'}</p>
              </div>
              <div>
                <p className="text-[10px] font-bold uppercase text-slate-400">Contacto</p>
                <p className="text-slate-600 text-xs flex items-center gap-1">
                  <Mail className="w-3 h-3" /> {v.ownerEmail || '—'}
                </p>
                <p className="text-slate-600 text-xs flex items-center gap-1">
                  <Phone className="w-3 h-3" /> {v.ownerPhone || '—'}
                </p>
              </div>
              <div>
                <p className="text-[10px] font-bold uppercase text-slate-400">Turno</p>
                <p className="font-semibold text-slate-700">#{v.invitation?.priorityOrder}</p>
              </div>
            </div>

            <div className="flex gap-3 pt-4">
              <button
                type="button"
                onClick={() => accept(v.id)}
                disabled={busyId === v.id}
                className="flex-1 py-2.5 bg-emerald-600 hover:bg-emerald-700 text-white font-bold rounded-xl text-sm disabled:opacity-60 flex items-center justify-center gap-2"
              >
                {busyId === v.id ? <Loader2 className="w-4 h-4 animate-spin" /> : <CheckCircle2 className="w-4 h-4" />}
                Aceptar
              </button>
              <button
                type="button"
                onClick={() => setRejectModal({ id: v.id, reason: '' })}
                disabled={busyId === v.id}
                className="px-4 py-2.5 bg-white border border-rose-200 text-rose-600 hover:bg-rose-50 font-bold rounded-xl text-sm disabled:opacity-60 flex items-center gap-2"
              >
                <XCircle className="w-4 h-4" /> Rechazar
              </button>
            </div>
          </div>
        ))}
      </div>

      {rejectModal && (
        <ModalShell title="Rechazar invitación" onClose={() => setRejectModal(null)}>
          <p className="text-sm text-slate-600 mb-3">
            Si rechazas, el dueño lo verá y la vacancia pasa al siguiente agente de su lista.
          </p>
          <textarea
            className="w-full border border-slate-300 rounded-lg p-2 text-sm min-h-[80px]"
            placeholder="Motivo (opcional)"
            value={rejectModal.reason}
            onChange={(e) => setRejectModal({ ...rejectModal, reason: e.target.value })}
          />
          <div className="flex gap-2 mt-3">
            <button type="button" onClick={() => setRejectModal(null)} className="flex-1 py-2 text-sm text-slate-500 font-semibold">
              Cancelar
            </button>
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
// Tab: Mis vacancias activas (aceptadas, con acciones por estado)
// ══════════════════════════════════════════════════════════════════════
const MyVacanciesTab: React.FC<{ onChanged: () => void }> = ({ onChanged }) => {
  const [items, setItems] = useState<AgentVacancyDTO[]>([]);
  const [loading, setLoading] = useState(true);
  const [photosModal, setPhotosModal] = useState<AgentVacancyDTO | null>(null);
  const [prospectModal, setProspectModal] = useState<AgentVacancyDTO | null>(null);
  const [contractModal, setContractModal] = useState<AgentVacancyDTO | null>(null);

  const load = async () => {
    setLoading(true);
    try {
      setItems(await realEstateAgentService.getMyVacancies());
    } finally {
      setLoading(false);
    }
  };
  useEffect(() => { load(); }, []);

  if (loading) return <div className="animate-pulse h-48 bg-slate-100 rounded-2xl" />;
  if (items.length === 0) {
    return (
      <div className="bg-white rounded-2xl border border-slate-200 p-8 text-center text-slate-500">
        <Briefcase className="w-10 h-10 mx-auto text-slate-300 mb-3" />
        <p className="text-sm">No tienes vacancias activas.</p>
        <p className="text-xs mt-1">Acepta una invitación para empezar a comercializar.</p>
      </div>
    );
  }

  return (
    <>
      <div className="space-y-4">
        {items.map((v) => {
          const label = STATE_LABELS[v.chainState || ''] || { text: v.chainState || v.status, cls: 'bg-slate-100 text-slate-700' };
          return (
            <div key={v.id} className="bg-white rounded-2xl border border-slate-200 p-5 shadow-sm">
              <div className="flex items-start justify-between gap-4 mb-3">
                <div className="flex items-start gap-3">
                  <div className="w-10 h-10 rounded-xl bg-indigo-100 text-indigo-600 flex items-center justify-center shrink-0">
                    <Home className="w-5 h-5" />
                  </div>
                  <div>
                    <p className="font-bold text-slate-800">{v.propertyName || '(sin nombre)'}</p>
                    <p className="text-xs text-slate-500 flex items-center gap-1 mt-0.5">
                      <MapPin className="w-3 h-3" /> {v.propertyAddress || '—'}
                    </p>
                  </div>
                </div>
                <span className={`inline-flex items-center px-2 py-1 rounded-lg text-xs font-bold ${label.cls}`}>{label.text}</span>
              </div>

              <div className="grid grid-cols-1 md:grid-cols-3 gap-3 pb-3 border-b border-slate-100 text-sm">
                <div>
                  <p className="text-[10px] font-bold uppercase text-slate-400">Dueño</p>
                  <p className="font-semibold text-slate-700">{v.ownerName || '—'}</p>
                  <p className="text-[11px] text-slate-500">{v.ownerEmail}</p>
                </div>
                <div>
                  <p className="text-[10px] font-bold uppercase text-slate-400">Abierta</p>
                  <p className="font-medium text-slate-700">{fmtDate(v.openedAt)}</p>
                </div>
                <div>
                  <p className="text-[10px] font-bold uppercase text-slate-400">Fotos</p>
                  <p className="font-medium text-slate-700">{v.photosUploadedAt ? fmtDate(v.photosUploadedAt) : 'Pendientes'}</p>
                </div>
              </div>

              <div className="flex gap-2 pt-4 flex-wrap">
                {v.chainState === 'AGENT_ACCEPTED' && (
                  <button
                    type="button"
                    onClick={() => setPhotosModal(v)}
                    className="px-4 py-2 bg-indigo-600 hover:bg-indigo-700 text-white font-bold rounded-xl text-sm flex items-center gap-2"
                  >
                    <Camera className="w-4 h-4" /> Subir fotos del inmueble
                  </button>
                )}
                {v.chainState === 'PHOTOS_UPLOADED' && (
                  <button
                    type="button"
                    onClick={() => setProspectModal(v)}
                    className="px-4 py-2 bg-violet-600 hover:bg-violet-700 text-white font-bold rounded-xl text-sm flex items-center gap-2"
                  >
                    <UserPlus className="w-4 h-4" /> Proponer prospecto
                  </button>
                )}
                {v.chainState === 'PROSPECT_PROPOSED' && (
                  <span className="text-sm text-slate-500 italic">Esperando decisión del dueño…</span>
                )}
                {v.chainState === 'AWAITING_CONTRACT' && (
                  <button
                    type="button"
                    onClick={() => setContractModal(v)}
                    className="px-4 py-2 bg-teal-600 hover:bg-teal-700 text-white font-bold rounded-xl text-sm flex items-center gap-2"
                  >
                    <FileSignature className="w-4 h-4" /> Reportar contrato firmado
                  </button>
                )}
                {v.chainState === 'CONTRACT_SIGNED' && (
                  <span className="text-sm text-emerald-700 font-semibold flex items-center gap-1">
                    <CheckCircle2 className="w-4 h-4" /> Contrato reportado — esperando confirmación del dueño
                  </span>
                )}
              </div>
              {v.notes && (
                <p className="mt-3 pt-3 border-t border-slate-100 text-xs text-slate-500 whitespace-pre-wrap">{v.notes}</p>
              )}
            </div>
          );
        })}
      </div>

      {photosModal && (
        <PhotosUploadModal vacancy={photosModal} onClose={() => setPhotosModal(null)} onDone={() => { setPhotosModal(null); load(); onChanged(); }} />
      )}
      {prospectModal && (
        <ProspectModal vacancy={prospectModal} onClose={() => setProspectModal(null)} onDone={() => { setProspectModal(null); load(); onChanged(); }} />
      )}
      {contractModal && (
        <ContractModal vacancy={contractModal} onClose={() => setContractModal(null)} onDone={() => { setContractModal(null); load(); onChanged(); }} />
      )}
    </>
  );
};

// ── Sub-modals for MyVacanciesTab ────────────────────────────────────────────

const PhotosUploadModal: React.FC<{ vacancy: AgentVacancyDTO; onClose: () => void; onDone: () => void }> = ({ vacancy, onClose, onDone }) => {
  const [files, setFiles] = useState<File[]>([]);
  const [busy, setBusy] = useState(false);
  const submit = async () => {
    if (files.length === 0) { alert('Sube al menos 1 foto.'); return; }
    setBusy(true);
    try {
      const ids: string[] = [];
      for (const f of files) {
        ids.push(await realEstateAgentService.uploadFile(f, 'property-photo'));
      }
      await realEstateAgentService.recordPhotos(vacancy.id, ids);
      onDone();
    } catch (e: unknown) {
      const msg = (e as { response?: { data?: { message?: string } } })?.response?.data?.message;
      alert(msg || 'Error al subir fotos.');
    } finally {
      setBusy(false);
    }
  };
  return (
    <ModalShell title={`Fotos de ${vacancy.propertyName || 'inmueble'}`} onClose={onClose}>
      <p className="text-sm text-slate-600 mb-3">
        Sube fotos del inmueble. Al guardarlas, el estado pasa a <strong>Pendiente de renta</strong> y el dueño recibe la notificación.
      </p>
      <input
        type="file"
        multiple
        accept="image/*"
        onChange={(e) => setFiles(e.target.files ? Array.from(e.target.files) : [])}
        className="text-sm w-full text-slate-500 file:mr-4 file:py-2 file:px-4 file:rounded-full file:border-0 file:text-sm file:font-semibold file:bg-indigo-50 file:text-indigo-700 hover:file:bg-indigo-100"
      />
      {files.length > 0 && <p className="text-xs text-slate-500 mt-2">{files.length} archivo(s) seleccionado(s).</p>}
      <div className="flex gap-2 mt-4">
        <button type="button" onClick={onClose} className="flex-1 py-2 text-sm text-slate-500 font-semibold">Cancelar</button>
        <button type="button" onClick={submit} disabled={busy} className="flex-1 py-2 bg-indigo-600 hover:bg-indigo-700 text-white font-bold rounded-lg text-sm disabled:opacity-60 flex items-center justify-center gap-2">
          {busy ? <Loader2 className="w-4 h-4 animate-spin" /> : <Upload className="w-4 h-4" />} Subir fotos
        </button>
      </div>
    </ModalShell>
  );
};

const ProspectModal: React.FC<{ vacancy: AgentVacancyDTO; onClose: () => void; onDone: () => void }> = ({ vacancy, onClose, onDone }) => {
  const [form, setForm] = useState({ name: '', phone: '', email: '', notes: '' });
  const [busy, setBusy] = useState(false);
  const submit = async () => {
    if (!form.name.trim()) { alert('Nombre del prospecto obligatorio.'); return; }
    setBusy(true);
    try {
      await realEstateAgentService.submitProspect({
        vacancyId: vacancy.id,
        name: form.name.trim(),
        phone: form.phone.trim() || undefined,
        email: form.email.trim() || undefined,
        notes: form.notes.trim() || undefined,
      });
      onDone();
    } catch (e: unknown) {
      const msg = (e as { response?: { data?: { message?: string } } })?.response?.data?.message;
      alert(msg || 'Error al proponer prospecto.');
    } finally {
      setBusy(false);
    }
  };
  const input = 'w-full border border-slate-300 rounded-lg p-2 text-sm';
  return (
    <ModalShell title={`Proponer prospecto para ${vacancy.propertyName || 'inmueble'}`} onClose={onClose}>
      <div className="space-y-3">
        <div>
          <label className="text-xs font-semibold text-slate-600 block mb-1">Nombre completo *</label>
          <input className={input} value={form.name} onChange={(e) => setForm((f) => ({ ...f, name: e.target.value }))} />
        </div>
        <div className="grid grid-cols-2 gap-2">
          <div>
            <label className="text-xs font-semibold text-slate-600 block mb-1">Teléfono</label>
            <input className={input} value={form.phone} onChange={(e) => setForm((f) => ({ ...f, phone: e.target.value }))} />
          </div>
          <div>
            <label className="text-xs font-semibold text-slate-600 block mb-1">Email</label>
            <input className={input} type="email" value={form.email} onChange={(e) => setForm((f) => ({ ...f, email: e.target.value }))} />
          </div>
        </div>
        <div>
          <label className="text-xs font-semibold text-slate-600 block mb-1">Notas</label>
          <textarea className={input + ' min-h-[70px]'} value={form.notes} onChange={(e) => setForm((f) => ({ ...f, notes: e.target.value }))} />
        </div>
      </div>
      <div className="flex gap-2 mt-4">
        <button type="button" onClick={onClose} className="flex-1 py-2 text-sm text-slate-500 font-semibold">Cancelar</button>
        <button type="button" onClick={submit} disabled={busy} className="flex-1 py-2 bg-violet-600 hover:bg-violet-700 text-white font-bold rounded-lg text-sm disabled:opacity-60">
          {busy ? 'Enviando…' : 'Enviar al dueño'}
        </button>
      </div>
    </ModalShell>
  );
};

const ContractModal: React.FC<{ vacancy: AgentVacancyDTO; onClose: () => void; onDone: () => void }> = ({ vacancy, onClose, onDone }) => {
  const [form, setForm] = useState({
    months: '12', monthlyRent: '', deposit: '', commissionPct: '',
    agentSource: 'PLATFORM' as 'PLATFORM' | 'PRIVATE',
  });
  const [file, setFile] = useState<File | null>(null);
  const [busy, setBusy] = useState(false);
  const submit = async () => {
    if (!file) { alert('Sube el PDF del contrato firmado.'); return; }
    const months = Number(form.months);
    const monthlyRent = Number(form.monthlyRent);
    if (!months || months <= 0) { alert('Duración en meses inválida.'); return; }
    if (!monthlyRent || monthlyRent <= 0) { alert('Renta mensual inválida.'); return; }
    setBusy(true);
    try {
      const evidenceFileId = await realEstateAgentService.uploadFile(file, 'contract-evidence');
      await realEstateAgentService.closeContract(vacancy.id, {
        evidenceFileId,
        months,
        monthlyRent,
        deposit: form.deposit ? Number(form.deposit) : 0,
        agentSource: form.agentSource,
        commissionPct: form.commissionPct ? Number(form.commissionPct) : undefined,
      });
      onDone();
    } catch (e: unknown) {
      const msg = (e as { response?: { data?: { message?: string } } })?.response?.data?.message;
      alert(msg || 'Error al reportar contrato.');
    } finally {
      setBusy(false);
    }
  };
  const input = 'w-full border border-slate-300 rounded-lg p-2 text-sm';
  return (
    <ModalShell title={`Reportar contrato de ${vacancy.propertyName || 'inmueble'}`} onClose={onClose}>
      <p className="text-xs text-slate-500 mb-3">
        Esto crea una comisión PENDIENTE y notifica al dueño para que cree formalmente el lease.
      </p>
      <div className="space-y-3">
        <div>
          <label className="text-xs font-semibold text-slate-600 block mb-1">PDF del contrato firmado *</label>
          <input type="file" accept=".pdf,image/*" onChange={(e) => setFile(e.target.files?.[0] || null)} className="text-xs w-full" />
        </div>
        <div className="grid grid-cols-2 gap-2">
          <div>
            <label className="text-xs font-semibold text-slate-600 block mb-1">Duración (meses) *</label>
            <input className={input} type="number" value={form.months} onChange={(e) => setForm((f) => ({ ...f, months: e.target.value }))} />
          </div>
          <div>
            <label className="text-xs font-semibold text-slate-600 block mb-1">Renta mensual *</label>
            <input className={input} type="number" step="0.01" value={form.monthlyRent} onChange={(e) => setForm((f) => ({ ...f, monthlyRent: e.target.value }))} />
          </div>
          <div>
            <label className="text-xs font-semibold text-slate-600 block mb-1">Depósito</label>
            <input className={input} type="number" step="0.01" value={form.deposit} onChange={(e) => setForm((f) => ({ ...f, deposit: e.target.value }))} />
          </div>
          <div>
            <label className="text-xs font-semibold text-slate-600 block mb-1">Origen de agente</label>
            <select className={input} value={form.agentSource} onChange={(e) => setForm((f) => ({ ...f, agentSource: e.target.value as 'PLATFORM' | 'PRIVATE' }))}>
              <option value="PLATFORM">Plataforma (3% default)</option>
              <option value="PRIVATE">Privado (% lo fija el dueño)</option>
            </select>
          </div>
        </div>
        <div>
          <label className="text-xs font-semibold text-slate-600 block mb-1">% de comisión override (opcional)</label>
          <input className={input} type="number" step="0.01" placeholder="Ej: 0.03 = 3%" value={form.commissionPct} onChange={(e) => setForm((f) => ({ ...f, commissionPct: e.target.value }))} />
          <p className="text-[10px] text-slate-400 mt-1">
            Expresado en fracción: 0.03 = 3%. Si es privado, el dueño confirmará este valor.
          </p>
        </div>
      </div>
      <div className="flex gap-2 mt-4">
        <button type="button" onClick={onClose} className="flex-1 py-2 text-sm text-slate-500 font-semibold">Cancelar</button>
        <button type="button" onClick={submit} disabled={busy} className="flex-1 py-2 bg-teal-600 hover:bg-teal-700 text-white font-bold rounded-lg text-sm disabled:opacity-60">
          {busy ? 'Enviando…' : 'Reportar contrato'}
        </button>
      </div>
    </ModalShell>
  );
};

// ══════════════════════════════════════════════════════════════════════
// Tab: Prospectos
// ══════════════════════════════════════════════════════════════════════
const ProspectsTab: React.FC = () => {
  const [items, setItems] = useState<ProspectSubmissionDTO[]>([]);
  const [loading, setLoading] = useState(true);
  useEffect(() => {
    setLoading(true);
    realEstateAgentService.getProspects().then(setItems).finally(() => setLoading(false));
  }, []);
  if (loading) return <div className="animate-pulse h-48 bg-slate-100 rounded-2xl" />;
  if (items.length === 0) {
    return (
      <div className="bg-white rounded-2xl border border-slate-200 p-8 text-center text-slate-500">
        <UserPlus className="w-10 h-10 mx-auto text-slate-300 mb-3" />
        <p className="text-sm">No has propuesto prospectos aún.</p>
      </div>
    );
  }
  const decisionLabel = (d: string) => ({
    PENDING: { text: 'Pendiente', cls: 'bg-amber-100 text-amber-700' },
    ACCEPTED: { text: 'Aceptado', cls: 'bg-emerald-100 text-emerald-700' },
    REJECTED: { text: 'Rechazado', cls: 'bg-rose-100 text-rose-700' },
  })[d] || { text: d, cls: 'bg-slate-100 text-slate-600' };
  return (
    <div className="space-y-3">
      {items.map((p) => {
        const l = decisionLabel(p.ownerDecision);
        return (
          <div key={p.id} className="bg-white rounded-2xl border border-slate-200 p-4 shadow-sm">
            <div className="flex items-start justify-between gap-3">
              <div>
                <p className="font-bold text-slate-800">{p.prospectName}</p>
                <div className="text-xs text-slate-500 space-y-0.5 mt-0.5">
                  {p.prospectEmail && <p className="flex items-center gap-1"><Mail className="w-3 h-3" /> {p.prospectEmail}</p>}
                  {p.prospectPhone && <p className="flex items-center gap-1"><Phone className="w-3 h-3" /> {p.prospectPhone}</p>}
                </div>
              </div>
              <span className={`px-2 py-1 text-xs font-bold rounded-lg ${l.cls}`}>{l.text}</span>
            </div>
            {p.notes && <p className="text-xs text-slate-500 mt-2 border-t border-slate-100 pt-2">{p.notes}</p>}
            {p.rejectionReason && (
              <p className="text-xs text-rose-600 mt-2 border-t border-rose-100 pt-2">
                <strong>Motivo rechazo:</strong> {p.rejectionReason}
              </p>
            )}
            <p className="text-[10px] text-slate-400 mt-2">Propuesto: {fmtDate(p.submittedAt)}</p>
          </div>
        );
      })}
    </div>
  );
};

// ══════════════════════════════════════════════════════════════════════
// Tab: Comisiones
// ══════════════════════════════════════════════════════════════════════
const CommissionsTab: React.FC = () => {
  const [items, setItems] = useState<AgentCommissionDTO[]>([]);
  const [loading, setLoading] = useState(true);
  const [busyId, setBusyId] = useState<string | null>(null);

  const load = async () => {
    setLoading(true);
    try { setItems(await realEstateAgentService.getCommissions()); } finally { setLoading(false); }
  };
  useEffect(() => { load(); }, []);

  const confirm = async (id: string) => {
    if (!window.confirm('¿Confirmas que recibiste el depósito en tu cuenta?')) return;
    setBusyId(id);
    try {
      await realEstateAgentService.confirmCommissionReceived(id);
      await load();
    } catch (e: unknown) {
      const msg = (e as { response?: { data?: { message?: string } } })?.response?.data?.message;
      alert(msg || 'No se pudo confirmar.');
    } finally { setBusyId(null); }
  };

  const statusLabel = (s: string) => ({
    PENDING: { text: 'Pendiente', cls: 'bg-amber-100 text-amber-700' },
    AWAITING_SPEI: { text: 'Validando SPEI', cls: 'bg-blue-100 text-blue-700' },
    PENDING_MANUAL_CONFIRM: { text: 'Confirma manualmente', cls: 'bg-orange-100 text-orange-700' },
    PAID: { text: 'Pagada', cls: 'bg-emerald-100 text-emerald-700' },
    VOIDED: { text: 'Anulada', cls: 'bg-slate-100 text-slate-600' },
  })[s] || { text: s, cls: 'bg-slate-100 text-slate-600' };

  if (loading) return <div className="animate-pulse h-48 bg-slate-100 rounded-2xl" />;
  if (items.length === 0) {
    return (
      <div className="bg-white rounded-2xl border border-slate-200 p-8 text-center text-slate-500">
        <DollarSign className="w-10 h-10 mx-auto text-slate-300 mb-3" />
        <p className="text-sm">Sin comisiones registradas.</p>
      </div>
    );
  }
  return (
    <div className="space-y-3">
      {items.map((c) => {
        const l = statusLabel(c.status);
        return (
          <div key={c.id} className="bg-white rounded-2xl border border-slate-200 p-4 shadow-sm">
            <div className="flex items-start justify-between gap-3 mb-2">
              <div>
                <p className="text-xs text-slate-400 font-bold uppercase">Comisión</p>
                <p className="text-2xl font-extrabold text-slate-900">{fmtMoney(c.commissionAmount)}</p>
                <p className="text-xs text-slate-500 mt-0.5">
                  {c.contractMonths} meses × {fmtMoney(c.monthlyRent)} × {(c.commissionPct * 100).toFixed(2)}%
                </p>
              </div>
              <span className={`px-2 py-1 text-xs font-bold rounded-lg ${l.cls}`}>{l.text}</span>
            </div>
            <div className="text-xs text-slate-500 border-t border-slate-100 pt-2">
              <p>Origen: <strong>{c.agentSource}</strong></p>
              <p>Creada: {fmtDate(c.createdAt)}</p>
              {c.paidAt && <p>Pagada: {fmtDate(c.paidAt)}</p>}
              {c.speiLastError && <p className="text-rose-600 mt-1">Último error SPEI: {c.speiLastError}</p>}
            </div>
            {c.status === 'PENDING_MANUAL_CONFIRM' && (
              <button
                type="button"
                onClick={() => confirm(c.id)}
                disabled={busyId === c.id}
                className="mt-3 w-full py-2 bg-emerald-600 hover:bg-emerald-700 text-white font-bold rounded-lg text-sm disabled:opacity-60"
              >
                {busyId === c.id ? 'Confirmando…' : 'Confirmar que recibí el depósito'}
              </button>
            )}
          </div>
        );
      })}
    </div>
  );
};

// ══════════════════════════════════════════════════════════════════════
// Tab: Cuenta bancaria (CLABE) — genérico para agente y proveedor
// ══════════════════════════════════════════════════════════════════════
export interface BankAccountService {
  getBankAccount: () => Promise<AgentBankAccountDTO | null>;
  upsertBankAccount: (payload: { clabe: string; bankName?: string; accountHolder?: string }) => Promise<AgentBankAccountDTO>;
}

export const BankAccountTab: React.FC<{ service: BankAccountService }> = ({ service }) => {
  const svc = service;
  const [account, setAccount] = useState<AgentBankAccountDTO | null>(null);
  const [loading, setLoading] = useState(true);
  const [form, setForm] = useState({ clabe: '', bankName: '', accountHolder: '' });
  const [busy, setBusy] = useState(false);
  const [msg, setMsg] = useState<{ ok: boolean; text: string } | null>(null);
  const [receiverBanks, setReceiverBanks] = useState<BanxicoInstitution[]>([]);

  const load = async () => {
    setLoading(true);
    try {
      const a = await svc.getBankAccount();
      setAccount(a);
      if (a) {
        setForm({ clabe: a.clabe, bankName: a.bankName || '', accountHolder: a.accountHolder || '' });
      }
    } finally { setLoading(false); }
  };
  useEffect(() => { load(); }, []);
  useEffect(() => {
    let cancelled = false;
    banxicoInstitutionService.getCatalog()
      .then((catalog) => {
        if (!cancelled) setReceiverBanks(catalog.receivers || []);
      })
      .catch(() => {
        if (!cancelled) setReceiverBanks([]);
      });
    return () => { cancelled = true; };
  }, []);
  useEffect(() => {
    if (!form.clabe || receiverBanks.length === 0) return;
    const detected = findBanxicoInstitutionByClabe(form.clabe, receiverBanks);
    if (detected?.name && detected.name !== form.bankName) {
      setForm((f) => ({ ...f, bankName: detected.name }));
    }
  }, [form.clabe, form.bankName, receiverBanks]);

  const save = async () => {
    if (!/^\d{18}$/.test(form.clabe)) {
      setMsg({ ok: false, text: 'La CLABE debe tener exactamente 18 dígitos.' });
      return;
    }
    setBusy(true);
    setMsg(null);
    try {
      const a = await svc.upsertBankAccount({
        clabe: form.clabe,
        bankName: form.bankName.trim() || undefined,
        accountHolder: form.accountHolder.trim() || undefined,
      });
      setAccount(a);
      setMsg({ ok: true, text: 'Cuenta guardada. Validación en proceso.' });
    } catch (e: unknown) {
      const eMsg = (e as { response?: { data?: { message?: string } } })?.response?.data?.message;
      setMsg({ ok: false, text: eMsg || 'Error al guardar.' });
    } finally { setBusy(false); }
  };

  if (loading) return <div className="animate-pulse h-48 bg-slate-100 rounded-2xl" />;

  const statusLabel = (s?: string) => ({
    PENDING: { text: 'Pendiente de validación', cls: 'bg-amber-100 text-amber-700' },
    VALIDATED: { text: 'Validada', cls: 'bg-emerald-100 text-emerald-700' },
    FAILED: { text: 'Falló validación', cls: 'bg-rose-100 text-rose-700' },
  })[s || ''] || { text: s || '—', cls: 'bg-slate-100 text-slate-600' };
  const l = statusLabel(account?.validationStatus);

  const input = 'w-full border border-slate-300 rounded-lg p-2 text-sm';

  return (
    <div className="bg-white rounded-2xl border border-slate-200 p-6 shadow-sm max-w-xl">
      <div className="flex items-center gap-3 mb-4">
        <div className="w-10 h-10 rounded-xl bg-violet-100 text-violet-600 flex items-center justify-center">
          <Landmark className="w-5 h-5" />
        </div>
        <div>
          <h2 className="text-lg font-bold text-slate-800">Cuenta bancaria para recibir pagos</h2>
          <p className="text-xs text-slate-500">El dueño transferirá aquí tu comisión / costo de servicio.</p>
        </div>
      </div>
      {account && (
        <div className="mb-4 p-3 bg-slate-50 border border-slate-100 rounded-xl flex items-center justify-between">
          <div>
            <p className="text-xs text-slate-500 font-bold uppercase">Estado</p>
            <p className="text-sm text-slate-700">{account.bankName || 'Banco no especificado'} · **** {account.clabe.slice(-4)}</p>
            {account.lastValidationError && <p className="text-xs text-rose-600 mt-1">{account.lastValidationError}</p>}
          </div>
          <span className={`px-2 py-1 text-xs font-bold rounded-lg ${l.cls}`}>{l.text}</span>
        </div>
      )}
      <div className="space-y-3">
        <div>
          <label className="text-xs font-semibold text-slate-600 block mb-1">CLABE (18 dígitos) *</label>
          <input
            className={input}
            maxLength={18}
            value={form.clabe}
            onChange={(e) => {
              const next = e.target.value.replace(/\D/g, '').slice(0, 18);
              const detected = findBanxicoInstitutionByClabe(next, receiverBanks);
              setForm((f) => ({ ...f, clabe: next, bankName: detected?.name || '' }));
            }}
            placeholder="012180001234567890"
          />
        </div>
        <div>
          <label className="text-xs font-semibold text-slate-600 block mb-1">Banco Banxico detectado por CLABE</label>
          <input className={`${input} bg-slate-50 text-slate-700`} value={form.bankName} readOnly placeholder="Se detecta automáticamente" />
          <p className="text-[11px] text-slate-400 mt-1">
            No editable: el banco se determina desde la CLABE con el catálogo oficial Banxico.
          </p>
        </div>
        <div>
          <label className="text-xs font-semibold text-slate-600 block mb-1">Titular de la cuenta</label>
          <input className={input} value={form.accountHolder} onChange={(e) => setForm((f) => ({ ...f, accountHolder: e.target.value }))} placeholder="Nombre completo" />
        </div>
      </div>
      {msg && (
        <div className={`mt-3 px-3 py-2 rounded-lg text-sm flex items-center gap-2 ${msg.ok ? 'bg-emerald-50 text-emerald-700 border border-emerald-100' : 'bg-rose-50 text-rose-700 border border-rose-100'}`}>
          {msg.ok ? <CheckCircle2 className="w-4 h-4" /> : <AlertCircle className="w-4 h-4" />} {msg.text}
        </div>
      )}
      <button
        type="button"
        onClick={save}
        disabled={busy}
        className="mt-4 w-full py-2.5 bg-violet-600 hover:bg-violet-700 text-white font-bold rounded-lg text-sm disabled:opacity-60"
      >
        {busy ? 'Guardando…' : account ? 'Actualizar' : 'Guardar cuenta'}
      </button>
    </div>
  );
};

// ── Modal shell reusable ─────────────────────────────────────────────────────
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
export const RealEstateAgentDashboardLayout: React.FC = () => {
  const navigate = useNavigate();
  const { user, logout } = useAuth();
  const [tab, setTab] = useState<AgentTab>('INVITES');
  const [refreshKey, setRefreshKey] = useState(0);
  const [prefsOpen, setPrefsOpen] = useState(false);
  const [prefsTab, setPrefsTab] = useState<'PREFS' | 'HISTORY'>('PREFS');
  // V63 — wizard bloqueante idéntico al del MAINTENANCE_PROVIDER.
  const [onboardingRequired, setOnboardingRequired] = useState<boolean | null>(null);

  const handleLogout = async () => { await logout(); navigate('/login'); };
  const onChanged = () => setRefreshKey((k) => k + 1);

  useEffect(() => {
    let cancelled = false;
    agentBankAccountService.getStatus('REAL_ESTATE_AGENT')
      .then(s => { if (!cancelled) setOnboardingRequired(!s.complete); })
      .catch(() => { if (!cancelled) setOnboardingRequired(true); });
    return () => { cancelled = true; };
  }, [refreshKey]);

  const tabs = useMemo(() => ([
    { key: 'INVITES' as const, label: 'Invitaciones', icon: Bell },
    { key: 'ACTIVE' as const, label: 'Mis vacancias', icon: Briefcase },
    { key: 'PROSPECTS' as const, label: 'Prospectos', icon: UserPlus },
    { key: 'COMMISSIONS' as const, label: 'Comisiones', icon: DollarSign },
    { key: 'BANK' as const, label: 'Cuenta bancaria', icon: Landmark },
  ]), []);

  return (
    <div className="min-h-screen bg-slate-50 font-sans flex flex-col">
      <header className="bg-slate-900 text-white border-b border-slate-800">
        <div className="max-w-6xl mx-auto px-4 sm:px-6 lg:px-8 h-16 flex items-center justify-between">
          <div className="flex items-center gap-3">
            <div className="w-8 h-8 rounded-lg bg-purple-500/20 text-purple-300 flex items-center justify-center">
              <Briefcase className="w-5 h-5" />
            </div>
            <span className="font-bold tracking-wide text-lg">Portal Agente Inmobiliario</span>
          </div>
          <div className="flex items-center gap-3 flex-wrap justify-end">
            <span className="text-sm font-medium text-slate-300 hidden sm:block">{user?.name}</span>
            <button type="button" onClick={onChanged} className="px-2.5 py-1.5 rounded-lg text-sm text-purple-200 hover:bg-slate-800" title="Recargar">
              <RefreshCw className="w-4 h-4" />
            </button>
            <button type="button" onClick={() => setPrefsOpen(true)} className="flex items-center gap-2 px-3 py-1.5 rounded-lg text-sm font-bold text-purple-300 hover:bg-slate-800">
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
                  tab === t.key ? 'text-purple-700 border-purple-500' : 'text-slate-500 border-transparent hover:text-slate-700'
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
          {tab === 'ACTIVE' && <MyVacanciesTab onChanged={onChanged} />}
          {tab === 'PROSPECTS' && <ProspectsTab />}
          {tab === 'COMMISSIONS' && <CommissionsTab />}
          {tab === 'BANK' && <BankAccountTab service={realEstateAgentService} />}
        </div>
      </main>

      {onboardingRequired && (
        <AgentOnboardingWizard
          role="REAL_ESTATE_AGENT"
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
                <h3 className="text-lg font-bold text-slate-800 flex items-center gap-2"><Bell className="w-5 h-5 text-purple-500" /> Mis notificaciones</h3>
                <div className="flex gap-2 mt-2">
                  <button type="button" onClick={() => setPrefsTab('PREFS')} className={`px-3 py-1.5 text-sm font-semibold border-b-2 -mb-px ${prefsTab === 'PREFS' ? 'text-purple-600 border-purple-500' : 'text-slate-500 border-transparent hover:text-slate-700'}`}>
                    Preferencias
                  </button>
                  <button type="button" onClick={() => setPrefsTab('HISTORY')} className={`px-3 py-1.5 text-sm font-semibold border-b-2 -mb-px ${prefsTab === 'HISTORY' ? 'text-purple-600 border-purple-500' : 'text-slate-500 border-transparent hover:text-slate-700'}`}>
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
