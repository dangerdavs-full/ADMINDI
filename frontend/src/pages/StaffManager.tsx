import React, { useEffect, useState } from 'react';
import { Briefcase, Plus, UserCircle, Phone, Mail, Edit2, Trash2, KeyRound, Shield, Lock, Link2, Send } from 'lucide-react';
import { staffService, StaffDTO } from '../services/staffService';
import { StaffFormModal } from '../components/modals/StaffFormModal';

export const StaffManager: React.FC<{ embedded?: boolean }> = ({ embedded }) => {
  const [staffList, setStaffList] = useState<StaffDTO[]>([]);
  const [isModalOpen, setIsModalOpen] = useState(false);
  const [loading, setLoading] = useState(true);
  const [editingStaff, setEditingStaff] = useState<StaffDTO | null>(null);
  /**
   * Estado del modal de confirmación tras crear un staff.
   *  · `reused=true`  → se reutilizó una cuenta PROPERTY_ADMIN ya existente (sin link nuevo).
   *  · `activationSent=true` → se creó la cuenta y se envió el link de activación one-shot al user.
   *  · `pass` solo se llena en modo legacy (ya no ocurre en creaciones nuevas).
   */
  const [newCredentials, setNewCredentials] = useState<{
    /** V50 — identificador de login (username). Se conserva el nombre del campo
     *  como `identifier` para dejar claro que ya no es necesariamente un email. */
    identifier: string;
    pass?: string;
    name: string;
    reused: boolean;
    activationSent?: boolean;
    activationChannel?: string;
  } | null>(null);
  const [resendingId, setResendingId] = useState<string | null>(null);

  const fetchStaff = async () => {
    setLoading(true);
    try {
      const data = await staffService.getMyStaff();
      setStaffList(data);
    } catch (error) {
      console.error('Error fetching staff', error);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => { fetchStaff(); }, []);

  const handleCreateOrUpdate = async (data: StaffDTO) => {
    if (editingStaff && editingStaff.id) {
      await staffService.updateStaff(editingStaff.id, data);
    } else {
      const result = await staffService.createStaff(data);
      // V50 — el backend ahora devuelve `username` como identificador canónico.
      // Fallback a loginEmail por retro-compat con payloads legados.
      const identifier = result.username || result.loginEmail || '';
      if (result.reuseExisting) {
        setNewCredentials({
          identifier,
          name: result.name,
          reused: true
        });
      } else if (result.activationSent) {
        setNewCredentials({
          identifier,
          name: result.name,
          reused: false,
          activationSent: true,
          activationChannel: result.activationChannel
        });
      } else if (result.tempPassword) {
        setNewCredentials({
          identifier,
          pass: result.tempPassword,
          name: result.name,
          reused: false
        });
      }
    }
    await fetchStaff();
  };

  const handleResendActivation = async (id: string, name: string) => {
    if (!window.confirm(`Reenviar link de activación a "${name}"?\n\nEl link anterior dejará de funcionar.`)) return;
    setResendingId(id);
    try {
      const res = await staffService.resendActivation(id);
      alert(`Link de activación enviado por ${res.channel}. Expira: ${new Date(res.expiresAt).toLocaleString()}`);
    } catch (err: unknown) {
      const maybe = err as { response?: { data?: { message?: string } } };
      alert(maybe?.response?.data?.message ?? 'No se pudo reenviar el link.');
    } finally {
      setResendingId(null);
    }
  };

  const handleOpenEdit = (staff: StaffDTO) => { setEditingStaff(staff); setIsModalOpen(true); };
  const handleOpenCreate = () => { setEditingStaff(null); setIsModalOpen(true); };

  const handleDelete = async (id: string, name: string) => {
    if (window.confirm(`⚠️ DESVINCULAR ADMINISTRADOR: "${name}".\n\n¿Estás seguro de que deseas revocar su acceso a tu organización?`)) {
      try {
        await staffService.deleteStaff(id);
        await fetchStaff();
      } catch (error) {
        alert("Error desvinculando al empleado.");
      }
    }
  };

  return (
    <div className={`w-full ${embedded ? '' : 'animate-in fade-in slide-in-from-bottom-4 duration-500'}`}>
      <div className={`flex justify-between items-center ${embedded ? 'mb-4' : 'mb-6'}`}>
        <div>
          <h2 className={`font-bold text-slate-800 flex items-center gap-2 ${embedded ? 'text-lg' : 'text-xl'}`}>
            <Briefcase className={`${embedded ? 'w-5 h-5' : 'w-6 h-6'} text-brand-500`} /> Cuerpo Administrativo
          </h2>
          {!embedded && (
            <p className="text-sm text-slate-500 mt-1">Delegados con identidad y permisos separados por contexto.</p>
          )}
        </div>
        <button onClick={handleOpenCreate} className="bg-brand-600 hover:bg-brand-700 text-white px-5 py-2.5 rounded-lg text-sm font-semibold transition-all flex items-center gap-2 shadow-sm shadow-brand-500/20">
          <Plus className="w-4 h-4" /> Añadir Administrador
        </button>
      </div>

      <div className="grid grid-cols-1 md:grid-cols-2 xl:grid-cols-3 gap-6">
        {loading ? (
          <div className="p-8 text-center text-slate-500 col-span-full animate-pulse">Cargando administradores...</div>
        ) : staffList.length === 0 ? (
          <div className="p-10 text-center col-span-full bg-slate-50 rounded-2xl border border-dashed border-slate-300">
            <div className="w-16 h-16 bg-white rounded-full flex items-center justify-center mx-auto mb-4 border border-slate-200">
              <UserCircle className="w-8 h-8 text-slate-300" />
            </div>
            <h3 className="font-semibold text-slate-700 mb-1">Sin delegados</h3>
            <p className="text-slate-500 text-sm max-w-sm mx-auto">Agrega administradores para que operen y gestionen tus inmuebles.</p>
          </div>
        ) : (
          staffList.map((staff) => (
            <div key={staff.id} className="bg-white rounded-2xl border border-slate-200 shadow-sm p-6 group hover:shadow-md transition-shadow relative">
              {/* Actions */}
              <div className="absolute top-4 right-4 flex gap-1 opacity-0 group-hover:opacity-100 transition-opacity">
                <button
                  onClick={() => handleResendActivation(staff.id!, staff.name)}
                  disabled={resendingId === staff.id}
                  title="Reenviar link de activación"
                  className="p-1.5 text-slate-400 hover:text-emerald-600 hover:bg-emerald-50 rounded-md transition-colors disabled:opacity-50"
                >
                  <Send className="w-4 h-4" />
                </button>
                <button onClick={() => handleOpenEdit(staff)} className="p-1.5 text-slate-400 hover:text-brand-600 hover:bg-brand-50 rounded-md transition-colors"><Edit2 className="w-4 h-4" /></button>
                <button onClick={() => handleDelete(staff.id!, staff.name)} className="p-1.5 text-slate-400 hover:text-red-600 hover:bg-red-50 rounded-md transition-colors"><Trash2 className="w-4 h-4" /></button>
              </div>

              {/* Avatar + Name */}
              <div className="w-14 h-14 bg-brand-50 rounded-full flex items-center justify-center text-brand-600 font-bold text-xl mb-3 border border-brand-100 shadow-inner">
                {staff.name.charAt(0).toUpperCase()}
              </div>
              <h4 className="font-bold text-slate-900 text-lg">{staff.name}</h4>
              <p className="text-xs font-bold text-brand-600 uppercase tracking-widest mb-4">
                {staff.role === 'PROPERTY_ADMIN' ? 'Administrador' :
                 staff.role === 'ACCOUNTANT' ? 'Contador' :
                 staff.role === 'REAL_ESTATE_AGENT' ? 'Agente Inmobiliario' :
                 staff.role === 'MAINTENANCE_PROVIDER' ? 'Proveedor Mantenimiento' :
                 staff.role}
              </p>

              {/* Identity Details */}
              <div className="space-y-3 text-sm">
                {/* Username (login identifier) */}
                <div className="flex items-start gap-2.5">
                  <Lock className="w-4 h-4 text-rose-400 mt-0.5 shrink-0" />
                  <div className="min-w-0">
                    <p className="text-[10px] font-bold text-slate-400 uppercase tracking-wider">Usuario de inicio de sesión</p>
                    <p className="font-medium text-slate-700 truncate">{staff.username || staff.loginEmail || '—'}</p>
                  </div>
                </div>

                {/* Contact Email */}
                <div className="flex items-start gap-2.5">
                  <Mail className="w-4 h-4 text-teal-500 mt-0.5 shrink-0" />
                  <div className="min-w-0">
                    <p className="text-[10px] font-bold text-slate-400 uppercase tracking-wider">Correo de Contacto</p>
                    <p className="font-medium text-slate-600 truncate">{staff.contactEmail || '—'}</p>
                  </div>
                </div>

                {/* Contact Phone */}
                <div className="flex items-start gap-2.5">
                  <Phone className="w-4 h-4 text-emerald-500 mt-0.5 shrink-0" />
                  <div>
                    <p className="text-[10px] font-bold text-slate-400 uppercase tracking-wider">Teléfono</p>
                    <p className="font-medium text-slate-600">{staff.contactPhone || 'Sin número'}</p>
                  </div>
                </div>

                {/* Permission Level */}
                <div className="flex items-start gap-2.5">
                  <Shield className="w-4 h-4 text-indigo-500 mt-0.5 shrink-0" />
                  <div>
                    <p className="text-[10px] font-bold text-slate-400 uppercase tracking-wider">Nivel de Permisos</p>
                    {staff.currentTemplateName ? (
                      <span className="text-xs font-bold text-indigo-700 bg-indigo-100 px-2 py-0.5 rounded-full">{staff.currentTemplateName}</span>
                    ) : (
                      <span className="text-xs font-medium text-slate-400">Sin plantilla asignada</span>
                    )}
                  </div>
                </div>
              </div>
            </div>
          ))
        )}
      </div>

      <StaffFormModal
        isOpen={isModalOpen}
        onClose={() => setIsModalOpen(false)}
        onSubmit={handleCreateOrUpdate}
        initialData={editingStaff}
      />

      {/* ─── Credentials / Reuse Modal ─── */}
      {newCredentials && (
        <div className="fixed inset-0 z-[120] flex items-center justify-center p-4">
          <div className="absolute inset-0 bg-slate-900/60 backdrop-blur-sm" onClick={() => setNewCredentials(null)} />
          <div className="relative bg-white rounded-xl w-full max-w-sm shadow-2xl p-6 text-center animate-in zoom-in-95 duration-200 border-2 border-brand-500">
            {newCredentials.reused ? (
              <>
                <Link2 className="w-12 h-12 text-teal-500 mx-auto mb-4" />
                <h2 className="text-xl font-bold text-slate-900 mb-2">¡Cuenta Reutilizada!</h2>
                <p className="text-sm text-slate-600 mb-4">
                  <strong>{newCredentials.name}</strong> ya tenía una cuenta de administrador. 
                  Se vinculó a tu organización <strong>sin crear nueva contraseña</strong>.
                </p>
                <div className="bg-teal-50 rounded p-3 border border-teal-200 text-sm text-teal-700 font-medium mb-6">
                  El administrador puede ingresar con sus credenciales existentes y ver tu organización en su selector de contexto.
                </div>
              </>
            ) : newCredentials.activationSent ? (
              <>
                <Send className="w-12 h-12 text-emerald-500 mx-auto mb-4" />
                <h2 className="text-xl font-bold text-slate-900 mb-2">Link de activación enviado</h2>
                <p className="text-sm text-slate-600 mb-4">
                  Le enviamos a <strong>{newCredentials.name}</strong> un link de activación
                  por <strong>{newCredentials.activationChannel === 'BOTH' ? 'email y WhatsApp' : newCredentials.activationChannel?.toLowerCase()}</strong>.
                  Con ese link establecerá <strong>su propia contraseña</strong>; tú no la sabrás.
                </p>
                <div className="bg-emerald-50 rounded p-3 border border-emerald-200 text-sm text-emerald-800 text-left mb-6">
                  <div className="text-xs text-emerald-600 font-bold uppercase mb-1">Usuario del administrador</div>
                  <div className="font-medium">{newCredentials.identifier}</div>
                  <div className="mt-2 text-xs">El link es de un solo uso y expira en 24 horas. Si no le llega, usa el botón <strong>Reenviar link</strong> desde la tarjeta del administrador.</div>
                </div>
              </>
            ) : (
              <>
                <KeyRound className="w-12 h-12 text-brand-500 mx-auto mb-4" />
                <h2 className="text-xl font-bold text-slate-900 mb-2">¡Token de Empleado!</h2>
                <p className="text-sm text-slate-600 mb-4">Entrega estas credenciales temporales a <strong>{newCredentials.name}</strong>:</p>
                <div className="bg-slate-50 rounded p-4 border border-slate-200 space-y-2 mb-6 text-left">
                  <div className="text-xs text-slate-500 font-bold uppercase">Usuario de acceso:</div>
                  <div className="font-medium text-slate-800">{newCredentials.identifier}</div>
                  <div className="text-xs text-slate-500 font-bold uppercase mt-2">Contraseña Desechable:</div>
                  <div className="font-mono font-bold text-brand-600 tracking-wider text-xl bg-brand-50 p-2 rounded text-center border border-brand-100">{newCredentials.pass}</div>
                </div>
              </>
            )}
            <button onClick={() => setNewCredentials(null)} className="w-full bg-slate-900 text-white font-bold py-3 rounded-lg hover:bg-slate-800 transition-colors">
              Completado
            </button>
          </div>
        </div>
      )}
    </div>
  );
};
