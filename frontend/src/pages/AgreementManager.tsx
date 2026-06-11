import React, { useEffect, useState } from 'react';
import { Handshake, CheckCircle, XCircle, Eye, X } from 'lucide-react';
import { agreementService, PaymentAgreementDTO } from '../services/agreementService';

type ViewMode = 'PENDING' | 'ALL';

const STATUS_LABELS: Record<string, { label: string; color: string }> = {
  REQUESTED: { label: 'Solicitado', color: 'bg-amber-100 text-amber-700' },
  APPROVED: { label: 'Aprobado', color: 'bg-emerald-100 text-emerald-700' },
  ACTIVE: { label: 'Activo', color: 'bg-blue-100 text-blue-700' },
  REJECTED: { label: 'Rechazado', color: 'bg-rose-100 text-rose-700' },
  COMPLETED: { label: 'Completado', color: 'bg-slate-100 text-slate-700' },
  BREACHED: { label: 'Incumplido', color: 'bg-red-100 text-red-700' },
  CANCELLED: { label: 'Cancelado', color: 'bg-slate-100 text-slate-500' },
};

const fmt = (n: number) => n.toLocaleString('en-US', { minimumFractionDigits: 2 });

export const AgreementManager: React.FC = () => {
  const [viewMode, setViewMode] = useState<ViewMode>('PENDING');
  const [agreements, setAgreements] = useState<PaymentAgreementDTO[]>([]);
  const [loading, setLoading] = useState(true);
  const [detail, setDetail] = useState<PaymentAgreementDTO | null>(null);

  // Approve modal
  const [approveModal, setApproveModal] = useState<PaymentAgreementDTO | null>(null);
  const [approvedAmount, setApprovedAmount] = useState('');
  const [installmentCount, setInstallmentCount] = useState(1);
  const [approveLoading, setApproveLoading] = useState(false);

  // Reject modal
  const [rejectModal, setRejectModal] = useState<PaymentAgreementDTO | null>(null);
  const [rejectionReason, setRejectionReason] = useState('');
  const [rejectLoading, setRejectLoading] = useState(false);

  const loadData = () => {
    setLoading(true);
    const fn = viewMode === 'PENDING' ? agreementService.getPendingAgreements : agreementService.getAllAgreements;
    fn().then(setAgreements).catch(console.error).finally(() => setLoading(false));
  };

  useEffect(() => { loadData(); }, [viewMode]);

  const handleApprove = async () => {
    if (!approveModal || !approvedAmount) return;
    setApproveLoading(true);
    try {
      const total = Number(approvedAmount);
      // Generate equal installments
      let installments: { dueDate: string; amount: number }[] | undefined;
      if (installmentCount > 1) {
        const perInst = Math.round((total / installmentCount) * 100) / 100;
        installments = [];
        const today = new Date();
        for (let i = 0; i < installmentCount; i++) {
          const d = new Date(today);
          d.setMonth(d.getMonth() + i + 1);
          installments.push({ dueDate: d.toISOString().split('T')[0], amount: i === installmentCount - 1 ? Math.round((total - perInst * i) * 100) / 100 : perInst });
        }
      }
      await agreementService.approveAgreement(approveModal.id, total, installments);
      setApproveModal(null);
      setApprovedAmount('');
      setInstallmentCount(1);
      loadData();
    } catch (e: any) {
      alert(e.response?.data?.message || 'Error al aprobar convenio.');
    } finally {
      setApproveLoading(false);
    }
  };

  const handleReject = async () => {
    if (!rejectModal) return;
    setRejectLoading(true);
    try {
      await agreementService.rejectAgreement(rejectModal.id, rejectionReason || undefined);
      setRejectModal(null);
      setRejectionReason('');
      loadData();
    } catch (e: any) {
      alert(e.response?.data?.message || 'Error al rechazar convenio.');
    } finally {
      setRejectLoading(false);
    }
  };

  const pendingCount = agreements.filter(a => a.status === 'REQUESTED').length;

  return (
    <div className="space-y-6">
      <div className="flex flex-col md:flex-row md:items-center justify-between gap-4">
        <div>
          <h2 className="text-2xl font-bold text-slate-800 flex items-center gap-2"><Handshake className="w-6 h-6 text-violet-500" /> Convenios de Pago</h2>
          <p className="text-slate-500 text-sm mt-1">Solicitudes de arrendatarios para acuerdos de pago.</p>
        </div>
        <div className="flex bg-white rounded-xl shadow-sm border border-slate-200 overflow-hidden">
          <button onClick={() => setViewMode('PENDING')} className={`px-4 py-2 text-sm font-bold transition-colors ${viewMode === 'PENDING' ? 'bg-violet-600 text-white' : 'text-slate-600 hover:bg-slate-50'}`}>
            Pendientes {pendingCount > 0 && <span className="ml-1 px-1.5 py-0.5 bg-white/30 rounded-full text-xs">{pendingCount}</span>}
          </button>
          <button onClick={() => setViewMode('ALL')} className={`px-4 py-2 text-sm font-bold transition-colors ${viewMode === 'ALL' ? 'bg-violet-600 text-white' : 'text-slate-600 hover:bg-slate-50'}`}>
            Todos
          </button>
        </div>
      </div>

      {/* List */}
      <div className="bg-white rounded-2xl border border-slate-200 shadow-sm overflow-hidden">
        {loading ? (
          <div className="p-10 text-center text-slate-400 animate-pulse">Cargando...</div>
        ) : agreements.length === 0 ? (
          <div className="p-10 text-center">
            <Handshake className="w-12 h-12 text-slate-200 mx-auto mb-3" />
            <p className="text-slate-500 font-medium">{viewMode === 'PENDING' ? 'No hay solicitudes pendientes.' : 'No hay convenios registrados.'}</p>
          </div>
        ) : (
          <div className="divide-y divide-slate-100">
            {agreements.map(ag => {
              const st = STATUS_LABELS[ag.status] || { label: ag.status, color: 'bg-slate-100 text-slate-600' };
              return (
                <div key={ag.id} className="p-5 flex flex-col md:flex-row md:items-center justify-between gap-4 hover:bg-slate-50/50 transition-colors">
                  <div className="flex-1 min-w-0">
                    <div className="flex items-center gap-3 mb-1">
                      <p className="font-bold text-slate-800">{ag.tenantName}</p>
                      <span className={`text-[11px] font-bold px-2 py-0.5 rounded-full ${st.color}`}>{st.label}</span>
                    </div>
                    <div className="flex flex-wrap gap-x-4 gap-y-1 text-sm text-slate-500">
                      <span><strong className="text-slate-600">Mes:</strong> {ag.monthYear || '-'}</span>
                      <span><strong className="text-slate-600">Solicita:</strong> ${fmt(ag.requestedAmount)}</span>
                      {ag.approvedAmount != null && <span className="text-emerald-600"><strong>Aprobado:</strong> ${fmt(ag.approvedAmount)}</span>}
                      {ag.deferredAmount != null && ag.deferredAmount > 0 && <span className="text-amber-600"><strong>Diferido:</strong> ${fmt(ag.deferredAmount)}</span>}
                    </div>
                    {ag.reason && <p className="text-xs text-slate-400 mt-1">Motivo: {ag.reason}</p>}
                    {ag.description && <p className="text-xs text-slate-500 mt-0.5 italic">{ag.description}</p>}
                  </div>
                  <div className="flex items-center gap-2 shrink-0">
                    {ag.status === 'REQUESTED' && (
                      <>
                        <button onClick={() => { setApproveModal(ag); setApprovedAmount(String(ag.requestedAmount)); }} className="px-3 py-2 bg-emerald-600 hover:bg-emerald-700 text-white rounded-lg text-sm font-bold flex items-center gap-1"><CheckCircle className="w-4 h-4" /> Aprobar</button>
                        <button onClick={() => setRejectModal(ag)} className="px-3 py-2 bg-rose-600 hover:bg-rose-700 text-white rounded-lg text-sm font-bold flex items-center gap-1"><XCircle className="w-4 h-4" /> Rechazar</button>
                      </>
                    )}
                    <button onClick={() => setDetail(ag)} className="px-3 py-2 bg-slate-100 hover:bg-slate-200 rounded-lg text-sm font-semibold text-slate-600 flex items-center gap-1"><Eye className="w-4 h-4" /> Detalle</button>
                  </div>
                </div>
              );
            })}
          </div>
        )}
      </div>

      {/* ─── Approve Modal ─── */}
      {approveModal && (
        <div className="fixed inset-0 z-[120] flex items-center justify-center p-4">
          <div className="absolute inset-0 bg-slate-900/70 backdrop-blur-sm" onClick={() => setApproveModal(null)} />
          <div className="relative bg-white rounded-2xl w-full max-w-md shadow-2xl border border-slate-200">
            <div className="px-6 py-4 border-b border-slate-100 bg-emerald-50">
              <h3 className="text-lg font-bold text-emerald-800">Aprobar Convenio</h3>
              <p className="text-sm text-emerald-600">{approveModal.tenantName} — {approveModal.monthYear}</p>
            </div>
            <div className="p-6 space-y-4">
              <div className="bg-slate-50 rounded-lg p-3 text-sm space-y-1">
                <p><strong>Solicitado:</strong> ${fmt(approveModal.requestedAmount)}</p>
                {approveModal.description && <p className="text-slate-500 italic">{approveModal.description}</p>}
              </div>
              <div>
                <label className="block text-xs font-semibold text-slate-600 mb-1">Monto a aprobar</label>
                <input className="w-full text-sm p-2.5 border border-slate-300 rounded-lg focus:ring-2 focus:ring-emerald-500 outline-none" type="number" step="0.01" value={approvedAmount} onChange={e => setApprovedAmount(e.target.value)} />
              </div>
              <div>
                <label className="block text-xs font-semibold text-slate-600 mb-1">Parcialidades</label>
                <select className="w-full text-sm p-2.5 border border-slate-300 rounded-lg outline-none" value={installmentCount} onChange={e => setInstallmentCount(Number(e.target.value))}>
                  <option value={1}>Pago único</option>
                  <option value={2}>2 parcialidades</option>
                  <option value={3}>3 parcialidades</option>
                  <option value={4}>4 parcialidades</option>
                  <option value={6}>6 parcialidades</option>
                </select>
              </div>
              {Number(approvedAmount) > 0 && (
                <div className="bg-emerald-50 border border-emerald-200 rounded-lg p-3 text-sm text-emerald-700">
                  <p><strong>Diferido:</strong> ${fmt(approveModal.requestedAmount - Number(approvedAmount) > 0 ? approveModal.requestedAmount - Number(approvedAmount) : 0)}</p>
                  {installmentCount > 1 && <p><strong>Cada parcialidad:</strong> ≈${fmt(Number(approvedAmount) / installmentCount)}</p>}
                </div>
              )}
              <div className="flex gap-3 pt-2">
                <button onClick={() => setApproveModal(null)} className="px-4 py-2.5 text-sm text-slate-500 font-semibold">Cancelar</button>
                <button onClick={handleApprove} disabled={approveLoading || !approvedAmount} className="flex-1 py-2.5 bg-emerald-600 hover:bg-emerald-700 text-white font-bold rounded-xl transition-colors disabled:opacity-60">
                  {approveLoading ? 'Procesando...' : 'Confirmar Aprobación'}
                </button>
              </div>
            </div>
          </div>
        </div>
      )}

      {/* ─── Reject Modal ─── */}
      {rejectModal && (
        <div className="fixed inset-0 z-[120] flex items-center justify-center p-4">
          <div className="absolute inset-0 bg-slate-900/70 backdrop-blur-sm" onClick={() => setRejectModal(null)} />
          <div className="relative bg-white rounded-2xl w-full max-w-md shadow-2xl border border-slate-200">
            <div className="px-6 py-4 border-b border-slate-100 bg-rose-50">
              <h3 className="text-lg font-bold text-rose-800">Rechazar Convenio</h3>
              <p className="text-sm text-rose-600">{rejectModal.tenantName} — {rejectModal.monthYear}</p>
            </div>
            <div className="p-6 space-y-4">
              <div>
                <label className="block text-xs font-semibold text-slate-600 mb-1">Motivo del rechazo</label>
                <textarea className="w-full text-sm p-2.5 border border-slate-300 rounded-lg focus:ring-2 focus:ring-rose-500 outline-none min-h-[80px]" placeholder="Explique el motivo..." value={rejectionReason} onChange={e => setRejectionReason(e.target.value)} />
              </div>
              <div className="flex gap-3 pt-2">
                <button onClick={() => setRejectModal(null)} className="px-4 py-2.5 text-sm text-slate-500 font-semibold">Cancelar</button>
                <button onClick={handleReject} disabled={rejectLoading} className="flex-1 py-2.5 bg-rose-600 hover:bg-rose-700 text-white font-bold rounded-xl transition-colors disabled:opacity-60">
                  {rejectLoading ? 'Procesando...' : 'Confirmar Rechazo'}
                </button>
              </div>
            </div>
          </div>
        </div>
      )}

      {/* ─── Detail Modal ─── */}
      {detail && (
        <div className="fixed inset-0 z-[120] flex items-center justify-center p-4">
          <div className="absolute inset-0 bg-slate-900/70 backdrop-blur-sm" onClick={() => setDetail(null)} />
          <div className="relative bg-white rounded-2xl w-full max-w-lg shadow-2xl border border-slate-200">
            <div className="px-6 py-4 border-b border-slate-100 bg-violet-50 flex items-center justify-between">
              <div><h3 className="text-lg font-bold text-violet-800">Detalle Convenio</h3><p className="text-sm text-violet-600">{detail.tenantName} — {detail.monthYear}</p></div>
              <button onClick={() => setDetail(null)} className="p-1 hover:bg-violet-100 rounded"><X className="w-5 h-5 text-violet-400" /></button>
            </div>
            <div className="p-6 space-y-4">
              <span className={`text-xs font-bold px-2.5 py-1 rounded-full ${(STATUS_LABELS[detail.status] || { color: 'bg-slate-100 text-slate-600' }).color}`}>{(STATUS_LABELS[detail.status] || { label: detail.status }).label}</span>
              <div className="grid grid-cols-2 gap-3 text-sm">
                <div><p className="text-xs text-slate-400 font-bold">Solicitado</p><p className="font-bold text-slate-700">${fmt(detail.requestedAmount)}</p></div>
                <div><p className="text-xs text-slate-400 font-bold">Aprobado</p><p className="font-bold text-emerald-600">{detail.approvedAmount != null ? `$${fmt(detail.approvedAmount)}` : '—'}</p></div>
                <div><p className="text-xs text-slate-400 font-bold">Diferido</p><p className="font-bold text-amber-600">{detail.deferredAmount != null ? `$${fmt(detail.deferredAmount)}` : '—'}</p></div>
                <div><p className="text-xs text-slate-400 font-bold">Aprobado por</p><p className="font-medium text-slate-600">{detail.approvedBy || '—'}</p></div>
              </div>
              {detail.reason && <div className="text-sm"><strong className="text-slate-600">Motivo:</strong> {detail.reason}</div>}
              {detail.description && <div className="text-sm text-slate-500 italic">{detail.description}</div>}
              {detail.rejectionReason && <div className="bg-rose-50 border border-rose-200 rounded-lg p-3 text-sm text-rose-700"><strong>Rechazo:</strong> {detail.rejectionReason}</div>}
              {detail.installments && detail.installments.length > 0 && (
                <div className="space-y-2">
                  <p className="text-xs font-bold text-slate-500 uppercase">Parcialidades</p>
                  {detail.installments.map(inst => (
                    <div key={inst.id} className="flex items-center justify-between text-sm bg-slate-50 rounded-lg p-3 border border-slate-100">
                      <span className="text-slate-600 font-medium">{inst.dueDate}</span>
                      <span className="font-bold text-slate-800">${fmt(inst.amount)}</span>
                      <span className={`px-2 py-0.5 text-[10px] font-bold rounded-full ${inst.status === 'PAID' ? 'bg-emerald-100 text-emerald-700' : inst.status === 'LATE' ? 'bg-rose-100 text-rose-700' : 'bg-amber-100 text-amber-700'}`}>
                        {inst.status === 'PAID' ? 'PAGADO' : inst.status === 'LATE' ? 'VENCIDO' : 'PENDIENTE'}
                      </span>
                    </div>
                  ))}
                </div>
              )}
            </div>
          </div>
        </div>
      )}
    </div>
  );
};
