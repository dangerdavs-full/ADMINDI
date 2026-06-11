import React, { useEffect, useState } from 'react';
import { HandCoins, Handshake } from 'lucide-react';
import { agreementService, PaymentAgreementDTO } from '../services/agreementService';

/**
 * V67 — Convenios (pagos a plazos / diferimientos) del inquilino para el
 * expediente. Lista todos los convenios ligados al {@code tenantProfileId}
 * en cualquier estado, con un resumen inline del avance (cuotas pagadas vs
 * totales) cuando aplique.
 */
interface Props {
  tenantProfileId: string;
}

const STATUS_STYLE: Record<string, { label: string; cls: string }> = {
  REQUESTED: { label: 'Solicitado', cls: 'bg-amber-100 text-amber-700 border-amber-200' },
  APPROVED: { label: 'Aprobado', cls: 'bg-blue-100 text-blue-700 border-blue-200' },
  ACTIVE: { label: 'Activo', cls: 'bg-emerald-100 text-emerald-700 border-emerald-200' },
  COMPLETED: { label: 'Completado', cls: 'bg-slate-100 text-slate-600 border-slate-200' },
  REJECTED: { label: 'Rechazado', cls: 'bg-rose-100 text-rose-700 border-rose-200' },
  BREACHED: { label: 'Incumplido', cls: 'bg-rose-100 text-rose-700 border-rose-200' },
  CANCELLED: { label: 'Cancelado', cls: 'bg-slate-100 text-slate-500 border-slate-200' },
};

const fmtMoney = (n?: number | null) =>
  typeof n === 'number' ? `$${n.toLocaleString('es-MX', { minimumFractionDigits: 2 })}` : '—';

const fmtDate = (iso?: string | null) =>
  iso ? new Date(iso).toLocaleDateString('es-MX', { day: '2-digit', month: 'short', year: 'numeric' }) : '—';

export const TenantAgreementsHistory: React.FC<Props> = ({ tenantProfileId }) => {
  const [items, setItems] = useState<PaymentAgreementDTO[] | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    setItems(null);
    setError(null);
    agreementService.getAgreementsByTenant(tenantProfileId)
      .then((data) => { if (!cancelled) setItems(data); })
      .catch((e) => {
        if (!cancelled) {
          const msg = e?.response?.data?.message || 'No se pudo cargar convenios.';
          setError(msg);
        }
      });
    return () => { cancelled = true; };
  }, [tenantProfileId]);

  if (error) {
    return <div className="px-6 py-4 text-sm text-rose-700 bg-rose-50">{error}</div>;
  }
  if (items === null) {
    return <div className="px-6 py-6 animate-pulse text-sm text-slate-400">Cargando convenios…</div>;
  }
  if (items.length === 0) {
    return (
      <div className="px-6 py-6 text-sm text-slate-500 flex items-center gap-2">
        <Handshake className="w-4 h-4 text-slate-300" />
        Sin convenios registrados para este inquilino.
      </div>
    );
  }

  return (
    <div className="px-6 py-4">
      <ul className="divide-y divide-slate-100">
        {items.map((ag) => {
          const st = STATUS_STYLE[ag.status] || { label: ag.status, cls: 'bg-slate-100 text-slate-600 border-slate-200' };
          const totalInstallments = ag.installments?.length ?? 0;
          const paidInstallments = ag.installments?.filter((i) => i.status === 'PAID').length ?? 0;
          return (
            <li key={ag.id} className="py-3">
              <div className="flex items-center justify-between gap-3 flex-wrap">
                <div className="flex items-center gap-3 min-w-0">
                  <span className="inline-flex items-center justify-center w-8 h-8 rounded-lg bg-violet-50 text-violet-700">
                    <HandCoins className="w-4 h-4" />
                  </span>
                  <div className="min-w-0">
                    <p className="text-sm font-bold text-slate-800 truncate">
                      {fmtMoney(ag.approvedAmount ?? ag.requestedAmount)}
                      {ag.monthYear ? <span className="font-normal text-xs text-slate-500"> · Periodo {ag.monthYear}</span> : null}
                    </p>
                    <p className="text-xs text-slate-500 truncate">
                      {ag.reason ? `Motivo: ${ag.reason}` : 'Sin motivo capturado'}
                      {ag.createdAt ? ` · Solicitado ${fmtDate(ag.createdAt)}` : ''}
                    </p>
                  </div>
                </div>
                <span className={`text-[11px] font-bold uppercase px-2 py-0.5 rounded-full border ${st.cls}`}>
                  {st.label}
                </span>
              </div>
              {totalInstallments > 0 && (
                <p className="mt-2 text-[11px] text-slate-500 pl-11">
                  Cuotas: <strong>{paidInstallments}</strong> / {totalInstallments} pagadas
                </p>
              )}
              {ag.rejectionReason && (
                <p className="mt-2 text-[11px] text-rose-700 bg-rose-50 border border-rose-200 rounded px-2 py-1 pl-2">
                  Motivo de rechazo: {ag.rejectionReason}
                </p>
              )}
            </li>
          );
        })}
      </ul>
    </div>
  );
};

export default TenantAgreementsHistory;
