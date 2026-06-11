import React, { useEffect, useState } from 'react';
import { FileText, CheckCircle, AlertCircle, Clock, Download, Eye, X, ShieldCheck, AlertTriangle, Zap, Coins } from 'lucide-react';
import { ledgerService, InvoiceDTO } from '../services/ledgerService';
import { paymentService, TransferProofDTO } from '../services/paymentService';
import { openSecureFile, describeSecureFileError } from '../services/secureFileService';
import { downloadAuthenticatedFile } from '../services/downloadService';

type ActiveTab = 'INVOICES' | 'TRAZABILIDAD';
type ProofFilter = 'ALL' | 'VALIDATED' | 'INCOMPLETE_DATA' | 'REJECTED_BY_CEP' | 'REJECTED';

const PROOF_STATUS_LABELS: Record<string, { label: string; color: string }> = {
  RECEIVED: { label: 'Recibido', color: 'bg-slate-100 text-slate-600' },
  INCOMPLETE_DATA: { label: 'Datos Faltantes', color: 'bg-amber-100 text-amber-700' },
  VALIDATED: { label: 'Validado (CEP)', color: 'bg-emerald-100 text-emerald-700' },
  REJECTED_BY_CEP: { label: 'Rechazado (CEP)', color: 'bg-rose-100 text-rose-700' },
  REJECTED: { label: 'Rechazado (Override)', color: 'bg-rose-100 text-rose-700' },
};

const SETTLEMENT_LABELS: Record<string, { label: string; color: string }> = {
  UNPAID: { label: 'SIN PAGO', color: 'bg-slate-100 text-slate-600' },
  PARTIALLY_PAID: { label: 'PARCIAL', color: 'bg-orange-100 text-orange-700' },
  PAID: { label: 'LIQUIDADO', color: 'bg-emerald-100 text-emerald-700' },
  OVERPAID: { label: 'EXCEDENTE', color: 'bg-teal-100 text-teal-700' },
};

const fmt = (n: number) => n.toLocaleString('en-US', { minimumFractionDigits: 2 });

export const LedgerManager: React.FC = () => {
  const [activeTab, setActiveTab] = useState<ActiveTab>('INVOICES');
  const [invoices, setInvoices] = useState<InvoiceDTO[]>([]);
  const [allProofs, setAllProofs] = useState<TransferProofDTO[]>([]);
  const [loading, setLoading] = useState(true);
  const [filter, setFilter] = useState<'ALL' | 'PENDING' | 'PARTIALLY_PAID' | 'PAID' | 'LATE'>('ALL');
  const [proofFilter, setProofFilter] = useState<ProofFilter>('ALL');
  const [detailProof, setDetailProof] = useState<TransferProofDTO | null>(null);

  const [downloadMonth, setDownloadMonth] = useState(() => {
    const today = new Date();
    return `${today.getFullYear()}-${String(today.getMonth() + 1).padStart(2, '0')}`;
  });

  const loadData = () => {
    setLoading(true);
    Promise.all([ledgerService.getOrgInvoices(), paymentService.getAllProofs()])
      .then(([inv, proofs]) => { setInvoices(inv); setAllProofs(proofs); })
      .catch(console.error)
      .finally(() => setLoading(false));
  };

  useEffect(() => { loadData(); }, []);

  // Se usa downloadAuthenticatedFile para que el Bearer viaje por header (no
  // por query-param) y se aproveche el refresh automático del interceptor de
  // api.ts. El anti-patrón previo (window.open con authorization=... en la URL)
  // NO autenticaba en Spring Security y filtraba el token a logs/referrers.
  const handleDownloadZip = () => {
    downloadAuthenticatedFile(
      `/reports/monthly?monthYear=${downloadMonth}`,
      `Cierre_Contable_${downloadMonth}.zip`,
    ).catch((err) => {
      console.error('Error descargando ZIP contable', err);
      alert('No se pudo descargar el reporte ZIP. Intenta de nuevo.');
    });
  };
  const handleDownloadExcel = () => {
    downloadAuthenticatedFile(
      `/reports/monthly/excel?monthYear=${downloadMonth}`,
      `Reporte_${downloadMonth}.xlsx`,
    ).catch((err) => {
      console.error('Error descargando Excel contable', err);
      alert('No se pudo descargar el reporte Excel. Intenta de nuevo.');
    });
  };

  const filteredInvoices = invoices.filter(inv => filter === 'ALL' || inv.status === filter);
  const filteredProofs = allProofs.filter(p => proofFilter === 'ALL' || p.status === proofFilter);

  const totalCobrar = invoices.filter(i => i.status !== 'PAID').reduce((sum, i) => sum + (i.outstandingAmount || i.totalAmount), 0);
  const totalAtrasado = invoices.filter(i => i.status === 'LATE').reduce((sum, i) => sum + (i.outstandingAmount || i.totalAmount), 0);
  const totalCredito = invoices.reduce((sum, i) => sum + (i.creditBalance || 0), 0);
  const validatedCount = allProofs.filter(p => p.status === 'VALIDATED').length;
  const pendingDataCount = allProofs.filter(p => p.status === 'INCOMPLETE_DATA').length;

  return (
    <div className="space-y-6">
      {/* Header */}
      <div className="flex flex-col md:flex-row md:items-center justify-between gap-4">
        <div>
          <h2 className="text-2xl font-bold text-slate-800 flex items-center gap-2"><FileText className="w-6 h-6 text-brand-500" /> Libro Mayor y Cobranza</h2>
          <p className="text-slate-500 text-sm mt-1">Facturación, pagos automáticos CEP y contabilidad.</p>
        </div>
        <div className="flex bg-white rounded-xl shadow-sm border border-slate-200 overflow-hidden">
          <input type="month" className="text-sm px-4 py-2 outline-none border-r border-slate-200 text-slate-700 font-bold" value={downloadMonth} onChange={e => setDownloadMonth(e.target.value)} />
          <button onClick={handleDownloadExcel} className="bg-emerald-600 hover:bg-emerald-700 transition-colors text-white font-bold text-sm px-3 py-2 flex items-center gap-1.5 border-r border-emerald-500"><Download className="w-4 h-4" /> Excel</button>
          <button onClick={handleDownloadZip} className="bg-indigo-600 hover:bg-indigo-700 transition-colors text-white font-bold text-sm px-3 py-2 flex items-center gap-1.5"><Download className="w-4 h-4" /> ZIP</button>
        </div>
      </div>

      {/* Summary Cards */}
      <div className="grid grid-cols-2 lg:grid-cols-5 gap-4">
        <div className="bg-slate-800 text-white p-5 rounded-2xl shadow-sm border border-slate-700">
          <p className="text-xs font-bold text-slate-400 uppercase mb-1">Pendiente</p>
          <h3 className="text-2xl font-extrabold">${fmt(totalCobrar)}</h3>
        </div>
        <div className="bg-rose-50 text-rose-900 p-5 rounded-2xl shadow-sm border border-rose-100">
          <p className="text-xs font-bold text-rose-500 uppercase mb-1">Morosidad</p>
          <h3 className="text-2xl font-extrabold">${fmt(totalAtrasado)}</h3>
        </div>
        <div className="bg-emerald-50 text-emerald-900 p-5 rounded-2xl shadow-sm border border-emerald-100">
          <p className="text-xs font-bold text-emerald-600 uppercase mb-1 flex items-center gap-1"><Zap className="w-3 h-3" /> Validados CEP</p>
          <h3 className="text-2xl font-extrabold">{validatedCount}</h3>
        </div>
        <div className="bg-amber-50 text-amber-900 p-5 rounded-2xl shadow-sm border border-amber-100">
          <p className="text-xs font-bold text-amber-600 uppercase mb-1">Datos Faltantes</p>
          <h3 className="text-2xl font-extrabold">{pendingDataCount}</h3>
        </div>
        {totalCredito > 0 && (
          <div className="bg-teal-50 text-teal-900 p-5 rounded-2xl shadow-sm border border-teal-100">
            <p className="text-xs font-bold text-teal-600 uppercase mb-1 flex items-center gap-1"><Coins className="w-3 h-3" /> Saldo a Favor</p>
            <h3 className="text-2xl font-extrabold">${fmt(totalCredito)}</h3>
          </div>
        )}
      </div>

      {/* Tabs */}
      <div className="flex gap-2">
        <button onClick={() => setActiveTab('INVOICES')} className={`px-4 py-2 rounded-lg text-sm font-bold transition-colors ${activeTab === 'INVOICES' ? 'bg-slate-900 text-white' : 'bg-white text-slate-600 hover:bg-slate-100 border border-slate-200'}`}>
          <FileText className="w-4 h-4 inline mr-1.5" />Libro Mayor
        </button>
        <button onClick={() => setActiveTab('TRAZABILIDAD')} className={`px-4 py-2 rounded-lg text-sm font-bold transition-colors ${activeTab === 'TRAZABILIDAD' ? 'bg-indigo-600 text-white' : 'bg-white text-slate-600 hover:bg-slate-100 border border-slate-200'}`}>
          <ShieldCheck className="w-4 h-4 inline mr-1.5" />Trazabilidad SPEI
        </button>
      </div>

      {/* ─── Invoices Tab ─── */}
      {activeTab === 'INVOICES' && (
        <div className="bg-white rounded-2xl border border-slate-200 overflow-hidden shadow-sm">
          <div className="p-4 border-b border-slate-100 flex items-center gap-2 bg-slate-50 overflow-x-auto">
            {(['ALL', 'PENDING', 'PARTIALLY_PAID', 'LATE', 'PAID'] as const).map(f => (
              <button key={f} onClick={() => setFilter(f)} className={`px-4 py-2 rounded-lg text-sm font-bold whitespace-nowrap transition-colors ${filter === f ? 'bg-brand-500 text-white shadow-sm' : 'bg-white text-slate-600 hover:bg-slate-100 border border-slate-200'}`}>
                {f === 'ALL' ? 'Todos' : f === 'PENDING' ? 'Pendientes' : f === 'PARTIALLY_PAID' ? 'Parciales' : f === 'LATE' ? 'Vencidos' : 'Pagados'}
              </button>
            ))}
          </div>

          {loading ? (
            <div className="p-10 text-center text-slate-400 animate-pulse">Cargando...</div>
          ) : filteredInvoices.length === 0 ? (
            <div className="p-10 text-center"><CheckCircle className="w-12 h-12 text-slate-200 mx-auto mb-3" /><p className="text-slate-500 font-medium">No hay recibos en esta categoría.</p></div>
          ) : (
            <div className="overflow-x-auto">
              <table className="w-full text-left border-collapse">
                <thead>
                  <tr className="bg-white border-b border-slate-100 text-xs uppercase tracking-wider text-slate-500">
                    <th className="p-4 font-bold">Mes</th>
                    <th className="p-4 font-bold">Inquilino</th>
                    <th className="p-4 font-bold text-right">Renta</th>
                    <th className="p-4 font-bold text-right">Pagado</th>
                    <th className="p-4 font-bold text-right">Pendiente</th>
                    <th className="p-4 font-bold text-center">Liquidación</th>
                    <th className="p-4 font-bold text-center">Estatus</th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-slate-50">
                  {filteredInvoices.map(inv => {
                    const stl = SETTLEMENT_LABELS[inv.settlementStatus] || SETTLEMENT_LABELS.UNPAID;
                    return (
                      <tr key={inv.id} className="hover:bg-slate-50/50 transition-colors">
                        <td className="p-4">
                          <div className="font-bold text-slate-800">{inv.monthYear}</div>
                          <div className="text-xs text-slate-500 flex items-center gap-1 mt-0.5"><Clock className="w-3 h-3" /> {inv.dueDate}</div>
                        </td>
                        <td className="p-4">
                          <div className="font-bold text-slate-700">{inv.tenantName}</div>
                          <div className="text-xs text-slate-400">{inv.tenantEmail}</div>
                        </td>
                        <td className="p-4 text-right">
                          <div className="font-extrabold text-slate-900">${fmt(inv.totalAmount)}</div>
                          {inv.appliedLateFee > 0 && <div className="text-[10px] text-rose-500 font-bold flex items-center justify-end gap-0.5"><AlertCircle className="w-3 h-3" />+${fmt(inv.appliedLateFee)}</div>}
                        </td>
                        <td className="p-4 text-right">
                          <div className={`font-bold ${inv.paidAmount > 0 ? 'text-emerald-600' : 'text-slate-300'}`}>${fmt(inv.paidAmount || 0)}</div>
                        </td>
                        <td className="p-4 text-right">
                          <div className={`font-bold ${(inv.outstandingAmount || 0) > 0 ? 'text-rose-600' : 'text-slate-300'}`}>
                            ${fmt(inv.outstandingAmount || 0)}
                          </div>
                          {(inv.creditBalance || 0) > 0 && (
                            <div className="text-[10px] text-teal-600 font-bold flex items-center justify-end gap-0.5"><Coins className="w-3 h-3" />+${fmt(inv.creditBalance)} saldo</div>
                          )}
                        </td>
                        <td className="p-4 text-center">
                          <span className={`inline-flex items-center px-2 py-0.5 rounded-full text-[10px] font-bold ${stl.color}`}>{stl.label}</span>
                        </td>
                        <td className="p-4 text-center">
                          <span className={`inline-flex items-center px-2.5 py-1 rounded-full text-xs font-bold ${
                            inv.status === 'PAID' ? 'bg-emerald-100 text-emerald-700' :
                            inv.status === 'LATE' ? 'bg-rose-100 text-rose-700' :
                            inv.status === 'PARTIALLY_PAID' ? 'bg-orange-100 text-orange-700' :
                            'bg-amber-100 text-amber-700'
                          }`}>
                            {inv.status === 'PAID' ? '✓ PAGADO' : inv.status === 'LATE' ? 'VENCIDO' : inv.status === 'PARTIALLY_PAID' ? 'PARCIAL' : 'PENDIENTE'}
                          </span>
                          {inv.paidDate && <p className="text-[10px] text-slate-400 mt-1">{inv.paidDate}</p>}
                        </td>
                      </tr>
                    );
                  })}
                </tbody>
              </table>
            </div>
          )}
        </div>
      )}

      {/* ─── Trazabilidad SPEI Tab ─── */}
      {activeTab === 'TRAZABILIDAD' && (
        <div className="bg-white rounded-2xl border border-slate-200 overflow-hidden shadow-sm">
          <div className="p-4 border-b border-slate-100 bg-indigo-50 flex flex-col md:flex-row md:items-center justify-between gap-3">
            <h3 className="text-lg font-bold text-indigo-800 flex items-center gap-2"><ShieldCheck className="w-5 h-5" /> Validaciones Automáticas CEP</h3>
            <div className="flex gap-1.5 overflow-x-auto">
              {(['ALL', 'VALIDATED', 'INCOMPLETE_DATA', 'REJECTED_BY_CEP'] as ProofFilter[]).map(f => (
                <button key={f} onClick={() => setProofFilter(f)} className={`px-3 py-1.5 rounded-lg text-xs font-bold whitespace-nowrap transition-colors ${proofFilter === f ? 'bg-indigo-600 text-white' : 'bg-white text-slate-600 hover:bg-indigo-100 border border-indigo-200'}`}>
                  {f === 'ALL' ? 'Todos' : PROOF_STATUS_LABELS[f]?.label || f}
                </button>
              ))}
            </div>
          </div>
          {filteredProofs.length === 0 ? (
            <div className="p-10 text-center"><CheckCircle className="w-12 h-12 text-slate-200 mx-auto mb-3" /><p className="text-slate-500 font-medium">No hay comprobantes registrados.</p></div>
          ) : (
            <div className="divide-y divide-slate-100">
              {filteredProofs.map(proof => {
                const statusInfo = PROOF_STATUS_LABELS[proof.status] || { label: proof.status, color: 'bg-slate-100 text-slate-600' };
                return (
                  <div key={proof.id} className="p-5 flex flex-col md:flex-row md:items-center justify-between gap-4 hover:bg-slate-50/50 transition-colors">
                    <div className="flex-1 min-w-0">
                      <div className="flex items-center gap-3 mb-1.5">
                        <p className="font-bold text-slate-800">{proof.tenantName}</p>
                        <span className={`text-[11px] font-bold px-2 py-0.5 rounded-full ${statusInfo.color}`}>{statusInfo.label}</span>
                      </div>
                      <div className="flex flex-wrap gap-x-4 gap-y-1 text-sm text-slate-500">
                        <span><strong className="text-slate-600">Mes:</strong> {proof.monthYear}</span>
                        {proof.claveRastreo && <span className="font-mono"><strong className="text-slate-600">Clave:</strong> {proof.claveRastreo}</span>}
                        {proof.amount != null && <span><strong className="text-slate-600">Monto:</strong> ${proof.amount.toLocaleString('en-US')}</span>}
                        {proof.reviewedBy && <span><strong className="text-slate-600">Validado por:</strong> {proof.reviewedBy}</span>}
                      </div>
                      {proof.rejectionReason && <p className="text-xs text-rose-600 mt-1 flex items-center gap-1"><AlertTriangle className="w-3 h-3" /> {proof.rejectionReason}</p>}
                    </div>
                    <button onClick={() => setDetailProof(proof)} className="px-3 py-2 bg-slate-100 hover:bg-slate-200 rounded-lg text-sm font-semibold text-slate-600 flex items-center gap-1 shrink-0"><Eye className="w-4 h-4" /> Detalle</button>
                  </div>
                );
              })}
            </div>
          )}
        </div>
      )}

      {/* ─── Proof Detail Modal (read-only) ─── */}
      {detailProof && (
        <div className="fixed inset-0 z-[120] flex items-center justify-center p-4">
          <div className="absolute inset-0 bg-slate-900/70 backdrop-blur-sm" onClick={() => setDetailProof(null)} />
          <div className="relative bg-white rounded-2xl w-full max-w-lg shadow-2xl border border-slate-200">
            <div className="px-6 py-4 border-b border-slate-100 bg-indigo-50 flex items-center justify-between">
              <div><h3 className="text-lg font-bold text-indigo-800">Detalle de Comprobante</h3><p className="text-sm text-indigo-600">{detailProof.tenantName} — {detailProof.monthYear}</p></div>
              <button onClick={() => setDetailProof(null)} className="p-1 hover:bg-indigo-100 rounded"><X className="w-5 h-5 text-indigo-400" /></button>
            </div>
            <div className="p-6 space-y-4">
              <span className={`text-xs font-bold px-2.5 py-1 rounded-full ${(PROOF_STATUS_LABELS[detailProof.status] || { color: 'bg-slate-100 text-slate-600' }).color}`}>{(PROOF_STATUS_LABELS[detailProof.status] || { label: detailProof.status }).label}</span>
              <div className="grid grid-cols-2 gap-3 text-sm">
                <div><p className="text-xs text-slate-400 font-bold">Clave Rastreo</p><p className="font-mono font-bold text-slate-700">{detailProof.claveRastreo || '—'}</p></div>
                <div><p className="text-xs text-slate-400 font-bold">Monto</p><p className="font-bold text-slate-700">{detailProof.amount != null ? `$${detailProof.amount.toLocaleString('en-US')}` : '—'}</p></div>
                <div><p className="text-xs text-slate-400 font-bold">Banco Emisor</p><p className="font-medium text-slate-600">{detailProof.bankEmitter || '—'}</p></div>
                <div><p className="text-xs text-slate-400 font-bold">Cuenta Receptora</p><p className="font-medium text-slate-600">{detailProof.accountReceiver || '—'}</p></div>
                <div><p className="text-xs text-slate-400 font-bold">Fecha Transferencia</p><p className="font-medium text-slate-600">{detailProof.transferDate || '—'}</p></div>
                <div><p className="text-xs text-slate-400 font-bold">Validado Por</p><p className="font-medium text-slate-600">{detailProof.reviewedBy || '—'}</p></div>
              </div>
              {detailProof.rejectionReason && <div className="bg-rose-50 border border-rose-200 rounded-lg p-3 text-sm text-rose-700"><strong>Motivo:</strong> {detailProof.rejectionReason}</div>}
              {detailProof.missingFields && <div className="bg-amber-50 border border-amber-200 rounded-lg p-3 text-sm text-amber-700"><strong>Campos faltantes:</strong> {detailProof.missingFields}</div>}
              {detailProof.fileUrl && detailProof.id && (
                <div className="flex flex-wrap gap-2">
                  <button
                    type="button"
                    onClick={async () => {
                      try {
                        await openSecureFile('transfer-proof', detailProof.id!);
                      } catch (err) {
                        window.alert(describeSecureFileError(err));
                      }
                    }}
                    className="inline-flex items-center gap-2 px-4 py-2 bg-slate-100 hover:bg-slate-200 rounded-lg text-sm font-semibold text-slate-700"
                  >
                    <FileText className="w-4 h-4" /> Ver adjunto
                  </button>
                  {detailProof.cepPdfAvailable && (
                    <button
                      type="button"
                      onClick={async () => {
                        try {
                          await openSecureFile('transfer-proof-cep-pdf', detailProof.id!);
                        } catch (err) {
                          window.alert(describeSecureFileError(err));
                        }
                      }}
                      className="inline-flex items-center gap-2 px-4 py-2 bg-emerald-600 hover:bg-emerald-700 rounded-lg text-sm font-semibold text-white"
                    >
                      <Download className="w-4 h-4" /> Abrir CEP PDF
                    </button>
                  )}
                  {detailProof.cepXmlAvailable && (
                    <button
                      type="button"
                      onClick={async () => {
                        try {
                          await openSecureFile('transfer-proof-cep-xml', detailProof.id!, {
                            download: true,
                            suggestedName: `cep-banxico-${detailProof.monthYear || detailProof.id}.xml`,
                          });
                        } catch (err) {
                          window.alert(describeSecureFileError(err));
                        }
                      }}
                      className="inline-flex items-center gap-2 px-4 py-2 bg-white hover:bg-slate-50 rounded-lg text-sm font-semibold text-slate-700 border border-slate-200"
                    >
                      <Download className="w-4 h-4" /> Descargar CEP XML
                    </button>
                  )}
                </div>
              )}
            </div>
          </div>
        </div>
      )}
    </div>
  );
};
