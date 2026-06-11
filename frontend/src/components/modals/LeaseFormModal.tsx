import React, { useState, useEffect } from 'react';
import { X, FileText, Home, Users, DollarSign, Calendar, Paperclip } from 'lucide-react';
import { LeaseDTO } from '../../services/leaseService';
import { PropertyDTO } from '../../services/propertyService';
import { TenantDTO } from '../../services/tenantService';

export interface LeaseCreatePayload extends Partial<LeaseDTO> {
  propertyId?: string;
}

interface Props {
  isOpen: boolean;
  onClose: () => void;
  onSubmit: (data: LeaseCreatePayload, contractPdf?: File | null) => Promise<void>;
  properties: PropertyDTO[];
  tenants: TenantDTO[];
  initialPropertyId?: string;
  /** UserEntity id (rol TENANT) — debe coincidir con backend LeaseService */
  initialTenantUserId?: string;
}

/**
 * Contrato siempre sobre {@link PropertyDTO} (dominio por inmueble). Sin selección de unidad.
 */
export const LeaseFormModal: React.FC<Props> = ({
  isOpen,
  onClose,
  onSubmit,
  properties,
  tenants,
  initialPropertyId,
  initialTenantUserId,
}) => {
  const [selectedPropertyId, setSelectedPropertyId] = useState('');
  const [formData, setFormData] = useState<Partial<LeaseDTO>>({
    tenantId: '',
    startDate: '',
    endDate: '',
    monthlyRent: 0,
    depositAmount: 0,
    paymentDay: 1,
  });
  const [loading, setLoading] = useState(false);
  const [contractPdf, setContractPdf] = useState<File | null>(null);

  useEffect(() => {
    if (!isOpen) {
      setSelectedPropertyId('');
      setFormData({ tenantId: '', startDate: '', endDate: '', monthlyRent: 0, depositAmount: 0, paymentDay: 1 });
      setContractPdf(null);
    } else {
      if (initialPropertyId) setSelectedPropertyId(initialPropertyId);
      if (initialTenantUserId) {
        setFormData((fd) => ({ ...fd, tenantId: initialTenantUserId }));
      }
    }
  }, [isOpen, initialPropertyId, initialTenantUserId]);

  if (!isOpen) return null;

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    const pid = selectedPropertyId || initialPropertyId || '';
    const tid = formData.tenantId || initialTenantUserId || '';
    if (!pid || !tid) {
      alert('Indique inmueble y arrendatario (usuario).');
      return;
    }
    if (!formData.startDate || !formData.endDate) {
      alert('Complete fechas de contrato.');
      return;
    }
    setLoading(true);
    try {
      await onSubmit(
        {
          propertyId: pid,
          tenantId: tid,
          startDate: formData.startDate,
          endDate: formData.endDate,
          monthlyRent: formData.monthlyRent,
          depositAmount: formData.depositAmount,
          paymentDay: formData.paymentDay,
          documentUrl: formData.documentUrl,
        },
        contractPdf
      );
      onClose();
    } catch (error: unknown) {
      const err = error as { response?: { data?: { message?: string } }; message?: string };
      console.error(error);
      alert(err.response?.data?.message || err.message || 'Error al crear contrato');
    } finally {
      setLoading(false);
    }
  };

  const inputClass =
    'w-full rounded-xl border border-slate-300 px-4 py-2.5 text-sm focus:border-emerald-500 focus:ring-2 focus:ring-emerald-500/20 outline-none transition-all placeholder:text-slate-400';

  return (
    <div className="fixed inset-0 z-[110] flex items-center justify-center p-4">
      <div className="absolute inset-0 bg-slate-900/60 backdrop-blur-sm" onClick={onClose} />
      <div className="relative bg-white rounded-2xl w-full max-w-lg shadow-2xl overflow-hidden animate-in fade-in zoom-in-95 duration-200 border border-slate-200 max-h-[90vh] flex flex-col">
        <div className="px-6 py-4 border-b border-slate-100 flex justify-between items-center bg-slate-50 shrink-0">
          <div className="flex items-center gap-3">
            <div className="w-10 h-10 bg-emerald-100 rounded-xl flex items-center justify-center text-emerald-600">
              <FileText className="w-5 h-5" />
            </div>
            <h3 className="text-xl font-bold text-slate-800">Nuevo contrato</h3>
          </div>
          <button onClick={onClose} className="p-2 text-slate-400 hover:bg-slate-200 rounded-full transition-colors">
            <X className="w-5 h-5" />
          </button>
        </div>

        <form onSubmit={handleSubmit} className="p-6 space-y-4 overflow-y-auto">
          <div>
            <label className="block text-sm font-semibold text-slate-700 mb-1.5 flex items-center gap-2">
              <Home className="w-4 h-4 text-slate-400" /> Inmueble
            </label>
            <select
              value={selectedPropertyId}
              onChange={(e) => setSelectedPropertyId(e.target.value)}
              disabled={!!initialPropertyId}
              className={`${inputClass} cursor-pointer bg-white disabled:opacity-70`}
            >
              <option value="">Seleccione inmueble...</option>
              {properties.map((p) => (
                <option key={p.id} value={p.id}>
                  {p.name}
                </option>
              ))}
            </select>
          </div>

          <p className="text-xs text-slate-600 bg-slate-50 border border-slate-200 rounded-xl p-3">
            El arriendo se registra sobre el <strong>inmueble</strong> completo (modelo dominante de la plataforma).
          </p>

          <div>
            <label className="block text-sm font-semibold text-slate-700 mb-1.5 flex items-center gap-2">
              <Users className="w-4 h-4 text-teal-500" /> Arrendatario (usuario)
            </label>
            <select
              value={formData.tenantId}
              required
              disabled={!!initialTenantUserId}
              onChange={(e) => setFormData({ ...formData, tenantId: e.target.value })}
              className={`${inputClass} cursor-pointer bg-white disabled:opacity-70`}
            >
              <option value="">Seleccione arrendatario...</option>
              {tenants.map((t) => (
                <option key={t.id} value={t.userId || ''}>
                  {t.name} ({t.email})
                </option>
              ))}
            </select>
          </div>

          <div className="grid grid-cols-2 gap-4">
            <div>
              <label className="block text-sm font-semibold text-slate-700 mb-1.5 flex items-center gap-2">
                <Calendar className="w-4 h-4 text-slate-400" /> Inicio
              </label>
              <input
                type="date"
                required
                value={formData.startDate || ''}
                onChange={(e) => setFormData({ ...formData, startDate: e.target.value })}
                className={inputClass}
              />
            </div>
            <div>
              <label className="block text-sm font-semibold text-slate-700 mb-1.5 flex items-center gap-2">
                <Calendar className="w-4 h-4 text-slate-400" /> Fin
              </label>
              <input
                type="date"
                required
                value={formData.endDate || ''}
                onChange={(e) => setFormData({ ...formData, endDate: e.target.value })}
                className={inputClass}
              />
            </div>
          </div>

          <div className="grid grid-cols-3 gap-4">
            <div>
              <label className="block text-sm font-semibold text-slate-700 mb-1.5 flex items-center gap-2">
                <DollarSign className="w-4 h-4 text-emerald-500" /> Renta
              </label>
              <input
                type="number"
                step="0.01"
                min="0"
                required
                value={formData.monthlyRent || ''}
                onChange={(e) => setFormData({ ...formData, monthlyRent: Number(e.target.value) })}
                className={inputClass}
                placeholder="15000"
              />
            </div>
            <div>
              <label className="block text-sm font-semibold text-slate-700 mb-1.5">Depósito</label>
              <input
                type="number"
                step="0.01"
                min="0"
                value={formData.depositAmount || ''}
                onChange={(e) => setFormData({ ...formData, depositAmount: Number(e.target.value) })}
                className={inputClass}
                placeholder="30000"
              />
            </div>
            <div>
              <label className="block text-sm font-semibold text-slate-700 mb-1.5">Día pago</label>
              <input
                type="number"
                min="1"
                max="31"
                required
                value={formData.paymentDay || 1}
                onChange={(e) => setFormData({ ...formData, paymentDay: Number(e.target.value) })}
                className={inputClass}
              />
            </div>
          </div>

          <div>
            <label className="block text-sm font-semibold text-slate-700 mb-1.5 flex items-center gap-2">
              <Paperclip className="w-4 h-4 text-slate-500" /> Contrato firmado (PDF)
            </label>
            <input
              type="file"
              accept="application/pdf,.pdf"
              className={`${inputClass} file:mr-3 file:py-1.5 file:px-3 file:rounded-lg file:border-0 file:bg-slate-100 file:text-sm file:font-semibold`}
              onChange={(e) => setContractPdf(e.target.files?.[0] ?? null)}
            />
            {contractPdf && (
              <p className="text-xs text-slate-600 mt-1">
                Archivo: <span className="font-medium">{contractPdf.name}</span>
              </p>
            )}
            <p className="text-xs text-slate-400 mt-1">Opcional. Máx. 10 MB. Solo PDF.</p>
          </div>

          <div className="mt-6 flex justify-end gap-3 pt-4 border-t border-slate-100">
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
              className="px-6 py-2.5 text-sm font-bold text-white bg-emerald-600 rounded-xl hover:bg-emerald-700 transition-colors shadow-sm shadow-emerald-500/30 disabled:opacity-70"
            >
              {loading ? 'Procesando...' : 'Crear contrato'}
            </button>
          </div>
        </form>
      </div>
    </div>
  );
};
