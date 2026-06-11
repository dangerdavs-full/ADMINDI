import React, { useState, useEffect } from 'react';
import { X, Home, Layers, Maximize2, BedDouble, Bath, ArrowUp } from 'lucide-react';
import { UnitDTO } from '../../services/unitService';

interface Props {
  isOpen: boolean;
  onClose: () => void;
  onSubmit: (data: UnitDTO) => Promise<void>;
  initialData: UnitDTO | null;
  propertyId: string;
}

const UNIT_TYPES = [
  { value: 'departamento', label: 'Departamento' },
  { value: 'local', label: 'Local Comercial' },
  { value: 'oficina', label: 'Oficina' },
  { value: 'bodega', label: 'Bodega' },
  { value: 'casa', label: 'Casa' },
  { value: 'otro', label: 'Otro' },
];

export const UnitFormModal: React.FC<Props> = ({ isOpen, onClose, onSubmit, initialData, propertyId }) => {
  const [formData, setFormData] = useState<UnitDTO>({
    propertyId: propertyId,
    name: '',
    type: 'departamento',
    squareMeters: undefined,
    bedrooms: undefined,
    bathrooms: undefined,
    floorCode: '',
    notes: '',
  });
  const [loading, setLoading] = useState(false);

  useEffect(() => {
    if (initialData) {
      setFormData({ ...initialData, propertyId });
    } else {
      setFormData({
        propertyId,
        name: '',
        type: 'departamento',
        squareMeters: undefined,
        bedrooms: undefined,
        bathrooms: undefined,
        floorCode: '',
        notes: '',
      });
    }
  }, [initialData, isOpen, propertyId]);

  if (!isOpen) return null;

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setLoading(true);
    try {
      await onSubmit(formData);
      onClose();
    } catch (error: any) {
      console.error(error);
      alert('Error guardando unidad: ' + (error.response?.data?.message || error.message));
    } finally {
      setLoading(false);
    }
  };

  const inputClass = "w-full rounded-xl border border-slate-300 px-4 py-2.5 text-sm focus:border-indigo-500 focus:ring-2 focus:ring-indigo-500/20 outline-none transition-all placeholder:text-slate-400";

  return (
    <div className="fixed inset-0 z-[110] flex items-center justify-center p-4">
      <div className="absolute inset-0 bg-slate-900/60 backdrop-blur-sm" onClick={onClose} />
      <div className="relative bg-white rounded-2xl w-full max-w-lg shadow-2xl overflow-hidden animate-in fade-in zoom-in-95 duration-200 border border-slate-200">
        <div className="px-6 py-4 border-b border-slate-100 flex justify-between items-center bg-slate-50">
          <div className="flex items-center gap-3">
            <div className="w-10 h-10 bg-violet-100 rounded-xl flex items-center justify-center text-violet-600">
              <Home className="w-5 h-5" />
            </div>
            <h3 className="text-xl font-bold text-slate-800">
              {initialData ? 'Editar Unidad' : 'Nueva Unidad'}
            </h3>
          </div>
          <button onClick={onClose} className="p-2 text-slate-400 hover:bg-slate-200 rounded-full transition-colors">
            <X className="w-5 h-5" />
          </button>
        </div>

        <form onSubmit={handleSubmit} className="p-6 space-y-4">
          <div className="grid grid-cols-2 gap-4">
            <div>
              <label className="block text-sm font-semibold text-slate-700 mb-1.5 flex items-center gap-2">
                <Home className="w-4 h-4 text-slate-400" /> Nombre / Número
              </label>
              <input
                required type="text"
                value={formData.name}
                onChange={(e) => setFormData({ ...formData, name: e.target.value })}
                className={inputClass}
                placeholder="Ej. Depto 4B, Local 12..."
              />
            </div>
            <div>
              <label className="block text-sm font-semibold text-slate-700 mb-1.5 flex items-center gap-2">
                <Layers className="w-4 h-4 text-slate-400" /> Tipo
              </label>
              <select
                value={formData.type}
                onChange={(e) => setFormData({ ...formData, type: e.target.value })}
                className={`${inputClass} cursor-pointer bg-white`}
              >
                {UNIT_TYPES.map(t => (
                  <option key={t.value} value={t.value}>{t.label}</option>
                ))}
              </select>
            </div>
          </div>

          <div className="grid grid-cols-3 gap-4">
            <div>
              <label className="block text-sm font-semibold text-slate-700 mb-1.5 flex items-center gap-2">
                <Maximize2 className="w-4 h-4 text-slate-400" /> m²
              </label>
              <input
                type="number" step="0.01" min="0"
                value={formData.squareMeters ?? ''}
                onChange={(e) => setFormData({ ...formData, squareMeters: e.target.value ? Number(e.target.value) : undefined })}
                className={inputClass}
                placeholder="85.5"
              />
            </div>
            <div>
              <label className="block text-sm font-semibold text-slate-700 mb-1.5 flex items-center gap-2">
                <BedDouble className="w-4 h-4 text-slate-400" /> Recámaras
              </label>
              <input
                type="number" min="0"
                value={formData.bedrooms ?? ''}
                onChange={(e) => setFormData({ ...formData, bedrooms: e.target.value ? Number(e.target.value) : undefined })}
                className={inputClass}
                placeholder="2"
              />
            </div>
            <div>
              <label className="block text-sm font-semibold text-slate-700 mb-1.5 flex items-center gap-2">
                <Bath className="w-4 h-4 text-slate-400" /> Baños
              </label>
              <input
                type="number" min="0"
                value={formData.bathrooms ?? ''}
                onChange={(e) => setFormData({ ...formData, bathrooms: e.target.value ? Number(e.target.value) : undefined })}
                className={inputClass}
                placeholder="1"
              />
            </div>
          </div>

          <div>
            <label className="block text-sm font-semibold text-slate-700 mb-1.5 flex items-center gap-2">
              <ArrowUp className="w-4 h-4 text-slate-400" /> Piso / Nivel
            </label>
            <input
              type="text"
              value={formData.floorCode || ''}
              onChange={(e) => setFormData({ ...formData, floorCode: e.target.value })}
              className={inputClass}
              placeholder="PB, 1, 2, Azotea..."
            />
          </div>

          <div>
            <label className="block text-sm font-semibold text-slate-700 mb-1.5">Notas</label>
            <textarea
              value={formData.notes || ''}
              onChange={(e) => setFormData({ ...formData, notes: e.target.value })}
              rows={2}
              className={`${inputClass} resize-none`}
              placeholder="Características adicionales..."
            />
          </div>

          <div className="mt-6 flex justify-end gap-3 pt-4 border-t border-slate-100">
            <button type="button" onClick={onClose}
              className="px-5 py-2.5 text-sm font-semibold text-slate-600 hover:bg-slate-100 rounded-xl transition-colors">
              Cancelar
            </button>
            <button type="submit" disabled={loading}
              className="px-6 py-2.5 text-sm font-bold text-white bg-violet-600 rounded-xl hover:bg-violet-700 transition-colors shadow-sm shadow-violet-500/30 disabled:opacity-70">
              {loading ? 'Procesando...' : initialData ? 'Guardar Cambios' : 'Crear Unidad'}
            </button>
          </div>
        </form>
      </div>
    </div>
  );
};
