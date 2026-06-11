import { useEffect, useState } from 'react';
import { Wrench, Download, FileSpreadsheet } from 'lucide-react';
import { ownerAccountingService, OwnerAccountingSummaryDTO } from '../services/ownerAccountingService';
import { defaultMonthYearInBounds } from '../utils/reportingPeriod';
import { downloadAuthenticatedFile } from '../services/downloadService';

export const OwnerAccountingSummaryPanel: React.FC = () => {
  const [bounds, setBounds] = useState<{ min: string; max: string } | null>(null);
  const [monthYear, setMonthYear] = useState<string>('');
  const [data, setData] = useState<OwnerAccountingSummaryDTO | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [reconciling, setReconciling] = useState(false);
  const [reconcileMsg, setReconcileMsg] = useState<string | null>(null);
  const [downloading, setDownloading] = useState<'zip' | 'excel' | null>(null);
  const [downloadError, setDownloadError] = useState<string | null>(null);

  useEffect(() => {
    ownerAccountingService
      .getReportingPeriodBounds()
      .then((b) => {
        const range = { min: b.minMonthYear, max: b.maxMonthYear };
        setBounds(range);
        setMonthYear((prev) => (prev && prev >= range.min && prev <= range.max ? prev : defaultMonthYearInBounds(range.min, range.max)));
      })
      .catch(() => {
        const d = new Date();
        const fallback = `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}`;
        setBounds({ min: fallback, max: fallback });
        setMonthYear(fallback);
      });
  }, []);

  useEffect(() => {
    if (!monthYear || !bounds) return;
    setLoading(true);
    setError(null);
    ownerAccountingService
      .getSummary(monthYear)
      .then(setData)
      .catch((e) => setError(e.response?.data?.message || e.message || 'Error al cargar resumen'))
      .finally(() => setLoading(false));
  }, [monthYear, bounds]);

  const money = (n: number | undefined) =>
    `$${Number(n ?? 0).toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`;

  const driverLabel = (d?: string) => {
    switch (d) {
      case 'AGREEMENT_DEFERRAL':
        return 'Convenio';
      case 'PAYMENT_SHORTFALL':
        return 'Pago parcial / motivo';
      case 'MIXED':
        return 'Mixto';
      case 'PAYMENT_DELINQUENCY':
        return 'Falta de pago';
      case 'NONE':
        return '-';
      default:
        return d || '-';
    }
  };

  if (!bounds || !monthYear || (loading && !data)) {
    return (
      <div className="text-slate-500 animate-pulse py-12 text-center font-medium">Cargando resumen contable...</div>
    );
  }

  if (error) {
    return (
      <div className="rounded-2xl border border-rose-200 bg-rose-50 text-rose-800 p-6 text-sm font-medium">{error}</div>
    );
  }

  if (!data) return null;

  // Descarga del reporte mensual del dueño. Reutiliza `/api/reports/monthly`
  // (ZIP con CSVs + comprobantes) y `/api/reports/monthly/excel` (XLSX con
  // facturas, pagos, morosidad y convenios). El PreAuthorize del controller
  // acepta OWNER, ACCOUNTANT y el permiso granular REPORT_EXPORT, así que el
  // PROPERTY_ADMIN con ese permiso también puede descargar desde aquí.
  const handleDownload = async (format: 'zip' | 'excel') => {
    if (!monthYear || downloading) return;
    setDownloading(format);
    setDownloadError(null);
    try {
      if (format === 'zip') {
        await downloadAuthenticatedFile(
          `/reports/monthly?monthYear=${monthYear}`,
          `Cierre_Contable_${monthYear}.zip`,
        );
      } else {
        await downloadAuthenticatedFile(
          `/reports/monthly/excel?monthYear=${monthYear}`,
          `Reporte_${monthYear}.xlsx`,
        );
      }
    } catch (e: unknown) {
      const err = e as { response?: { status?: number; data?: { message?: string } } };
      if (err.response?.status === 403) {
        setDownloadError('No tienes permiso para descargar este reporte. Tu rol necesita REPORT_EXPORT.');
      } else {
        setDownloadError(err.response?.data?.message || 'No se pudo descargar el reporte. Intenta de nuevo.');
      }
    } finally {
      setDownloading(null);
    }
  };

  const handleReconcile = async () => {
    setReconciling(true);
    setReconcileMsg(null);
    try {
      const r = await ownerAccountingService.reconcile();
      setReconcileMsg(
        `Reconciliación OK: ${r.propertiesUpdated}/${r.propertiesScanned} inmuebles corregidos, ${r.ghostInvoicesVoided} facturas fantasma anuladas, ${r.orphanLeasesTerminated} contratos huérfanos cerrados.`,
      );
      const fresh = await ownerAccountingService.getSummary(monthYear);
      setData(fresh);
    } catch (e: unknown) {
      const err = e as { response?: { data?: { message?: string } }; message?: string };
      setReconcileMsg(err.response?.data?.message || err.message || 'No se pudo reconciliar.');
    } finally {
      setReconciling(false);
    }
  };

  return (
    <div className="space-y-8">
      <div className="flex flex-wrap items-end justify-between gap-4">
        <div>
          <h1 className="text-2xl font-bold text-slate-900">Resumen contable</h1>
          <p className="text-sm text-slate-500 mt-1">
            Misma base que reportes por inmueble: facturas del mes, egresos con actividad en el mes (creado, aprobado o
            pagado), convenios ligados a esas facturas.
          </p>
          <div className="mt-3 flex flex-wrap items-center gap-2">
            <button
              type="button"
              onClick={handleReconcile}
              disabled={reconciling}
              className="inline-flex items-center gap-1.5 px-3 py-1.5 text-xs font-bold bg-slate-900 text-white rounded-lg hover:bg-slate-800 disabled:opacity-60"
              title="Recalcula la ocupación real de cada inmueble y anula facturas ligadas a expedientes archivados. Idempotente."
            >
              <Wrench className="w-3.5 h-3.5" /> {reconciling ? 'Reconciliando...' : 'Reconciliar estado'}
            </button>
            <button
              type="button"
              onClick={() => handleDownload('excel')}
              disabled={downloading !== null}
              className="inline-flex items-center gap-1.5 px-3 py-1.5 text-xs font-bold bg-emerald-600 text-white rounded-lg hover:bg-emerald-700 disabled:opacity-60"
              title="Descarga el libro mayor del mes en Excel con 4 hojas: facturas, pagos, morosidad y convenios."
            >
              <FileSpreadsheet className="w-3.5 h-3.5" />
              {downloading === 'excel' ? 'Descargando...' : 'Descargar Excel'}
            </button>
            <button
              type="button"
              onClick={() => handleDownload('zip')}
              disabled={downloading !== null}
              className="inline-flex items-center gap-1.5 px-3 py-1.5 text-xs font-bold bg-indigo-600 text-white rounded-lg hover:bg-indigo-700 disabled:opacity-60"
              title="Descarga un ZIP con los CSV contables del mes más los comprobantes SPEI originales (evidencia legal)."
            >
              <Download className="w-3.5 h-3.5" />
              {downloading === 'zip' ? 'Descargando...' : 'ZIP contable'}
            </button>
          </div>
          {reconcileMsg && (
            <p className="mt-2 text-xs text-slate-600 bg-slate-50 border border-slate-200 rounded-lg px-3 py-1.5 inline-block">
              {reconcileMsg}
            </p>
          )}
          {downloadError && (
            <p className="mt-2 text-xs text-rose-800 bg-rose-50 border border-rose-200 rounded-lg px-3 py-1.5 inline-block">
              {downloadError}
            </p>
          )}
        </div>
        <label className="flex flex-col gap-1 text-xs font-bold text-slate-500 uppercase tracking-wider">
          Periodo (mes)
          <input
            type="month"
            min={bounds.min}
            max={bounds.max}
            value={monthYear}
            onChange={(e) => {
              const v = e.target.value;
              if (v >= bounds.min && v <= bounds.max) setMonthYear(v);
            }}
            className="px-4 py-2 border border-slate-300 rounded-xl text-sm font-semibold text-slate-800 focus:ring-2 focus:ring-indigo-500 outline-none bg-white"
          />
          <span className="text-[10px] font-medium text-slate-400 normal-case">
            Permitido: {bounds.min} a {bounds.max} (mes calendario, zona del servidor)
          </span>
        </label>
      </div>

      <div className="grid grid-cols-2 md:grid-cols-4 lg:grid-cols-4 gap-4">
        {[
          { label: 'Renta esperada', value: money(data.expectedIncome), cls: 'text-slate-800' },
          { label: 'Cobrado', value: money(data.collectedIncome), cls: 'text-emerald-600' },
          { label: 'Pendiente cobro', value: money(data.outstandingIncome), cls: 'text-amber-600' },
          { label: 'Creditos (excedentes)', value: money(data.overpaidCredits), cls: 'text-teal-600' },
          // V64 — "Egresos" muestran el NETO (lo que realmente sale del dueño),
          // no el bruto. El ahorro por plataforma se muestra como tarjeta aparte.
          { label: 'Egresos aprobados (mes)', value: money(data.approvedExpenses), cls: 'text-slate-700' },
          { label: 'Egresos pagados (mes)', value: money(data.paidExpenses), cls: 'text-slate-700' },
          { label: 'Egresos pendientes (mes)', value: money(data.pendingExpenses), cls: 'text-orange-600' },
          { label: 'Recargos acumulados', value: money(data.lateFeeAccrued), cls: 'text-rose-600' },
        ].map((k) => (
          <div key={k.label} className="bg-white rounded-2xl border border-slate-200 p-5 shadow-sm">
            <p className="text-[10px] font-bold text-slate-400 uppercase tracking-wider mb-1">{k.label}</p>
            <p className={`text-xl font-extrabold ${k.cls}`}>{k.value}</p>
          </div>
        ))}
      </div>

      {/* V64 — Ahorro plataforma destacado: si hay crédito este mes se muestra
          en una tarjeta ámbar para que el dueño vea el beneficio de usar
          proveedores PLATFORM. Se oculta si es 0 para no ensuciar el dashboard. */}
      {typeof data.platformSavings === 'number' && data.platformSavings > 0 && (
        <div className="bg-gradient-to-r from-sky-50 to-teal-50 rounded-2xl border border-sky-200 p-5 shadow-sm flex items-center justify-between gap-4">
          <div>
            <p className="text-[10px] font-bold text-sky-500 uppercase tracking-wider mb-1">
              Ahorro plataforma (este mes)
            </p>
            <p className="text-2xl font-extrabold text-sky-700">{money(data.platformSavings)}</p>
            <p className="text-xs text-slate-500 mt-1 max-w-md">
              Ya lo descontamos de tus egresos. Es el 15% que ADMINDI absorbe cuando contratas a un proveedor de la plataforma.
            </p>
          </div>
          <div className="w-14 h-14 rounded-full bg-white border border-sky-200 flex items-center justify-center text-sky-600 font-bold text-xl shrink-0">
            15%
          </div>
        </div>
      )}

      <div className="grid grid-cols-2 md:grid-cols-4 gap-4">
        {[
          { label: 'Convenios activos (mes)', value: data.activeAgreementsCount },
          { label: 'Convenios incumplidos (mes)', value: data.breachedAgreementsCount },
          { label: 'Inquilinos en mora', value: data.delinquentTenantsCount },
          { label: 'Inmuebles con incidencias', value: data.propertiesWithIssuesCount },
        ].map((k) => (
          <div key={k.label} className="bg-indigo-50/80 rounded-2xl border border-indigo-100 p-4">
            <p className="text-[10px] font-bold text-indigo-400 uppercase tracking-wider mb-1">{k.label}</p>
            <p className="text-2xl font-extrabold text-indigo-800">{k.value}</p>
          </div>
        ))}
      </div>

      {data.alerts.length > 0 && (
        <div className="rounded-2xl border border-amber-200 bg-amber-50/80 p-5">
          <p className="text-sm font-bold text-amber-900 mb-2">Alertas</p>
          <ul className="list-disc list-inside text-sm text-amber-900 space-y-1">
            {data.alerts.map((a, i) => (
              <li key={i}>{a}</li>
            ))}
          </ul>
        </div>
      )}

      <div className="grid grid-cols-1 xl:grid-cols-1 gap-6">
        <div className="bg-white rounded-2xl border border-slate-200 shadow-sm overflow-hidden">
          <div className="px-5 py-3 border-b border-slate-100 bg-slate-50">
            <h3 className="font-bold text-slate-800">Cuentas por cobrar (saldo en el mes)</h3>
          </div>
          <div className="overflow-x-auto max-h-[420px] overflow-y-auto">
            {data.receivables.length === 0 ? (
              <p className="p-6 text-sm text-slate-500">Sin partidas pendientes en este periodo.</p>
            ) : (
              <table className="w-full text-xs min-w-[1100px]">
                <thead className="text-[10px] uppercase text-slate-500 bg-slate-50/80">
                  <tr>
                    <th className="text-left p-2 font-bold">Inmueble</th>
                    <th className="text-left p-2 font-bold">Arrendatario</th>
                    <th className="text-left p-2 font-bold">Mes</th>
                    <th className="text-right p-2 font-bold">Renta esperada</th>
                    <th className="text-right p-2 font-bold">Pagado</th>
                    <th className="text-right p-2 font-bold">Pendiente</th>
                    <th className="text-center p-2 font-bold">Liquidacion</th>
                    <th className="text-left p-2 font-bold">Motivo parcial</th>
                    <th className="text-left p-2 font-bold">Compromiso</th>
                    <th className="text-center p-2 font-bold">Convenio</th>
                    <th className="text-left p-2 font-bold">Origen saldo</th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-slate-100">
                  {data.receivables.map((r) => (
                    <tr key={r.invoiceId} className="hover:bg-slate-50/80">
                      <td className="p-2">
                        <div className="font-semibold text-slate-800">{r.propertyName || '-'}</div>
                        <div className="text-[10px] font-mono text-slate-400 truncate max-w-[140px]" title={r.propertyId}>
                          {r.propertyId?.slice(0, 8)}
                        </div>
                      </td>
                      <td className="p-2 text-slate-700">{r.tenantName}</td>
                      <td className="p-2 font-bold text-slate-800">{r.monthYear}</td>
                      <td className="p-2 text-right font-bold">{money(Number(r.expectedRent))}</td>
                      <td className="p-2 text-right text-emerald-700 font-bold">{money(Number(r.paidAmount))}</td>
                      <td className="p-2 text-right text-rose-600 font-bold">{money(Number(r.outstanding))}</td>
                      <td className="p-2 text-center font-bold text-slate-600">{r.settlementStatus}</td>
                      <td className="p-2 text-slate-600">{r.shortfallReason || '-'}</td>
                      <td className="p-2 text-slate-500">{r.promisedCompletionDate || '-'}</td>
                      <td className="p-2 text-center text-violet-700 font-bold">{r.agreementSummaryStatus || '-'}</td>
                      <td className="p-2 text-slate-700 font-semibold">{driverLabel(r.balanceDriver)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
          </div>
        </div>

        <div className="bg-white rounded-2xl border border-slate-200 shadow-sm overflow-hidden">
          <div className="px-5 py-3 border-b border-slate-100 bg-slate-50">
            <h3 className="font-bold text-slate-800">Egresos con actividad en el mes</h3>
          </div>
          <div className="overflow-x-auto max-h-72 overflow-y-auto">
            {data.expenses.length === 0 ? (
              <p className="p-6 text-sm text-slate-500">Sin egresos en este criterio de mes.</p>
            ) : (
              <table className="w-full text-xs min-w-[800px]">
                <thead className="text-[10px] uppercase text-slate-500 bg-slate-50/80">
                  <tr>
                    <th className="text-left p-2 font-bold">Inmueble</th>
                    <th className="text-left p-2 font-bold">Tipo</th>
                    <th className="text-left p-2 font-bold">Descripcion</th>
                    <th className="text-right p-2 font-bold">Monto</th>
                    <th className="text-center p-2 font-bold">Estado</th>
                    <th className="text-left p-2 font-bold">Origen</th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-slate-100">
                  {data.expenses.map((ex) => (
                    <tr key={ex.id} className="hover:bg-slate-50/80">
                      <td className="p-2">
                        <div className="font-semibold text-slate-800">{ex.propertyName || '-'}</div>
                        <div className="text-[10px] font-mono text-slate-400">{ex.propertyId?.slice(0, 8)}</div>
                      </td>
                      <td className="p-2 font-medium text-slate-700">{ex.type}</td>
                      <td className="p-2 text-slate-600 max-w-[220px] truncate" title={ex.description}>
                        {ex.description || '-'}
                      </td>
                      <td className="p-2 text-right font-bold text-slate-800">{money(Number(ex.amount))}</td>
                      <td className="p-2 text-center font-bold">{ex.status}</td>
                      <td className="p-2 text-slate-500">
                        {ex.linkedResourceType && ex.linkedResourceId
                          ? `${ex.linkedResourceType}:${ex.linkedResourceId.slice(0, 8)}`
                          : '-'}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
          </div>
        </div>
      </div>
    </div>
  );
};
