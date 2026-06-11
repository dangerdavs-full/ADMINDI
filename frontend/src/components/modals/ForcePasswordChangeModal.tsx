import React, { useState } from 'react';
import { ShieldAlert, AlertTriangle, KeyRound } from 'lucide-react';
import api from '../../services/api';

interface Props {
  onSuccess: () => void;
}

export const ForcePasswordChangeModal: React.FC<Props> = ({ onSuccess }) => {
  const [newPassword, setNewPassword] = useState('');
  const [confirmPassword, setConfirmPassword] = useState('');
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (newPassword !== confirmPassword) {
      setError('Las contraseñas no coinciden. Verífique ambas entradas.');
      return;
    }
    if (newPassword.length < 8) {
      setError('La contraseña debe tener al menos 8 caracteres por directiva de seguridad.');
      return;
    }

    setLoading(true);
    setError('');
    try {
      await api.post('/auth/change-password', { newPassword });
      onSuccess(); // Notificamos al contexto para que levante la restricción
    } catch (err: any) {
      setError(err.response?.data?.message || 'Error del servidor al cambiar contraseña.');
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="fixed inset-0 z-[100] flex items-center justify-center p-4 bg-slate-900/90 backdrop-blur-md">
      <div className="relative bg-white rounded-2xl w-full max-w-lg shadow-2xl overflow-hidden border border-red-500/20 animate-in fade-in zoom-in-95 duration-500">
        
        <div className="bg-red-50 p-6 border-b border-red-100 flex flex-col items-center text-center">
          <div className="w-16 h-16 bg-red-100 rounded-full flex items-center justify-center mb-4">
            <ShieldAlert className="w-8 h-8 text-red-600" />
          </div>
          <h2 className="text-2xl font-bold text-red-900 mb-2 uppercase tracking-wide">Acceso Restringido</h2>
          <p className="text-sm font-medium text-red-700 max-w-sm">
            Protocolo Fase 5.1: Se requiere establecer una credencial permanente en su primer ingreso.
          </p>
        </div>

        <form onSubmit={handleSubmit} className="p-8 space-y-6">
          <div className="bg-amber-50 p-4 rounded-xl border border-amber-200 flex gap-4">
            <AlertTriangle className="w-6 h-6 text-amber-600 flex-shrink-0" />
            <div className="text-sm text-amber-800">
              <strong className="block text-amber-900 mb-1">¡Aviso Administrativo Crítico!</strong>
              Sus credenciales de acceso son su responsabilidad absoluta. La pérdida o solicitud de 
              restablecimiento manual incurrirá en una <strong>multa administrativa de $500 MXN</strong> facturada al titular. Memorice o guarde su contraseña de forma segura.
            </div>
          </div>

          {error && (
            <div className="bg-red-50 text-red-600 text-sm p-3 rounded-lg border border-red-100 font-semibold text-center">
              {error}
            </div>
          )}

          <div className="space-y-4">
            <div>
              <label className="block text-sm font-bold text-slate-700 mb-1.5">Nueva Contraseña Permanente</label>
              <div className="relative">
                <div className="absolute inset-y-0 left-0 pl-3 flex items-center pointer-events-none">
                  <KeyRound className="h-5 w-5 text-slate-400" />
                </div>
                <input
                  required
                  type="password"
                  value={newPassword}
                  onChange={(e) => setNewPassword(e.target.value)}
                  className="pl-10 w-full rounded-xl border border-slate-300 bg-slate-50 px-4 py-3 text-sm outline-none focus:border-red-500 focus:bg-white focus:ring-4 focus:ring-red-500/10 transition-all font-medium text-slate-900 tracking-widest placeholder:tracking-normal"
                  placeholder="Mínimo 8 caracteres"
                />
              </div>
            </div>

            <div>
              <label className="block text-sm font-bold text-slate-700 mb-1.5">Confirmar Contraseña</label>
              <div className="relative">
                <div className="absolute inset-y-0 left-0 pl-3 flex items-center pointer-events-none">
                  <KeyRound className="h-5 w-5 text-slate-400" />
                </div>
                <input
                  required
                  type="password"
                  value={confirmPassword}
                  onChange={(e) => setConfirmPassword(e.target.value)}
                  className="pl-10 w-full rounded-xl border border-slate-300 bg-slate-50 px-4 py-3 text-sm outline-none focus:border-red-500 focus:bg-white focus:ring-4 focus:ring-red-500/10 transition-all font-medium text-slate-900 tracking-widest placeholder:tracking-normal"
                  placeholder="Repita su nueva contraseña"
                />
              </div>
            </div>
          </div>

          <button
            type="submit"
            disabled={loading}
            className="w-full px-4 py-3.5 mt-2 text-sm font-bold text-white uppercase tracking-wider bg-slate-900 rounded-xl hover:bg-slate-800 transition-colors shadow-lg shadow-slate-900/20 disabled:opacity-70 flex justify-center items-center"
          >
            {loading ? (
              <div className="w-5 h-5 border-2 border-white/30 border-t-white rounded-full animate-spin" />
            ) : 'Entendido, Guardar Contraseña'}
          </button>
        </form>
      </div>
    </div>
  );
};
