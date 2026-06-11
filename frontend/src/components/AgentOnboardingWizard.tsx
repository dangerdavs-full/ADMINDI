import React, { useCallback, useEffect, useState } from 'react';
import { AlertTriangle, Loader2, Landmark, User, ShieldCheck, X } from 'lucide-react';
import {
  agentBankAccountService,
  AgentRole,
} from '../services/agentBankAccountService';
import {
  banxicoInstitutionService,
  BanxicoInstitution,
  findBanxicoInstitutionByClabe,
} from '../services/banxicoInstitutionService';

/**
 * V63 — Wizard bloqueante que obliga al agente (mantenimiento o inmobiliario)
 * a registrar su cuenta bancaria antes de operar en la plataforma. Se muestra
 * automáticamente si al mount del dashboard el backend reporta
 * `bank-account/status = {complete: false}`, y también puede abrirse de forma
 * imperativa cuando un endpoint operativo devuelve HTTP 412
 * BANK_ACCOUNT_REQUIRED.
 *
 * Reglas de UX:
 *  - Fondo sin cierre (no se puede cerrar con backdrop ni con ESC si es obligatorio).
 *  - Los 3 campos son obligatorios.
 *  - Validación CLABE: 18 dígitos numéricos (el backend valida módulo-10;
 *    aquí hacemos un pre-check cliente para UX).
 *  - Al éxito, invoca `onCompleted()` para que el dashboard se desbloquee.
 */

interface Props {
  role: AgentRole;
  /** Si true, el modal no puede cerrarse hasta que el agente capture los datos. */
  blocking?: boolean;
  onCompleted: () => void;
  onDismiss?: () => void;
}

const CLABE_LEN = 18;

export const AgentOnboardingWizard: React.FC<Props> = ({
  role,
  blocking = true,
  onCompleted,
  onDismiss,
}) => {
  const [clabe, setClabe] = useState('');
  const [bankName, setBankName] = useState('');
  const [accountHolder, setAccountHolder] = useState('');
  const [loading, setLoading] = useState(false);
  const [initialLoading, setInitialLoading] = useState(true);
  const [serverErr, setServerErr] = useState<string | null>(null);
  const [receiverBanks, setReceiverBanks] = useState<BanxicoInstitution[]>([]);

  // Pre-carga: si ya hay datos parciales (ej. CLABE sin banco) el wizard los
  // muestra como punto de partida en lugar de forzar recaptura desde cero.
  useEffect(() => {
    let cancelled = false;
    agentBankAccountService.get(role)
      .then(acc => {
        if (cancelled || !acc) return;
        if (acc.clabe) setClabe(acc.clabe);
        if (acc.bankName) setBankName(acc.bankName);
        if (acc.accountHolder) setAccountHolder(acc.accountHolder);
      })
      .catch(() => { /* silenciar: si no hay cuenta, el form queda vacío */ })
      .finally(() => { if (!cancelled) setInitialLoading(false); });
    return () => { cancelled = true; };
  }, [role]);

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
    if (!clabe || receiverBanks.length === 0) return;
    const detected = findBanxicoInstitutionByClabe(clabe, receiverBanks);
    if (detected?.name && detected.name !== bankName) {
      setBankName(detected.name);
    }
  }, [clabe, receiverBanks, bankName]);

  const clabeClean = clabe.replace(/\D/g, '');
  const clabeIsLenOk = clabeClean.length === CLABE_LEN;
  const canSubmit = clabeIsLenOk
      && accountHolder.trim().length >= 3
      && !loading;

  const handleSubmit = useCallback(async () => {
    if (!canSubmit) return;
    setLoading(true);
    setServerErr(null);
    try {
      await agentBankAccountService.upsert(role, {
        clabe: clabeClean,
        bankName: bankName.trim() || undefined,
        accountHolder: accountHolder.trim(),
      });
      onCompleted();
    } catch (e: any) {
      const msg = e?.response?.data?.message
                || e?.response?.data?.error
                || e?.message
                || 'No se pudo guardar la cuenta bancaria.';
      setServerErr(typeof msg === 'string' ? msg : 'Error al guardar.');
    } finally {
      setLoading(false);
    }
  }, [canSubmit, role, clabeClean, bankName, accountHolder, onCompleted]);

  const handleBackdrop = () => {
    if (!blocking && onDismiss) onDismiss();
  };

  return (
    <div className="fixed inset-0 z-[200] flex items-center justify-center p-4">
      <div
        className="absolute inset-0 bg-slate-900/80 backdrop-blur-sm"
        onClick={handleBackdrop}
      />
      <div className="relative bg-white rounded-2xl w-full max-w-lg shadow-2xl border border-slate-200 max-h-[92vh] flex flex-col">
        <div className="px-6 py-4 border-b border-slate-100 bg-indigo-50 flex items-start justify-between gap-3">
          <div>
            <h3 className="text-lg font-bold text-indigo-900 flex items-center gap-2">
              <Landmark className="w-5 h-5" /> Registra tu cuenta bancaria
            </h3>
            <p className="text-xs text-indigo-800 mt-0.5">
              Necesitamos tu CLABE, banco y titular para que los dueños puedan pagarte.
            </p>
          </div>
          {!blocking && onDismiss && (
            <button
              type="button"
              onClick={onDismiss}
              className="p-1.5 rounded-lg text-slate-400 hover:text-slate-700 hover:bg-slate-100"
              aria-label="Cerrar"
            >
              <X className="w-5 h-5" />
            </button>
          )}
        </div>

        {initialLoading ? (
          <div className="p-8 flex items-center justify-center gap-2 text-slate-500">
            <Loader2 className="w-4 h-4 animate-spin" /> Cargando tus datos...
          </div>
        ) : (
          <div className="p-6 space-y-4 overflow-y-auto">
            <div className="p-3 rounded-lg bg-amber-50 border border-amber-200 text-sm text-amber-800 flex gap-2 items-start">
              <AlertTriangle className="w-4 h-4 flex-shrink-0 mt-0.5" />
              <span>
                Hasta que completes estos datos no podrás aceptar tickets, subir cotizaciones
                ni cerrar contratos. Los datos son privados; solo los dueños a los que prestes
                servicio los ven enmascarados al pagarte.
              </span>
            </div>

            <div>
              <label className="block text-xs font-semibold text-slate-600 mb-1">
                CLABE interbancaria (18 dígitos) *
              </label>
              <input
                className="w-full text-sm p-2.5 border border-slate-300 rounded-lg focus:ring-2 focus:ring-indigo-500 outline-none transition-all font-mono tracking-wider"
                placeholder="000000000000000000"
                maxLength={22}
                inputMode="numeric"
                value={clabe}
                        onChange={e => {
                          const next = e.target.value.replace(/\D/g, '').slice(0, CLABE_LEN);
                          setClabe(next);
                          const detected = findBanxicoInstitutionByClabe(next, receiverBanks);
                          setBankName(detected?.name || '');
                        }}
                        disabled={loading}
                        autoFocus
              />
              <p className={`text-[11px] mt-1 ${clabeIsLenOk ? 'text-emerald-600' : 'text-slate-400'}`}>
                {clabeClean.length} / {CLABE_LEN} dígitos
              </p>
            </div>

            <div>
              <label className="block text-xs font-semibold text-slate-600 mb-1">
                Banco Banxico detectado por CLABE
              </label>
              <input
                className="w-full text-sm p-2.5 border border-slate-300 rounded-lg bg-slate-50 text-slate-700"
                maxLength={60}
                value={bankName}
                readOnly
                disabled={loading}
              />
              <p className="text-[11px] text-slate-400 mt-1">
                Lo determinamos automáticamente con la CLABE para mantener consistencia con Banxico.
              </p>
            </div>

            <div>
              <label className="block text-xs font-semibold text-slate-600 mb-1">
                Titular de la cuenta *
              </label>
              <input
                className="w-full text-sm p-2.5 border border-slate-300 rounded-lg focus:ring-2 focus:ring-indigo-500 outline-none transition-all"
                placeholder="Nombre tal cual aparece en el banco"
                maxLength={120}
                value={accountHolder}
                onChange={e => setAccountHolder(e.target.value)}
                disabled={loading}
              />
              <p className="text-[11px] text-slate-400 mt-1 flex items-center gap-1">
                <User className="w-3 h-3" /> Debe coincidir con el titular registrado en el banco.
              </p>
            </div>

            {serverErr && (
              <div className="p-3 rounded-lg bg-rose-50 border border-rose-200 text-sm text-rose-700 flex gap-2 items-start">
                <AlertTriangle className="w-4 h-4 flex-shrink-0 mt-0.5" />
                <span>{serverErr}</span>
              </div>
            )}
          </div>
        )}

        <div className="px-6 py-4 border-t border-slate-100 bg-slate-50 flex gap-3 justify-end">
          {!blocking && onDismiss && (
            <button
              type="button"
              onClick={onDismiss}
              disabled={loading}
              className="px-4 py-2 text-sm font-semibold text-slate-600 hover:text-slate-900 disabled:opacity-50"
            >
              Cancelar
            </button>
          )}
          <button
            type="button"
            onClick={handleSubmit}
            disabled={!canSubmit}
            className="px-4 py-2 bg-indigo-600 hover:bg-indigo-700 text-white font-bold rounded-xl text-sm inline-flex items-center gap-2 disabled:opacity-50 disabled:cursor-not-allowed"
          >
            {loading ? <Loader2 className="w-4 h-4 animate-spin" /> : <ShieldCheck className="w-4 h-4" />}
            {loading ? 'Guardando...' : 'Guardar y continuar'}
          </button>
        </div>
      </div>
    </div>
  );
};

export default AgentOnboardingWizard;
