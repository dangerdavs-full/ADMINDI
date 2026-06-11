import React, { useState, useEffect, useMemo } from 'react';
import { X, Briefcase, Mail, Phone, User as UserIcon, Shield, Lock, AtSign, Check, Ban, ChevronDown } from 'lucide-react';
import { StaffDTO } from '../../services/staffService';
import { permissionService, PermissionTemplate } from '../../services/permissionService';
import { resolveTemplateGuide, humanizePermission, toneClasses } from '../../utils/permissionGuide';
import { useUsernameAvailability } from '../../hooks/useUsernameAvailability';
import { UsernameAvailabilityHint } from '../UsernameAvailabilityHint';

interface Props {
  isOpen: boolean;
  onClose: () => void;
  onSubmit: (data: StaffDTO) => Promise<void>;
  initialData: StaffDTO | null;
}

export const StaffFormModal: React.FC<Props> = ({ isOpen, onClose, onSubmit, initialData }) => {
  const [name, setName] = useState('');
  const [username, setUsername] = useState('');
  const [contactEmail, setContactEmail] = useState('');
  const [contactCountryCode, setContactCountryCode] = useState('+52');
  const [contactPhone, setContactPhone] = useState('');
  const [permissionTemplateId, setPermissionTemplateId] = useState('');
  const [loading, setLoading] = useState(false);
  const [templates, setTemplates] = useState<PermissionTemplate[]>([]);
  const [templatesLoading, setTemplatesLoading] = useState(false);
  const [showTechnicalPerms, setShowTechnicalPerms] = useState(false);

  const selectedTemplate = useMemo(
    () => templates.find(t => t.id === permissionTemplateId),
    [templates, permissionTemplateId]
  );
  const selectedGuide = useMemo(
    () => (selectedTemplate ? resolveTemplateGuide(selectedTemplate) : null),
    [selectedTemplate]
  );

  const isEdit = !!initialData;

  // V67 — verificación en vivo de disponibilidad del username (solo en
  // modo creación; en edición el username es inmutable).
  const usernameAvailability = useUsernameAvailability(username, {
    disabled: isEdit,
  });

  useEffect(() => {
    if (isOpen) {
      setTemplatesLoading(true);
      permissionService.listTemplates()
        .then(setTemplates)
        .catch(() => setTemplates([]))
        .finally(() => setTemplatesLoading(false));
    }
  }, [isOpen]);

  useEffect(() => {
    if (initialData) {
      setName(initialData.name || '');
      setUsername(initialData.username || initialData.loginEmail || '');
      setContactEmail(initialData.contactEmail || '');
      // Parse phone: split country code from number
      if (initialData.contactCountryCode) {
        setContactCountryCode(initialData.contactCountryCode);
      }
      if (initialData.contactPhone) {
        // If contactPhone includes the country code prefix, strip it
        const phone = initialData.contactPhone.replace(/^\+\d{1,3}\s*/, '');
        setContactPhone(phone);
      } else {
        setContactPhone('');
      }
      setPermissionTemplateId(''); // Don't pre-select template on edit (shows current)
    } else {
      setName('');
      setUsername('');
      setContactEmail('');
      setContactCountryCode('+52');
      setContactPhone('');
      setPermissionTemplateId('');
    }
  }, [initialData, isOpen]);

  if (!isOpen) return null;

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!contactPhone.trim()) {
      alert('El teléfono de contacto es obligatorio.');
      return;
    }
    const contactEmailTrim = contactEmail.trim().toLowerCase();
    if (!isEdit) {
      if (!username.trim()) {
        alert('El usuario para iniciar sesión es obligatorio.');
        return;
      }
      // V51 — case-sensitive: admite A-Z además de a-z.
      if (!/^[A-Za-z0-9][A-Za-z0-9._-]{2,63}$/.test(username.trim())) {
        alert('El usuario sólo admite letras (mayúsculas o minúsculas), números, punto, guión o guión bajo (3–64 caracteres, iniciando con letra o número).');
        return;
      }
      if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(contactEmailTrim)) {
        alert('El email es obligatorio y debe tener formato válido.');
        return;
      }
    }
    if (!isEdit && usernameAvailability.status === 'taken') {
      alert('El nombre de usuario ya está ocupado. Elige otro o usa la sugerencia.');
      return;
    }
    setLoading(true);
    try {
      const formattedPhone = `${contactCountryCode} ${contactPhone.replace(/\s+/g, '')}`;
      await onSubmit({
        name,
        // V51 — preservamos case del username tipeado.
        username: username.trim() || undefined,
        // V51 — email obligatorio ahora: usa contactEmail como loginEmail también.
        loginEmail: contactEmailTrim || undefined,
        contactEmail: contactEmailTrim || undefined,
        contactPhone: formattedPhone,
        contactCountryCode,
        role: 'PROPERTY_ADMIN' as any,
        permissionTemplateId: permissionTemplateId || undefined,
      });
      onClose();
    } catch (error: any) {
      console.error(error);
      const msg = error.response?.data?.message || error.response?.data || 'Error al guardar.';
      alert(typeof msg === 'string' ? msg : JSON.stringify(msg));
    } finally {
      setLoading(false);
    }
  };

  const inputCls = "w-full rounded-xl border border-slate-300 px-4 py-2.5 text-sm focus:border-brand-500 focus:ring-2 focus:ring-brand-500/20 outline-none transition-all placeholder:text-slate-400";
  const labelCls = "block text-xs font-bold text-slate-600 mb-1.5 flex items-center gap-1.5 uppercase tracking-wide";

  return (
    <div className="fixed inset-0 z-[110] flex items-center justify-center p-4">
      <div className="absolute inset-0 bg-slate-900/60 backdrop-blur-sm" onClick={onClose} />

      <div className="relative bg-white rounded-2xl w-full max-w-lg shadow-2xl overflow-hidden animate-in fade-in zoom-in-95 duration-200 border border-slate-200 max-h-[90vh] overflow-y-auto">
        {/* Header */}
        <div className="px-6 py-4 border-b border-slate-100 flex justify-between items-center bg-slate-50 sticky top-0 z-10">
          <div className="flex items-center gap-3">
            <div className="w-10 h-10 bg-brand-100 rounded-xl flex items-center justify-center text-brand-600">
              <Briefcase className="w-5 h-5" />
            </div>
            <h3 className="text-xl font-bold text-slate-800">
              {isEdit ? 'Editar Administrador' : 'Registrar Nuevo Administrador'}
            </h3>
          </div>
          <button onClick={onClose} className="p-2 text-slate-400 hover:bg-slate-200 rounded-full transition-colors">
            <X className="w-5 h-5" />
          </button>
        </div>

        <form onSubmit={handleSubmit} className="p-6 space-y-5">
          {/* ─── Nombre ─── */}
          <div>
            <label className={labelCls}><UserIcon className="w-3.5 h-3.5 text-slate-400" /> Nombre Completo</label>
            <input required type="text" value={name} onChange={e => setName(e.target.value)} className={inputCls} placeholder="Ej. Juan Pérez" />
          </div>

          {/* ─── Sección de Identidad ─── */}
          <div className="bg-slate-50 rounded-xl p-4 border border-slate-200 space-y-4">
            <p className="text-xs font-bold text-slate-500 uppercase tracking-widest flex items-center gap-1.5">
              <Lock className="w-3.5 h-3.5" /> Credenciales de Acceso
            </p>
            <div>
              <label className={labelCls}><AtSign className="w-3.5 h-3.5 text-brand-500" /> Usuario para iniciar sesión</label>
              <input
                required={!isEdit}
                type="text"
                autoComplete="off"
                value={username}
                onChange={e => setUsername(e.target.value.replace(/\s+/g, ''))}
                className={`${inputCls} ${isEdit ? 'bg-slate-100 text-slate-500 cursor-not-allowed' : ''}`}
                placeholder="ej. Juan-Perez-2026"
                disabled={isEdit}
              />
              {isEdit
                ? <p className="text-[10px] text-slate-400 mt-1">El usuario no se puede cambiar por seguridad.</p>
                : <p className="text-[10px] text-slate-500 mt-1">Identificador único global. Mín. 3 caracteres. Sólo letras, números, punto, guión o guión bajo. <strong>Distingue mayúsculas y minúsculas.</strong></p>}
              {!isEdit && (
                <UsernameAvailabilityHint
                  state={usernameAvailability}
                  onAcceptSuggestion={(s) => setUsername(s)}
                />
              )}
            </div>
          </div>

          {/* ─── Sección de Contacto ─── */}
          <div className="bg-teal-50/50 rounded-xl p-4 border border-teal-100 space-y-4">
            <p className="text-xs font-bold text-teal-700 uppercase tracking-widest flex items-center gap-1.5">
              <AtSign className="w-3.5 h-3.5" /> Datos de Contacto Operativo
            </p>
            <div>
              <label className={labelCls}><Mail className="w-3.5 h-3.5 text-teal-500" /> Correo de Contacto *</label>
              <input required={!isEdit} type="email" value={contactEmail} onChange={e => setContactEmail(e.target.value)} className={inputCls} placeholder="contacto@empresa.com" />
              <p className="text-[10px] text-slate-400 mt-1">Email obligatorio: canal oficial de notificaciones y recuperación de cuenta.</p>
            </div>
            <div>
              <label className={labelCls}><Phone className="w-3.5 h-3.5 text-emerald-500" /> Teléfono de Contacto *</label>
              <div className="flex gap-2">
                <select value={contactCountryCode} onChange={e => setContactCountryCode(e.target.value)} className="w-[110px] rounded-xl border border-slate-300 px-3 py-2.5 text-sm focus:border-brand-500 focus:ring-2 focus:ring-brand-500/20 outline-none transition-all bg-white">
                  <option value="+52">🇲🇽 +52</option>
                  <option value="+1">🇺🇸 +1</option>
                  <option value="+34">🇪🇸 +34</option>
                  <option value="+57">🇨🇴 +57</option>
                  <option value="+54">🇦🇷 +54</option>
                  <option value="+56">🇨🇱 +56</option>
                </select>
                <input required type="tel" value={contactPhone} onChange={e => setContactPhone(e.target.value.replace(/[^0-9]/g, ''))} className={`flex-1 ${inputCls} font-medium tracking-wide`} placeholder="55 1234 5678" />
              </div>
            </div>
          </div>

          {/* ─── Nivel de Permisos ─── */}
          <div className="bg-indigo-50/50 rounded-xl p-4 border border-indigo-100 space-y-3">
            <p className="text-xs font-bold text-indigo-700 uppercase tracking-widest flex items-center gap-1.5">
              <Shield className="w-3.5 h-3.5" /> Nivel de Permisos
            </p>

            {isEdit && initialData?.currentTemplateName && (
              <div className="flex items-center gap-2 mb-2">
                <span className="text-xs text-slate-500">Actual:</span>
                <span className="text-xs font-bold text-indigo-700 bg-indigo-100 px-2 py-0.5 rounded-full">{initialData.currentTemplateName}</span>
              </div>
            )}

            {templatesLoading ? (
              <div className="text-xs text-slate-400 animate-pulse">Cargando plantillas...</div>
            ) : templates.length === 0 ? (
              <div className="text-xs text-amber-600 bg-amber-50 p-2 rounded border border-amber-200">
                No hay plantillas configuradas. El administrador tendrá acceso base sin permisos específicos.
              </div>
            ) : (
              <>
                <select
                  value={permissionTemplateId}
                  onChange={e => { setPermissionTemplateId(e.target.value); setShowTechnicalPerms(false); }}
                  className={inputCls}
                >
                  <option value="">{isEdit ? '— Sin cambio —' : '— Seleccionar nivel (opcional) —'}</option>
                  {templates.map(t => (
                    <option key={t.id} value={t.id}>{t.name}{t.isSystem ? ' (Sistema)' : ''}</option>
                  ))}
                </select>

                {!permissionTemplateId && (
                  <p className="text-[11px] text-slate-500 leading-relaxed">
                    Elige un nivel para ver qué podrá hacer el colaborador. Si no asignas ninguno, tendrá acceso base sin permisos específicos.
                  </p>
                )}

                {selectedTemplate && selectedGuide && (() => {
                  const perms = Array.isArray(selectedTemplate.permissions) ? selectedTemplate.permissions : [];
                  const tones = toneClasses(selectedGuide.tone);
                  return (
                    <div className={`rounded-lg border p-3 space-y-3 ${tones.card}`}>
                      {/* Encabezado del nivel */}
                      <div className="flex items-start gap-2">
                        <Shield className={`w-4 h-4 mt-0.5 flex-shrink-0 ${tones.accent}`} />
                        <div className="flex-1 min-w-0">
                          <p className={`text-xs font-bold uppercase tracking-wide ${tones.accent}`}>{selectedTemplate.name}</p>
                          <p className="text-xs text-slate-700 leading-snug mt-0.5">{selectedGuide.headline}</p>
                        </div>
                      </div>

                      {/* Qué puede hacer */}
                      {selectedGuide.canDo.length > 0 && (
                        <div>
                          <p className="text-[10px] font-bold text-emerald-700 uppercase tracking-wide mb-1.5 flex items-center gap-1">
                            <Check className="w-3 h-3" /> Puede
                          </p>
                          <ul className="space-y-1">
                            {selectedGuide.canDo.map((item, i) => (
                              <li key={i} className="text-[11px] text-slate-700 flex items-start gap-1.5 leading-snug">
                                <span className="text-emerald-500 mt-0.5">•</span>
                                <span>{item}</span>
                              </li>
                            ))}
                          </ul>
                        </div>
                      )}

                      {/* Qué NO puede hacer */}
                      {selectedGuide.cannotDo && selectedGuide.cannotDo.length > 0 && (
                        <div>
                          <p className="text-[10px] font-bold text-rose-700 uppercase tracking-wide mb-1.5 flex items-center gap-1">
                            <Ban className="w-3 h-3" /> No puede
                          </p>
                          <ul className="space-y-1">
                            {selectedGuide.cannotDo.map((item, i) => (
                              <li key={i} className="text-[11px] text-slate-600 flex items-start gap-1.5 leading-snug">
                                <span className="text-rose-400 mt-0.5">•</span>
                                <span>{item}</span>
                              </li>
                            ))}
                          </ul>
                        </div>
                      )}

                      {/* Detalle técnico (colapsado por defecto) */}
                      {perms.length > 0 && (
                        <div className="pt-2 border-t border-slate-200/60">
                          <button
                            type="button"
                            onClick={() => setShowTechnicalPerms(v => !v)}
                            className="text-[10px] font-semibold text-slate-500 hover:text-slate-700 flex items-center gap-1 transition-colors"
                          >
                            <ChevronDown className={`w-3 h-3 transition-transform ${showTechnicalPerms ? 'rotate-180' : ''}`} />
                            {showTechnicalPerms ? 'Ocultar' : 'Ver'} detalle técnico ({perms.length} permiso{perms.length !== 1 ? 's' : ''})
                          </button>
                          {showTechnicalPerms && (
                            <div className="flex flex-wrap gap-1 mt-2">
                              {perms.map(p => (
                                <span
                                  key={p}
                                  title={p}
                                  className="text-[10px] bg-white border border-slate-200 text-slate-700 px-1.5 py-0.5 rounded-full"
                                >
                                  {humanizePermission(p)}
                                </span>
                              ))}
                            </div>
                          )}
                        </div>
                      )}
                    </div>
                  );
                })()}
              </>
            )}
          </div>

          {/* ─── Info box on creation ─── */}
          {!isEdit && (
            <div className="bg-brand-50 rounded-lg p-3 border border-brand-100">
              <p className="text-xs text-brand-800 font-medium leading-relaxed">
                Al registrar, se enviará un link de activación por WhatsApp (y por email si se capturó correo de contacto).
                El usuario definirá su contraseña al abrir el link. El identificador de login será el <strong>usuario</strong> que capturaste aquí.
              </p>
            </div>
          )}

          {/* ─── Actions ─── */}
          <div className="flex justify-end gap-3 pt-4 border-t border-slate-100">
            <button type="button" onClick={onClose} className="px-5 py-2.5 text-sm font-semibold text-slate-600 hover:bg-slate-100 rounded-xl transition-colors">Cancelar</button>
            <button
              type="submit"
              disabled={loading || (!isEdit && usernameAvailability.status === 'taken')}
              className="px-6 py-2.5 text-sm font-bold text-white bg-brand-600 rounded-xl hover:bg-brand-700 transition-colors shadow-sm shadow-brand-500/30 disabled:opacity-70 flex items-center gap-2"
            >
              {loading ? 'Procesando...' : isEdit ? 'Actualizar' : 'Autorizar Creación'}
            </button>
          </div>
        </form>
      </div>
    </div>
  );
};
