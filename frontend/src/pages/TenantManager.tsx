import { useEffect, useState } from 'react';
import { Users, Search, Edit3, Plus, DollarSign, Calendar, Copy, Check, FolderOpen, Trash2, BellRing, Bell, Banknote, HandCoins } from 'lucide-react';
import { tenantService, TenantDTO, TenantExpedienteSummaryDTO } from '../services/tenantService';
import { propertyService } from '../services/propertyService';
import { TenantFormModal } from '../components/modals/TenantFormModal';
import { ReauthDeleteModal } from '../components/modals/ReauthDeleteModal';
import { ReauthConfirmModal } from '../components/modals/ReauthConfirmModal';
import { StaffApprovalRequestModal } from '../components/modals/StaffApprovalRequestModal';
import { leaseService, LeaseDTO, hasLeaseDocument } from '../services/leaseService';
import { openSecureFile, describeSecureFileError } from '../services/secureFileService';
import { approvalRequestService, canExecuteDirectly } from '../services/approvalRequestService';
import {
  manualReminderService,
  ManualReminderEligibility,
  ManualReminderIneligibleReason,
} from '../services/manualReminderService';
import { useAuth } from '../context/AuthContext';
import NotificationHistoryPanel from './NotificationHistoryPanel';
import { CollapsibleSection } from '../components/CollapsibleSection';
import { TenantPaymentsHistory } from '../components/TenantPaymentsHistory';
import { TenantAgreementsHistory } from '../components/TenantAgreementsHistory';

const handleOpenLeasePdf = async (leaseId: string) => {
  try {
    await openSecureFile('lease-document', leaseId);
  } catch (err) {
    window.alert(describeSecureFileError(err));
  }
};

export const TenantManager: React.FC = () => {
  const { user } = useAuth();
  const isDirectExecutor = canExecuteDirectly(user?.role);

  const [tenants, setTenants] = useState<TenantDTO[]>([]);
  const [properties, setProperties] = useState<Record<string, string>>({});
  const [filteredTenants, setFilteredTenants] = useState<TenantDTO[]>([]);
  const [search, setSearch] = useState('');
  const [expediente, setExpediente] = useState<TenantDTO | null>(null);
  const [tenantLeases, setTenantLeases] = useState<LeaseDTO[]>([]);

  const [isModalOpen, setIsModalOpen] = useState(false);
  const [editingTenant, setEditingTenant] = useState<TenantDTO | null>(null);
  const [deleteTarget, setDeleteTarget] = useState<TenantDTO | null>(null);
  const [requestArchiveTarget, setRequestArchiveTarget] = useState<TenantDTO | null>(null);
  const [requestFeedback, setRequestFeedback] = useState<string | null>(null);

  const [newCredentials, setNewCredentials] = useState<{ identifier: string; pass: string; name: string } | null>(null);
  const [copied, setCopied] = useState(false);
  const [expedienteDetail, setExpedienteDetail] = useState<TenantExpedienteSummaryDTO | null>(null);

  // Recordatorio manual de pago (Fase B2). El botón vive en el header del expediente.
  // - reminderEligibility: estado de elegibilidad consultado al abrir un expediente.
  // - reminderModalOpen: controla el modal de reauth (password + MFA).
  // - reminderFeedback: mensaje flash tras un envío exitoso (se autolimpia).
  const [reminderEligibility, setReminderEligibility] = useState<ManualReminderEligibility | null>(null);
  const [reminderModalOpen, setReminderModalOpen] = useState(false);
  const [reminderFeedback, setReminderFeedback] = useState<string | null>(null);

  const fetchProperties = async () => {
    try {
      const data = await propertyService.getMyProperties();
      const map: Record<string, string> = {};
      data.forEach((p) => {
        map[p.id!] = p.name;
      });
      setProperties(map);
    } catch (e) {
      console.error(e);
    }
  };

  const fetchTenants = async () => {
    try {
      const data = await tenantService.getMyTenants();
      setTenants(data);
      setFilteredTenants(data);
    } catch (e) {
      console.error(e);
    }
  };

  useEffect(() => {
    fetchProperties();
    fetchTenants();
  }, []);

  useEffect(() => {
    const uid = expediente?.userId;
    if (!uid) {
      setTenantLeases([]);
      return;
    }
    leaseService
      .getMyLeases()
      .then((rows) => setTenantLeases(rows.filter((l) => l.tenantId === uid)))
      .catch(() => setTenantLeases([]));
  }, [expediente]);

  useEffect(() => {
    if (!expediente?.id) {
      setExpedienteDetail(null);
      return;
    }
    tenantService
      .getExpedienteSummary(expediente.id)
      .then(setExpedienteDetail)
      .catch(() => setExpedienteDetail(null));
  }, [expediente?.id]);

  // Al abrir/cambiar expediente consultamos elegibilidad del recordatorio manual.
  // El SUPER_ADMIN queda fuera client-side (el backend también lo rechaza); el resto
  // verá el botón activo/inactivo según el resultado. Si la petición falla dejamos
  // null → botón deshabilitado con tooltip genérico.
  useEffect(() => {
    if (!expediente?.id || user?.role === 'SUPER_ADMIN') {
      setReminderEligibility(null);
      return;
    }
    manualReminderService
      .checkEligibility(expediente.id)
      .then(setReminderEligibility)
      .catch(() => setReminderEligibility(null));
  }, [expediente?.id, user?.role]);

  const handleSendManualReminder = async (password: string, mfaCode: string) => {
    if (!expediente?.id) return;
    const result = await manualReminderService.sendManualReminder(expediente.id, password, mfaCode);
    // Mostramos el flash con info del envío y refrescamos elegibilidad para actualizar
    // el contador de envíos restantes sin pedir otra vez al backend con otra llamada.
    setReminderFeedback(
      `Recordatorio enviado a ${expediente.name}. Envíos restantes hoy: ${result.remainingToday}.`
    );
    setTimeout(() => setReminderFeedback(null), 6000);
    setReminderEligibility((prev) =>
      prev ? { ...prev, remainingToday: result.remainingToday, eligible: result.remainingToday > 0 } : prev
    );
  };

  const reminderButton = (() => {
    if (!expediente?.id || user?.role === 'SUPER_ADMIN') return null;
    if (reminderEligibility == null) {
      return (
        <button
          type="button"
          disabled
          className="inline-flex items-center gap-2 px-3 py-2 text-sm font-semibold text-slate-400 border border-slate-200 rounded-xl cursor-not-allowed bg-slate-50"
        >
          <BellRing className="w-4 h-4" /> Verificando...
        </button>
      );
    }
    if (reminderEligibility.eligible) {
      return (
        <button
          type="button"
          onClick={() => setReminderModalOpen(true)}
          className="inline-flex items-center gap-2 px-3 py-2 text-sm font-semibold text-white bg-emerald-600 hover:bg-emerald-700 rounded-xl shadow-sm shadow-emerald-500/20 transition-colors"
          title={`Forzará envío por correo, WhatsApp y campana. Te quedan ${reminderEligibility.remainingToday} envíos en las próximas 24h.`}
        >
          <BellRing className="w-4 h-4" /> Enviar recordatorio de pago
          <span className="ml-1 text-[10px] font-bold bg-white/20 rounded-md px-1.5 py-0.5">
            {reminderEligibility.remainingToday}/2
          </span>
        </button>
      );
    }
    const reason: ManualReminderIneligibleReason | undefined = reminderEligibility.reason;
    const label =
      reason === 'NO_INVOICE_DUE'
        ? 'Al corriente — nada que cobrar'
        : reason === 'RATE_LIMIT_REACHED'
        ? 'Máximo diario alcanzado (2/2)'
        : 'No disponible';
    const title =
      reason === 'RATE_LIMIT_REACHED'
        ? 'Podrás enviar otro recordatorio después de 24 horas del último envío.'
        : reason === 'NO_INVOICE_DUE'
        ? 'Este inquilino no tiene facturas con saldo pendiente.'
        : 'Acción no disponible en este momento.';
    return (
      <button
        type="button"
        disabled
        title={title}
        className="inline-flex items-center gap-2 px-3 py-2 text-sm font-semibold text-slate-400 border border-slate-200 rounded-xl cursor-not-allowed bg-slate-50"
      >
        <BellRing className="w-4 h-4" /> {label}
      </button>
    );
  })();

  useEffect(() => {
    const q = search.toLowerCase();
    setFilteredTenants(
      tenants.filter(
        (t) =>
          t.name.toLowerCase().includes(q) ||
          (t.username || '').toLowerCase().includes(q) ||
          (t.email || '').toLowerCase().includes(q) ||
          (t.propertyId && properties[t.propertyId]?.toLowerCase().includes(q))
      )
    );
  }, [search, tenants, properties]);

  const handleConfirmDeleteTenant = async (password: string, mfaCode: string) => {
    if (!deleteTarget?.id) return;
    await tenantService.deleteTenant(deleteTarget.id, password, mfaCode);
    if (expediente?.id === deleteTarget.id) setExpediente(null);
    setDeleteTarget(null);
    await fetchTenants();
  };

  const handleRequestArchive = async (password: string, mfaCode: string, reason: string | undefined) => {
    if (!requestArchiveTarget?.id) return;
    await approvalRequestService.requestTenantArchive(requestArchiveTarget.id, {
      password,
      mfaCode,
      reason,
    });
    const label = requestArchiveTarget.name;
    setRequestArchiveTarget(null);
    setRequestFeedback(`Solicitud enviada para "${label}". El dueño recibirá la tarea de aprobación.`);
    setTimeout(() => setRequestFeedback(null), 6000);
  };

  const handleCreateOrUpdate = async (data: TenantDTO, contractPdf?: File | null) => {
    if (editingTenant && editingTenant.id) {
      await tenantService.updateTenant(editingTenant.id, data);
    } else {
      const result = await tenantService.createTenant(data, contractPdf);
      if (result.tempPassword) {
        setNewCredentials({
          identifier: result.username || result.email || '',
          name: result.name,
          pass: result.tempPassword,
        });
      }
    }
    await fetchTenants();
  };

  return (
    <div className="space-y-6">
      <div className="flex flex-col md:flex-row justify-between items-start md:items-center gap-4 bg-white p-6 rounded-2xl border border-slate-200 shadow-sm">
        <div>
          <h2 className="text-2xl font-bold tracking-tight text-slate-800">Arrendatarios</h2>
          <p className="text-sm text-slate-500 mt-1">
            Alta integral: expediente + contrato ACTIVO en un solo paso (sin modal de contrato aparte). La baja operativa del
            arrendatario (revoca acceso y archiva expediente según backend) está disponible desde la tabla: acción «Eliminar
            inquilino».
          </p>
        </div>
        <div className="flex items-center gap-3 w-full md:w-auto">
          <div className="relative flex-1 md:w-64">
            <Search className="w-4 h-4 absolute left-3 top-1/2 -translate-y-1/2 text-slate-400" />
            <input
              type="text"
              placeholder="Buscar inquilino..."
              value={search}
              onChange={(e) => setSearch(e.target.value)}
              className="w-full pl-9 pr-4 py-2 bg-slate-50 border border-slate-200 rounded-xl text-sm focus:border-teal-500 focus:ring-1 focus:ring-teal-500 outline-none transition-all"
            />
          </div>
          <button
            onClick={() => {
              setEditingTenant(null);
              setIsModalOpen(true);
            }}
            className="flex items-center gap-2 bg-slate-900 hover:bg-slate-800 text-white px-4 py-2 rounded-xl text-sm font-semibold transition-all shadow-sm shadow-slate-900/20 active:scale-95"
          >
            <Plus className="w-4 h-4" />
            Nuevo expediente
          </button>
        </div>
      </div>

      <div className="bg-white rounded-2xl border border-slate-200 shadow-sm overflow-hidden">
        <div className="overflow-x-auto">
          <table className="w-full">
            <thead className="bg-slate-50/80 border-b border-slate-100">
              <tr>
                <th className="px-6 py-4 text-left text-xs font-bold text-slate-500 uppercase tracking-wider">
                  Inquilino / Contacto
                </th>
                <th className="px-6 py-4 text-left text-xs font-bold text-slate-500 uppercase tracking-wider">Inmueble</th>
                <th className="px-6 py-4 text-left text-xs font-bold text-slate-500 uppercase tracking-wider">Monto Renta</th>
                <th className="px-6 py-4 text-left text-xs font-bold text-slate-500 uppercase tracking-wider">Día Cobro</th>
                <th className="px-6 py-4 text-left text-xs font-bold text-slate-500 uppercase tracking-wider">Tenencia</th>
                <th className="px-6 py-4 text-left text-xs font-bold text-slate-500 uppercase tracking-wider">Expediente</th>
                <th className="px-6 py-4 text-right text-xs font-bold text-slate-500 uppercase tracking-wider">Acciones</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-slate-100">
              {filteredTenants.map((t) => (
                <tr key={t.id} className="hover:bg-slate-50/50 transition-colors group">
                  <td className="px-6 py-4 whitespace-nowrap">
                    <div className="flex items-center gap-3">
                      <div className="w-9 h-9 rounded-full bg-teal-100 text-teal-700 flex items-center justify-center font-bold text-sm tracking-tight border border-teal-200 shrink-0">
                        {t.name.substring(0, 2).toUpperCase()}
                      </div>
                      <div>
                        <p className="text-sm font-bold text-slate-800">{t.name}</p>
                        <p className="text-xs text-slate-500">{t.phone}</p>
                      </div>
                    </div>
                  </td>
                  <td className="px-6 py-4 whitespace-nowrap">
                    <span className="inline-flex items-center px-2.5 py-1 rounded-md text-xs font-semibold bg-indigo-50 text-indigo-700 border border-indigo-100">
                      {t.propertyId ? properties[t.propertyId] || 'Cargando...' : 'Sin Inmueble'}
                    </span>
                  </td>
                  <td className="px-6 py-4 whitespace-nowrap">
                    <div className="flex items-center gap-1.5 text-sm font-bold text-slate-800">
                      <DollarSign className="w-4 h-4 text-emerald-500" />
                      {Number(t.rentAmount).toLocaleString('en-US', { minimumFractionDigits: 2 })}
                    </div>
                  </td>
                  <td className="px-6 py-4 whitespace-nowrap">
                    <div className="flex items-center gap-1.5 text-sm text-slate-600 font-medium">
                      <Calendar className="w-4 h-4 text-slate-400" />
                      Día {t.paymentDay}
                    </div>
                  </td>
                  <td className="px-6 py-4 whitespace-nowrap">
                    {t.leaseId && t.leaseStatus === 'ACTIVE' ? (
                      <span className="text-xs font-semibold text-emerald-700 bg-emerald-50 px-2 py-1 rounded-lg border border-emerald-100">
                        ACTIVO
                      </span>
                    ) : (
                      <span className="text-xs text-slate-400">—</span>
                    )}
                  </td>
                  <td className="px-6 py-4 whitespace-nowrap">
                    <button
                      type="button"
                      onClick={() => setExpediente(t)}
                      className={`inline-flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-xs font-bold border transition-colors ${
                        expediente?.id === t.id
                          ? 'bg-violet-600 text-white border-violet-600'
                          : 'bg-white text-violet-700 border-violet-200 hover:bg-violet-50'
                      }`}
                    >
                      <FolderOpen className="w-3.5 h-3.5" />
                      Ver
                    </button>
                  </td>
                  <td className="px-6 py-4 whitespace-nowrap text-right text-sm">
                    <div className="flex justify-end gap-2">
                      <button
                        onClick={() => {
                          setEditingTenant(t);
                          setIsModalOpen(true);
                        }}
                        className="p-1.5 text-slate-400 hover:text-indigo-600 hover:bg-slate-100 rounded-md transition-colors"
                        title="Editar"
                      >
                        <Edit3 className="w-4 h-4" />
                      </button>
                      {isDirectExecutor ? (
                        <button
                          type="button"
                          onClick={() => setDeleteTarget(t)}
                          className="inline-flex items-center gap-1.5 px-2 py-1.5 text-rose-700 hover:bg-rose-50 rounded-lg border border-rose-100 transition-colors"
                          title="Dar de baja operativa (archiva expediente; no borra historial). Requiere reautenticación."
                        >
                          <Trash2 className="w-4 h-4 shrink-0" />
                          <span className="text-xs font-bold whitespace-nowrap">Dar de baja</span>
                        </button>
                      ) : (
                        <button
                          type="button"
                          onClick={() => setRequestArchiveTarget(t)}
                          className="inline-flex items-center gap-1.5 px-2 py-1.5 text-amber-700 hover:bg-amber-50 rounded-lg border border-amber-200 transition-colors"
                          title="Solicitar al dueño el archivo del expediente. Requiere tu contraseña y MFA; el dueño aprobará desde su bandeja."
                        >
                          <Trash2 className="w-4 h-4 shrink-0" />
                          <span className="text-xs font-bold whitespace-nowrap">Solicitar archivo</span>
                        </button>
                      )}
                    </div>
                  </td>
                </tr>
              ))}

              {filteredTenants.length === 0 && (
                <tr>
                  <td colSpan={7} className="px-6 py-12 text-center text-slate-500">
                    <Users className="w-10 h-10 mx-auto text-slate-300 mb-2" />
                    <p className="text-sm font-medium">No se encontraron inquilinos.</p>
                  </td>
                </tr>
              )}
            </tbody>
          </table>
        </div>
      </div>

      {expediente && (
        <div className="bg-white rounded-2xl border border-violet-200 shadow-sm overflow-hidden">
          <div className="px-6 py-4 border-b border-slate-100 flex flex-wrap items-center justify-between gap-3 bg-violet-50/50">
            <div>
              <p className="text-xs font-bold text-violet-600 uppercase tracking-wider">Expediente del arrendatario</p>
              <h3 className="text-lg font-bold text-slate-900">{expediente.name}</h3>
              <p className="text-sm text-slate-500">{expediente.email}</p>
            </div>
            <div className="flex flex-wrap gap-2 items-center">
              {reminderButton}
              <button
                type="button"
                onClick={() => setExpediente(null)}
                className="px-3 py-2 text-sm font-semibold text-slate-600 border border-slate-200 rounded-xl hover:bg-slate-50"
              >
                Cerrar
              </button>
            </div>
          </div>
          {reminderFeedback && (
            <div className="px-6 py-3 bg-emerald-50 border-b border-emerald-100 text-sm font-semibold text-emerald-800 flex items-center gap-2">
              <BellRing className="w-4 h-4" /> {reminderFeedback}
            </div>
          )}
          <div className="p-6 grid grid-cols-1 md:grid-cols-2 gap-6">
            <div className="space-y-2 text-sm">
              <p>
                <span className="font-bold text-slate-500">Dirección del inmueble:</span>{' '}
                {expedienteDetail?.propertyAddress ||
                  (expediente.propertyId ? properties[expediente.propertyId] || expediente.propertyId : '—')}
              </p>
              <p>
                <span className="font-bold text-slate-500">Inmueble (nombre):</span>{' '}
                {expedienteDetail?.propertyName ||
                  (expediente.propertyId ? properties[expediente.propertyId] || expediente.propertyId : '—')}
              </p>
              <p>
                <span className="font-bold text-slate-500">Renta / día de pago:</span> $
                {Number(expediente.rentAmount).toLocaleString('en-US', { minimumFractionDigits: 2 })} / día{' '}
                {expediente.paymentDay}
              </p>
              {expedienteDetail?.leaseId && (
                <p>
                  <span className="font-bold text-slate-500">Contrato vigente:</span>{' '}
                  <span className="font-mono text-xs">{expedienteDetail.leaseId}</span> ({expedienteDetail.leaseStatus || '—'})
                  {expedienteDetail.leaseStartDate && expedienteDetail.leaseEndDate && (
                    <span className="block text-xs text-slate-500 mt-0.5">
                      {expedienteDetail.leaseStartDate} → {expedienteDetail.leaseEndDate}
                    </span>
                  )}
                </p>
              )}
              <p className="text-xs text-slate-500 pt-2 border-t border-slate-100">
                Estado contable y cobranza del periodo: use la vista del inmueble (menú Inmuebles → abrir expediente del
                inmueble) para reportes mensuales, facturas y convenios.
              </p>
            </div>
            <div>
              <p className="text-xs font-bold text-slate-500 uppercase mb-2">Contrato y PDF</p>
              {expedienteDetail?.leaseDocumentUrl && expedienteDetail.leaseId ? (
                <button
                  type="button"
                  onClick={() => handleOpenLeasePdf(expedienteDetail.leaseId!)}
                  className="inline-flex items-center gap-2 text-violet-700 font-bold text-sm bg-violet-50 border border-violet-200 rounded-xl px-4 py-3 hover:bg-violet-100"
                >
                  {expedienteDetail.leaseDocumentFileName
                    ? `Abrir PDF firmado: ${expedienteDetail.leaseDocumentFileName}`
                    : 'Abrir contrato (PDF)'}
                </button>
              ) : (
                <p className="text-sm text-slate-500">No hay PDF de contrato registrado en el expediente.</p>
              )}
              <p className="text-xs font-bold text-slate-500 uppercase mb-2 mt-6">Historial de contratos (listado)</p>
              {tenantLeases.length === 0 ? (
                <p className="text-sm text-slate-500">Sin contratos en listado.</p>
              ) : (
                <ul className="space-y-2">
                  {tenantLeases.map((l) => (
                    <li key={l.id} className="rounded-xl border border-slate-200 p-3 text-sm">
                      <div className="font-bold text-slate-800">
                        {l.startDate} → {l.endDate} · ${Number(l.monthlyRent).toLocaleString('en-US')}{' '}
                        <span className="text-xs font-semibold text-slate-500">({l.status || '—'})</span>
                      </div>
                      {hasLeaseDocument(l.documentUrl) && l.id && (
                        <button
                          type="button"
                          onClick={() => handleOpenLeasePdf(l.id!)}
                          className="text-violet-600 font-semibold text-xs mt-1 inline-block hover:underline"
                        >
                          {l.documentFileName ? `PDF: ${l.documentFileName}` : 'Ver contrato (PDF)'}
                        </button>
                      )}
                    </li>
                  ))}
                </ul>
              )}
            </div>
          </div>

          {/* V67 — Historial colapsable: notificaciones, pagos, convenios.
              Arranca colapsado para no saturar la vista del expediente. El
              dueño puede expandir cada sección cuando necesite revisarla. */}
          {expediente.id && (
            <>
              <CollapsibleSection
                title="Historial de notificaciones"
                subtitle="Email y WhatsApp — últimos 3 meses"
                icon={<Bell className="w-4 h-4" />}
                tone="indigo"
              >
                <NotificationHistoryPanel
                  mode="tenant"
                  tenantProfileId={expediente.id}
                  embedded
                />
              </CollapsibleSection>

              <CollapsibleSection
                title="Historial de pagos"
                subtitle="Comprobantes validados, rechazados y expirados"
                icon={<Banknote className="w-4 h-4" />}
                tone="emerald"
              >
                <TenantPaymentsHistory tenantProfileId={expediente.id} />
              </CollapsibleSection>

              <CollapsibleSection
                title="Convenios"
                subtitle="Planes de pago y diferimientos solicitados"
                icon={<HandCoins className="w-4 h-4" />}
                tone="violet"
              >
                <TenantAgreementsHistory tenantProfileId={expediente.id} />
              </CollapsibleSection>
            </>
          )}
        </div>
      )}

      <TenantFormModal
        isOpen={isModalOpen}
        onClose={() => setIsModalOpen(false)}
        onSubmit={handleCreateOrUpdate}
        initialData={editingTenant}
      />

      <ReauthDeleteModal
        isOpen={deleteTarget !== null}
        onClose={() => setDeleteTarget(null)}
        onConfirm={handleConfirmDeleteTenant}
        propertyName={deleteTarget?.name || ''}
      />

      <StaffApprovalRequestModal
        isOpen={requestArchiveTarget !== null}
        onClose={() => setRequestArchiveTarget(null)}
        onConfirm={handleRequestArchive}
        action="TENANT_ARCHIVE"
        resourceLabel={requestArchiveTarget?.name || ''}
      />

      <ReauthConfirmModal
        isOpen={reminderModalOpen}
        onClose={() => setReminderModalOpen(false)}
        onConfirm={handleSendManualReminder}
        title="Enviar recordatorio de pago"
        description={
          expediente
            ? `Confirma tu identidad con contraseña y MFA para enviar un recordatorio de pago a ${expediente.name}. El mensaje se enviará por correo, WhatsApp y campana aunque el inquilino haya desactivado algún canal.`
            : 'Confirma tu identidad para enviar el recordatorio.'
        }
        confirmLabel="Enviar ahora"
        accent="emerald"
      />

      {requestFeedback && (
        <div className="fixed bottom-6 right-6 z-[110] bg-emerald-600 text-white px-5 py-3 rounded-xl shadow-lg text-sm font-semibold animate-in slide-in-from-bottom-4 duration-300">
          {requestFeedback}
        </div>
      )}

      {newCredentials && (
        <div className="fixed inset-0 z-[120] flex items-center justify-center p-4">
          <div className="absolute inset-0 bg-slate-900/80 backdrop-blur-sm" />
          <div className="relative bg-white rounded-3xl w-full max-w-md shadow-2xl p-8 border border-emerald-500/30 text-center animate-in zoom-in-95 duration-300">
            <div className="w-16 h-16 bg-emerald-100 rounded-full flex items-center justify-center mx-auto mb-4 border-4 border-white shadow-lg">
              <Check className="w-8 h-8 text-emerald-600" />
            </div>
            <h3 className="text-2xl font-bold text-slate-800 mb-2">¡Expediente abierto!</h3>
            <p className="text-slate-600 text-sm mb-6">
              Entrega estas credenciales temporales a <b>{newCredentials.name}</b> para el portal de arrendatario.
            </p>

            <div className="bg-slate-50 rounded-xl p-4 border border-slate-200 text-left space-y-3 mb-6 relative group">
              <div>
                <p className="text-xs text-slate-400 font-semibold uppercase tracking-wider">Usuario de acceso</p>
                <p className="text-sm font-bold text-slate-800">{newCredentials.identifier}</p>
              </div>
              <div>
                <p className="text-xs text-slate-400 font-semibold uppercase tracking-wider">Contraseña temporal</p>
                <p className="text-2xl font-mono font-bold tracking-widest text-slate-800">{newCredentials.pass}</p>
              </div>

              <button
                onClick={() => {
                  navigator.clipboard.writeText(
                    `Hola ${newCredentials.name}, tu acceso al portal es: \nUsuario: ${newCredentials.identifier}\nContraseña temporal: ${newCredentials.pass}\nAl ingresar deberás cambiarla.`
                  );
                  setCopied(true);
                  setTimeout(() => setCopied(false), 2000);
                }}
                className="absolute right-4 top-1/2 -translate-y-1/2 p-2.5 bg-white border border-slate-200 rounded-lg shadow-sm hover:border-emerald-500 hover:text-emerald-600 transition-colors"
              >
                {copied ? <Check className="w-5 h-5 text-emerald-500" /> : <Copy className="w-5 h-5" />}
              </button>
            </div>

            <button
              onClick={() => setNewCredentials(null)}
              className="w-full py-3 bg-slate-900 text-white rounded-xl font-bold hover:bg-slate-800 transition-all active:scale-95 shadow-lg shadow-slate-900/20"
            >
              He copiado los datos
            </button>
          </div>
        </div>
      )}
    </div>
  );
};
