import React, { useEffect, useState } from 'react';
import { Building, MapPin, Search, Edit3, Trash2, Plus, ArrowUpRight, Hash, ShieldAlert, Megaphone, Loader2 } from 'lucide-react';
import { useAuth } from '../context/AuthContext';
import { propertyService, PropertyDTO } from '../services/propertyService';
import { PropertyFormModal } from '../components/modals/PropertyFormModal';
import { PropertyHardDeleteModal } from '../components/modals/PropertyHardDeleteModal';
import { StaffApprovalRequestModal } from '../components/modals/StaffApprovalRequestModal';
import { PropertyDetailView } from './PropertyDetailView';
import { GlobalAnalytics } from './GlobalAnalytics';
import { approvalRequestService } from '../services/approvalRequestService';
import { agentWorkflowService } from '../services/agentWorkflowService';

const STATUS_LABELS: Record<PropertyDTO['status'], { label: string; classes: string }> = {
  AVAILABLE:          { label: 'DISPONIBLE',        classes: 'bg-emerald-100/80 text-emerald-700 border-emerald-200' },
  OCCUPIED:           { label: 'OCUPADO',           classes: 'bg-indigo-100/80 text-indigo-700 border-indigo-200' },
  MAINTENANCE:        { label: 'MANTENIMIENTO',     classes: 'bg-amber-100/80 text-amber-700 border-amber-200' },
  PENDING_RENT:       { label: 'BUSCANDO INQUILINO', classes: 'bg-sky-100/80 text-sky-700 border-sky-200' },
  PROSPECT_PROPOSED:  { label: 'PROSPECTO PROPUESTO', classes: 'bg-fuchsia-100/80 text-fuchsia-700 border-fuchsia-200' },
  AWAITING_CONTRACT:  { label: 'POR FIRMAR',         classes: 'bg-violet-100/80 text-violet-700 border-violet-200' },
  DELETED:            { label: 'ELIMINADO',         classes: 'bg-slate-100 text-slate-500 border-slate-200' },
};

export const PropertyManager: React.FC = () => {
  const { user } = useAuth();
  const [properties, setProperties] = useState<PropertyDTO[]>([]);
  const [filteredProperties, setFilteredProperties] = useState<PropertyDTO[]>([]);
  const [search, setSearch] = useState('');
  const [isModalOpen, setIsModalOpen] = useState(false);
  const [editingProperty, setEditingProperty] = useState<PropertyDTO | null>(null);
  const [selectedPropertyId, setSelectedPropertyId] = useState<string | null>(null);
  const [deleteTarget, setDeleteTarget] = useState<PropertyDTO | null>(null);
  const [requestDeleteTarget, setRequestDeleteTarget] = useState<PropertyDTO | null>(null);
  const [requestFeedback, setRequestFeedback] = useState<string | null>(null);
  const [notifyingId, setNotifyingId] = useState<string | null>(null);

  // V52 — el rol "owner" del UI es estrictamente OWNER. SUPER_ADMIN queda fuera
  // de este manager: su dashboard está en /dashboard (Plataforma Root).
  // V66 — SOLO el dueño puede ejecutar el hard-delete de un inmueble. Staff
  // (property admin, etc.) debe usar el flujo de solicitud/aprobación.
  const isOwner = user?.role === 'OWNER';

  // Sólo el dueño dispara la cadena. Admin staff tiene que pasar por el dueño
  // (misma lógica que para eliminar inmueble).
  const handleStartAgentChain = async (prop: PropertyDTO, e: React.MouseEvent) => {
    e.stopPropagation();
    if (!prop.id) return;
    if (!window.confirm(
      `Vas a notificar al primer agente inmobiliario de tu lista de prioridades para buscar inquilino en "${prop.name}". Tendrá 72h para responder antes de pasar al siguiente.\n\n¿Confirmas?`
    )) return;
    setNotifyingId(prop.id);
    try {
      await agentWorkflowService.startAgentChainForProperty(prop.id);
      setRequestFeedback(`✅ Notificación enviada. "${prop.name}" ahora está en búsqueda de inquilino.`);
      setTimeout(() => setRequestFeedback(null), 6000);
      await fetchProperties();
    } catch (err: any) {
      const data = err?.response?.data;
      // V51 — caso especial: inmueble sin historial de renta (anti-spam a agentes).
      // El backend devuelve 409 + error:"NO_RENTAL_HISTORY" + message + hint.
      if (data?.error === 'NO_RENTAL_HISTORY') {
        alert(
          `Este inmueble todavía no puede difundirse automáticamente a tu cadena de agentes.\n\n` +
          `${data.message ?? ''}\n\n` +
          `${data.hint ?? 'Contacta a un agente directamente para esta primera colocación.'}`
        );
      } else {
        const msg = data?.error ?? 'No se pudo iniciar la cadena.';
        const hint = data?.hint;
        alert([msg, hint].filter(Boolean).join('\n\n'));
      }
    } finally {
      setNotifyingId(null);
    }
  };

  const fetchProperties = async () => {
    try {
      const data = await propertyService.getMyProperties();
      setProperties(data);
      setFilteredProperties(data);
    } catch (error) {
      console.error('Error fetching properties', error);
    }
  };

  useEffect(() => { fetchProperties(); }, []);

  useEffect(() => {
    const q = search.toLowerCase();
    setFilteredProperties(
      properties.filter(
        (p) =>
          p.name.toLowerCase().includes(q) ||
          (p.address || '').toLowerCase().includes(q) ||
          (p.type || '').toLowerCase().includes(q)
      )
    );
  }, [search, properties]);

  const handleCreateOrUpdate = async (data: PropertyDTO) => {
    if (editingProperty && editingProperty.id) {
      await propertyService.updateProperty(editingProperty.id, data);
    } else {
      await propertyService.createProperty(data);
    }
    await fetchProperties();
  };

  // V66 — el hard-delete lo ejecuta el modal dedicado PropertyHardDeleteModal,
  // que muestra el impact preview y recolecta reauth. Aquí sólo refrescamos.
  const handlePropertyDeleted = async () => {
    setDeleteTarget(null);
    await fetchProperties();
  };

  // Staff (Acceso Total): opens the approval request modal (double reauth policy).
  const handleRequestDeleteConfirm = async (password: string, mfaCode: string, reason: string | undefined) => {
    if (!requestDeleteTarget?.id) return;
    await approvalRequestService.requestPropertyDelete(requestDeleteTarget.id, { password, mfaCode, reason });
    const label = requestDeleteTarget.name;
    setRequestDeleteTarget(null);
    setRequestFeedback(`Solicitud de eliminación enviada para "${label}". El dueño decidirá desde su bandeja.`);
    setTimeout(() => setRequestFeedback(null), 6000);
  };

  const openNew = () => { setEditingProperty(null); setIsModalOpen(true); };
  const openEdit = (prop: PropertyDTO, e: React.MouseEvent) => {
    e.stopPropagation();
    setEditingProperty(prop);
    setIsModalOpen(true);
  };

  // Detail View
  if (selectedPropertyId) {
    return <PropertyDetailView
      propertyId={selectedPropertyId}
      onBack={() => { setSelectedPropertyId(null); fetchProperties(); }}
    />;
  }

  const typeLabels: Record<string, string> = {
    habitacional: 'Habitacional', comercial: 'Comercial', mixto: 'Mixto',
    industrial: 'Industrial', oficinas: 'Oficinas'
  };

  return (
    <div className="space-y-6">
      <GlobalAnalytics />
      
      {/* Header */}
      <div className="flex flex-col md:flex-row justify-between items-start md:items-center gap-4 bg-white p-6 rounded-2xl border border-slate-200 shadow-sm">
        <div>
          <h2 className="text-2xl font-bold tracking-tight text-slate-800">Inmuebles</h2>
          <p className="text-sm text-slate-500 mt-1">Gestiona los edificios, plazas y complejos de tu empresa.</p>
        </div>
        <div className="flex items-center gap-3 w-full md:w-auto">
          <div className="relative flex-1 md:w-64">
            <Search className="w-4 h-4 absolute left-3 top-1/2 -translate-y-1/2 text-slate-400" />
            <input type="text" placeholder="Buscar inmueble..."
              value={search} onChange={(e) => setSearch(e.target.value)}
              className="w-full pl-9 pr-4 py-2 bg-slate-50 border border-slate-200 rounded-xl text-sm focus:border-indigo-500 focus:ring-1 focus:ring-indigo-500 outline-none transition-all" />
          </div>
          <button onClick={openNew}
            className="flex items-center gap-2 bg-indigo-600 hover:bg-indigo-700 text-white px-4 py-2 rounded-xl text-sm font-semibold transition-all shadow-sm shadow-indigo-600/20 active:scale-95">
            <Plus className="w-4 h-4" /> Añadir Inmueble
          </button>
        </div>
      </div>

      {/* Property Cards */}
      <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-6">
        {filteredProperties.map((prop) => (
          <div key={prop.id}
            onClick={() => setSelectedPropertyId(prop.id!)}
            className="group bg-white rounded-2xl border border-slate-200 shadow-sm hover:shadow-xl hover:border-indigo-200 transition-all duration-300 overflow-hidden flex flex-col cursor-pointer"
          >
            <div className="h-32 bg-gradient-to-br from-indigo-500/10 to-purple-500/10 flex items-center justify-center relative p-4 shrink-0 border-b border-indigo-50/50">
              <Building className="w-12 h-12 text-indigo-300/50 group-hover:scale-110 transition-transform duration-500" />
              <div className="absolute top-4 right-4">
                {(() => {
                  const meta = STATUS_LABELS[prop.status] ?? STATUS_LABELS.AVAILABLE;
                  return (
                    <span className={`inline-flex items-center px-2.5 py-1 rounded-full text-xs font-semibold backdrop-blur-md border ${meta.classes}`}>
                      {meta.label}
                    </span>
                  );
                })()}
              </div>
              {prop.type && (
                <div className="absolute top-4 left-4">
                  <span className="inline-flex items-center px-2 py-0.5 rounded-md text-xs font-medium bg-white/80 text-slate-600 border border-slate-200/50 backdrop-blur-sm">
                    {typeLabels[prop.type] || prop.type}
                  </span>
                </div>
              )}
            </div>
            
            <div className="p-6 flex flex-col flex-1">
              <div className="space-y-2 mb-4">
                <h3 className="font-bold text-slate-800 text-lg leading-tight line-clamp-1" title={prop.name}>
                  {prop.name}
                </h3>
                <div className="flex items-start gap-1.5 text-slate-400">
                  <MapPin className="w-4 h-4 shrink-0 mt-0.5" />
                  <p className="text-xs line-clamp-2 leading-relaxed" title={prop.address}>{prop.address}</p>
                </div>
                {prop.predial && (
                  <div className="flex items-center gap-1.5 text-xs text-slate-400">
                    <Hash className="w-3.5 h-3.5" /> {prop.predial}
                  </div>
                )}
              </div>

              <div className="mt-auto pt-4 border-t border-slate-100 flex items-center justify-between">
                <div className="flex items-center gap-1.5 px-3 py-1 bg-slate-50 text-slate-500 rounded-lg text-xs font-medium border border-slate-100 hover:text-indigo-600 hover:bg-indigo-50 transition-colors">
                  <span>Ver Detalle</span>
                  <ArrowUpRight className="w-3.5 h-3.5" />
                </div>
                
                <div className="flex items-center gap-2">
                  {isOwner && prop.status === 'AVAILABLE' && (
                    <button
                      title="Notificar al primer agente inmobiliario para que busque inquilino (72h por agente)"
                      onClick={(e) => handleStartAgentChain(prop, e)}
                      disabled={notifyingId === prop.id}
                      className="p-2 text-sky-600 hover:bg-sky-50 rounded-lg transition-colors disabled:opacity-40"
                    >
                      {notifyingId === prop.id
                        ? <Loader2 className="w-4 h-4 animate-spin" />
                        : <Megaphone className="w-4 h-4" />}
                    </button>
                  )}

                  <button title="Editar Inmueble" onClick={(e) => openEdit(prop, e)}
                    className="p-2 text-indigo-600 hover:bg-indigo-50 rounded-lg transition-colors">
                    <Edit3 className="w-4 h-4" />
                  </button>

                  {/* V66 — SOLO OWNER puede ejecutar hard-delete. Staff ve el botón
                      ámbar de "solicitar eliminación" que crea una tarea al dueño. */}
                  {isOwner ? (
                    <button title="Eliminar inmueble y toda su contabilidad (acción irreversible)"
                      onClick={(e) => { e.stopPropagation(); setDeleteTarget(prop); }}
                      className="p-2 text-rose-500 hover:bg-rose-50 rounded-lg transition-colors">
                      <Trash2 className="w-4 h-4" />
                    </button>
                  ) : (
                    <button title="Solicitar eliminación al dueño (requiere tu contraseña y MFA)"
                      onClick={(e) => { e.stopPropagation(); setRequestDeleteTarget(prop); }}
                      className="p-2 text-amber-500 hover:bg-amber-50 rounded-lg transition-colors">
                      <ShieldAlert className="w-4 h-4" />
                    </button>
                  )}
                </div>
              </div>
            </div>
          </div>
        ))}

        {filteredProperties.length === 0 && (
          <div className="col-span-full py-16 flex flex-col items-center justify-center bg-white rounded-2xl border border-slate-200 border-dashed text-slate-500">
            <Building className="w-12 h-12 text-slate-300 mb-3" />
            <h4 className="text-lg font-medium text-slate-700">Sin Inmuebles</h4>
            <p className="text-sm mt-1">Tu búsqueda no arrojó resultados o aún no tienes edificios registrados.</p>
          </div>
        )}
      </div>

      <PropertyFormModal
        isOpen={isModalOpen}
        onClose={() => setIsModalOpen(false)}
        onSubmit={handleCreateOrUpdate}
        initialData={editingProperty}
      />

      {/* V66 — Hard-delete modal con preview de impacto + reauth. Solo OWNER. */}
      <PropertyHardDeleteModal
        isOpen={deleteTarget !== null}
        propertyId={deleteTarget?.id || null}
        propertyName={deleteTarget?.name || ''}
        onClose={() => setDeleteTarget(null)}
        onDeleted={handlePropertyDeleted}
      />

      {/* Approval request modal — staff with Acceso Total */}
      <StaffApprovalRequestModal
        isOpen={requestDeleteTarget !== null}
        onClose={() => setRequestDeleteTarget(null)}
        onConfirm={handleRequestDeleteConfirm}
        action="PROPERTY_DELETE"
        resourceLabel={requestDeleteTarget?.name || ''}
      />

      {requestFeedback && (
        <div className="fixed bottom-6 right-6 z-[110] bg-emerald-600 text-white px-5 py-3 rounded-xl shadow-lg text-sm font-semibold animate-in slide-in-from-bottom-4 duration-300">
          {requestFeedback}
        </div>
      )}
    </div>
  );
};
