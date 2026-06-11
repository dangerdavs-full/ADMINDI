import React, { useCallback, useEffect, useState } from 'react';
import { AlertTriangle, Loader2, Trash2, X, ShieldCheck } from 'lucide-react';
import { propertyService } from '../../services/propertyService';

/**
 * V66 — Modal de confirmación severa para el hard-delete de un inmueble.
 *
 * Flujo:
 *  1. Al abrir, carga el preview de impacto (cuántos tickets, facturas,
 *     archivos, etc. se perderán).
 *  2. Muestra la lista al dueño para que entienda qué se borra.
 *  3. Le exige escribir literalmente BORRAR (anti accidente) y capturar
 *     password + MFA opcional (reauth).
 *  4. Al confirmar, invoca propertyService.deleteProperty con el reauth.
 *
 * Solo OWNER debe ver este modal — el guard final vive en el backend.
 */

interface Props {
  isOpen: boolean;
  propertyId: string | null;
  propertyName: string;
  onClose: () => void;
  onDeleted: () => void;
}

interface ImpactSummary {
  leases: number;
  tenantProfiles: number;
  invoices: number;
  payments: number;
  transferProofs: number;
  maintenanceTickets: number;
  expenses: number;
  propertyFiles: number;
  units: number;
}

const REQUIRED_TEXT = 'BORRAR';

export const PropertyHardDeleteModal: React.FC<Props> = ({
  isOpen,
  propertyId,
  propertyName,
  onClose,
  onDeleted,
}) => {
  const [impact, setImpact] = useState<ImpactSummary | null>(null);
  const [loadingImpact, setLoadingImpact] = useState(false);
  const [impactError, setImpactError] = useState<string | null>(null);

  const [typed, setTyped] = useState('');
  const [password, setPassword] = useState('');
  const [mfaCode, setMfaCode] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [err, setErr] = useState<string | null>(null);

  // Cargar impact cada vez que se abre con un propertyId válido.
  useEffect(() => {
    if (!isOpen || !propertyId) return;
    setImpact(null);
    setImpactError(null);
    setTyped('');
    setPassword('');
    setMfaCode('');
    setErr(null);
    setLoadingImpact(true);
    propertyService.getDeleteImpact(propertyId)
      .then(res => setImpact(res))
      .catch(e => setImpactError(e?.response?.data?.message || 'No se pudo cargar el impacto.'))
      .finally(() => setLoadingImpact(false));
  }, [isOpen, propertyId]);

  const canSubmit = typed.trim().toUpperCase() === REQUIRED_TEXT
      && password.length >= 1
      && !submitting
      && !loadingImpact;

  const handleSubmit = useCallback(async () => {
    if (!canSubmit || !propertyId) return;
    setSubmitting(true);
    setErr(null);
    try {
      await propertyService.deleteProperty(propertyId, {
        password,
        mfaCode: mfaCode || undefined,
      });
      onDeleted();
    } catch (e: any) {
      const msg = e?.response?.data?.message
                || e?.response?.data?.error
                || e?.message
                || 'No se pudo eliminar el inmueble.';
      setErr(typeof msg === 'string' ? msg : 'Error al eliminar.');
    } finally {
      setSubmitting(false);
    }
  }, [canSubmit, propertyId, password, mfaCode, onDeleted]);

  if (!isOpen || !propertyId) return null;

  const impactRows: Array<{ label: string; value: number }> = impact ? [
    { label: 'Contratos / leases cerrados', value: impact.leases },
    { label: 'Expedientes de inquilinos', value: impact.tenantProfiles },
    { label: 'Facturas', value: impact.invoices },
    { label: 'Pagos registrados', value: impact.payments },
    { label: 'Comprobantes de renta (SPEI / efectivo)', value: impact.transferProofs },
    { label: 'Tickets de mantenimiento', value: impact.maintenanceTickets },
    { label: 'Gastos / egresos', value: impact.expenses },
    { label: 'Archivos del inmueble (fotos, documentos)', value: impact.propertyFiles },
    { label: 'Unidades del inmueble', value: impact.units },
  ] : [];

  return (
    <div className="fixed inset-0 z-[200] flex items-center justify-center p-4">
      <div className="absolute inset-0 bg-slate-900/80 backdrop-blur-sm" onClick={!submitting ? onClose : undefined} />
      <div className="relative bg-white rounded-2xl w-full max-w-lg shadow-2xl border border-rose-200 max-h-[92vh] flex flex-col">
        <div className="px-6 py-4 border-b border-rose-100 bg-rose-50 flex items-start justify-between gap-3">
          <div>
            <h3 className="text-lg font-bold text-rose-900 flex items-center gap-2">
              <AlertTriangle className="w-5 h-5" /> Eliminar inmueble
            </h3>
            <p className="text-xs text-rose-700 mt-0.5">
              Acción irreversible. Estás a punto de borrar <strong>"{propertyName}"</strong> y TODA su historia contable.
            </p>
          </div>
          <button
            type="button"
            onClick={onClose}
            disabled={submitting}
            className="p-1.5 rounded-lg text-slate-400 hover:text-slate-700 hover:bg-slate-100 disabled:opacity-50"
            aria-label="Cerrar"
          >
            <X className="w-5 h-5" />
          </button>
        </div>

        <div className="p-6 space-y-4 overflow-y-auto">
          <div className="rounded-lg bg-amber-50 border border-amber-200 p-3 text-sm text-amber-900">
            <p className="font-bold mb-1 flex items-center gap-2">
              <AlertTriangle className="w-4 h-4" /> Se perderán permanentemente:
            </p>
            {loadingImpact ? (
              <p className="text-xs text-amber-700 flex items-center gap-2">
                <Loader2 className="w-3.5 h-3.5 animate-spin" /> Calculando impacto...
              </p>
            ) : impactError ? (
              <p className="text-xs text-rose-700">{impactError}</p>
            ) : (
              <ul className="text-xs space-y-0.5 mt-2">
                {impactRows.map(r => (
                  <li key={r.label} className="flex items-center justify-between">
                    <span>{r.label}</span>
                    <strong className={r.value > 0 ? 'text-rose-700' : 'text-amber-700'}>{r.value}</strong>
                  </li>
                ))}
                <li className="pt-2 mt-2 border-t border-amber-200 text-rose-800 font-bold">
                  Los archivos físicos (fotos, PDFs, comprobantes) se borran del storage.
                </li>
              </ul>
            )}
          </div>

          <div>
            <label className="block text-xs font-semibold text-slate-600 mb-1">
              Escribe <strong className="text-rose-700">{REQUIRED_TEXT}</strong> para confirmar *
            </label>
            <input
              className="w-full text-sm p-2.5 border border-slate-300 rounded-lg focus:ring-2 focus:ring-rose-500 outline-none font-mono tracking-wider"
              value={typed}
              onChange={e => setTyped(e.target.value)}
              disabled={submitting}
              placeholder={REQUIRED_TEXT}
            />
          </div>

          <div>
            <label className="block text-xs font-semibold text-slate-600 mb-1">Contraseña *</label>
            <input
              type="password"
              className="w-full text-sm p-2.5 border border-slate-300 rounded-lg focus:ring-2 focus:ring-rose-500 outline-none"
              value={password}
              onChange={e => setPassword(e.target.value)}
              disabled={submitting}
              autoComplete="current-password"
            />
          </div>

          <div>
            <label className="block text-xs font-semibold text-slate-600 mb-1">Código MFA (si lo tienes activo)</label>
            <input
              className="w-full text-sm p-2.5 border border-slate-300 rounded-lg focus:ring-2 focus:ring-rose-500 outline-none font-mono"
              value={mfaCode}
              onChange={e => setMfaCode(e.target.value)}
              disabled={submitting}
              placeholder="000000"
              maxLength={8}
            />
          </div>

          {err && (
            <div className="p-3 rounded-lg bg-rose-50 border border-rose-200 text-sm text-rose-700 flex gap-2 items-start">
              <AlertTriangle className="w-4 h-4 flex-shrink-0 mt-0.5" />
              <span>{err}</span>
            </div>
          )}
        </div>

        <div className="px-6 py-4 border-t border-slate-100 bg-slate-50 flex gap-3 justify-end">
          <button
            type="button"
            onClick={onClose}
            disabled={submitting}
            className="px-4 py-2 text-sm font-semibold text-slate-600 hover:text-slate-900 disabled:opacity-50"
          >
            Cancelar
          </button>
          <button
            type="button"
            onClick={handleSubmit}
            disabled={!canSubmit}
            className="px-4 py-2 bg-rose-600 hover:bg-rose-700 text-white font-bold rounded-xl text-sm inline-flex items-center gap-2 disabled:opacity-50 disabled:cursor-not-allowed"
          >
            {submitting ? <Loader2 className="w-4 h-4 animate-spin" /> : <Trash2 className="w-4 h-4" />}
            {submitting ? 'Eliminando...' : 'Eliminar para siempre'}
          </button>
        </div>

        <div className="px-6 pb-3 pt-0 flex items-center gap-2 text-[10px] text-slate-400">
          <ShieldCheck className="w-3 h-3" />
          Solo el dueño puede ejecutar esta acción. Queda registrada en auditoría.
        </div>
      </div>
    </div>
  );
};

export default PropertyHardDeleteModal;
