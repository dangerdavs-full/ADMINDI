import React, { useEffect, useState } from 'react';
import { FileText, Search, Plus, DollarSign, Calendar, XCircle, CheckCircle } from 'lucide-react';
import { leaseService, LeaseDTO } from '../services/leaseService';
import { propertyService, PropertyDTO } from '../services/propertyService';
import { tenantService, TenantDTO } from '../services/tenantService';
import { LeaseFormModal } from '../components/modals/LeaseFormModal';
import { StaffApprovalRequestModal } from '../components/modals/StaffApprovalRequestModal';
import { approvalRequestService, canExecuteDirectly } from '../services/approvalRequestService';
import { useAuth } from '../context/AuthContext';

export const LeaseManager: React.FC = () => {
  const { user } = useAuth();
  const isDirectExecutor = canExecuteDirectly(user?.role);

  const [leases, setLeases] = useState<LeaseDTO[]>([]);
  const [filteredLeases, setFilteredLeases] = useState<LeaseDTO[]>([]);
  const [search, setSearch] = useState('');
  const [isModalOpen, setIsModalOpen] = useState(false);
  const [properties, setProperties] = useState<PropertyDTO[]>([]);
  const [tenants, setTenants] = useState<TenantDTO[]>([]);
  const [requestTerminateTarget, setRequestTerminateTarget] = useState<LeaseDTO | null>(null);
  const [requestFeedback, setRequestFeedback] = useState<string | null>(null);

  const fetchAll = async () => {
    try {
      const [l, p, t] = await Promise.all([
        leaseService.getMyLeases(),
        propertyService.getMyProperties(),
        tenantService.getMyTenants(),
      ]);
      setLeases(l);
      setFilteredLeases(l);
      setProperties(p);
      setTenants(t);
    } catch (e) { console.error(e); }
  };

  useEffect(() => { fetchAll(); }, []);

  useEffect(() => {
    const q = search.toLowerCase();
    setFilteredLeases(
      leases.filter(l =>
        (l.tenantName || '').toLowerCase().includes(q) ||
        (l.propertyName || '').toLowerCase().includes(q) ||
        (l.propertyId || '').toLowerCase().includes(q)
      )
    );
  }, [search, leases]);

  const handleCreate = async (data: Partial<LeaseDTO>, contractPdf?: File | null) => {
    try {
      await leaseService.createLease(data, contractPdf);
      await fetchAll();
    } catch (e: any) {
      alert('Error: ' + (e.response?.data?.message || e.message));
    }
  };

  const handleTerminate = async (id: string, label: string) => {
    if (!window.confirm(`¿Terminar el contrato de "${label}"?`)) return;
    try {
      await leaseService.terminateLease(id);
      await fetchAll();
    } catch (e: any) {
      alert('Error: ' + (e.response?.data?.message || e.message));
    }
  };

  const handleRequestTerminate = async (password: string, mfaCode: string, reason: string | undefined) => {
    if (!requestTerminateTarget?.id) return;
    await approvalRequestService.requestLeaseTerminate(requestTerminateTarget.id, {
      password,
      mfaCode,
      reason,
    });
    const label = requestTerminateTarget.propertyName || requestTerminateTarget.tenantName || 'contrato';
    setRequestTerminateTarget(null);
    setRequestFeedback(`Solicitud de terminación enviada para "${label}". El dueño decidirá desde su bandeja.`);
    setTimeout(() => setRequestFeedback(null), 6000);
    await fetchAll();
  };

  const statusBadge = (st: string) => {
    if (st === 'ACTIVE') return <span className="inline-flex items-center gap-1 px-2.5 py-1 rounded-full text-xs font-semibold bg-emerald-100 text-emerald-700 border border-emerald-200"><CheckCircle className="w-3 h-3" /> Activo</span>;
    if (st === 'TERMINATED') return <span className="inline-flex items-center gap-1 px-2.5 py-1 rounded-full text-xs font-semibold bg-slate-100 text-slate-600 border border-slate-200"><XCircle className="w-3 h-3" /> Terminado</span>;
    return <span className="inline-flex px-2.5 py-1 rounded-full text-xs font-semibold bg-amber-100 text-amber-700 border border-amber-200">{st}</span>;
  };

  return (
    <div className="space-y-6">
      {/* Header */}
      <div className="flex flex-col md:flex-row justify-between items-start md:items-center gap-4 bg-white p-6 rounded-2xl border border-slate-200 shadow-sm">
        <div>
          <h2 className="text-2xl font-bold tracking-tight text-slate-800">Contratos</h2>
          <p className="text-sm text-slate-500 mt-1">Gestión de arrendamientos activos e históricos.</p>
        </div>
        <div className="flex items-center gap-3 w-full md:w-auto">
          <div className="relative flex-1 md:w-64">
            <Search className="w-4 h-4 absolute left-3 top-1/2 -translate-y-1/2 text-slate-400" />
            <input type="text" placeholder="Buscar contrato..."
              value={search} onChange={(e) => setSearch(e.target.value)}
              className="w-full pl-9 pr-4 py-2 bg-slate-50 border border-slate-200 rounded-xl text-sm focus:border-indigo-500 focus:ring-1 focus:ring-indigo-500 outline-none transition-all" />
          </div>
          <button onClick={() => setIsModalOpen(true)}
            className="flex items-center gap-2 bg-emerald-600 hover:bg-emerald-700 text-white px-4 py-2 rounded-xl text-sm font-semibold transition-all shadow-sm active:scale-95">
            <Plus className="w-4 h-4" /> Nuevo Contrato
          </button>
        </div>
      </div>

      {/* Leases Table */}
      <div className="bg-white rounded-2xl border border-slate-200 shadow-sm overflow-hidden">
        <div className="overflow-x-auto">
          <table className="w-full">
            <thead className="bg-slate-50/80 border-b border-slate-100">
              <tr>
                <th className="px-6 py-4 text-left text-xs font-bold text-slate-500 uppercase">Inquilino</th>
                <th className="px-6 py-4 text-left text-xs font-bold text-slate-500 uppercase">Inmueble</th>
                <th className="px-6 py-4 text-left text-xs font-bold text-slate-500 uppercase">Renta</th>
                <th className="px-6 py-4 text-left text-xs font-bold text-slate-500 uppercase">Vigencia</th>
                <th className="px-6 py-4 text-left text-xs font-bold text-slate-500 uppercase">Día Pago</th>
                <th className="px-6 py-4 text-left text-xs font-bold text-slate-500 uppercase">Estado</th>
                <th className="px-6 py-4 text-right text-xs font-bold text-slate-500 uppercase">Acciones</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-slate-100">
              {filteredLeases.map(l => (
                <tr key={l.id} className="hover:bg-slate-50/50 transition-colors group">
                  <td className="px-6 py-4 whitespace-nowrap">
                    <p className="text-sm font-bold text-slate-800">{l.tenantName}</p>
                    <p className="text-xs text-slate-400">{l.tenantEmail}</p>
                  </td>
                  <td className="px-6 py-4 text-sm text-slate-800 font-medium">{l.propertyName || '—'}</td>
                  <td className="px-6 py-4">
                    <div className="flex items-center gap-1 text-sm font-bold text-slate-800">
                      <DollarSign className="w-4 h-4 text-emerald-500" />
                      {Number(l.monthlyRent).toLocaleString('en-US', { minimumFractionDigits: 2 })}
                    </div>
                  </td>
                  <td className="px-6 py-4">
                    <div className="flex items-center gap-1 text-xs text-slate-500">
                      <Calendar className="w-3.5 h-3.5" />
                      {l.startDate} → {l.endDate}
                    </div>
                  </td>
                  <td className="px-6 py-4 text-sm text-slate-600 font-medium">Día {l.paymentDay}</td>
                  <td className="px-6 py-4">{statusBadge(l.status || 'ACTIVE')}</td>
                  <td className="px-6 py-4 text-right">
                    {l.status === 'ACTIVE' && (
                      isDirectExecutor ? (
                        <button onClick={() => handleTerminate(l.id!, l.propertyName || 'Contrato')}
                          className="text-xs font-semibold text-rose-500 hover:bg-rose-50 px-3 py-1.5 rounded-lg transition-colors">
                          Terminar
                        </button>
                      ) : (
                        <button onClick={() => setRequestTerminateTarget(l)}
                          title="Solicitar al dueño la terminación del contrato (requiere tu contraseña y MFA)"
                          className="text-xs font-semibold text-amber-600 hover:bg-amber-50 px-3 py-1.5 rounded-lg border border-amber-200 transition-colors">
                          Solicitar terminación
                        </button>
                      )
                    )}
                  </td>
                </tr>
              ))}
              {filteredLeases.length === 0 && (
                <tr>
                  <td colSpan={7} className="px-6 py-12 text-center text-slate-500">
                    <FileText className="w-10 h-10 mx-auto text-slate-300 mb-2" />
                    <p className="text-sm font-medium">No se encontraron contratos.</p>
                  </td>
                </tr>
              )}
            </tbody>
          </table>
        </div>
      </div>

      <LeaseFormModal
        isOpen={isModalOpen}
        onClose={() => setIsModalOpen(false)}
        onSubmit={handleCreate}
        properties={properties}
        tenants={tenants}
      />

      <StaffApprovalRequestModal
        isOpen={requestTerminateTarget !== null}
        onClose={() => setRequestTerminateTarget(null)}
        onConfirm={handleRequestTerminate}
        action="LEASE_TERMINATE"
        resourceLabel={
          requestTerminateTarget
            ? `${requestTerminateTarget.propertyName || 'Contrato'} · ${requestTerminateTarget.tenantName || ''}`
            : ''
        }
      />

      {requestFeedback && (
        <div className="fixed bottom-6 right-6 z-[110] bg-emerald-600 text-white px-5 py-3 rounded-xl shadow-lg text-sm font-semibold animate-in slide-in-from-bottom-4 duration-300">
          {requestFeedback}
        </div>
      )}
    </div>
  );
};
