import React, { useEffect, useState } from 'react';
import { Shield, UserCheck, Trash2, ChevronDown, AlertCircle } from 'lucide-react';
import { permissionService, PermissionTemplate, PermissionGrant } from '../services/permissionService';
import { staffService, StaffDTO } from '../services/staffService';
import { useAuth } from '../context/AuthContext';

export const PermissionManager: React.FC = () => {
  const { user } = useAuth();
  const [templates, setTemplates] = useState<PermissionTemplate[]>([]);
  const [grants, setGrants] = useState<PermissionGrant[]>([]);
  const [staffList, setStaffList] = useState<StaffDTO[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');

  // Form state
  const [selectedUserId, setSelectedUserId] = useState('');
  const [selectedTemplateId, setSelectedTemplateId] = useState('');
  const [assigning, setAssigning] = useState(false);

  const ownerId = user?.contextId || localStorage.getItem('contextId') || '';

  const fetchData = async () => {
    setLoading(true);
    setError('');
    try {
      const [tpls, grts, staff] = await Promise.all([
        permissionService.listTemplates(),
        ownerId ? permissionService.getGrants(ownerId) : Promise.resolve([]),
        staffService.getMyStaff().catch(() => []),
      ]);
      setTemplates(tpls);
      setGrants(grts);
      setStaffList(staff);
    } catch (err: any) {
      setError(err.response?.data?.message || 'Error cargando permisos');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    fetchData();
  }, []);

  const handleAssign = async () => {
    if (!selectedUserId || !selectedTemplateId) return;
    setAssigning(true);
    setError('');
    try {
      await permissionService.grantPermission(
        selectedUserId,
        ownerId,
        selectedTemplateId,
        user?.name || 'owner'
      );
      setSelectedUserId('');
      setSelectedTemplateId('');
      await fetchData();
    } catch (err: any) {
      setError(err.response?.data?.message || 'Error asignando permiso');
    } finally {
      setAssigning(false);
    }
  };

  const handleRevoke = async (grantId: string, userName: string) => {
    if (!window.confirm(`¿Revocar los permisos de "${userName}"?`)) return;
    try {
      await permissionService.revokeGrant(grantId);
      await fetchData();
    } catch (err: any) {
      setError(err.response?.data?.message || 'Error revocando permiso');
    }
  };

  const getTemplateName = (templateId: string) => {
    return templates.find(t => t.id === templateId)?.name || templateId;
  };

  const getStaffName = (userId: string) => {
    const staff = staffList.find(s => s.id === userId);
    return staff?.name || userId;
  };

  const getStaffEmail = (userId: string) => {
    const s = staffList.find(s => s.id === userId);
    return s?.username || s?.loginEmail || '';
  };

  // Staff sin grant asignado
  const unassignedStaff = staffList.filter(
    s => !grants.some(g => g.userId === s.id)
  );

  if (loading) {
    return (
      <div className="flex items-center justify-center p-12">
        <div className="animate-spin w-8 h-8 border-4 border-brand-500 border-t-transparent rounded-full" />
      </div>
    );
  }

  return (
    <div className="w-full animate-in fade-in slide-in-from-bottom-4 duration-500">
      {/* Header */}
      <div className="mb-8">
        <h2 className="text-xl font-bold text-slate-800 flex items-center gap-2">
          <Shield className="w-6 h-6 text-brand-500" />
          Gestión de Permisos
        </h2>
        <p className="text-sm text-slate-500 mt-1">
          Asigna plantillas de permisos a tu personal para controlar qué puede hacer cada uno.
        </p>
      </div>

      {error && (
        <div className="mb-6 p-4 bg-red-50 border border-red-200 rounded-xl flex items-center gap-3 text-red-700 text-sm">
          <AlertCircle className="w-5 h-5 shrink-0" />
          {error}
        </div>
      )}

      {/* Plantillas disponibles */}
      <div className="mb-8">
        <h3 className="text-sm font-bold text-slate-500 uppercase tracking-wider mb-3">Plantillas Disponibles</h3>
        <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
          {templates.map((tpl) => (
            <div
              key={tpl.id}
              className="bg-white rounded-xl border border-slate-200 p-5 shadow-sm hover:shadow-md transition-shadow"
            >
              <div className="flex items-center gap-2 mb-2">
                <Shield className={`w-5 h-5 ${tpl.isSystem ? 'text-amber-500' : 'text-brand-500'}`} />
                <h4 className="font-bold text-slate-900">{tpl.name}</h4>
                {tpl.isSystem && (
                  <span className="text-[10px] font-bold bg-amber-100 text-amber-700 px-1.5 py-0.5 rounded-full uppercase">Sistema</span>
                )}
              </div>
              <div className="flex flex-wrap gap-1 mt-3">
                {tpl.permissions?.slice(0, 6).map((perm) => (
                  <span
                    key={perm}
                    className="text-[11px] bg-slate-100 text-slate-600 px-2 py-0.5 rounded-full font-mono"
                  >
                    {perm}
                  </span>
                ))}
                {(tpl.permissions?.length || 0) > 6 && (
                  <span className="text-[11px] text-slate-400 px-1">+{tpl.permissions.length - 6} más</span>
                )}
              </div>
            </div>
          ))}
        </div>
      </div>

      {/* Formulario de asignación */}
      {unassignedStaff.length > 0 && (
        <div className="mb-8 bg-brand-50 rounded-xl border border-brand-100 p-5">
          <h3 className="text-sm font-bold text-brand-700 uppercase tracking-wider mb-4">Asignar Permiso</h3>
          <div className="flex flex-col md:flex-row gap-3">
            <div className="relative flex-1">
              <select
                value={selectedUserId}
                onChange={(e) => setSelectedUserId(e.target.value)}
                className="w-full appearance-none bg-white border border-slate-200 rounded-lg px-4 py-2.5 pr-10 text-sm font-medium text-slate-800 focus:ring-2 focus:ring-brand-500 focus:border-brand-500 outline-none"
              >
                <option value="">Seleccionar empleado...</option>
                {unassignedStaff.map((s) => (
                  <option key={s.id} value={s.id!}>{s.name} ({s.username || s.loginEmail})</option>
                ))}
              </select>
              <ChevronDown className="absolute right-3 top-3 w-4 h-4 text-slate-400 pointer-events-none" />
            </div>
            <div className="relative flex-1">
              <select
                value={selectedTemplateId}
                onChange={(e) => setSelectedTemplateId(e.target.value)}
                className="w-full appearance-none bg-white border border-slate-200 rounded-lg px-4 py-2.5 pr-10 text-sm font-medium text-slate-800 focus:ring-2 focus:ring-brand-500 focus:border-brand-500 outline-none"
              >
                <option value="">Seleccionar plantilla...</option>
                {templates.map((t) => (
                  <option key={t.id} value={t.id}>{t.name}</option>
                ))}
              </select>
              <ChevronDown className="absolute right-3 top-3 w-4 h-4 text-slate-400 pointer-events-none" />
            </div>
            <button
              onClick={handleAssign}
              disabled={!selectedUserId || !selectedTemplateId || assigning}
              className="bg-brand-600 hover:bg-brand-700 disabled:bg-slate-300 disabled:cursor-not-allowed text-white px-6 py-2.5 rounded-lg text-sm font-bold transition-all flex items-center gap-2 shadow-sm whitespace-nowrap"
            >
              <UserCheck className="w-4 h-4" />
              {assigning ? 'Asignando...' : 'Asignar'}
            </button>
          </div>
        </div>
      )}

      {/* Grants activos */}
      <div>
        <h3 className="text-sm font-bold text-slate-500 uppercase tracking-wider mb-3">Permisos Asignados</h3>
        {grants.length === 0 ? (
          <div className="p-10 text-center bg-slate-50 rounded-2xl border border-dashed border-slate-300">
            <div className="w-16 h-16 bg-white rounded-full flex items-center justify-center mx-auto mb-4 border border-slate-200">
              <Shield className="w-8 h-8 text-slate-300" />
            </div>
            <h4 className="font-semibold text-slate-700 mb-1">Sin permisos asignados</h4>
            <p className="text-slate-500 text-sm max-w-sm mx-auto">
              Asigna una plantilla de permisos a tu personal para controlar su acceso.
            </p>
          </div>
        ) : (
          <div className="space-y-3">
            {grants.map((grant) => (
              <div
                key={grant.id}
                className="bg-white rounded-xl border border-slate-200 p-4 flex items-center justify-between shadow-sm hover:shadow-md transition-shadow group"
              >
                <div className="flex items-center gap-4">
                  <div className="w-10 h-10 bg-brand-50 rounded-full flex items-center justify-center text-brand-600 font-bold text-lg border border-brand-100">
                    {getStaffName(grant.userId).charAt(0).toUpperCase()}
                  </div>
                  <div>
                    <p className="font-bold text-slate-900">{getStaffName(grant.userId)}</p>
                    <p className="text-xs text-slate-500">{getStaffEmail(grant.userId)}</p>
                  </div>
                </div>
                <div className="flex items-center gap-4">
                  <span className="text-sm font-medium text-brand-700 bg-brand-50 px-3 py-1 rounded-full border border-brand-100">
                    {getTemplateName(grant.templateId)}
                  </span>
                  <button
                    onClick={() => handleRevoke(grant.id, getStaffName(grant.userId))}
                    className="p-2 text-slate-400 hover:text-red-600 hover:bg-red-50 rounded-lg transition-colors opacity-0 group-hover:opacity-100"
                    title="Revocar permiso"
                  >
                    <Trash2 className="w-4 h-4" />
                  </button>
                </div>
              </div>
            ))}
          </div>
        )}
      </div>
    </div>
  );
};
