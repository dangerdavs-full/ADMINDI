import React, { useState } from 'react';
import { X, Lock, KeyRound, Send } from 'lucide-react';
import { APPROVAL_ACTION_META, ApprovalActionType } from '../../services/approvalRequestService';

interface Props {
  isOpen: boolean;
  onClose: () => void;
  /** Called with the staff-provided credentials and reason. */
  onConfirm: (password: string, mfaCode: string, reason: string | undefined) => Promise<void>;
  /** Which approval flow this modal represents — drives copy + accent colour. */
  action: ApprovalActionType;
  /** Resource display name (property address, tenant name, lease id, …). */
  resourceLabel: string;
  /** If true, the reason field is mandatory before the staff can submit. */
  requireReason?: boolean;
}

/**
 * Modal usado por el personal (Property Admin con Acceso Total) para iniciar una
 * solicitud de aprobación hacia el dueño. Aplica la política de "double reauth":
 *
 * 1. El staff escribe su propia contraseña + MFA aquí (se valida en backend).
 * 2. El backend crea un ActionTask y notifica al dueño.
 * 3. El dueño entra al inbox, revisa y aprueba con SU contraseña + MFA.
 *
 * El campo "motivo" es opcional por default pero recomendable — se guarda en la
 * payload de la tarea y lo verá el dueño junto con la identidad del solicitante.
 */
export const StaffApprovalRequestModal: React.FC<Props> = ({
  isOpen,
  onClose,
  onConfirm,
  action,
  resourceLabel,
  requireReason = false,
}) => {
  const meta = APPROVAL_ACTION_META[action];
  const [password, setPassword] = useState('');
  const [mfaCode, setMfaCode] = useState('');
  const [reason, setReason] = useState('');
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');

  if (!isOpen) return null;

  const palette = {
    rose: {
      border: 'border-rose-200', header: 'bg-rose-50 border-rose-100',
      iconBg: 'bg-rose-100 text-rose-600', btn: 'bg-rose-600 hover:bg-rose-700 shadow-rose-500/30',
      ring: 'focus:border-rose-500 focus:ring-rose-500/20',
    },
    amber: {
      border: 'border-amber-200', header: 'bg-amber-50 border-amber-100',
      iconBg: 'bg-amber-100 text-amber-700', btn: 'bg-amber-600 hover:bg-amber-700 shadow-amber-500/30',
      ring: 'focus:border-amber-500 focus:ring-amber-500/20',
    },
    indigo: {
      border: 'border-indigo-200', header: 'bg-indigo-50 border-indigo-100',
      iconBg: 'bg-indigo-100 text-indigo-600', btn: 'bg-indigo-600 hover:bg-indigo-700 shadow-indigo-500/30',
      ring: 'focus:border-indigo-500 focus:ring-indigo-500/20',
    },
  }[meta.accent];

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setError('');
    if (!password.trim()) { setError('Ingresa tu contraseña.'); return; }
    const trimmedReason = reason.trim();
    if (requireReason && trimmedReason.length === 0) {
      setError('Describe brevemente el motivo de la solicitud.');
      return;
    }
    setLoading(true);
    try {
      await onConfirm(password, mfaCode, trimmedReason.length > 0 ? trimmedReason : undefined);
      setPassword(''); setMfaCode(''); setReason('');
      onClose();
    } catch (err: any) {
      setError(err.response?.data?.message || err.message || 'Error al enviar la solicitud.');
    } finally {
      setLoading(false);
    }
  };

  const inputClass = `w-full rounded-xl border border-slate-300 px-4 py-2.5 text-sm ${palette.ring} focus:ring-2 outline-none transition-all placeholder:text-slate-400`;

  return (
    <div className="fixed inset-0 z-[120] flex items-center justify-center p-4">
      <div className="absolute inset-0 bg-slate-900/70 backdrop-blur-sm" onClick={onClose} />
      <div className={`relative bg-white rounded-2xl w-full max-w-md shadow-2xl overflow-hidden animate-in fade-in zoom-in-95 duration-200 border ${palette.border}`}>
        <div className={`px-6 py-4 border-b flex justify-between items-center ${palette.header}`}>
          <div className="flex items-center gap-3">
            <div className={`w-10 h-10 rounded-xl flex items-center justify-center ${palette.iconBg}`}>
              <Send className="w-5 h-5" />
            </div>
            <div>
              <h3 className="text-lg font-bold text-slate-800">{meta.title}</h3>
              <p className="text-xs text-slate-500">El dueño debe aprobar antes de ejecutar</p>
            </div>
          </div>
          <button onClick={onClose} className="p-2 text-slate-400 hover:bg-slate-200 rounded-full transition-colors">
            <X className="w-5 h-5" />
          </button>
        </div>

        <form onSubmit={handleSubmit} className="p-6 space-y-4">
          <div className="bg-slate-50 border border-slate-200 rounded-xl p-4 text-sm text-slate-700 space-y-2">
            <p>{meta.description}</p>
            <p className="text-xs text-slate-500">
              Recurso afectado: <span className="font-semibold text-slate-700">{resourceLabel}</span>
            </p>
          </div>

          <div>
            <label className="block text-sm font-semibold text-slate-700 mb-1.5 flex items-center gap-2">
              <Lock className="w-4 h-4 text-slate-400" /> Tu contraseña
            </label>
            <input
              type="password"
              required
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              className={inputClass}
              placeholder="Confirma tu identidad"
              autoFocus
            />
          </div>

          <div>
            <label className="block text-sm font-semibold text-slate-700 mb-1.5 flex items-center gap-2">
              <KeyRound className="w-4 h-4 text-slate-400" /> Código MFA
              <span className="text-xs text-slate-400 ml-auto">(si está habilitado)</span>
            </label>
            <input
              type="text"
              inputMode="numeric"
              maxLength={6}
              value={mfaCode}
              onChange={(e) => setMfaCode(e.target.value.replace(/\D/g, ''))}
              className={inputClass}
              placeholder="6 dígitos"
            />
          </div>

          <div>
            <label className="block text-sm font-semibold text-slate-700 mb-1.5">
              Motivo {requireReason
                ? <span className="text-xs font-normal text-rose-500">(obligatorio)</span>
                : <span className="text-xs font-normal text-slate-400">(opcional pero recomendado)</span>}
            </label>
            <textarea
              value={reason}
              onChange={(e) => setReason(e.target.value)}
              rows={3}
              maxLength={500}
              className={`w-full rounded-xl border border-slate-300 px-4 py-2.5 text-sm ${palette.ring} focus:ring-2 outline-none transition-all placeholder:text-slate-400 resize-none`}
              placeholder="Ayuda al dueño a entender el contexto de esta solicitud."
            />
          </div>

          {error && (
            <div className="bg-rose-50 border border-rose-200 rounded-xl p-3 text-sm text-rose-700 font-medium">
              {error}
            </div>
          )}

          <div className="flex justify-end gap-3 pt-3 border-t border-slate-100">
            <button
              type="button"
              onClick={onClose}
              className="px-5 py-2.5 text-sm font-semibold text-slate-600 hover:bg-slate-100 rounded-xl transition-colors"
            >
              Cancelar
            </button>
            <button
              type="submit"
              disabled={loading}
              className={`px-6 py-2.5 text-sm font-bold text-white rounded-xl transition-colors shadow-sm disabled:opacity-70 ${palette.btn}`}
            >
              {loading ? 'Enviando…' : 'Enviar solicitud'}
            </button>
          </div>
        </form>
      </div>
    </div>
  );
};
