import React, { useState, useEffect } from 'react';
import { X, Users, Mail, Phone, Building, DollarSign, Calendar, Paperclip, AtSign } from 'lucide-react';
import { TenantDTO } from '../../services/tenantService';
import { propertyService, PropertyDTO } from '../../services/propertyService';
import { useUsernameAvailability } from '../../hooks/useUsernameAvailability';
import { UsernameAvailabilityHint } from '../UsernameAvailabilityHint';

interface Props {
  isOpen: boolean;
  onClose: () => void;
  onSubmit: (data: TenantDTO, contractPdf?: File | null) => Promise<void>;
  initialData: TenantDTO | null;
}

const isoDate = (d: Date) => d.toISOString().slice(0, 10);

export const TenantFormModal: React.FC<Props> = ({ isOpen, onClose, onSubmit, initialData }) => {
  const [formData, setFormData] = useState<TenantDTO>({
    name: '',
    username: '',
    email: '',
    phone: '',
    propertyId: '',
    rentAmount: 0,
    paymentDay: 5,
    hasLateFee: false,
    lateFeeType: 'FIXED_AMOUNT',
    lateFeeValue: 0,
    gracePeriodDays: 0,
    leaseStartDate: '',
    leaseEndDate: '',
    depositAmount: 0,
  } as TenantDTO);

  const [countryCode, setCountryCode] = useState('+52');
  const [phoneNumber, setPhoneNumber] = useState('');
  const [loading, setLoading] = useState(false);
  const [properties, setProperties] = useState<PropertyDTO[]>([]);
  const [contractPdf, setContractPdf] = useState<File | null>(null);

  useEffect(() => {
    if (isOpen) {
      propertyService.getMyProperties().then(setProperties).catch(console.error);
    }
  }, [isOpen]);

  useEffect(() => {
    if (initialData) {
      setFormData(initialData);
      if (initialData.phone) {
        const parts = initialData.phone.split(' ');
        if (parts.length > 1) {
          setCountryCode(parts[0]);
          setPhoneNumber(parts.slice(1).join(''));
        } else {
          setPhoneNumber(initialData.phone);
        }
      }
      setContractPdf(null);
    } else {
      const end = new Date();
      end.setFullYear(end.getFullYear() + 1);
      setFormData({
        name: '',
        username: '',
        email: '',
        phone: '',
        propertyId: '',
        rentAmount: 0,
        paymentDay: 1,
        hasLateFee: false,
        lateFeeType: 'FIXED_AMOUNT',
        lateFeeValue: 0,
        gracePeriodDays: 0,
        leaseStartDate: isoDate(new Date()),
        leaseEndDate: isoDate(end),
        depositAmount: 0,
      } as TenantDTO);
      setCountryCode('+52');
      setPhoneNumber('');
      setContractPdf(null);
    }
  }, [initialData, isOpen, properties]);

  useEffect(() => {
    if (!initialData && isOpen && properties.length > 0) {
      setFormData((fd) => {
        if (fd.propertyId) return fd;
        const avail = properties.find((p) => p.status === 'AVAILABLE');
        return avail?.id ? { ...fd, propertyId: avail.id! } : fd;
      });
    }
  }, [initialData, isOpen, properties]);

  // V67 — verificación de disponibilidad en vivo (solo en modo alta).
  const usernameAvailability = useUsernameAvailability(formData.username || '', {
    disabled: !!initialData,
  });

  if (!isOpen) return null;

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!initialData) {
      if (!formData.username || !formData.username.trim()) {
        alert('El usuario para iniciar sesión es obligatorio.');
        return;
      }
      // V51 — username case-sensitive: admite A-Z.
      if (!/^[A-Za-z0-9][A-Za-z0-9._-]{2,63}$/.test((formData.username || '').trim())) {
        alert('El usuario sólo admite letras (mayúsculas o minúsculas), números, punto, guión o guión bajo (3–64 caracteres, iniciando con letra o número).');
        return;
      }
      if (!formData.email || !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(formData.email.trim())) {
        alert('El email es obligatorio y debe tener formato válido.');
        return;
      }
      if (usernameAvailability.status === 'taken') {
        alert('El nombre de usuario ya está ocupado. Elige otro o usa la sugerencia.');
        return;
      }
    }
    setLoading(true);
    try {
      const formattedPhone = `${countryCode} ${phoneNumber.replace(/\s+/g, '')}`;
      const payload: TenantDTO = {
        ...formData,
        // V51 — username preserva case (solo trim).
        username: formData.username ? formData.username.trim() : undefined,
        email: formData.email ? formData.email.trim().toLowerCase() : undefined,
        phone: formattedPhone,
      };
      if (!initialData) {
        await onSubmit(payload, contractPdf);
      } else {
        await onSubmit(payload, undefined);
      }
      onClose();
    } catch (error: unknown) {
      console.error(error);
      const err = error as { response?: { data?: { message?: string } }; message?: string };
      alert(err.response?.data?.message || err.message || 'Error guardando el inquilino.');
    } finally {
      setLoading(false);
    }
  };

  const availableProperties = initialData ? properties : properties.filter((p) => p.status === 'AVAILABLE');

  const inputClass =
    'w-full rounded-xl border border-slate-300 px-4 py-2.5 text-sm focus:border-teal-500 focus:ring-2 focus:ring-teal-500/20 outline-none transition-all placeholder:text-slate-400';

  return (
    <div className="fixed inset-0 z-[110] flex items-center justify-center p-4">
      <div className="absolute inset-0 bg-slate-900/60 backdrop-blur-sm" onClick={onClose} />

      <div className="relative bg-white rounded-2xl w-full max-w-2xl shadow-2xl overflow-hidden animate-in fade-in zoom-in-95 duration-200 border border-slate-200 max-h-[90vh] flex flex-col">
        <div className="px-6 py-4 border-b border-slate-100 flex justify-between items-center bg-slate-50 shrink-0">
          <div className="flex items-center gap-3">
            <div className="w-10 h-10 bg-teal-100 rounded-xl flex items-center justify-center text-teal-600">
              <Users className="w-5 h-5" />
            </div>
            <div>
              <h3 className="text-xl font-bold text-slate-800">
                {initialData ? 'Editar arrendatario' : 'Nuevo expediente (arrendatario + contrato)'}
              </h3>
              {!initialData ? (
                <p className="text-xs text-slate-500 mt-0.5">
                  Un solo paso: se crea el usuario, el perfil y la tenencia ACTIVA sobre el inmueble.
                </p>
              ) : (
                <p className="text-xs text-amber-700 mt-0.5">
                  El inmueble del expediente no se puede cambiar desde esta edición; un traslado será un flujo aparte.
                </p>
              )}
            </div>
          </div>
          <button onClick={onClose} className="p-2 text-slate-400 hover:bg-slate-200 rounded-full transition-colors">
            <X className="w-5 h-5" />
          </button>
        </div>

        <form onSubmit={handleSubmit} className="p-6 overflow-y-auto">
          <div className="grid grid-cols-1 md:grid-cols-2 gap-6">
            <div className="space-y-4">
              <h4 className="text-sm font-bold text-slate-400 uppercase tracking-wider mb-2">Datos del arrendatario</h4>

              <div>
                <label className="block text-sm font-semibold text-slate-700 mb-1.5">Nombre completo o razón social</label>
                <input
                  required
                  type="text"
                  value={formData.name || ''}
                  onChange={(e) => setFormData({ ...formData, name: e.target.value })}
                  className={inputClass}
                  placeholder="Ej. Roberto Sánchez"
                />
              </div>

              <div>
                <label className="block text-sm font-semibold text-slate-700 mb-1.5 flex items-center gap-2">
                  <AtSign className="w-4 h-4 text-brand-500" /> Usuario para iniciar sesión
                </label>
                <input
                  required={!initialData}
                  type="text"
                  autoComplete="off"
                  value={formData.username || ''}
                  onChange={(e) => setFormData({ ...formData, username: e.target.value.replace(/\s+/g, '') })}
                  className={inputClass}
                  placeholder="ej. Roberto-Sanchez"
                  disabled={!!initialData}
                />
                <p className="text-[11px] text-slate-500 mt-1">Mínimo 3 caracteres. Sólo letras, números, punto, guión y guión bajo. <strong>Distingue mayúsculas y minúsculas.</strong> No se puede cambiar después.</p>
                {!initialData && (
                  <UsernameAvailabilityHint
                    state={usernameAvailability}
                    onAcceptSuggestion={(s) => setFormData({ ...formData, username: s })}
                  />
                )}
              </div>

              <div>
                <label className="block text-sm font-semibold text-slate-700 mb-1.5 flex items-center gap-2">
                  <Mail className="w-4 h-4 text-slate-400" /> Email *
                </label>
                <input
                  required={!initialData}
                  type="email"
                  value={formData.email || ''}
                  onChange={(e) => setFormData({ ...formData, email: e.target.value })}
                  className={inputClass}
                  placeholder="roberto@correo.com"
                  disabled={!!initialData}
                />
                <p className="text-[11px] text-slate-500 mt-1">Email obligatorio: canal oficial de notificaciones y recuperación de cuenta.</p>
              </div>

              <div>
                <label className="block text-sm font-semibold text-slate-700 mb-1.5 flex items-center gap-2">
                  <Phone className="w-4 h-4 text-emerald-500" /> WhatsApp / teléfono
                </label>
                <div className="flex gap-2">
                  <select
                    value={countryCode}
                    onChange={(e) => setCountryCode(e.target.value)}
                    className="w-[100px] rounded-xl border border-slate-300 px-3 py-2.5 text-sm bg-white"
                  >
                    <option value="+52">+52</option>
                    <option value="+1">+1</option>
                    <option value="+57">+57</option>
                    <option value="+34">+34</option>
                  </select>
                  <input
                    required
                    type="tel"
                    value={phoneNumber}
                    onChange={(e) => setPhoneNumber(e.target.value.replace(/[^0-9]/g, ''))}
                    className="flex-1 rounded-xl border border-slate-300 px-4 py-2.5 text-sm"
                    placeholder="5512345678"
                  />
                </div>
              </div>
            </div>

            <div className="space-y-4">
              <h4 className="text-sm font-bold text-slate-400 uppercase tracking-wider mb-2">Inmueble y renta</h4>

              <div>
                <label className="block text-sm font-semibold text-slate-700 mb-1.5 flex items-center gap-2">
                  <Building className="w-4 h-4 text-slate-400" /> Inmueble
                </label>
                {initialData ? (
                  <div
                    className={`${inputClass} bg-slate-100 text-slate-800 cursor-not-allowed border-slate-200`}
                    title="El inmueble no se modifica en edición"
                  >
                    {formData.propertyId
                      ? properties.find((p) => p.id === formData.propertyId)?.name || formData.propertyId
                      : '—'}
                  </div>
                ) : (
                  <>
                    <select
                      required
                      value={formData.propertyId || ''}
                      onChange={(e) => setFormData({ ...formData, propertyId: e.target.value })}
                      className={`${inputClass} bg-white`}
                    >
                      <option value="" disabled>
                        Seleccione...
                      </option>
                      {availableProperties.map((p) => (
                        <option key={p.id} value={p.id}>
                          {p.name} {p.status ? `(${p.status})` : ''}
                        </option>
                      ))}
                    </select>
                    {availableProperties.length === 0 && (
                      <p className="text-xs text-amber-600 mt-1">No hay inmuebles disponibles (AVAILABLE).</p>
                    )}
                  </>
                )}
              </div>

              <div>
                <label className="block text-sm font-semibold text-slate-700 mb-1.5 flex items-center gap-2">
                  <DollarSign className="w-4 h-4 text-slate-400" /> Renta mensual
                </label>
                <input
                  required
                  type="number"
                  min="0.01"
                  step="0.01"
                  value={formData.rentAmount || ''}
                  onChange={(e) => setFormData({ ...formData, rentAmount: parseFloat(e.target.value) })}
                  className={inputClass}
                />
              </div>

              <div>
                <label className="block text-sm font-semibold text-slate-700 mb-1.5 flex items-center gap-2">
                  <Calendar className="w-4 h-4 text-slate-400" /> Día de cobro (1–31)
                </label>
                <input
                  required
                  type="number"
                  min="1"
                  max="31"
                  value={formData.paymentDay || ''}
                  onChange={(e) => setFormData({ ...formData, paymentDay: parseInt(e.target.value, 10) })}
                  className={inputClass}
                />
              </div>
            </div>

            {!initialData && (
              <div className="col-span-1 md:col-span-2 space-y-4 border-t border-slate-200 pt-6">
                <h4 className="text-sm font-bold text-slate-800">Contrato (tenencia)</h4>
                <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
                  <div>
                    <label className="block text-sm font-semibold text-slate-700 mb-1.5">Inicio</label>
                    <input
                      required
                      type="date"
                      value={formData.leaseStartDate || ''}
                      onChange={(e) => setFormData({ ...formData, leaseStartDate: e.target.value })}
                      className={inputClass}
                    />
                  </div>
                  <div>
                    <label className="block text-sm font-semibold text-slate-700 mb-1.5">Fin</label>
                    <input
                      required
                      type="date"
                      value={formData.leaseEndDate || ''}
                      onChange={(e) => setFormData({ ...formData, leaseEndDate: e.target.value })}
                      className={inputClass}
                    />
                  </div>
                  <div>
                    <label className="block text-sm font-semibold text-slate-700 mb-1.5">Depósito</label>
                    <input
                      type="number"
                      min="0"
                      step="0.01"
                      value={formData.depositAmount ?? 0}
                      onChange={(e) => setFormData({ ...formData, depositAmount: parseFloat(e.target.value) || 0 })}
                      className={inputClass}
                    />
                  </div>
                </div>
                <div>
                  <label className="block text-sm font-semibold text-slate-700 mb-1.5 flex items-center gap-2">
                    <Paperclip className="w-4 h-4 text-slate-500" /> Contrato firmado (PDF, opcional)
                  </label>
                  <input
                    type="file"
                    accept="application/pdf,.pdf"
                    className={`${inputClass} file:mr-3 file:py-1.5 file:px-3 file:rounded-lg file:border-0 file:bg-slate-100`}
                    onChange={(e) => setContractPdf(e.target.files?.[0] ?? null)}
                  />
                </div>
              </div>
            )}

            <div className="col-span-1 md:col-span-2 mt-2 border-t border-slate-200 pt-6">
              <div className="flex items-center justify-between mb-4">
                <div>
                  <h4 className="text-sm font-bold text-slate-800">Recargos moratorios (opcional)</h4>
                  <p className="text-xs text-slate-500">Solo si aplica multa por pago tardío.</p>
                </div>
                <label className="relative inline-flex items-center cursor-pointer">
                  <input
                    type="checkbox"
                    className="sr-only peer"
                    checked={formData.hasLateFee}
                    onChange={(e) => setFormData({ ...formData, hasLateFee: e.target.checked })}
                  />
                  <div className="w-11 h-6 bg-slate-200 peer-focus:outline-none rounded-full peer peer-checked:after:translate-x-full peer-checked:after:border-white after:content-[''] after:absolute after:top-[2px] after:left-[2px] after:bg-white after:border-slate-300 after:border after:rounded-full after:h-5 after:w-5 after:transition-all peer-checked:bg-rose-500"></div>
                </label>
              </div>

              {formData.hasLateFee && (
                <div className="grid grid-cols-1 md:grid-cols-3 gap-4 bg-slate-50 p-4 rounded-xl border border-slate-200">
                  <div>
                    <label className="block text-xs font-bold text-slate-700 uppercase mb-1">Días de gracia</label>
                    <input
                      type="number"
                      min="0"
                      value={formData.gracePeriodDays}
                      onChange={(e) => setFormData({ ...formData, gracePeriodDays: Number(e.target.value) })}
                      className={inputClass}
                    />
                  </div>
                  <div>
                    <label className="block text-xs font-bold text-slate-700 uppercase mb-1">Tipo</label>
                    <select
                      value={formData.lateFeeType}
                      onChange={(e) =>
                        setFormData({ ...formData, lateFeeType: e.target.value as 'PERCENTAGE' | 'FIXED_AMOUNT' })
                      }
                      className={`${inputClass} bg-white`}
                    >
                      <option value="FIXED_AMOUNT">Monto fijo</option>
                      <option value="PERCENTAGE">Porcentaje</option>
                    </select>
                  </div>
                  <div>
                    <label className="block text-xs font-bold text-slate-700 uppercase mb-1">Valor</label>
                    <input
                      required={formData.hasLateFee}
                      type="number"
                      min="0"
                      step="0.01"
                      value={formData.lateFeeValue || ''}
                      onChange={(e) => setFormData({ ...formData, lateFeeValue: Number(e.target.value) })}
                      className={inputClass}
                    />
                  </div>
                </div>
              )}
            </div>
          </div>

          <div className="mt-8 flex justify-end gap-3 pt-4 border-t border-slate-100">
            <button
              type="button"
              onClick={onClose}
              className="px-5 py-2.5 text-sm font-semibold text-slate-600 hover:bg-slate-100 rounded-xl transition-colors"
            >
              Cancelar
            </button>
            <button
              type="submit"
              disabled={loading
                || (!initialData && availableProperties.length === 0)
                || (!initialData && usernameAvailability.status === 'taken')}
              className="px-6 py-2.5 text-sm font-bold text-white bg-teal-600 rounded-xl hover:bg-teal-700 transition-colors shadow-sm disabled:opacity-70"
            >
              {loading ? 'Procesando...' : initialData ? 'Guardar cambios' : 'Abrir expediente'}
            </button>
          </div>
        </form>
      </div>
    </div>
  );
};
