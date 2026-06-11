import React, { useState } from 'react';
import { Search, ShieldAlert, KeyRound, ShieldOff, RotateCcw, UserPlus, Activity, Users, AlertTriangle, Trash2, Lock, MessageCircle } from 'lucide-react';
import api from '../services/api';
import { useAuth } from '../context/AuthContext';

interface UserSearchDTO {
  id: string;
  // V52 — username es el identificador case-sensitive de login. Lo recibimos del
  // backend para poder desambiguar cuando varios usuarios comparten email (el
  // email dejó de ser único en V52) y para enviar recovery por username en
  // lugar de email.
  username: string | null;
  name: string;
  email: string;
  role: string;
  organizationId: string | null;
}

interface DeleteModalState {
  user: UserSearchDTO;
}

interface DeleteOutcome {
  userName: string;
  userEmail: string;
  role: string;
  archivedTenantProfiles: number;
}

type RecoveryType = 'PASSWORD_RESET' | 'MFA_RESET' | 'PIN_RESET' | 'FULL_RECOVERY';

interface RecoveryModal {
  user: UserSearchDTO;
  type: RecoveryType;
}

interface RecoveryOutcome {
  tempPassword?: string;
  mfaReset: boolean;
  passwordReset: boolean;
  pinReset?: boolean;
  sessionsRevoked: number;
  userName: string;
}

const recoveryTypeConfig: Record<RecoveryType, { label: string; icon: React.ReactNode; color: string; btnClass: string; desc: string }> = {
  PASSWORD_RESET: {
    label: 'Nueva Contraseña Temporal',
    icon: <KeyRound className="w-5 h-5" />,
    color: 'text-amber-600 bg-amber-50 border-amber-200',
    btnClass: 'text-amber-600 hover:bg-amber-50',
    desc: 'Genera una contraseña temporal y obliga al usuario a cambiarla en su siguiente ingreso. Se revocan todas las sesiones activas.',
  },
  MFA_RESET: {
    label: 'Resetear MFA',
    icon: <ShieldOff className="w-5 h-5" />,
    color: 'text-orange-600 bg-orange-50 border-orange-200',
    btnClass: 'text-orange-600 hover:bg-orange-50',
    desc: 'Desactiva el segundo factor de autenticación. El usuario deberá reconfigurar MFA en su siguiente ingreso. Se revocan todas las sesiones activas.',
  },
  PIN_RESET: {
    label: 'Resetear NIP WhatsApp',
    icon: <MessageCircle className="w-5 h-5" />,
    color: 'text-teal-600 bg-teal-50 border-teal-200',
    btnClass: 'text-teal-600 hover:bg-teal-50',
    desc: 'Borra el NIP que el inquilino usa en el chatbot de WhatsApp. Al próximo mensaje el bot le pedirá configurar un NIP nuevo. Solo aplica a cuentas TENANT.',
  },
  FULL_RECOVERY: {
    label: 'Recuperación Completa',
    icon: <RotateCcw className="w-5 h-5" />,
    color: 'text-red-600 bg-red-50 border-red-200',
    btnClass: 'text-red-600 hover:bg-red-50',
    desc: 'Resetea contraseña, MFA y NIP de WhatsApp en una sola operación. El usuario recibirá una contraseña temporal y deberá reconfigurar MFA. Se revocan todas las sesiones activas.',
  },
};

const roleBadge = (role: string) => {
  const colors: Record<string, string> = {
    SUPER_ADMIN: 'bg-red-100 text-red-700',
    OWNER: 'bg-indigo-100 text-indigo-700',
    PROPERTY_ADMIN: 'bg-violet-100 text-violet-700',
    ACCOUNTANT: 'bg-cyan-100 text-cyan-700',
    TENANT: 'bg-emerald-100 text-emerald-700',
  };
  return colors[role] || 'bg-slate-100 text-slate-700';
};

export const GlobalSearchManager = () => {
  const { user: currentUser } = useAuth();
  // Invariante de sistema (coincide con el guard del backend):
  //   * No se puede tocar (delete ni recovery) otra cuenta SUPER_ADMIN desde este panel.
  //   * No se puede operar sobre la propia cuenta desde aquí.
  // Evita auto-bloqueo de la plataforma y ataques de insider.
  //
  // V52 (2026-04-17): el campo `currentUser.email` en AuthContext contiene en
  // realidad el *username* (legacy naming, ver comentario en AuthContext.tsx).
  // Por eso la comparación correcta es username vs username — el email es
  // dato de contacto y puede repetirse entre cuentas, por lo que nunca sería
  // una clave segura para identificar "esta es mi propia cuenta".
  const isProtectedTarget = (target: UserSearchDTO): boolean => {
    if (target.role === 'SUPER_ADMIN') return true;
    const actorUsername = currentUser?.email;
    if (actorUsername && target.username && actorUsername === target.username) return true;
    return false;
  };

  const [query, setQuery] = useState('');
  const [results, setResults] = useState<UserSearchDTO[]>([]);
  const [loading, setLoading] = useState(false);
  const [includeInactive, setIncludeInactive] = useState(false);
  const [searchError, setSearchError] = useState('');
  
  const [newSuperAdminName, setNewSuperAdminName] = useState('');
  const [newSuperAdminEmail, setNewSuperAdminEmail] = useState('');
  const [newSuperAdminPassword, setNewSuperAdminPassword] = useState('');
  const [showSuperAdminModal, setShowSuperAdminModal] = useState(false);

  // Recovery state
  const [recoveryModal, setRecoveryModal] = useState<RecoveryModal | null>(null);
  const [recoveryReason, setRecoveryReason] = useState('');
  const [recoveryPassword, setRecoveryPassword] = useState('');
  const [recoveryMfaCode, setRecoveryMfaCode] = useState('');
  const [recoveryLoading, setRecoveryLoading] = useState(false);
  const [recoveryError, setRecoveryError] = useState('');
  const [recoveryOutcome, setRecoveryOutcome] = useState<RecoveryOutcome | null>(null);

  const [newCredentials, setNewCredentials] = useState<{email: string, pass: string, msg: string} | null>(null);

  // Delete user state (SUPERADMIN-only, requires reauth MFA + password)
  const [deleteModal, setDeleteModal] = useState<DeleteModalState | null>(null);
  const [deletePassword, setDeletePassword] = useState('');
  const [deleteMfaCode, setDeleteMfaCode] = useState('');
  const [deleteReason, setDeleteReason] = useState('');
  const [deleteLoading, setDeleteLoading] = useState(false);
  const [deleteError, setDeleteError] = useState('');
  const [deleteOutcome, setDeleteOutcome] = useState<DeleteOutcome | null>(null);

  const handleSearch = async (e?: React.FormEvent) => {
    if (e) e.preventDefault();
    setSearchError('');
    if (!query || query.trim().length < 2) {
      setSearchError('Escribe al menos 2 caracteres para buscar.');
      return;
    }
    
    setLoading(true);
    try {
      const res = await api.get(`/users/search?q=${encodeURIComponent(query.trim())}&includeInactive=${includeInactive}`);
      setResults(res.data);
    } catch (error) {
      console.error(error);
    } finally {
      setLoading(false);
    }
  };

  const openRecoveryModal = (user: UserSearchDTO, type: RecoveryType) => {
    setRecoveryModal({ user, type });
    setRecoveryReason('');
    setRecoveryPassword('');
    setRecoveryMfaCode('');
    setRecoveryError('');
  };

  const executeRecovery = async () => {
    if (!recoveryModal) return;
    if (!recoveryPassword) {
      setRecoveryError('Ingresa tu contraseña.');
      return;
    }
    if (!recoveryMfaCode || recoveryMfaCode.trim().length < 6) {
      setRecoveryError('Ingresa tu código MFA de 6 dígitos.');
      return;
    }
    if (recoveryReason.trim().length < 10) {
      setRecoveryError('El motivo debe tener al menos 10 caracteres.');
      return;
    }

    setRecoveryLoading(true);
    setRecoveryError('');
    try {
      const res = await api.post('/admin/recovery', {
        // V52 — el email ya no es único: varias cuentas pueden compartirlo. Si
        // mandamos targetEmail y hay ambigüedad, el backend rechaza el recovery
        // para no resetear la cuenta equivocada. Por eso preferimos targetUsername
        // (identificador case-sensitive y único) y sólo caemos a email si, por
        // datos legacy, el user seleccionado no tiene username. En ese caso el
        // backend ya valida la unicidad antes de actuar.
        targetUsername: recoveryModal.user.username ?? undefined,
        targetEmail: recoveryModal.user.username ? undefined : recoveryModal.user.email,
        type: recoveryModal.type,
        reason: recoveryReason.trim(),
        password: recoveryPassword,
        mfaCode: recoveryMfaCode.trim(),
      });
      setRecoveryOutcome({
        ...res.data,
        userName: recoveryModal.user.name,
      });
      setRecoveryModal(null);
      setRecoveryReason('');
      setRecoveryPassword('');
      setRecoveryMfaCode('');
    } catch (err: unknown) {
      const e = err as { response?: { data?: { message?: string } } };
      setRecoveryError(e.response?.data?.message || 'Error ejecutando recuperación.');
    } finally {
      setRecoveryLoading(false);
    }
  };

  const openDeleteModal = (user: UserSearchDTO) => {
    setDeleteModal({ user });
    setDeletePassword('');
    setDeleteMfaCode('');
    setDeleteReason('');
    setDeleteError('');
  };

  const executeDelete = async () => {
    if (!deleteModal) return;
    if (!deletePassword) {
      setDeleteError('Ingresa tu contraseña de SUPER_ADMIN.');
      return;
    }
    if (!deleteMfaCode || deleteMfaCode.trim().length < 6) {
      setDeleteError('Ingresa tu código MFA de 6 dígitos.');
      return;
    }
    if (deleteReason.trim().length < 10) {
      setDeleteError('El motivo debe tener al menos 10 caracteres (queda en auditoría).');
      return;
    }
    setDeleteLoading(true);
    setDeleteError('');
    try {
      const res = await api.delete(`/admin/users/${deleteModal.user.id}`, {
        data: {
          password: deletePassword,
          mfaCode: deleteMfaCode.trim(),
          reason: deleteReason.trim(),
        },
      });
      setDeleteOutcome({
        userName: deleteModal.user.name,
        userEmail: deleteModal.user.email,
        role: deleteModal.user.role,
        archivedTenantProfiles: res.data?.archivedTenantProfiles ?? 0,
      });
      setDeleteModal(null);
      setDeletePassword('');
      setDeleteMfaCode('');
      setDeleteReason('');
      // Refrescar la lista para quitar al usuario eliminado.
      await handleSearch();
    } catch (err: unknown) {
      const e = err as { response?: { data?: { message?: string } } };
      setDeleteError(e.response?.data?.message || 'No se pudo eliminar el usuario.');
    } finally {
      setDeleteLoading(false);
    }
  };

  const handleCreateSuperAdmin = async (e: React.FormEvent) => {
    e.preventDefault();
    try {
      const res = await api.post('/auth/super-admin', { 
        name: newSuperAdminName, 
        email: newSuperAdminEmail,
        password: newSuperAdminPassword
      });
      setShowSuperAdminModal(false);
      setNewCredentials({
        email: newSuperAdminEmail,
        pass: res.data.tempPassword,
        msg: `Nuevo Super Administrador ROOT creado con éxito.`
      });
      setNewSuperAdminName('');
      setNewSuperAdminEmail('');
      setNewSuperAdminPassword('');
    } catch (error) {
      alert("Error creando super administrador. Quizá el correo ya exista.");
    }
  };

  return (
    <div className="w-full animate-in fade-in slide-in-from-bottom-4 duration-500">
      <div className="flex justify-between items-center mb-6">
        <div>
          <h2 className="text-2xl font-bold text-slate-800 flex items-center gap-2">
            <Search className="w-7 h-7 text-indigo-500" />
            Gestión Global de Cuentas
          </h2>
          <p className="text-slate-500 text-sm mt-1">Busca cuentas activas del sistema. Administra accesos, contraseñas y MFA para recovery.</p>
        </div>
        <button
          onClick={() => setShowSuperAdminModal(true)}
          className="bg-slate-900 hover:bg-black text-white px-5 py-2.5 rounded-lg text-sm font-semibold transition-all flex items-center gap-2 shadow-sm"
        >
          <UserPlus className="w-4 h-4" />
          Crear Colega Root
        </button>
      </div>

      {/* Delete outcome banner */}
      {deleteOutcome && (
        <div className="mb-6 p-4 bg-rose-50 border border-rose-200 rounded-xl text-rose-800 animate-in fade-in duration-300">
          <div className="flex items-center gap-2 mb-2">
            <Trash2 className="w-5 h-5" />
            <strong>Usuario eliminado: {deleteOutcome.userName}</strong>
          </div>
          <div className="space-y-1 text-sm">
            <div>📧 {deleteOutcome.userEmail} · {deleteOutcome.role}</div>
            {deleteOutcome.role === 'TENANT' && (
              <div>📁 Expedientes archivados con snapshot financiero: <strong>{deleteOutcome.archivedTenantProfiles}</strong></div>
            )}
            <div className="text-xs text-rose-600 mt-1">🚫 Todas sus sesiones fueron revocadas. Operación auditada.</div>
          </div>
          <button onClick={() => setDeleteOutcome(null)} className="mt-2 text-sm underline text-rose-700">Cerrar</button>
        </div>
      )}

      {/* Recovery outcome banner */}
      {recoveryOutcome && (
        <div className="mb-6 p-4 bg-emerald-50 border border-emerald-200 rounded-xl text-emerald-800 animate-in fade-in duration-300">
          <div className="flex items-center gap-2 mb-2">
            <AlertTriangle className="w-5 h-5" />
            <strong>Recuperación completada: {recoveryOutcome.userName}</strong>
          </div>
          <div className="space-y-1 text-sm">
            {recoveryOutcome.passwordReset && (
              <div>
                🔑 Contraseña temporal (solo se muestra una vez):
                <span className="ml-2 font-mono font-bold text-lg bg-emerald-100 px-2 py-0.5 rounded border border-emerald-300">
                  {recoveryOutcome.tempPassword}
                </span>
              </div>
            )}
            {recoveryOutcome.mfaReset && <div>🔓 MFA reseteado — requerirá nueva configuración en siguiente login.</div>}
            <div>🚫 {recoveryOutcome.sessionsRevoked} sesión(es) revocada(s).</div>
            <div className="text-xs text-emerald-600 mt-1">⚠️ Cargo administrativo de $500 MXN registrado en auditoría.</div>
          </div>
          <button onClick={() => setRecoveryOutcome(null)} className="mt-2 text-sm underline text-emerald-700">Cerrar</button>
        </div>
      )}

      {/* Buscador de Cuentas */}
      <div className="bg-white rounded-xl shadow-sm border border-slate-200 overflow-hidden text-sm mb-6">
        <form onSubmit={handleSearch} className="flex items-center gap-2 p-2 border-b border-slate-100 bg-slate-50">
          <input 
            type="text" 
            placeholder="Buscar por nombre o correo (mín. 2 caracteres)..." 
            className="flex-1 bg-transparent border-none outline-none px-4 py-2 font-medium text-slate-700 placeholder:text-slate-400"
            value={query}
            onChange={(e) => { setQuery(e.target.value); setSearchError(''); }}
            minLength={2}
          />
          <label className="flex items-center gap-1.5 text-xs text-slate-500 whitespace-nowrap px-2 cursor-pointer select-none">
            <input type="checkbox" checked={includeInactive} onChange={(e) => setIncludeInactive(e.target.checked)}
              className="rounded border-slate-300" />
            Incluir inactivos
          </label>
          <button type="submit" disabled={loading} className="bg-brand-600 hover:bg-brand-700 text-white font-bold px-6 py-2 rounded-lg transition-colors flex items-center gap-2">
             <Search className="w-4 h-4"/> {loading ? 'Buscando...' : 'Buscar'}
          </button>
        </form>
        {searchError && (
          <div className="px-4 py-2 bg-amber-50 text-amber-700 text-xs font-medium border-b border-amber-100">
            ⚠️ {searchError}
          </div>
        )}

        <div className="overflow-x-auto">
          {results.length === 0 ? (
            <div className="p-16 text-center text-slate-500">
              <Users className="w-12 h-12 mx-auto text-slate-200 mb-2"/>
              Utiliza el buscador para localizar cuentas.
            </div>
          ) : (
            <table className="w-full text-left border-collapse">
              <thead>
                <tr className="bg-white border-b border-slate-100 text-xs uppercase tracking-wider text-slate-500">
                  <th className="p-4 font-bold">Usuario</th>
                  <th className="p-4 font-bold">Rol</th>
                  <th className="p-4 font-bold text-center">Contexto</th>
                  <th className="p-4 font-bold text-right">Acciones de Recuperación</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-slate-50">
                {results.map((user) => (
                  <tr key={user.id} className="hover:bg-slate-50 transition-colors group">
                    <td className="p-4">
                      <div className="font-bold text-slate-800">{user.name}</div>
                      {/* V52 — mostramos username como identificador primario
                          (case-sensitive, único global) y email como dato de
                          contacto secundario. El email puede repetirse entre
                          cuentas; el username no. El prefijo "@" decorativo
                          fue removido porque se confundía con dato almacenado:
                          el username en DB NO incluye arroba. */}
                      {user.username && (
                        <div className="text-xs font-mono text-slate-700">
                          {user.username}
                        </div>
                      )}
                      <div className="text-xs text-slate-500">{user.email}</div>
                    </td>
                    <td className="p-4">
                      <span className={`inline-flex items-center px-2.5 py-1 rounded-full text-xs font-bold ${roleBadge(user.role)}`}>
                        {user.role}
                      </span>
                    </td>
                    <td className="p-4 text-center text-xs text-slate-400 font-mono">
                      {user.organizationId || 'ROOT'}
                    </td>
                    <td className="p-4">
                      <div className="flex items-center justify-end gap-1 opacity-70 group-hover:opacity-100 transition-opacity">
                        {isProtectedTarget(user) ? (
                          <span
                            className="flex items-center gap-1 text-xs text-slate-500 bg-slate-100 border border-slate-200 px-2 py-1 rounded-md"
                            title={
                              user.role === 'SUPER_ADMIN'
                                ? 'Las cuentas SUPER_ADMIN no se pueden modificar desde este panel. Requiere procedimiento fuera de banda.'
                                : 'No puedes operar sobre tu propia cuenta desde aquí.'
                            }
                          >
                            <Lock className="w-3.5 h-3.5" />
                            Protegido
                          </span>
                        ) : (
                          <>
                            <button
                              onClick={() => openRecoveryModal(user, 'PASSWORD_RESET')}
                              title="Nueva Contraseña Temporal"
                              className={`p-1.5 rounded-md transition-colors ${recoveryTypeConfig.PASSWORD_RESET.btnClass}`}
                            >
                              <KeyRound className="w-4 h-4" />
                            </button>
                            <button
                              onClick={() => openRecoveryModal(user, 'MFA_RESET')}
                              title="Resetear MFA"
                              className={`p-1.5 rounded-md transition-colors ${recoveryTypeConfig.MFA_RESET.btnClass}`}
                            >
                              <ShieldOff className="w-4 h-4" />
                            </button>
                            {user.role === 'TENANT' && (
                              <button
                                onClick={() => openRecoveryModal(user, 'PIN_RESET')}
                                title="Resetear NIP de WhatsApp"
                                className={`p-1.5 rounded-md transition-colors ${recoveryTypeConfig.PIN_RESET.btnClass}`}
                              >
                                <MessageCircle className="w-4 h-4" />
                              </button>
                            )}
                            <button
                              onClick={() => openRecoveryModal(user, 'FULL_RECOVERY')}
                              title="Recuperación Completa (Contraseña + MFA + NIP)"
                              className={`p-1.5 rounded-md transition-colors ${recoveryTypeConfig.FULL_RECOVERY.btnClass}`}
                            >
                              <RotateCcw className="w-4 h-4" />
                            </button>
                            <button
                              onClick={() => openDeleteModal(user)}
                              title="Eliminar usuario (MFA + contraseña)"
                              className="p-1.5 rounded-md transition-colors text-rose-600 hover:bg-rose-50"
                            >
                              <Trash2 className="w-4 h-4" />
                            </button>
                          </>
                        )}
                      </div>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </div>
      </div>

      {/* Delete Confirmation Modal */}
      {deleteModal && (
        <div className="fixed inset-0 z-[120] flex items-center justify-center p-4">
          <div className="absolute inset-0 bg-slate-900/60 backdrop-blur-sm" onClick={() => setDeleteModal(null)} />
          <div className="relative bg-white rounded-xl w-full max-w-md shadow-2xl border border-slate-200 animate-in zoom-in-95 duration-200">
            <div className="p-5 rounded-t-xl border-b text-rose-700 bg-rose-50 border-rose-200">
              <div className="flex items-center gap-3">
                <Trash2 className="w-5 h-5" />
                <h3 className="font-bold text-lg">Eliminar usuario</h3>
              </div>
            </div>

            <div className="p-5 space-y-4">
              <div className="bg-slate-50 rounded-lg p-3 border border-slate-200">
                <p className="text-sm text-slate-600">
                  <strong>Usuario:</strong> {deleteModal.user.name}
                </p>
                <p className="text-sm text-slate-500">{deleteModal.user.email}</p>
                <p className="text-xs mt-1">
                  <span className={`inline-flex items-center px-2 py-0.5 rounded-full text-xs font-bold ${roleBadge(deleteModal.user.role)}`}>
                    {deleteModal.user.role}
                  </span>
                </p>
              </div>

              <div className="bg-amber-50 border border-amber-200 rounded-lg p-3 text-xs text-amber-800 space-y-1">
                <div><strong>⚠️ Esta acción desactiva la cuenta y revoca sus sesiones.</strong></div>
                {deleteModal.user.role === 'TENANT' && (
                  <div>Si es inquilino, se archivarán sus expedientes con snapshot financiero inmutable (adeudo, meses pagados, evidencias). No se requiere autorización del dueño.</div>
                )}
                <div>Como SUPER_ADMIN debes confirmar tu identidad con contraseña y MFA.</div>
              </div>

              <div>
                <label className="block text-sm font-bold text-slate-700 mb-1">
                  Tu contraseña <span className="text-red-500">*</span>
                </label>
                <input
                  type="password"
                  value={deletePassword}
                  onChange={e => setDeletePassword(e.target.value)}
                  className="w-full px-3 py-2 border border-slate-200 rounded-lg text-sm focus:ring-2 focus:ring-brand-500 focus:border-brand-500 outline-none"
                  autoComplete="current-password"
                />
              </div>

              <div>
                <label className="block text-sm font-bold text-slate-700 mb-1">
                  Código MFA (6 dígitos) <span className="text-red-500">*</span>
                </label>
                <input
                  type="text"
                  inputMode="numeric"
                  maxLength={6}
                  value={deleteMfaCode}
                  onChange={e => setDeleteMfaCode(e.target.value.replace(/\D/g, ''))}
                  className="w-full px-3 py-2 border border-slate-200 rounded-lg text-sm tracking-widest font-mono focus:ring-2 focus:ring-brand-500 focus:border-brand-500 outline-none"
                  placeholder="000000"
                  autoComplete="one-time-code"
                />
              </div>

              <div>
                <label className="block text-sm font-bold text-slate-700 mb-1">
                  Motivo <span className="text-red-500">*</span>
                </label>
                <textarea
                  value={deleteReason}
                  onChange={e => setDeleteReason(e.target.value)}
                  placeholder="Describe el motivo de la eliminación (mínimo 10 caracteres)..."
                  className="w-full px-3 py-2 border border-slate-200 rounded-lg text-sm resize-none h-20 focus:ring-2 focus:ring-brand-500 focus:border-brand-500 outline-none"
                />
              </div>

              {deleteError && (
                <div className="text-sm text-red-600 bg-red-50 border border-red-200 rounded-lg p-2">
                  {deleteError}
                </div>
              )}
            </div>

            <div className="p-5 border-t border-slate-200 flex gap-3 justify-end">
              <button
                onClick={() => setDeleteModal(null)}
                className="px-4 py-2 text-sm font-medium text-slate-600 hover:bg-slate-50 rounded-lg transition-colors"
              >
                Cancelar
              </button>
              <button
                onClick={executeDelete}
                disabled={
                  deleteLoading ||
                  !deletePassword ||
                  deleteMfaCode.length < 6 ||
                  deleteReason.trim().length < 10
                }
                className="px-4 py-2 text-sm font-bold text-white bg-rose-600 hover:bg-rose-700 disabled:bg-slate-300 disabled:cursor-not-allowed rounded-lg transition-colors flex items-center gap-1.5"
              >
                <Trash2 className="w-4 h-4" />
                {deleteLoading ? 'Eliminando...' : 'Eliminar usuario'}
              </button>
            </div>
          </div>
        </div>
      )}

      {/* Recovery Confirmation Modal */}
      {recoveryModal && (
        <div className="fixed inset-0 z-[120] flex items-center justify-center p-4">
          <div className="absolute inset-0 bg-slate-900/60 backdrop-blur-sm" onClick={() => setRecoveryModal(null)} />
          <div className="relative bg-white rounded-xl w-full max-w-md shadow-2xl border border-slate-200 animate-in zoom-in-95 duration-200">
            <div className={`p-5 rounded-t-xl border-b ${recoveryTypeConfig[recoveryModal.type].color}`}>
              <div className="flex items-center gap-3">
                {recoveryTypeConfig[recoveryModal.type].icon}
                <h3 className="font-bold text-lg">{recoveryTypeConfig[recoveryModal.type].label}</h3>
              </div>
            </div>

            <div className="p-5 space-y-4">
              <div className="bg-slate-50 rounded-lg p-3 border border-slate-200">
                <p className="text-sm text-slate-600">
                  <strong>Usuario:</strong> {recoveryModal.user.name}
                </p>
                <p className="text-sm text-slate-500">{recoveryModal.user.email}</p>
                <p className="text-xs mt-1">
                  <span className={`inline-flex items-center px-2 py-0.5 rounded-full text-xs font-bold ${roleBadge(recoveryModal.user.role)}`}>
                    {recoveryModal.user.role}
                  </span>
                </p>
              </div>
              
              <p className="text-sm text-slate-600">
                {recoveryTypeConfig[recoveryModal.type].desc}
              </p>

              <div className="bg-amber-50 border border-amber-200 rounded-lg p-3 text-sm text-amber-800">
                <strong>⚠️ Cargo administrativo:</strong> Esta acción generará un cargo de <strong>$500 MXN</strong> al dueño asociado.
              </div>

              <div>
                <label className="block text-sm font-bold text-slate-700 mb-1">
                  Tu contraseña <span className="text-red-500">*</span>
                </label>
                <input
                  type="password"
                  value={recoveryPassword}
                  onChange={e => setRecoveryPassword(e.target.value)}
                  className="w-full px-3 py-2 border border-slate-200 rounded-lg text-sm focus:ring-2 focus:ring-brand-500 focus:border-brand-500 outline-none"
                  autoComplete="current-password"
                />
              </div>

              <div>
                <label className="block text-sm font-bold text-slate-700 mb-1">
                  Código MFA (6 dígitos) <span className="text-red-500">*</span>
                </label>
                <input
                  type="text"
                  inputMode="numeric"
                  maxLength={6}
                  value={recoveryMfaCode}
                  onChange={e => setRecoveryMfaCode(e.target.value.replace(/\D/g, ''))}
                  className="w-full px-3 py-2 border border-slate-200 rounded-lg text-sm tracking-widest font-mono focus:ring-2 focus:ring-brand-500 focus:border-brand-500 outline-none"
                  placeholder="000000"
                  autoComplete="one-time-code"
                />
              </div>

              <div>
                <label className="block text-sm font-bold text-slate-700 mb-1">
                  Motivo de recuperación <span className="text-red-500">*</span>
                </label>
                <textarea
                  value={recoveryReason}
                  onChange={e => setRecoveryReason(e.target.value)}
                  placeholder="Describe el motivo de esta recuperación (mínimo 10 caracteres)..."
                  className="w-full px-3 py-2 border border-slate-200 rounded-lg text-sm resize-none h-20 focus:ring-2 focus:ring-brand-500 focus:border-brand-500 outline-none"
                />
              </div>

              {recoveryError && (
                <div className="text-sm text-red-600 bg-red-50 border border-red-200 rounded-lg p-2">
                  {recoveryError}
                </div>
              )}
            </div>

            <div className="p-5 border-t border-slate-200 flex gap-3 justify-end">
              <button
                onClick={() => setRecoveryModal(null)}
                className="px-4 py-2 text-sm font-medium text-slate-600 hover:bg-slate-50 rounded-lg transition-colors"
              >
                Cancelar
              </button>
              <button
                onClick={executeRecovery}
                disabled={
                  recoveryLoading ||
                  !recoveryPassword ||
                  recoveryMfaCode.length < 6 ||
                  recoveryReason.trim().length < 10
                }
                className="px-4 py-2 text-sm font-bold text-white bg-red-600 hover:bg-red-700 disabled:bg-slate-300 disabled:cursor-not-allowed rounded-lg transition-colors"
              >
                {recoveryLoading ? 'Ejecutando...' : 'Confirmar Recuperación'}
              </button>
            </div>
          </div>
        </div>
      )}

       {/* Modal Super Admin */}
      {showSuperAdminModal && (
        <div className="fixed inset-0 z-50 flex items-center justify-center p-4">
          <div className="absolute inset-0 bg-slate-900/60 backdrop-blur-sm" onClick={() => setShowSuperAdminModal(false)} />
          <div className="relative bg-white rounded-2xl w-full max-w-md shadow-2xl overflow-hidden text-left animate-in fade-in zoom-in-95 duration-200">
            <div className="p-6 border-b border-slate-100 bg-slate-50">
              <h2 className="text-xl font-bold text-slate-900 flex items-center gap-2">
                <ShieldAlert className="w-6 h-6 text-red-600" />
                Registrar Colega Root
              </h2>
              <p className="text-xs text-slate-500 mt-1">
                Otorgará acceso total a la plataforma. Úsalo con precaución extrema.
              </p>
            </div>
            <form onSubmit={handleCreateSuperAdmin} className="p-6 space-y-4">
              <div>
                <label className="block text-sm font-semibold text-slate-700 mb-1">Nombre Completo</label>
                <input type="text" value={newSuperAdminName} onChange={e => setNewSuperAdminName(e.target.value)} required className="w-full px-4 py-2 border border-slate-300 rounded-lg focus:ring-2 outline-none transition-all" />
              </div>
              <div>
                <label className="block text-sm font-semibold text-slate-700 mb-1">Correo Electrónico de Confianza</label>
                <input type="email" value={newSuperAdminEmail} onChange={e => setNewSuperAdminEmail(e.target.value)} required className="w-full px-4 py-2 border border-slate-300 rounded-lg focus:ring-2 outline-none transition-all" />
              </div>
              <div>
                <label className="block text-sm font-semibold text-slate-700 mb-1">Contraseña (Mínimo 6 caracteres)</label>
                <input type="text" value={newSuperAdminPassword} onChange={e => setNewSuperAdminPassword(e.target.value)} required className="w-full px-4 py-2 border border-slate-300 rounded-lg focus:ring-2 outline-none transition-all" />
              </div>
              <div className="pt-4 flex justify-end gap-3">
                <button type="button" onClick={() => setShowSuperAdminModal(false)} className="px-4 py-2 text-sm font-bold text-slate-600 border border-slate-300 rounded-lg hover:bg-slate-50">Cancelar</button>
                <button type="submit" className="bg-red-600 hover:bg-red-700 text-white font-bold px-6 py-2 rounded-lg text-sm transition-colors shadow-sm">Autorizar Privilegios</button>
              </div>
            </form>
          </div>
        </div>
      )}

      {/* Result Modal (Super Admin creation) */}
      {newCredentials && (
        <div className="fixed inset-0 z-[60] flex items-center justify-center p-4">
          <div className="absolute inset-0 bg-slate-900/60 backdrop-blur-sm"
               onClick={() => setNewCredentials(null)} />
          <div className="relative bg-white rounded-2xl w-full max-w-md shadow-2xl p-6 text-center animate-in fade-in zoom-in-95 duration-200">
            <div className="w-16 h-16 bg-red-100 rounded-full flex items-center justify-center mx-auto mb-4">
              <Activity className="w-8 h-8 text-red-600" />
            </div>
            <h2 className="text-xl font-bold text-slate-900 mb-2">¡Administrado con Éxito!</h2>
            <p className="text-sm text-slate-500 mb-6">{newCredentials.msg}</p>
            <div className="bg-slate-50 rounded-lg p-4 border border-slate-200 mb-6 space-y-2 text-left">
              <div className="flex justify-between items-center text-sm">
                <span className="text-slate-500 font-medium">Correo:</span>
                <span className="font-bold text-slate-900">{newCredentials.email}</span>
              </div>
              <div className="flex justify-between items-center text-sm">
                <span className="text-slate-500 font-medium">Contraseña/Temporal:</span>
                <span className="font-bold text-brand-600 tracking-wider bg-brand-50 border border-brand-100 px-3 py-1 rounded">{newCredentials.pass}</span>
              </div>
            </div>
            <button 
              onClick={() => setNewCredentials(null)}
              className="w-full bg-slate-900 text-white font-medium px-4 py-2.5 rounded-lg hover:bg-slate-800"
            >
              Cerrar
            </button>
          </div>
        </div>
      )}

    </div>
  );
};
