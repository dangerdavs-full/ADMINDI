import React, { useCallback, useEffect, useState } from 'react';
import {
  Loader2,
  CheckCircle2,
  XCircle,
  Clock,
  AlertCircle,
  DollarSign,
  Eye,
  Download,
  X,
  RefreshCw,
  Calendar,
  Banknote,
  Landmark,
} from 'lucide-react';
import { paymentService, TransferProofDTO } from '../services/paymentService';
import { openSecureFile, describeSecureFileError } from '../services/secureFileService';

/**
 * V57/V59 — Panel del dueño para validar comprobantes de renta pendientes de
 * decisión manual. Cubre DOS orígenes:
 *
 *  - CASH (flujo normal): el inquilino sube foto del comprobante en efectivo.
 *  - SPEI caído a validación manual: cuando Banxico CEP no respondió al momento
 *    del submit, o cuando el dueño no tenía CLABE registrada. En ambos casos
 *    el proof queda PENDING_OWNER_VALIDATION con ventana de 120h igual que CASH.
 *
 * Incluye timer regresivo (hasta 120h desde submitted_at) y botones de
 * aprobar / rechazar con razón obligatoria al rechazar.
 *
 * Estados que muestra:
 *  - PENDING_OWNER_VALIDATION: el dueño debe decidir (pestaña Pendientes).
 *  - VALIDATED_BY_OWNER / REJECTED_BY_OWNER / EXPIRED_AWAITING_OWNER: histórico
 *    de decisiones manuales (pestaña Histórico). Tanto CASH como SPEI que
 *    pasaron por esta bandeja quedan en estos estados — no filtramos por
 *    paymentType para no ocultar los SPEI manuales.
 *
 * Este componente es auto-contenido: carga su propia data al mount, tiene
 * refresh manual y se puede refrescar externamente vía la prop onChange.
 */

interface RentProofsPanelProps {
  onChange?: () => void;
}

export const RentProofsPanel: React.FC<RentProofsPanelProps> = ({ onChange }) => {
  // V65 — El histórico se movió al expediente del inmueble/inquilino. Este
  // panel queda exclusivamente para comprobantes PENDIENTES de decisión.
  // Borramos la pestaña y la prop/tabs para no mostrar UI redundante.
  const [pending, setPending] = useState<TransferProofDTO[]>([]);
  const [loading, setLoading] = useState(false);
  const [busyId, setBusyId] = useState<string | null>(null);
  const [viewProof, setViewProof] = useState<TransferProofDTO | null>(null);
  const [rejectProof, setRejectProof] = useState<TransferProofDTO | null>(null);
  const [rejectReason, setRejectReason] = useState('');

  const load = useCallback(async () => {
    setLoading(true);
    try {
      setPending(await paymentService.getPendingProofs());
    } catch (e: any) {
      console.error('Error cargando comprobantes:', e);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => { load(); }, [load]);

  const handleApprove = async (proof: TransferProofDTO) => {
    const kindLabel = proof.paymentType === 'SPEI' ? 'pago SPEI' : 'pago en efectivo';
    const amountStr = proof.amount?.toLocaleString('en-US', { minimumFractionDigits: 2 }) || '—';
    const msg = `¿Aprobar ${kindLabel} de $${amountStr} MXN para ${proof.tenantName || 'inquilino'}?\n\n`
              + `El pago se aplicará a la factura inmediatamente y quedará registrado en contabilidad.`
              + (proof.paymentType === 'SPEI'
                  ? '\n\nAntes de aprobar, verifica que la transferencia esté reflejada en tu estado de cuenta.'
                  : '');
    if (!window.confirm(msg)) return;
    setBusyId(proof.id);
    try {
      await paymentService.overrideProof(proof.id, true);
      await load();
      onChange?.();
    } catch (e: any) {
      alert(e.response?.data?.message || 'No se pudo aprobar el comprobante.');
    } finally {
      setBusyId(null);
    }
  };

  const confirmReject = async () => {
    if (!rejectProof) return;
    if (!rejectReason.trim()) {
      alert('Indica el motivo del rechazo.');
      return;
    }
    setBusyId(rejectProof.id);
    try {
      await paymentService.overrideProof(rejectProof.id, false, rejectReason.trim());
      setRejectProof(null);
      setRejectReason('');
      await load();
      onChange?.();
    } catch (e: any) {
      alert(e.response?.data?.message || 'No se pudo rechazar el comprobante.');
    } finally {
      setBusyId(null);
    }
  };

  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between flex-wrap gap-3">
        <div>
          <h3 className="text-sm font-bold text-slate-800">Comprobantes pendientes de validación</h3>
          <p className="text-xs text-slate-500">
            El histórico por inmueble o inquilino vive ahora en el expediente correspondiente.
          </p>
        </div>
        <button
          onClick={load}
          disabled={loading}
          className="px-3 py-1.5 text-sm text-slate-600 hover:text-slate-900 flex items-center gap-1.5"
        >
          <RefreshCw className={`w-4 h-4 ${loading ? 'animate-spin' : ''}`} />
          Refrescar
        </button>
      </div>

      {loading && pending.length === 0 ? (
        <div className="flex items-center gap-2 text-sm text-slate-500 justify-center py-10">
          <Loader2 className="w-4 h-4 animate-spin" /> Cargando comprobantes…
        </div>
      ) : (
        pending.length === 0 ? (
          <EmptyState
            icon={<CheckCircle2 className="w-6 h-6" />}
            title="Sin comprobantes pendientes"
            subtitle="Aquí aparecerán los comprobantes que requieren tu decisión: pagos en efectivo y SPEI que no pudieron validarse automáticamente."
          />
        ) : (
          <div className="space-y-3">
            {pending.map(proof => (
              <PendingProofCard
                key={proof.id}
                proof={proof}
                busy={busyId === proof.id}
                onView={() => setViewProof(proof)}
                onApprove={() => handleApprove(proof)}
                onReject={() => { setRejectProof(proof); setRejectReason(''); }}
              />
            ))}
          </div>
        )
      )}

      {/* Modal detalle */}
      {viewProof && (
        <ProofDetailModal proof={viewProof} onClose={() => setViewProof(null)} />
      )}

      {/* Modal rechazo */}
      {rejectProof && (
        <RejectModal
          proof={rejectProof}
          reason={rejectReason}
          onChangeReason={setRejectReason}
          busy={busyId === rejectProof.id}
          onCancel={() => { setRejectProof(null); setRejectReason(''); }}
          onConfirm={confirmReject}
        />
      )}
    </div>
  );
};

// ─── Componentes internos ────────────────────────────────────────────────────

const EmptyState: React.FC<{ icon: React.ReactNode; title: string; subtitle: string }> = ({ icon, title, subtitle }) => (
  <div className="border-2 border-dashed border-slate-200 rounded-xl py-10 text-center">
    <div className="inline-flex w-12 h-12 bg-slate-50 rounded-full items-center justify-center text-slate-400 mb-3">{icon}</div>
    <p className="text-sm font-medium text-slate-700">{title}</p>
    <p className="text-xs text-slate-500 mt-1">{subtitle}</p>
  </div>
);

const PendingProofCard: React.FC<{
  proof: TransferProofDTO;
  busy: boolean;
  onView: () => void;
  onApprove: () => void;
  onReject: () => void;
}> = ({ proof, busy, onView, onApprove, onReject }) => {
  const hoursLeft = proof.hoursRemaining ?? 0;
  const urgent = hoursLeft < 24;
  const warn = hoursLeft >= 24 && hoursLeft < 48;

  // V59 — Tag y chrome dependen del origen del proof:
  //  - CASH = flujo normal de pago en efectivo.
  //  - SPEI = transferencia que cayó a validación manual (Banxico caído /
  //    dueño sin CLABE). El dueño debe confirmar contra su estado de cuenta.
  const isSpei = proof.paymentType === 'SPEI';
  const kindTag = isSpei
    ? { label: 'SPEI · VERIFICAR EN EDO. CUENTA', cls: 'bg-indigo-100 text-indigo-800', icon: <Landmark className="w-3 h-3" /> }
    : { label: 'EFECTIVO', cls: 'bg-amber-100 text-amber-800', icon: <Banknote className="w-3 h-3" /> };

  // Solo CASH tiene límite fijo de 3 intentos por factura. SPEI manual no
  // comparte la misma política, así que evitamos el "/3" engañoso.
  const attemptLabel = proof.attemptNumber
    ? (isSpei ? ` · Intento ${proof.attemptNumber}` : ` · Intento ${proof.attemptNumber}/3`)
    : '';

  // Nota del inquilino (CASH) viene prefijada con "[NOTA INQUILINO]".
  // Si es SPEI automático al caer en manual, el owner_validation_notes puede
  // tener el marcador "[AUTO]" del sistema: lo mostramos como aviso distinto.
  const tenantNote = proof.ownerValidationNotes?.includes('[NOTA INQUILINO]')
    ? proof.ownerValidationNotes.replace('[NOTA INQUILINO]', '').trim()
    : null;
  const systemNote = proof.ownerValidationNotes?.startsWith('[AUTO]')
    ? proof.ownerValidationNotes.replace('[AUTO]', '').trim()
    : null;

  return (
    <div className={`border rounded-xl p-4 ${urgent ? 'border-red-300 bg-red-50' : warn ? 'border-amber-300 bg-amber-50' : 'border-slate-200 bg-white'}`}>
      <div className="flex items-start justify-between gap-3 flex-wrap">
        <div className="flex-1 min-w-0">
          <div className="flex items-center gap-2 mb-1 flex-wrap">
            <DollarSign className={`w-4 h-4 ${isSpei ? 'text-indigo-600' : 'text-amber-600'}`} />
            <p className="font-bold text-slate-800 truncate">
              ${proof.amount?.toLocaleString('en-US', { minimumFractionDigits: 2 }) || '—'} MXN
            </p>
            <span className={`text-xs px-2 py-0.5 rounded-full font-semibold flex items-center gap-1 ${kindTag.cls}`}>
              {kindTag.icon} {kindTag.label}
            </span>
            <span className={`text-xs px-2 py-0.5 rounded-full font-semibold flex items-center gap-1 ${urgent ? 'bg-red-100 text-red-700' : warn ? 'bg-amber-100 text-amber-700' : 'bg-slate-100 text-slate-700'}`}>
              <Clock className="w-3 h-3" />
              {hoursLeft > 0 ? `${hoursLeft} h restantes` : 'Expirando'}
            </span>
          </div>
          <p className="text-sm text-slate-600">
            <strong>{proof.tenantName || 'Inquilino'}</strong>
            {proof.monthYear && <> · Renta de <strong>{proof.monthYear}</strong></>}
          </p>
          <p className="text-xs text-slate-500 mt-1">
            Subido el {new Date(proof.submittedAt || '').toLocaleString('es-MX')}
            {attemptLabel}
          </p>

          {/* V59 — Datos bancarios del SPEI para cotejar con estado de cuenta. */}
          {isSpei && (proof.claveRastreo || proof.bankEmitter) && (
            <div className="mt-2 text-xs text-slate-700 bg-indigo-50 border border-indigo-200 rounded p-2 space-y-0.5">
              {proof.claveRastreo && (
                <p><span className="font-semibold">Clave de rastreo:</span> <span className="font-mono">{proof.claveRastreo}</span></p>
              )}
              {proof.bankEmitter && (
                <p><span className="font-semibold">Banco emisor:</span> {proof.bankEmitter}</p>
              )}
              {proof.transferDate && (
                <p><span className="font-semibold">Fecha de transferencia:</span> {new Date(proof.transferDate).toLocaleDateString('es-MX')}</p>
              )}
            </div>
          )}

          {systemNote && (
            <div className="mt-2 text-xs text-indigo-900 bg-indigo-50 border border-indigo-200 rounded p-2">
              <span className="font-semibold">¿Por qué llega aquí?</span> {systemNote}
            </div>
          )}
          {tenantNote && (
            <div className="mt-2 text-xs text-slate-700 bg-slate-50 border border-slate-200 rounded p-2">
              <span className="font-semibold">Nota del inquilino:</span> {tenantNote}
            </div>
          )}
        </div>
      </div>

      <div className="flex items-center gap-2 mt-3 pt-3 border-t border-slate-100">
        <button
          onClick={onView}
          className="px-3 py-1.5 text-sm text-slate-600 hover:text-slate-900 flex items-center gap-1.5 border border-slate-200 rounded-lg hover:bg-slate-50"
          disabled={busy}
        >
          <Eye className="w-4 h-4" /> Ver comprobante
        </button>
        <div className="flex-1" />
        <button
          onClick={onReject}
          disabled={busy}
          className="px-3 py-1.5 text-sm bg-white border border-red-200 text-red-700 hover:bg-red-50 rounded-lg flex items-center gap-1.5 disabled:opacity-60 font-semibold"
        >
          <XCircle className="w-4 h-4" /> Rechazar
        </button>
        <button
          onClick={onApprove}
          disabled={busy}
          className="px-3 py-1.5 text-sm bg-emerald-600 hover:bg-emerald-700 text-white rounded-lg flex items-center gap-1.5 disabled:opacity-60 font-bold"
        >
          {busy ? <Loader2 className="w-4 h-4 animate-spin" /> : <CheckCircle2 className="w-4 h-4" />}
          Aprobar pago
        </button>
      </div>
    </div>
  );
};

export const HistoryProofRow: React.FC<{
  proof: TransferProofDTO;
  onView: () => void;
}> = ({ proof, onView }) => {
  // V59 — Incluimos VALIDATED/REJECTED porque el filtro del contenedor
  // solo deja pasar esos estados cuando reviewedBy contiene "[OVERRIDE]",
  // que es el marcador de SPEI aprobado/rechazado manualmente por el dueño.
  const statusLabels: Record<string, { label: string; cls: string; icon: React.ReactNode }> = {
    VALIDATED_BY_OWNER: { label: 'Aprobado', cls: 'bg-emerald-100 text-emerald-700', icon: <CheckCircle2 className="w-3.5 h-3.5" /> },
    REJECTED_BY_OWNER: { label: 'Rechazado', cls: 'bg-red-100 text-red-700', icon: <XCircle className="w-3.5 h-3.5" /> },
    EXPIRED_AWAITING_OWNER: { label: 'Expirado', cls: 'bg-slate-100 text-slate-600', icon: <AlertCircle className="w-3.5 h-3.5" /> },
    VALIDATED: { label: 'Aprobado', cls: 'bg-emerald-100 text-emerald-700', icon: <CheckCircle2 className="w-3.5 h-3.5" /> },
    REJECTED: { label: 'Rechazado', cls: 'bg-red-100 text-red-700', icon: <XCircle className="w-3.5 h-3.5" /> },
  };
  const s = statusLabels[proof.status] || { label: proof.status, cls: 'bg-slate-100 text-slate-600', icon: null };
  const isSpei = proof.paymentType === 'SPEI';

  return (
    <div className="flex items-center gap-3 p-3 border border-slate-200 rounded-lg bg-white hover:bg-slate-50">
      <span className={`inline-flex items-center gap-1 text-xs font-semibold px-2 py-0.5 rounded-full ${s.cls}`}>
        {s.icon} {s.label}
      </span>
      <span className={`inline-flex items-center gap-1 text-[10px] font-bold uppercase px-1.5 py-0.5 rounded ${isSpei ? 'bg-indigo-50 text-indigo-700' : 'bg-amber-50 text-amber-700'}`}>
        {isSpei ? <Landmark className="w-3 h-3" /> : <Banknote className="w-3 h-3" />}
        {isSpei ? 'SPEI' : 'Efectivo'}
      </span>
      <div className="flex-1 min-w-0">
        <p className="text-sm text-slate-800 truncate">
          <strong>${proof.amount?.toLocaleString('en-US') || '—'}</strong> · {proof.tenantName || 'Inquilino'} · {proof.monthYear}
        </p>
        <p className="text-xs text-slate-500 flex items-center gap-1 mt-0.5">
          <Calendar className="w-3 h-3" />
          {new Date(proof.submittedAt || '').toLocaleDateString('es-MX')}
          {proof.reviewedAt && <> · revisado {new Date(proof.reviewedAt).toLocaleDateString('es-MX')}</>}
        </p>
      </div>
      <button
        onClick={onView}
        className="px-2 py-1 text-xs text-slate-500 hover:text-slate-800"
      >
        <Eye className="w-4 h-4" />
      </button>
    </div>
  );
};

export const ProofDetailModal: React.FC<{
  proof: TransferProofDTO;
  onClose: () => void;
}> = ({ proof, onClose }) => {
  // V59 — SPEI manual no respeta la regla "X/3" (eso es de CASH). Mostramos
  // solo "Intento N" cuando sea SPEI.
  const isSpei = proof.paymentType === 'SPEI';
  const attemptText = proof.attemptNumber
    ? (isSpei ? `Intento ${proof.attemptNumber}` : `Intento ${proof.attemptNumber}/3`)
    : null;

  return (
  <div className="fixed inset-0 z-[150] flex items-center justify-center p-4">
    <div className="absolute inset-0 bg-slate-900/70 backdrop-blur-sm" onClick={onClose} />
    <div className="relative bg-white rounded-2xl w-full max-w-lg shadow-2xl">
      <div className="px-5 py-4 border-b border-slate-100 flex items-center justify-between">
        <div>
          <h3 className="font-bold text-slate-800">Detalle del comprobante</h3>
          <p className="text-xs text-slate-500">
            {proof.paymentType || '—'}{attemptText ? ` · ${attemptText}` : ''}
          </p>
        </div>
        <button onClick={onClose} className="text-slate-400 hover:text-slate-600"><X className="w-5 h-5" /></button>
      </div>
      <div className="p-5 space-y-3 text-sm">
        <Detail label="Inquilino" value={proof.tenantName || '—'} />
        <Detail label="Factura" value={proof.monthYear || '—'} />
        <Detail label="Monto" value={`$${proof.amount?.toLocaleString('en-US') || '—'} MXN`} />
        <Detail label="Enviado" value={new Date(proof.submittedAt || '').toLocaleString('es-MX')} />
        {isSpei && proof.claveRastreo && <Detail label="Clave de rastreo" value={proof.claveRastreo} />}
        {isSpei && proof.bankEmitter && <Detail label="Banco emisor" value={proof.bankEmitter} />}
        {isSpei && proof.transferDate && (
          <Detail label="Fecha de transferencia" value={new Date(proof.transferDate).toLocaleDateString('es-MX')} />
        )}
        {proof.expiresAt && proof.status === 'PENDING_OWNER_VALIDATION' && (
          <Detail label="Expira" value={new Date(proof.expiresAt).toLocaleString('es-MX')} />
        )}
        {proof.reviewedAt && <Detail label="Revisado" value={new Date(proof.reviewedAt).toLocaleString('es-MX')} />}
        {proof.ownerValidationNotes && (
          <div className="bg-slate-50 border border-slate-200 rounded p-2 text-xs text-slate-700">
            <span className="font-semibold">Notas:</span> {proof.ownerValidationNotes}
          </div>
        )}
        {proof.rejectionReason && (
          <div className="bg-red-50 border border-red-200 rounded p-2 text-xs text-red-700">
            <span className="font-semibold">Motivo de rechazo:</span> {proof.rejectionReason}
          </div>
        )}
        {proof.fileUrl && (
          <button
            onClick={() => openSecureFile('transfer-proof', proof.id)}
            className="w-full py-2 bg-slate-800 hover:bg-slate-900 text-white font-semibold rounded-lg flex items-center justify-center gap-2 text-sm"
          >
            <Eye className="w-4 h-4" /> Ver archivo adjunto
          </button>
        )}
        {(proof.cepPdfAvailable || proof.cepXmlAvailable) && (
          <div className="grid grid-cols-1 sm:grid-cols-2 gap-2">
            {proof.cepPdfAvailable && (
              <button
                onClick={async () => {
                  try {
                    await openSecureFile('transfer-proof-cep-pdf', proof.id);
                  } catch (err) {
                    window.alert(describeSecureFileError(err));
                  }
                }}
                className="w-full py-2 bg-emerald-600 hover:bg-emerald-700 text-white font-semibold rounded-lg flex items-center justify-center gap-2 text-sm"
              >
                <Download className="w-4 h-4" /> Abrir CEP PDF
              </button>
            )}
            {proof.cepXmlAvailable && (
              <button
                onClick={async () => {
                  try {
                    await openSecureFile('transfer-proof-cep-xml', proof.id, {
                      download: true,
                      suggestedName: `cep-banxico-${proof.monthYear || proof.id}.xml`,
                    });
                  } catch (err) {
                    window.alert(describeSecureFileError(err));
                  }
                }}
                className="w-full py-2 bg-white hover:bg-slate-50 text-slate-700 border border-slate-200 font-semibold rounded-lg flex items-center justify-center gap-2 text-sm"
              >
                <Download className="w-4 h-4" /> Descargar CEP XML
              </button>
            )}
          </div>
        )}
      </div>
    </div>
  </div>
  );
};

const Detail: React.FC<{ label: string; value: string }> = ({ label, value }) => (
  <div className="flex items-start justify-between gap-3 text-sm">
    <span className="text-slate-500">{label}</span>
    <span className="text-slate-800 font-medium text-right">{value}</span>
  </div>
);

const RejectModal: React.FC<{
  proof: TransferProofDTO;
  reason: string;
  onChangeReason: (v: string) => void;
  busy: boolean;
  onCancel: () => void;
  onConfirm: () => void;
}> = ({ proof, reason, onChangeReason, busy, onCancel, onConfirm }) => (
  <div className="fixed inset-0 z-[155] flex items-center justify-center p-4">
    <div className="absolute inset-0 bg-slate-900/70 backdrop-blur-sm" onClick={!busy ? onCancel : undefined} />
    <div className="relative bg-white rounded-2xl w-full max-w-md shadow-2xl">
      <div className="px-5 py-4 border-b border-slate-100 bg-red-50">
        <h3 className="font-bold text-red-900">Rechazar comprobante</h3>
        <p className="text-xs text-red-700 mt-0.5">
          ${proof.amount?.toLocaleString('en-US') || '—'} · {proof.tenantName} · {proof.monthYear}
        </p>
      </div>
      <div className="p-5 space-y-3">
        <p className="text-sm text-slate-600">
          El inquilino recibirá esta razón para que sepa por qué rechazaste el comprobante.
        </p>
        <textarea
          rows={3}
          maxLength={500}
          placeholder="Ej: el monto no coincide con la renta de abril"
          value={reason}
          onChange={(e) => onChangeReason(e.target.value)}
          className="w-full text-sm p-2.5 border border-slate-300 rounded-lg focus:ring-2 focus:ring-red-500 outline-none"
        />
        <div className="flex gap-2 pt-2">
          <button onClick={onCancel} disabled={busy} className="flex-1 py-2 text-sm font-semibold text-slate-700 border border-slate-200 rounded-lg hover:bg-slate-50">
            Cancelar
          </button>
          <button
            onClick={onConfirm}
            disabled={busy || !reason.trim()}
            className="flex-1 py-2 text-sm font-bold text-white bg-red-600 hover:bg-red-700 rounded-lg disabled:opacity-60 flex items-center justify-center gap-1.5"
          >
            {busy ? <Loader2 className="w-4 h-4 animate-spin" /> : <XCircle className="w-4 h-4" />}
            Rechazar
          </button>
        </div>
      </div>
    </div>
  </div>
);

export default RentProofsPanel;
