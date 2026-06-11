import React, { useState } from 'react';
import { X, XCircle } from 'lucide-react';

interface Props {
  isOpen: boolean;
  onClose: () => void;
  /** Called with the trimmed reason (undefined when left empty). */
  onConfirm: (reason: string | undefined) => Promise<void>;
  /** Short title describing what is being rejected. */
  title: string;
  /** Free-form description shown above the reason textarea. */
  description: string;
  confirmLabel?: string;
}

/**
 * Modal para que el dueño rechace una solicitud de aprobación (PROPERTY_DELETE_REQUESTED,
 * TENANT_ARCHIVE_REQUESTED, LEASE_TERMINATE_REQUESTED). El rechazo NO requiere reauth
 * (el backend no exige password+MFA para `/tasks/{id}/reject`) pero sí admite una razón
 * opcional que se registra en la auditoría y se envía al solicitante en la notificación.
 */
export const ApprovalRejectModal: React.FC<Props> = ({
  isOpen,
  onClose,
  onConfirm,
  title,
  description,
  confirmLabel = 'Rechazar solicitud',
}) => {
  const [reason, setReason] = useState('');
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');

  if (!isOpen) return null;

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setError('');
    setLoading(true);
    try {
      const trimmed = reason.trim();
      await onConfirm(trimmed.length > 0 ? trimmed : undefined);
      setReason('');
      onClose();
    } catch (err: any) {
      setError(err.response?.data?.message || err.message || 'Error al rechazar la solicitud.');
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="fixed inset-0 z-[120] flex items-center justify-center p-4">
      <div className="absolute inset-0 bg-slate-900/70 backdrop-blur-sm" onClick={onClose} />
      <div className="relative bg-white rounded-2xl w-full max-w-md shadow-2xl overflow-hidden animate-in fade-in zoom-in-95 duration-200 border border-slate-200">
        <div className="px-6 py-4 border-b flex justify-between items-center bg-slate-50 border-slate-100">
          <div className="flex items-center gap-3">
            <div className="w-10 h-10 bg-slate-200 text-slate-600 rounded-xl flex items-center justify-center">
              <XCircle className="w-5 h-5" />
            </div>
            <div>
              <h3 className="text-lg font-bold text-slate-800">{title}</h3>
              <p className="text-xs text-slate-500">La solicitud quedará registrada como rechazada</p>
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
            <label className="block text-sm font-semibold text-slate-700 mb-1.5">
              Motivo del rechazo <span className="text-xs font-normal text-slate-400">(opcional)</span>
            </label>
            <textarea
              value={reason}
              onChange={(e) => setReason(e.target.value)}
              rows={3}
              maxLength={500}
              className="w-full rounded-xl border border-slate-300 px-4 py-2.5 text-sm focus:border-slate-500 focus:ring-2 focus:ring-slate-500/20 outline-none transition-all placeholder:text-slate-400 resize-none"
              placeholder="Ej. prefiero que se maneje el caso por otra vía, o falta información…"
            />
            <p className="text-xs text-slate-400 mt-1">
              El motivo se envía al solicitante y se guarda en la auditoría.
            </p>
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
              className="px-6 py-2.5 text-sm font-bold text-white bg-slate-700 hover:bg-slate-800 rounded-xl transition-colors shadow-sm disabled:opacity-70"
            >
              {loading ? 'Enviando…' : confirmLabel}
            </button>
          </div>
        </form>
      </div>
    </div>
  );
};
