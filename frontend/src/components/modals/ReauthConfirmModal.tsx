import React, { useState } from 'react';
import { X, ShieldCheck, Lock, KeyRound } from 'lucide-react';

interface Props {
  isOpen: boolean;
  onClose: () => void;
  onConfirm: (password: string, mfaCode: string) => Promise<void>;
  title?: string;
  description?: string;
  confirmLabel?: string;
  accent?: 'emerald' | 'indigo' | 'rose';
}

/**
 * Modal genérico de reautenticación (MFA + contraseña) para acciones sensibles
 * que no encajan en ReauthDeleteModal (p. ej. acknowledge/aprobar). Mantiene el
 * mismo contrato de onConfirm(password, mfaCode) para máxima compatibilidad.
 */
export const ReauthConfirmModal: React.FC<Props> = ({
  isOpen,
  onClose,
  onConfirm,
  title = 'Confirmar acción',
  description = 'Esta acción requiere que reautentiques tu sesión.',
  confirmLabel = 'Confirmar',
  accent = 'indigo',
}) => {
  const [password, setPassword] = useState('');
  const [mfaCode, setMfaCode] = useState('');
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');

  if (!isOpen) return null;

  const palette = {
    emerald: {
      border: 'border-emerald-200', header: 'bg-emerald-50 border-emerald-100',
      iconBg: 'bg-emerald-100 text-emerald-600', btn: 'bg-emerald-600 hover:bg-emerald-700 shadow-emerald-500/30',
      ring: 'focus:border-emerald-500 focus:ring-emerald-500/20',
    },
    indigo: {
      border: 'border-indigo-200', header: 'bg-indigo-50 border-indigo-100',
      iconBg: 'bg-indigo-100 text-indigo-600', btn: 'bg-indigo-600 hover:bg-indigo-700 shadow-indigo-500/30',
      ring: 'focus:border-indigo-500 focus:ring-indigo-500/20',
    },
    rose: {
      border: 'border-rose-200', header: 'bg-rose-50 border-rose-100',
      iconBg: 'bg-rose-100 text-rose-600', btn: 'bg-rose-600 hover:bg-rose-700 shadow-rose-500/30',
      ring: 'focus:border-rose-500 focus:ring-rose-500/20',
    },
  }[accent];

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setError('');
    if (!password.trim()) { setError('Ingresa tu contraseña.'); return; }
    setLoading(true);
    try {
      await onConfirm(password, mfaCode);
      setPassword(''); setMfaCode('');
      onClose();
    } catch (err: any) {
      setError(err.response?.data?.message || err.message || 'Error de reautenticación.');
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
              <ShieldCheck className="w-5 h-5" />
            </div>
            <div>
              <h3 className="text-lg font-bold text-slate-800">{title}</h3>
              <p className="text-xs text-slate-500">Reautenticación requerida</p>
            </div>
          </div>
          <button onClick={onClose} className="p-2 text-slate-400 hover:bg-slate-200 rounded-full transition-colors">
            <X className="w-5 h-5" />
          </button>
        </div>

        <form onSubmit={handleSubmit} className="p-6 space-y-4">
          <div className="bg-slate-50 border border-slate-200 rounded-xl p-4 text-sm text-slate-700">
            {description}
          </div>

          <div>
            <label className="block text-sm font-semibold text-slate-700 mb-1.5 flex items-center gap-2">
              <Lock className="w-4 h-4 text-slate-400" /> Contraseña
            </label>
            <input
              type="password"
              required
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              className={inputClass}
              placeholder="Tu contraseña actual"
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
              placeholder="6 dígitos de tu autenticador"
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
              {loading ? 'Verificando...' : confirmLabel}
            </button>
          </div>
        </form>
      </div>
    </div>
  );
};
