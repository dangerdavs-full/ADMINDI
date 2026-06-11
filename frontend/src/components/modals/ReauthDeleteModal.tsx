import React, { useState } from 'react';
import { X, ShieldAlert, Lock, KeyRound } from 'lucide-react';

interface Props {
  isOpen: boolean;
  onClose: () => void;
  onConfirm: (password: string, mfaCode: string) => Promise<void>;
  /** Legacy prop used by property-deletion flows. Kept for backwards compatibility. */
  propertyName?: string;
  /** Optional header title; defaults to "Confirmar Eliminación". */
  title?: string;
  /** Optional warning block; when provided overrides the default "estás a punto de eliminar" copy. */
  warningMessage?: React.ReactNode;
  /** Optional subtitle next to the title. */
  subtitle?: string;
  /** Optional submit button label; defaults to "Confirmar Eliminación". */
  confirmLabel?: string;
}

export const ReauthDeleteModal: React.FC<Props> = ({
  isOpen,
  onClose,
  onConfirm,
  propertyName,
  title = 'Confirmar Eliminación',
  warningMessage,
  subtitle = 'Reautenticación requerida',
  confirmLabel = 'Confirmar Eliminación',
}) => {
  const [password, setPassword] = useState('');
  const [mfaCode, setMfaCode] = useState('');
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');

  if (!isOpen) return null;

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setError('');
    if (!password.trim()) {
      setError('Ingresa tu contraseña.');
      return;
    }
    setLoading(true);
    try {
      await onConfirm(password, mfaCode);
      setPassword('');
      setMfaCode('');
      onClose();
    } catch (err: any) {
      setError(err.response?.data?.message || err.message || 'Error de reautenticación.');
    } finally {
      setLoading(false);
    }
  };

  const inputClass = "w-full rounded-xl border border-slate-300 px-4 py-2.5 text-sm focus:border-rose-500 focus:ring-2 focus:ring-rose-500/20 outline-none transition-all placeholder:text-slate-400";

  return (
    <div className="fixed inset-0 z-[120] flex items-center justify-center p-4">
      <div className="absolute inset-0 bg-slate-900/70 backdrop-blur-sm" onClick={onClose} />
      <div className="relative bg-white rounded-2xl w-full max-w-md shadow-2xl overflow-hidden animate-in fade-in zoom-in-95 duration-200 border border-rose-200">
        {/* Header */}
        <div className="px-6 py-4 border-b border-rose-100 flex justify-between items-center bg-rose-50">
          <div className="flex items-center gap-3">
            <div className="w-10 h-10 bg-rose-100 rounded-xl flex items-center justify-center text-rose-600">
              <ShieldAlert className="w-5 h-5" />
            </div>
            <div>
              <h3 className="text-lg font-bold text-slate-800">{title}</h3>
              <p className="text-xs text-slate-500">{subtitle}</p>
            </div>
          </div>
          <button onClick={onClose} className="p-2 text-slate-400 hover:bg-slate-200 rounded-full transition-colors">
            <X className="w-5 h-5" />
          </button>
        </div>

        <form onSubmit={handleSubmit} className="p-6 space-y-4">
          {/* Warning */}
          <div className="bg-rose-50 border border-rose-200 rounded-xl p-4">
            {warningMessage ? (
              <div className="text-sm text-rose-800 font-medium">{warningMessage}</div>
            ) : (
              <p className="text-sm text-rose-800 font-medium">
                Estás a punto de eliminar <strong>"{propertyName}"</strong>. Esta acción es una baja lógica (soft delete).
              </p>
            )}
          </div>

          {/* Password */}
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

          {/* MFA Code */}
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

          {/* Error */}
          {error && (
            <div className="bg-rose-50 border border-rose-200 rounded-xl p-3 text-sm text-rose-700 font-medium">
              {error}
            </div>
          )}

          {/* Actions */}
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
              className="px-6 py-2.5 text-sm font-bold text-white bg-rose-600 rounded-xl hover:bg-rose-700 transition-colors shadow-sm shadow-rose-500/30 disabled:opacity-70"
            >
              {loading ? 'Verificando...' : confirmLabel}
            </button>
          </div>
        </form>
      </div>
    </div>
  );
};
