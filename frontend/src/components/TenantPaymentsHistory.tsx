import React, { useEffect, useState } from 'react';
import { Banknote, Landmark, Download } from 'lucide-react';
import { paymentService, TransferProofDTO } from '../services/paymentService';
import { openSecureFile, describeSecureFileError } from '../services/secureFileService';

/**
 * Historial terminal de comprobantes del inquilino.
 * Muestra solo estados finales para dejar claro que intento fue rechazado,
 * cual fue validado y cuales artefactos CEP oficiales existen.
 */
interface Props {
  tenantProfileId: string;
}

const STATUS_STYLE: Record<string, { label: string; cls: string }> = {
  VALIDATED: { label: 'Validado por Banxico', cls: 'bg-emerald-100 text-emerald-700 border-emerald-200' },
  VALIDATED_BY_OWNER: { label: 'Validado por dueno', cls: 'bg-emerald-100 text-emerald-700 border-emerald-200' },
  REJECTED: { label: 'Rechazado', cls: 'bg-rose-100 text-rose-700 border-rose-200' },
  REJECTED_BY_CEP: { label: 'Rechazado por CEP', cls: 'bg-rose-100 text-rose-700 border-rose-200' },
  REJECTED_BY_OWNER: { label: 'Rechazado por dueno', cls: 'bg-rose-100 text-rose-700 border-rose-200' },
  EXPIRED_AWAITING_OWNER: { label: 'Expirado (sin validar)', cls: 'bg-amber-100 text-amber-700 border-amber-200' },
};

const fmtMoney = (n?: number | null) =>
  typeof n === 'number' ? `$${n.toLocaleString('es-MX', { minimumFractionDigits: 2 })}` : '-';

const fmtDate = (iso?: string | null) =>
  iso ? new Date(iso).toLocaleDateString('es-MX', { day: '2-digit', month: 'short', year: 'numeric' }) : '-';

export const TenantPaymentsHistory: React.FC<Props> = ({ tenantProfileId }) => {
  const [items, setItems] = useState<TransferProofDTO[] | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    setItems(null);
    setError(null);
    paymentService.getProofsHistory({ tenantProfileId })
      .then((data) => {
        if (!cancelled) setItems(data);
      })
      .catch((e) => {
        if (!cancelled) {
          const msg = e?.response?.data?.message || 'No se pudo cargar el historial.';
          setError(msg);
        }
      });
    return () => {
      cancelled = true;
    };
  }, [tenantProfileId]);

  if (error) {
    return <div className="px-6 py-4 text-sm text-rose-700 bg-rose-50">{error}</div>;
  }
  if (items === null) {
    return <div className="px-6 py-6 animate-pulse text-sm text-slate-400">Cargando historial...</div>;
  }
  if (items.length === 0) {
    return (
      <div className="px-6 py-6 text-sm text-slate-500 flex items-center gap-2">
        <Banknote className="w-4 h-4 text-slate-300" />
        Sin comprobantes en estado final todavia.
      </div>
    );
  }

  return (
    <div className="px-6 py-4">
      <ul className="divide-y divide-slate-100">
        {items.map((p) => {
          const st = STATUS_STYLE[p.status] || { label: p.status, cls: 'bg-slate-100 text-slate-600 border-slate-200' };
          const isCash = p.paymentType === 'CASH';
          return (
            <li key={p.id} className="py-3 flex items-center justify-between gap-3 flex-wrap">
              <div className="flex items-center gap-3 min-w-0">
                <span className={`inline-flex items-center justify-center w-8 h-8 rounded-lg ${isCash ? 'bg-amber-50 text-amber-700' : 'bg-indigo-50 text-indigo-700'}`}>
                  {isCash ? <Banknote className="w-4 h-4" /> : <Landmark className="w-4 h-4" />}
                </span>
                <div className="min-w-0">
                  <p className="text-sm font-bold text-slate-800 truncate">
                    {fmtMoney(p.amount)} <span className="font-normal text-xs text-slate-500">- {isCash ? 'Efectivo' : 'SPEI'}{p.bankEmitter ? ` - ${p.bankEmitter}` : ''}</span>
                  </p>
                  <p className="text-xs text-slate-500 truncate">
                    {typeof p.attemptNumber === 'number' && p.attemptNumber > 0 ? `Intento #${p.attemptNumber} - ` : ''}
                    {p.monthYear ? `Periodo ${p.monthYear} - ` : ''}
                    Subido {fmtDate(p.submittedAt)}
                    {p.reviewedAt ? ` - Decision ${fmtDate(p.reviewedAt)}` : ''}
                  </p>
                  {p.rejectionReason && (
                    <p className="text-[11px] text-rose-600 truncate mt-1">
                      Motivo: {p.rejectionReason}
                    </p>
                  )}
                </div>
              </div>
              <div className="flex items-center gap-2 flex-wrap justify-end">
                <span className={`text-[11px] font-bold uppercase px-2 py-0.5 rounded-full border ${st.cls}`}>
                  {st.label}
                </span>
                {p.cepPdfAvailable && (
                  <button
                    type="button"
                    onClick={async () => {
                      try {
                        await openSecureFile('transfer-proof-cep-pdf', p.id);
                      } catch (err) {
                        window.alert(describeSecureFileError(err));
                      }
                    }}
                    className="inline-flex items-center gap-1 px-2 py-1 rounded-lg border border-emerald-200 bg-emerald-50 text-[11px] font-bold text-emerald-700"
                  >
                    <Download className="w-3 h-3" /> CEP PDF
                  </button>
                )}
                {p.cepXmlAvailable && (
                  <button
                    type="button"
                    onClick={async () => {
                      try {
                        await openSecureFile('transfer-proof-cep-xml', p.id, {
                          download: true,
                          suggestedName: `cep-banxico-${p.monthYear || p.id}.xml`,
                        });
                      } catch (err) {
                        window.alert(describeSecureFileError(err));
                      }
                    }}
                    className="inline-flex items-center gap-1 px-2 py-1 rounded-lg border border-slate-200 bg-white text-[11px] font-bold text-slate-700"
                  >
                    <Download className="w-3 h-3" /> CEP XML
                  </button>
                )}
              </div>
            </li>
          );
        })}
      </ul>
    </div>
  );
};

export default TenantPaymentsHistory;
