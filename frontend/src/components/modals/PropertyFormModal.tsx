import React, { useState, useEffect } from 'react';
import { X, Building, MapPin, Activity, FileText, Hash, AlignLeft } from 'lucide-react';
import { PropertyDTO } from '../../services/propertyService';

interface Props {
  isOpen: boolean;
  onClose: () => void;
  onSubmit: (data: PropertyDTO) => Promise<void>;
  initialData: PropertyDTO | null;
}

const PROPERTY_TYPES = [
  { value: 'habitacional', label: 'Habitacional' },
  { value: 'comercial', label: 'Comercial' },
  { value: 'mixto', label: 'Mixto' },
  { value: 'industrial', label: 'Industrial' },
  { value: 'oficinas', label: 'Oficinas' },
];

function composeFullAddress(a: {
  street: string;
  ext: string;
  interior: string;
  neighborhood: string;
  city: string;
  state: string;
  zip: string;
  reference: string;
}): string {
  const line1 = [a.street.trim(), a.ext.trim()].filter(Boolean).join(' ');
  const parts = [
    line1,
    a.interior.trim() ? `Int. ${a.interior.trim()}` : '',
    a.neighborhood.trim(),
    [a.zip.trim(), a.city.trim(), a.state.trim()].filter(Boolean).join(' '),
    a.reference.trim() ? `Ref: ${a.reference.trim()}` : '',
  ].filter(Boolean);
  return parts.join(', ');
}

export const PropertyFormModal: React.FC<Props> = ({ isOpen, onClose, onSubmit, initialData }) => {
  const [formData, setFormData] = useState<PropertyDTO>({
    name: '',
    address: '',
    type: 'habitacional',
    predial: '',
    description: '',
    status: 'AVAILABLE'
  });
  const [addr, setAddr] = useState({
    street: '',
    ext: '',
    interior: '',
    neighborhood: '',
    city: '',
    state: '',
    zip: '',
    reference: '',
  });
  const [loading, setLoading] = useState(false);

  useEffect(() => {
    if (initialData) {
      setFormData(initialData);
      setAddr({
        street: initialData.address || '',
        ext: '',
        interior: '',
        neighborhood: '',
        city: '',
        state: '',
        zip: '',
        reference: '',
      });
    } else {
      setFormData({
        name: '',
        address: '',
        type: 'habitacional',
        predial: '',
        description: '',
        status: 'AVAILABLE'
      });
      setAddr({
        street: '',
        ext: '',
        interior: '',
        neighborhood: '',
        city: '',
        state: '',
        zip: '',
        reference: '',
      });
    }
  }, [initialData, isOpen]);

  if (!isOpen) return null;

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    const full = composeFullAddress(addr);
    if (!full.trim()) {
      alert('Complete la direccion del inmueble.');
      return;
    }
    setLoading(true);
    try {
      await onSubmit({ ...formData, address: full });
      onClose();
    } catch (error) {
      console.error(error);
      alert('Error guardando el inmueble.');
    } finally {
      setLoading(false);
    }
  };

  const inputClass = "w-full rounded-xl border border-slate-300 px-4 py-2.5 text-sm focus:border-indigo-500 focus:ring-2 focus:ring-indigo-500/20 outline-none transition-all placeholder:text-slate-400";

  return (
    <div className="fixed inset-0 z-[110] flex items-center justify-center p-4">
      <div className="absolute inset-0 bg-slate-900/60 backdrop-blur-sm" onClick={onClose} />
      
      <div className="relative bg-white rounded-2xl w-full max-w-lg shadow-2xl overflow-hidden animate-in fade-in zoom-in-95 duration-200 border border-slate-200 max-h-[90vh] flex flex-col">
        <div className="px-6 py-4 border-b border-slate-100 flex justify-between items-center bg-slate-50 shrink-0">
          <div className="flex items-center gap-3">
            <div className="w-10 h-10 bg-indigo-100 rounded-xl flex items-center justify-center text-indigo-600">
              <Building className="w-5 h-5" />
            </div>
            <h3 className="text-xl font-bold text-slate-800">
              {initialData ? 'Editar Inmueble' : 'Registrar Inmueble'}
            </h3>
          </div>
          <button onClick={onClose} className="p-2 text-slate-400 hover:bg-slate-200 rounded-full transition-colors">
            <X className="w-5 h-5" />
          </button>
        </div>

        <form onSubmit={handleSubmit} className="p-6 space-y-4 overflow-y-auto">
          <div>
            <label className="block text-sm font-semibold text-slate-700 mb-1.5 flex items-center gap-2">
              <Building className="w-4 h-4 text-slate-400" /> Nombre del Edificio / Complejo
            </label>
            <input
              required
              type="text"
              value={formData.name || ''}
              onChange={(e) => setFormData({ ...formData, name: e.target.value })}
              className={inputClass}
              placeholder="Ej. Torre Mayor, Plaza Central..."
            />
          </div>

          <div className="grid grid-cols-2 gap-4">
            <div>
              <label className="block text-sm font-semibold text-slate-700 mb-1.5 flex items-center gap-2">
                <Activity className="w-4 h-4 text-indigo-500" /> Tipo
              </label>
              <select
                value={formData.type || 'habitacional'}
                onChange={(e) => setFormData({ ...formData, type: e.target.value })}
                className={`${inputClass} cursor-pointer bg-white`}
              >
                {PROPERTY_TYPES.map(t => (
                  <option key={t.value} value={t.value}>{t.label}</option>
                ))}
              </select>
            </div>
            <div>
              <label className="block text-sm font-semibold text-slate-700 mb-1.5 flex items-center gap-2">
                <Hash className="w-4 h-4 text-slate-400" /> Cuenta Predial
              </label>
              <input
                type="text"
                value={formData.predial || ''}
                onChange={(e) => setFormData({ ...formData, predial: e.target.value })}
                className={inputClass}
                placeholder="Clave catastral..."
              />
            </div>
          </div>

          <div className="space-y-3">
            <p className="text-sm font-bold text-slate-800 flex items-center gap-2">
              <MapPin className="w-4 h-4 text-slate-400" /> Direccion del inmueble (unidad rentable)
            </p>
            <div className="grid grid-cols-1 md:grid-cols-2 gap-3">
              <input
                required
                placeholder="Calle"
                value={addr.street}
                onChange={(e) => setAddr({ ...addr, street: e.target.value })}
                className={inputClass}
              />
              <input
                required
                placeholder="Num. exterior"
                value={addr.ext}
                onChange={(e) => setAddr({ ...addr, ext: e.target.value })}
                className={inputClass}
              />
              <input
                placeholder="Num. interior (opcional)"
                value={addr.interior}
                onChange={(e) => setAddr({ ...addr, interior: e.target.value })}
                className={inputClass}
              />
              <input
                required
                placeholder="Colonia"
                value={addr.neighborhood}
                onChange={(e) => setAddr({ ...addr, neighborhood: e.target.value })}
                className={inputClass}
              />
              <input
                required
                placeholder="Ciudad"
                value={addr.city}
                onChange={(e) => setAddr({ ...addr, city: e.target.value })}
                className={inputClass}
              />
              <input
                required
                placeholder="Estado"
                value={addr.state}
                onChange={(e) => setAddr({ ...addr, state: e.target.value })}
                className={inputClass}
              />
              <input
                required
                placeholder="Codigo postal"
                value={addr.zip}
                onChange={(e) => setAddr({ ...addr, zip: e.target.value })}
                className={inputClass}
              />
              <input
                placeholder="Referencia (opcional)"
                value={addr.reference}
                onChange={(e) => setAddr({ ...addr, reference: e.target.value })}
                className={inputClass}
              />
            </div>
            <p className="text-xs text-slate-500">
              Vista previa: {composeFullAddress(addr) || '(complete campos requeridos)'}
            </p>
          </div>

          <div>
            <label className="block text-sm font-semibold text-slate-700 mb-1.5 flex items-center gap-2">
              <AlignLeft className="w-4 h-4 text-slate-400" /> Descripción / Notas
            </label>
            <textarea
              value={formData.description || ''}
              onChange={(e) => setFormData({ ...formData, description: e.target.value })}
              rows={3}
              className={`${inputClass} resize-none`}
              placeholder="Notas adicionales, referencias, características especiales..."
            />
          </div>

          <div>
            <label className="block text-sm font-semibold text-slate-700 mb-1.5 flex items-center gap-2">
              <FileText className="w-4 h-4 text-indigo-500" /> Estatus Operativo
            </label>
            <select
              value={formData.status}
              onChange={(e) => setFormData({ ...formData, status: e.target.value as PropertyDTO['status'] })}
              className={`${inputClass} cursor-pointer bg-white`}
            >
              <option value="AVAILABLE">Disponible</option>
              <option value="OCCUPIED">Ocupado / Rentado</option>
              <option value="MAINTENANCE">En Mantenimiento</option>
            </select>
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
              className="px-6 py-2.5 text-sm font-bold text-white bg-indigo-600 rounded-xl hover:bg-indigo-700 transition-colors shadow-sm shadow-indigo-500/30 disabled:opacity-70 flex items-center gap-2"
            >
              {loading ? 'Procesando...' : initialData ? 'Guardar Cambios' : 'Crear Inmueble'}
            </button>
          </div>
        </form>
      </div>
    </div>
  );
};
