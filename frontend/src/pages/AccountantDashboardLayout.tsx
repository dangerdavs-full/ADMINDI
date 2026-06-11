import { useState, useEffect } from 'react';
import { useAuth } from '../context/AuthContext';
import { useNavigate } from 'react-router-dom';
import { Building2, FileText, DollarSign, Download, LogOut, ArrowRightLeft, ChevronDown, BarChart3, FileSpreadsheet, Bell } from 'lucide-react';
import { contextService, ContextOption } from '../services/contextService';
import { ownerAccountingService } from '../services/ownerAccountingService';
import { defaultMonthYearInBounds } from '../utils/reportingPeriod';
import { ledgerService, InvoiceDTO } from '../services/ledgerService';
import { paymentService, PaymentDTO } from '../services/paymentService';
import { NotificationPreferencesPanel } from './NotificationPreferencesPanel';
import { downloadAuthenticatedFile } from '../services/downloadService';

type AcctTab = 'LEDGER' | 'PAYMENTS' | 'EXPORT' | 'NOTIFS';

export const AccountantDashboardLayout: React.FC = () => {
  const { user, logout, switchContext } = useAuth();
  const navigate = useNavigate();

  const [currentTab, setCurrentTab] = useState<AcctTab>('LEDGER');
  const [invoices, setInvoices] = useState<InvoiceDTO[]>([]);
  const [payments, setPayments] = useState<PaymentDTO[]>([]);
  const [loading, setLoading] = useState(true);

  // Context
  const [contexts, setContexts] = useState<ContextOption[]>([]);
  const [switchingCtx, setSwitchingCtx] = useState(false);
  const [ctxDropdown, setCtxDropdown] = useState(false);
  const [contentKey, setContentKey] = useState(0);

  // Filters
  const [reportBounds, setReportBounds] = useState<{ min: string; max: string } | null>(null);
  const [filterMonth, setFilterMonth] = useState('');
  const [filterStatus, setFilterStatus] = useState<string>('ALL');

  useEffect(() => { contextService.getContexts().then(setContexts).catch(() => setContexts([])); }, []);

  useEffect(() => {
    ownerAccountingService
      .getReportingPeriodBounds()
      .then((b) => {
        const range = { min: b.minMonthYear, max: b.maxMonthYear };
        setReportBounds(range);
        setFilterMonth(defaultMonthYearInBounds(range.min, range.max));
      })
      .catch(() => {
        const d = new Date();
        const cur = `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}`;
        setReportBounds({ min: cur, max: cur });
        setFilterMonth(cur);
      });
  }, []);

  useEffect(() => {
    setLoading(true);
    Promise.all([
      ledgerService.getOrgInvoices(),
      paymentService.getPayments()
    ])
      .then(([inv, pay]) => { setInvoices(inv); setPayments(pay); })
      .catch(console.error)
      .finally(() => setLoading(false));
  }, [contentKey]);

  const handleSwitchCtx = async (ctxId: string) => {
    if (ctxId === user?.contextId || switchingCtx) return;
    setSwitchingCtx(true); setCtxDropdown(false);
    try { await switchContext(ctxId); setContentKey(p => p + 1); } catch (e) { console.error(e); } finally { setSwitchingCtx(false); }
  };

  const handleLogout = async () => {
    await logout();
    navigate('/login');
  };

  const activeCtxName = contexts.find(c => c.id === user?.contextId)?.name || (contexts.length === 1 ? contexts[0].name : null);
  const hasMultiCtx = contexts.length > 1;

  const filteredInvoices = invoices.filter(inv => {
    if (filterStatus !== 'ALL' && inv.status !== filterStatus) return false;
    if (filterMonth && inv.monthYear !== filterMonth) return false;
    return true;
  });

  const totalPending = filteredInvoices.filter(i => i.status !== 'PAID').reduce((s, i) => s + (i.outstandingAmount || i.totalAmount), 0);
  const totalPaid = filteredInvoices.reduce((s, i) => s + (i.paidAmount || 0), 0);
  const totalLate = filteredInvoices.filter(i => i.status === 'LATE').reduce((s, i) => s + (i.outstandingAmount || i.totalAmount), 0);
  const totalCredit = filteredInvoices.reduce((s, i) => s + (i.creditBalance || 0), 0);

  // Descargas autenticadas vía downloadAuthenticatedFile: el Bearer viaja por
  // header (el anti-patrón previo window.open con authorization en la URL NO
  // autenticaba y filtraba el token). También aprovecha el refresh automático
  // del interceptor de api.ts para tokens expirados.
  //
  // Nota V53: antes existía un botón 'Reporte PDF Ejecutivo' que pegaba a
  // `/api/reports/monthly/pdf` — ese endpoint NO está implementado en
  // ReportController, así que cualquier click devolvía 404. Lo retiramos hasta
  // que el Paso G (IA de reporting) genere un PDF ejecutivo real con OpenPDF.
  const [downloadError, setDownloadError] = useState<string | null>(null);

  const handleDownload = (format: 'zip' | 'excel') => {
    setDownloadError(null);
    const ext = format === 'zip' ? 'zip' : 'xlsx';
    const filename = format === 'zip'
      ? `Cierre_Contable_${filterMonth}.zip`
      : `Reporte_${filterMonth}.xlsx`;
    const path = format === 'zip'
      ? `/reports/monthly?monthYear=${filterMonth}`
      : `/reports/monthly/excel?monthYear=${filterMonth}`;
    downloadAuthenticatedFile(path, filename).catch((err) => {
      console.error(`Error descargando reporte ${ext}`, err);
      setDownloadError(
        err?.response?.status === 403
          ? 'No tienes permiso para descargar este reporte en este contexto.'
          : 'No se pudo descargar el reporte. Intenta de nuevo.',
      );
    });
  };

  const tabs = [
    { id: 'LEDGER' as AcctTab, label: 'Libro Mayor', icon: <FileText className="w-4 h-4" /> },
    { id: 'PAYMENTS' as AcctTab, label: 'Historial Pagos', icon: <DollarSign className="w-4 h-4" /> },
    { id: 'EXPORT' as AcctTab, label: 'Exportar', icon: <Download className="w-4 h-4" /> },
    { id: 'NOTIFS' as AcctTab, label: 'Alertas y canales', icon: <Bell className="w-4 h-4" /> },
  ];

  return (
    <div className="flex h-screen bg-slate-50">
      {/* Sidebar */}
      <aside className="w-64 bg-white border-r border-slate-200 flex flex-col">
        <div className="p-6 border-b border-slate-200">
          <h2 className="text-xl font-bold text-slate-900">ADMINDI</h2>
          <p className="text-sm text-slate-500 mt-1">{user?.name}</p>
          <span className="inline-flex mt-1 px-2 py-0.5 text-xs font-semibold rounded-full bg-violet-100 text-violet-700">Contador</span>
        </div>

        {/* Context Selector */}
        {(activeCtxName || hasMultiCtx) && (
          <div className="px-4 py-3 border-b border-slate-100">
            <p className="text-[10px] font-bold text-slate-400 uppercase tracking-wider mb-1.5 flex items-center gap-1"><ArrowRightLeft className="w-3 h-3" /> Contexto activo</p>
            {hasMultiCtx ? (
              <div className="relative">
                <button onClick={() => setCtxDropdown(!ctxDropdown)} disabled={switchingCtx} className="w-full flex items-center justify-between gap-2 px-3 py-2 bg-violet-50 border border-violet-200 rounded-lg text-sm font-semibold text-violet-700 hover:bg-violet-100 transition-colors disabled:opacity-60">
                  <span className="truncate">{switchingCtx ? 'Cambiando...' : (activeCtxName || 'Seleccionar...')}</span>
                  <ChevronDown className={`w-4 h-4 shrink-0 transition-transform ${ctxDropdown ? 'rotate-180' : ''}`} />
                </button>
                {ctxDropdown && (
                  <>
                    <div className="fixed inset-0 z-10" onClick={() => setCtxDropdown(false)} />
                    <div className="absolute left-0 right-0 top-full mt-1 bg-white border border-slate-200 rounded-lg shadow-xl z-20 overflow-hidden">
                      {contexts.map(ctx => (
                        <button key={ctx.id} onClick={() => handleSwitchCtx(ctx.id)} className={`w-full text-left px-3 py-2.5 text-sm transition-colors flex items-center gap-2 ${ctx.id === user?.contextId ? 'bg-violet-50 text-violet-700 font-bold' : 'text-slate-700 hover:bg-slate-50 font-medium'}`}>
                          <Building2 className="w-4 h-4 shrink-0 text-slate-400" />
                          <span className="truncate">{ctx.name}</span>
                        </button>
                      ))}
                    </div>
                  </>
                )}
              </div>
            ) : (
              <div className="flex items-center gap-2 px-3 py-2 bg-slate-50 border border-slate-200 rounded-lg text-sm font-medium text-slate-600">
                <Building2 className="w-4 h-4 shrink-0 text-slate-400" /><span className="truncate">{activeCtxName || 'Sin contexto'}</span>
              </div>
            )}
          </div>
        )}

        <nav className="flex-1 p-4 space-y-1 overflow-y-auto">
          {tabs.map(tab => (
            <button key={tab.id} onClick={() => setCurrentTab(tab.id)} className={`flex items-center gap-3 w-full px-3 py-2 rounded-lg text-sm font-medium transition-colors ${currentTab === tab.id ? 'bg-violet-50 text-violet-700' : 'text-slate-600 hover:bg-slate-50'}`}>
              {tab.icon}<span>{tab.label}</span>
            </button>
          ))}
        </nav>
        <div className="p-4 border-t border-slate-200">
          <button onClick={handleLogout} className="flex items-center gap-3 w-full px-3 py-2 rounded-lg text-sm font-medium text-rose-600 hover:bg-rose-50 transition-colors"><LogOut className="w-4 h-4" /> Cerrar Sesión</button>
        </div>
      </aside>

      {/* Content */}
      <main className="flex-1 overflow-auto">
        <div className="max-w-7xl mx-auto p-8">
          {/* Month filter (no aplica a la vista de Preferencias) */}
          {currentTab !== 'NOTIFS' && (
          <div className="flex flex-wrap items-center gap-4 mb-6">
            <input
              type="month"
              min={reportBounds?.min}
              max={reportBounds?.max}
              value={filterMonth}
              onChange={(e) => {
                const v = e.target.value;
                if (reportBounds && v >= reportBounds.min && v <= reportBounds.max) setFilterMonth(v);
              }}
              disabled={!reportBounds}
              className="px-4 py-2 border border-slate-300 rounded-lg text-sm font-semibold text-slate-700 focus:ring-2 focus:ring-violet-500 outline-none disabled:opacity-50"
            />
            {currentTab === 'LEDGER' && (
              <div className="flex gap-2">
                {['ALL', 'PENDING', 'PARTIALLY_PAID', 'LATE', 'PAID'].map(f => (
                  <button key={f} onClick={() => setFilterStatus(f)} className={`px-3 py-1.5 rounded-lg text-xs font-bold transition-colors ${filterStatus === f ? 'bg-violet-500 text-white' : 'bg-white text-slate-600 hover:bg-slate-100 border border-slate-200'}`}>
                    {f === 'ALL' ? 'Todos' : f === 'PENDING' ? 'Pendientes' : f === 'LATE' ? 'Vencidos' : f === 'PARTIALLY_PAID' ? 'Parciales' : 'Pagados'}
                  </button>
                ))}
              </div>
            )}
          </div>
          )}

          {currentTab === 'LEDGER' && (
            <div className="space-y-6">
              {/* Summary cards */}
              <div className="grid grid-cols-2 md:grid-cols-4 gap-4">
                <div className="bg-white p-5 rounded-2xl border border-slate-200 shadow-sm">
                  <p className="text-xs font-bold text-slate-400 uppercase mb-1">Cobrado</p>
                  <p className="text-2xl font-extrabold text-emerald-600">${totalPaid.toLocaleString('en-US', { minimumFractionDigits: 2 })}</p>
                </div>
                <div className="bg-white p-5 rounded-2xl border border-slate-200 shadow-sm">
                  <p className="text-xs font-bold text-slate-400 uppercase mb-1">Pendiente</p>
                  <p className="text-2xl font-extrabold text-amber-600">${totalPending.toLocaleString('en-US', { minimumFractionDigits: 2 })}</p>
                </div>
                <div className="bg-white p-5 rounded-2xl border border-slate-200 shadow-sm">
                  <p className="text-xs font-bold text-slate-400 uppercase mb-1">Morosidad</p>
                  <p className="text-2xl font-extrabold text-rose-600">${totalLate.toLocaleString('en-US', { minimumFractionDigits: 2 })}</p>
                </div>
                {totalCredit > 0 && (
                  <div className="bg-white p-5 rounded-2xl border border-teal-200 shadow-sm">
                    <p className="text-xs font-bold text-teal-500 uppercase mb-1">Saldo a Favor</p>
                    <p className="text-2xl font-extrabold text-teal-600">${totalCredit.toLocaleString('en-US', { minimumFractionDigits: 2 })}</p>
                  </div>
                )}
              </div>

              {/* Invoice table */}
              <div className="bg-white rounded-2xl border border-slate-200 overflow-hidden shadow-sm">
                {loading ? (
                  <div className="p-10 text-center text-slate-400 animate-pulse">Cargando...</div>
                ) : filteredInvoices.length === 0 ? (
                  <div className="p-10 text-center text-slate-500 font-medium">Sin resultados para este período.</div>
                ) : (
                  <div className="overflow-x-auto">
                    <table className="w-full text-left border-collapse">
                      <thead>
                        <tr className="bg-slate-50 border-b border-slate-100 text-xs uppercase tracking-wider text-slate-500">
                          <th className="p-4 font-bold">Mes</th>
                          <th className="p-4 font-bold">Inquilino</th>
                          <th className="p-4 font-bold">Inmueble</th>
                          <th className="p-4 font-bold text-right">Total</th>
                          <th className="p-4 font-bold text-right">Pagado</th>
                          <th className="p-4 font-bold text-right">Pendiente</th>
                          <th className="p-4 font-bold text-center">Liquidación</th>
                          <th className="p-4 font-bold text-center">Estatus</th>
                          <th className="p-4 font-bold">Parcial / convenio</th>
                          <th className="p-4 font-bold">Fecha Pago</th>
                        </tr>
                      </thead>
                      <tbody className="divide-y divide-slate-50">
                        {filteredInvoices.map(inv => (
                          <tr key={inv.id} className="hover:bg-slate-50/50 transition-colors">
                            <td className="p-4 font-bold text-slate-800">{inv.monthYear}</td>
                            <td className="p-4"><div className="font-semibold text-slate-700">{inv.tenantName}</div><div className="text-xs text-slate-400">{inv.tenantEmail}</div></td>
                            <td className="p-4 text-xs font-mono text-slate-500 max-w-[100px] truncate" title={inv.propertyId}>{inv.propertyId ? `${inv.propertyId.slice(0, 8)}…` : '—'}</td>
                            <td className="p-4 text-right font-extrabold text-slate-900">${inv.totalAmount.toLocaleString('en-US')}</td>
                            <td className="p-4 text-right"><span className={`font-bold ${(inv.paidAmount || 0) > 0 ? 'text-emerald-600' : 'text-slate-300'}`}>${(inv.paidAmount || 0).toLocaleString('en-US')}</span></td>
                            <td className="p-4 text-right"><span className={`font-bold ${(inv.outstandingAmount || 0) > 0 ? 'text-rose-600' : 'text-slate-300'}`}>${(inv.outstandingAmount || 0).toLocaleString('en-US')}</span></td>
                            <td className="p-4 text-center">
                              <span className={`inline-flex px-2 py-0.5 rounded-full text-[10px] font-bold ${inv.settlementStatus === 'PAID' ? 'bg-emerald-100 text-emerald-700' : inv.settlementStatus === 'OVERPAID' ? 'bg-teal-100 text-teal-700' : inv.settlementStatus === 'PARTIALLY_PAID' ? 'bg-orange-100 text-orange-700' : 'bg-slate-100 text-slate-500'}`}>
                                {inv.settlementStatus === 'PAID' ? 'LIQUIDADO' : inv.settlementStatus === 'OVERPAID' ? 'EXCEDENTE' : inv.settlementStatus === 'PARTIALLY_PAID' ? 'PARCIAL' : 'SIN PAGO'}
                              </span>
                              {(inv.creditBalance || 0) > 0 && <div className="text-[10px] text-teal-600 font-bold mt-0.5">+${inv.creditBalance.toLocaleString('en-US')}</div>}
                            </td>
                            <td className="p-4 text-center">
                              <span className={`inline-flex px-2.5 py-1 rounded-full text-xs font-bold ${inv.status === 'PAID' ? 'bg-emerald-100 text-emerald-700' : inv.status === 'LATE' ? 'bg-rose-100 text-rose-700' : inv.status === 'PARTIALLY_PAID' ? 'bg-orange-100 text-orange-700' : 'bg-amber-100 text-amber-700'}`}>
                                {inv.status === 'PAID' ? 'PAGADO' : inv.status === 'LATE' ? 'VENCIDO' : inv.status === 'PARTIALLY_PAID' ? 'PARCIAL' : 'PENDIENTE'}
                              </span>
                            </td>
                            <td className="p-4 text-xs text-slate-600">
                              {inv.shortfallReason && <div><span className="font-bold text-amber-700">{inv.shortfallReason}</span></div>}
                              {inv.promisedCompletionDate && <div className="text-slate-500">Compromiso: {inv.promisedCompletionDate}</div>}
                              {inv.agreementSummaryStatus && <div className="text-violet-600 font-bold">Convenio: {inv.agreementSummaryStatus}</div>}
                              {!inv.shortfallReason && !inv.agreementSummaryStatus && <span className="text-slate-300">—</span>}
                            </td>
                            <td className="p-4 text-sm text-slate-500">{inv.paidDate || '-'}</td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  </div>
                )}
              </div>
            </div>
          )}

          {currentTab === 'PAYMENTS' && (
            <div className="bg-white rounded-2xl border border-slate-200 overflow-hidden shadow-sm">
              <div className="p-4 border-b border-slate-100 bg-slate-50">
                <h3 className="text-lg font-bold text-slate-800 flex items-center gap-2"><DollarSign className="w-5 h-5 text-violet-500" /> Historial de Pagos Confirmados</h3>
              </div>
              {payments.length === 0 ? (
                <div className="p-10 text-center text-slate-500">Sin pagos registrados.</div>
              ) : (
                <div className="overflow-x-auto">
                  <table className="w-full text-left border-collapse">
                    <thead>
                      <tr className="bg-slate-50 border-b border-slate-100 text-xs uppercase tracking-wider text-slate-500">
                        <th className="p-4 font-bold">Mes</th>
                        <th className="p-4 font-bold">Inquilino</th>
                        <th className="p-4 font-bold text-right">Monto</th>
                        <th className="p-4 font-bold text-right">Aplicado</th>
                        <th className="p-4 font-bold text-right">No Aplicado</th>
                        <th className="p-4 font-bold">Método</th>
                        <th className="p-4 font-bold">Confirmado</th>
                        <th className="p-4 font-bold">Fecha</th>
                      </tr>
                    </thead>
                    <tbody className="divide-y divide-slate-50">
                      {payments.map(p => (
                        <tr key={p.id} className="hover:bg-slate-50/50 transition-colors">
                          <td className="p-4 font-bold text-slate-800">{p.monthYear || '-'}</td>
                          <td className="p-4"><div className="font-semibold text-slate-700">{p.tenantName}</div><div className="text-xs text-slate-400">{p.tenantEmail}</div></td>
                          <td className="p-4 text-right font-extrabold text-slate-900">${p.amount.toLocaleString('en-US')}</td>
                          <td className="p-4 text-right font-bold text-emerald-600">{p.appliedAmount != null ? `$${p.appliedAmount.toLocaleString('en-US')}` : '-'}</td>
                          <td className="p-4 text-right">{(p.unappliedAmount || 0) > 0 ? <span className="font-bold text-teal-600">${p.unappliedAmount!.toLocaleString('en-US')}</span> : <span className="text-slate-300">$0</span>}</td>
                          <td className="p-4"><span className="px-2 py-1 bg-slate-100 rounded text-xs font-bold text-slate-600">{p.paymentMethod}</span></td>
                          <td className="p-4 text-sm text-slate-500">{p.confirmedBy || '-'}</td>
                          <td className="p-4 text-sm text-slate-500">{p.paidAt || '-'}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              )}
            </div>
          )}

          {currentTab === 'EXPORT' && (
            <div className="max-w-lg mx-auto space-y-6">
              <div className="text-center mb-8">
                <BarChart3 className="w-12 h-12 text-violet-500 mx-auto mb-3" />
                <h3 className="text-2xl font-bold text-slate-800">Exportar Reportes</h3>
                <p className="text-sm text-slate-500 mt-1">Selecciona el mes y el formato de exportación.</p>
              </div>
              {downloadError && (
                <div className="rounded-xl border border-rose-200 bg-rose-50 text-rose-800 text-sm font-medium px-4 py-3">
                  {downloadError}
                </div>
              )}
              <div className="space-y-3">
                <button onClick={() => handleDownload('excel')} className="w-full flex items-center justify-between p-4 bg-white border border-slate-200 rounded-xl hover:border-violet-300 hover:bg-violet-50 transition-all group">
                  <div className="flex items-center gap-3">
                    <div className="w-10 h-10 bg-emerald-100 rounded-lg flex items-center justify-center text-emerald-600"><FileSpreadsheet className="w-5 h-5" /></div>
                    <div className="text-left"><p className="font-bold text-slate-700">Excel Contable</p><p className="text-xs text-slate-500">Resumen, Pagos, Morosidad, Convenios</p></div>
                  </div>
                  <Download className="w-5 h-5 text-slate-400 group-hover:text-violet-500" />
                </button>
                <button onClick={() => handleDownload('zip')} className="w-full flex items-center justify-between p-4 bg-white border border-slate-200 rounded-xl hover:border-violet-300 hover:bg-violet-50 transition-all group">
                  <div className="flex items-center gap-3">
                    <div className="w-10 h-10 bg-blue-100 rounded-lg flex items-center justify-center text-blue-600"><Download className="w-5 h-5" /></div>
                    <div className="text-left"><p className="font-bold text-slate-700">ZIP Contable</p><p className="text-xs text-slate-500">CSV facturas, convenios, egresos + comprobantes</p></div>
                  </div>
                  <Download className="w-5 h-5 text-slate-400 group-hover:text-violet-500" />
                </button>
              </div>
              <p className="text-xs text-slate-400 leading-relaxed pt-2 border-t border-slate-100">
                El PDF ejecutivo con totales narrados y gráficas llega con el Paso G del roadmap
                (reporting asistido por IA). Hasta entonces el Excel contable y el ZIP con los
                comprobantes originales son la evidencia canónica.
              </p>
            </div>
          )}

          {currentTab === 'NOTIFS' && (
            <NotificationPreferencesPanel />
          )}
        </div>
      </main>
    </div>
  );
};
