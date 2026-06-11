import React, { useEffect, useState, useCallback, useMemo, useRef } from 'react';
import {
  ArrowLeft,
  MapPin,
  Upload,
  Trash2,
  Image,
  Download,
  BarChart3,
  History,
  Receipt,
  Handshake,
  LayoutGrid,
  Building2,
  Megaphone,
  Loader2,
  Printer,
} from 'lucide-react';
import {
  propertyService,
  PropertyDTO,
  PropertyFileDTO,
  PropertyMovementDTO,
  PropertyMonthlyReportDTO,
  PropertyAnnualReportDTO,
  VacancyListItemDTO,
} from '../services/propertyService';
import { leaseService, LeaseDTO, hasLeaseDocument } from '../services/leaseService';
import { openSecureFile, describeSecureFileError } from '../services/secureFileService';
import type { InvoiceDTO } from '../services/ledgerService';
import { paymentService, TransferProofDTO } from '../services/paymentService';
import { HistoryProofRow, ProofDetailModal } from './RentProofsPanel';
import { agreementService, PaymentAgreementDTO } from '../services/agreementService';
import { clampMonthYear, defaultMonthYearInBounds } from '../utils/reportingPeriod';
import { ReauthDeleteModal } from '../components/modals/ReauthDeleteModal';
import { StaffApprovalRequestModal } from '../components/modals/StaffApprovalRequestModal';
import { approvalRequestService, canExecuteDirectly } from '../services/approvalRequestService';
import { agentWorkflowService } from '../services/agentWorkflowService';
import { useAuth } from '../context/AuthContext';

interface Props {
  propertyId: string;
  onBack: () => void;
}

type DetailTab = 'RESUMEN' | 'GALERIA' | 'TIMELINE' | 'COBRANZA' | 'CONVENIOS';

const FILE_LABELS = [
  'BASELINE',
  'UPDATE',
  'BEFORE',
  'AFTER',
  'VISIT',
  'MAINTENANCE',
  'VACANCY',
  'AGREEMENT_EVIDENCE',
] as const;

/**
 * Abre un archivo protegido (contratos, fotos, planos, evidencias) pasando por el
 * endpoint autorizado con JWT. Reemplaza a los `<a href>` directos al disco que
 * tenían IDOR — ahora el backend valida pertenencia por id antes de servir.
 */
const handleSecureOpen = async (kind: Parameters<typeof openSecureFile>[0], id: string) => {
  try {
    await openSecureFile(kind, id);
  } catch (err) {
    window.alert(describeSecureFileError(err));
  }
};

const monthYearNow = () => {
  const d = new Date();
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}`;
};

export const PropertyDetailView: React.FC<Props> = ({ propertyId, onBack }) => {
  const [property, setProperty] = useState<PropertyDTO | null>(null);
  const [files, setFiles] = useState<PropertyFileDTO[]>([]);
  const [tab, setTab] = useState<DetailTab>('RESUMEN');
  const [uploading, setUploading] = useState(false);

  const [timeline, setTimeline] = useState<PropertyMovementDTO[]>([]);
  const [invoices, setInvoices] = useState<InvoiceDTO[]>([]);
  const [agreements, setAgreements] = useState<PaymentAgreementDTO[]>([]);
  // V65 — histórico de comprobantes de renta filtrado por este inmueble.
  // Muestra los que ya fueron decididos manualmente o expiraron.
  const [proofsHistory, setProofsHistory] = useState<TransferProofDTO[]>([]);
  const [proofDetail, setProofDetail] = useState<TransferProofDTO | null>(null);
  const [monthly, setMonthly] = useState<PropertyMonthlyReportDTO | null>(null);
  const [reportBounds, setReportBounds] = useState<{
    min: string;
    max: string;
    minYear: number;
    maxYear: number;
  } | null>(null);
  const [reportMonth, setReportMonth] = useState(monthYearNow);
  const [annualYear, setAnnualYear] = useState(new Date().getFullYear());
  const [annual, setAnnual] = useState<PropertyAnnualReportDTO | null>(null);

  const [vacancies, setVacancies] = useState<VacancyListItemDTO[]>([]);
  const [leasesOnProperty, setLeasesOnProperty] = useState<LeaseDTO[]>([]);

  const [uploadModal, setUploadModal] = useState<{ category: 'PHOTO' | 'PLAN' } | null>(null);
  const [uploadLabel, setUploadLabel] = useState<string>('VISIT');
  const [uploadNote, setUploadNote] = useState('');
  const fileInputRef = useRef<HTMLInputElement | null>(null);

  // ── File deletion (reauth for owner / approval for staff) ──────────────────
  // Target is captured when the trash icon is clicked; the exact modal rendered
  // depends on {@link canExecuteDirectly}. We keep a single piece of state so
  // only one modal is ever open at a time.
  const { user } = useAuth();
  const canDeleteDirectly = canExecuteDirectly(user?.role);
  const [deleteFileTarget, setDeleteFileTarget] = useState<PropertyFileDTO | null>(null);
  const [requestDeleteFileTarget, setRequestDeleteFileTarget] = useState<PropertyFileDTO | null>(null);

  // V52 — "Poner en renta": OWNER dispara directo, PROPERTY_ADMIN crea approval request.
  const [vacancyStartBusy, setVacancyStartBusy] = useState(false);
  const [requestVacancyStartOpen, setRequestVacancyStartOpen] = useState(false);
  const [vacancyStartFeedback, setVacancyStartFeedback] = useState<string | null>(null);

  const fetchCore = useCallback(async () => {
    const [prop, f] = await Promise.all([propertyService.getPropertyDetail(propertyId), propertyService.getFiles(propertyId)]);
    setProperty(prop);
    setFiles(f);
  }, [propertyId]);

  useEffect(() => {
    propertyService
      .getReportingPeriodBounds(propertyId)
      .then((b) => {
        const range = { min: b.minMonthYear, max: b.maxMonthYear, minYear: b.minYear, maxYear: b.maxYear };
        setReportBounds(range);
        setReportMonth((prev) => clampMonthYear(prev || defaultMonthYearInBounds(range.min, range.max), range.min, range.max));
        setAnnualYear((y) => Math.min(Math.max(y, range.minYear), range.maxYear));
      })
      .catch(() => {
        const d = new Date();
        const cur = `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}`;
        setReportBounds({ min: cur, max: cur, minYear: d.getFullYear(), maxYear: d.getFullYear() });
      });
  }, [propertyId]);

  const fetchInvoicesAndAgreements = useCallback(async () => {
    try {
      const [inv, ags, proofs] = await Promise.all([
        propertyService.getPropertyInvoices(propertyId),
        agreementService.getAllAgreements().catch(() => [] as PaymentAgreementDTO[]),
        // V65 — histórico de comprobantes del inmueble (estados terminales)
        paymentService.getProofsHistory({ propertyId }).catch(() => [] as TransferProofDTO[]),
      ]);
      setInvoices(inv);
      setAgreements(ags);
      setProofsHistory(proofs);
    } catch (e) {
      console.error(e);
    }
  }, [propertyId]);

  const fetchTimeline = useCallback(async () => {
    try {
      const t = await propertyService.getTimeline(propertyId);
      setTimeline(t);
    } catch (e) {
      console.error(e);
    }
  }, [propertyId]);

  const fetchMonthly = useCallback(async () => {
    try {
      const m = await propertyService.getMonthlyReport(propertyId, reportMonth);
      setMonthly(m);
    } catch (e) {
      console.error(e);
      setMonthly(null);
    }
  }, [propertyId, reportMonth]);

  useEffect(() => {
    fetchCore().catch(console.error);
  }, [fetchCore]);

  useEffect(() => {
    Promise.all([
      propertyService.listVacanciesByProperty(propertyId).catch(() => [] as VacancyListItemDTO[]),
      leaseService.getMyLeases().then((rows) => rows.filter((l) => l.propertyId === propertyId)),
    ])
      .then(([v, l]) => {
        setVacancies(v);
        setLeasesOnProperty(l);
      })
      .catch(console.error);
  }, [propertyId]);

  useEffect(() => {
    fetchInvoicesAndAgreements().catch(console.error);
  }, [fetchInvoicesAndAgreements]);

  useEffect(() => {
    if (tab === 'TIMELINE') fetchTimeline();
  }, [tab, fetchTimeline]);

  useEffect(() => {
    if (tab === 'RESUMEN') fetchMonthly();
  }, [tab, fetchMonthly]);

  const openUpload = (category: 'PHOTO' | 'PLAN') => {
    setUploadModal({ category });
    setUploadLabel('VISIT');
    setUploadNote('');
  };

  const triggerFilePick = () => fileInputRef.current?.click();

  const onFileSelected = async (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    if (!file || !uploadModal) return;
    setUploading(true);
    try {
      await propertyService.uploadFile(propertyId, uploadModal.category, file, {
        label: uploadLabel || undefined,
        note: uploadNote.trim() || undefined,
      });
      setUploadModal(null);
      await fetchCore();
      if (tab === 'RESUMEN') fetchMonthly();
    } catch (err: any) {
      alert('Error: ' + (err.response?.data?.message || err.message));
    } finally {
      setUploading(false);
      e.target.value = '';
    }
  };

  /**
   * Opens the appropriate modal for deleting a file. OWNER / SUPER_ADMIN see the
   * reauth modal and execute the deletion directly; every other role opens the
   * approval-request modal so the owner can confirm with password + MFA later.
   */
  const handleDeleteFile = (file: PropertyFileDTO) => {
    if (canDeleteDirectly) {
      setDeleteFileTarget(file);
    } else {
      setRequestDeleteFileTarget(file);
    }
  };

  const confirmDirectFileDelete = async (password: string, mfaCode: string) => {
    if (!deleteFileTarget) return;
    try {
      await propertyService.deleteFile(deleteFileTarget.id, password, mfaCode || null);
      setDeleteFileTarget(null);
      await fetchCore();
    } catch (e: any) {
      // Re-throw so the modal surfaces the error (401/422/etc.) instead of silently closing.
      throw e;
    }
  };

  const confirmRequestFileDelete = async (
    password: string,
    mfaCode: string,
    reason: string | undefined,
  ) => {
    if (!requestDeleteFileTarget) return;
    const trimmed = reason?.trim();
    try {
      await approvalRequestService.requestPropertyFileDelete(requestDeleteFileTarget.id, {
        password,
        mfaCode: mfaCode || null,
        reason: trimmed ? trimmed : null,
      });
      setRequestDeleteFileTarget(null);
      alert('Solicitud enviada. El dueño decidirá desde su bandeja.');
    } catch (e: any) {
      throw e;
    }
  };

  /**
   * Dueño dispara la cadena de agentes inmobiliarios directamente. Maneja el 409
   * especial {@code NO_RENTAL_HISTORY} (guardia anti-spam para primeras colocaciones)
   * y las otras razones (sin prioridades configuradas) con mensajería concreta.
   */
  const handleStartVacancyOwner = async () => {
    if (!property?.id) return;
    if (!window.confirm(
      `Vas a notificar al primer agente inmobiliario de tu lista de prioridades para buscar inquilino en "${property.name}". Tendrá 72h para responder antes de pasar al siguiente.\n\n¿Confirmas?`
    )) return;
    setVacancyStartBusy(true);
    try {
      await agentWorkflowService.startAgentChainForProperty(property.id);
      setVacancyStartFeedback(`✅ Cadena iniciada. "${property.name}" quedó en búsqueda de inquilino.`);
      setTimeout(() => setVacancyStartFeedback(null), 6000);
      await fetchCore();
    } catch (err: any) {
      const data = err?.response?.data;
      if (data?.error === 'NO_RENTAL_HISTORY') {
        alert(
          `Este inmueble todavía no puede difundirse automáticamente a tu cadena de agentes.\n\n` +
          `${data.message ?? ''}\n\n` +
          `${data.hint ?? 'Contacta a un agente directamente para esta primera colocación.'}`
        );
      } else {
        const msg = data?.error ?? data?.message ?? 'No se pudo iniciar la cadena.';
        const hint = data?.hint;
        alert([msg, hint].filter(Boolean).join('\n\n'));
      }
    } finally {
      setVacancyStartBusy(false);
    }
  };

  const confirmRequestVacancyStart = async (
    password: string,
    mfaCode: string,
    reason: string | undefined,
  ) => {
    if (!property?.id) return;
    const trimmed = reason?.trim();
    await approvalRequestService.requestVacancyStart(property.id, {
      password,
      mfaCode: mfaCode || null,
      reason: trimmed ? trimmed : null,
    });
    setRequestVacancyStartOpen(false);
    setVacancyStartFeedback(
      `Solicitud enviada al dueño para poner en renta "${property.name}". Decidirá desde su bandeja.`,
    );
    setTimeout(() => setVacancyStartFeedback(null), 6000);
  };

  const loadAnnual = async () => {
    try {
      const a = await propertyService.getAnnualReport(propertyId, annualYear);
      setAnnual(a);
    } catch (e: any) {
      alert(e.response?.data?.message || e.message);
    }
  };

  const invoiceIdsOnProperty = useMemo(() => new Set(invoices.map((i) => i.id)), [invoices]);
  const propertyAgreements = useMemo(
    () => agreements.filter((a) => a.invoiceId && invoiceIdsOnProperty.has(a.invoiceId)),
    [agreements, invoiceIdsOnProperty]
  );

  const photos = files.filter((f) => f.category === 'PHOTO');
  const plans = files.filter((f) => f.category === 'PLAN');
  const archivedPaymentFiles = files.filter((f) => f.label === 'PAYMENT_PROOF');

  const typeLabels: Record<string, string> = {
    habitacional: 'Habitacional',
    comercial: 'Comercial',
    mixto: 'Mixto',
    industrial: 'Industrial',
    oficinas: 'Oficinas',
  };

  const tabs: { id: DetailTab; label: string; icon: React.ReactNode }[] = [
    { id: 'RESUMEN', label: 'Resumen', icon: <BarChart3 className="w-4 h-4" /> },
    { id: 'GALERIA', label: 'Galería', icon: <Image className="w-4 h-4" /> },
    { id: 'TIMELINE', label: 'Historial operativo', icon: <History className="w-4 h-4" /> },
    { id: 'COBRANZA', label: 'Cobranza', icon: <Receipt className="w-4 h-4" /> },
    { id: 'CONVENIOS', label: 'Convenios', icon: <Handshake className="w-4 h-4" /> },
  ];

  if (!property) return <div className="text-slate-500 p-8">Cargando...</div>;

  const money = (n: number | undefined) =>
    `$${Number(n ?? 0).toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`;

  const activeLease = leasesOnProperty.find((l) => l.status === 'ACTIVE');
  const openVacancy = vacancies.find((v) => v.closedAt == null);

  // V52 — Condiciones para mostrar "Poner en renta":
  //   * No hay contrato activo.
  //   * Inmueble disponible (o en un estado abierto sin cadena) — usamos status == AVAILABLE
  //     como regla simple; el backend filtra con más precisión.
  //   * Ninguna vacancia abierta con cadena ya asignada (evita dobles arranques).
  const vacancyStartEligible =
    !activeLease &&
    (property.status === 'AVAILABLE' || property.status === 'MAINTENANCE') &&
    (!openVacancy || openVacancy.status === 'OPEN');
  const canOwnerStartVacancy = user?.role === 'OWNER' && vacancyStartEligible;
  const canStaffRequestVacancyStart = user?.role === 'PROPERTY_ADMIN' && vacancyStartEligible;

  return (
    <div className="space-y-6">
      <div className="flex items-center gap-4">
        <button
          onClick={onBack}
          className="p-2 hover:bg-slate-100 rounded-xl transition-colors print:hidden"
        >
          <ArrowLeft className="w-5 h-5 text-slate-600" />
        </button>
        <div>
          <h2 className="text-2xl font-bold text-slate-800">{property.name}</h2>
          <div className="flex items-center gap-2 text-sm text-slate-500 mt-1">
            <MapPin className="w-4 h-4" /> {property.address}
          </div>
        </div>
      </div>

      <div className="bg-white rounded-2xl border border-slate-200 p-5 shadow-sm">
        <div className="flex items-start gap-3">
          <Building2 className="w-8 h-8 text-indigo-600 shrink-0 mt-0.5" />
          <div className="flex-1 min-w-0">
            <p className="text-xs font-bold text-slate-500 uppercase tracking-wider">Expediente operativo del inmueble</p>
            <p className="text-sm text-slate-800 mt-1">
              <span className="font-bold text-slate-600">Estado:</span>{' '}
              {property.status === 'OCCUPIED'
                ? 'Ocupado'
                : property.status === 'AVAILABLE'
                  ? 'Disponible'
                  : property.status || '—'}
              {' · '}
              <span className="font-bold text-slate-600">Arrendatario actual:</span>{' '}
              {activeLease
                ? `${activeLease.tenantName || '—'} (${activeLease.tenantEmail || '—'})`
                : 'Sin contrato ACTIVO'}
              {' · '}
              <span className="font-bold text-slate-600">Vacancia comercial:</span>{' '}
              {openVacancy
                ? `Abierta (${openVacancy.status || 'OPEN'})`
                : 'Sin vacancia abierta (o cerrada por nuevo expediente)'}
            </p>
            {activeLease?.id && hasLeaseDocument(activeLease.documentUrl) && (
              <button
                type="button"
                onClick={() => handleSecureOpen('lease-document', activeLease.id!)}
                className="text-sm font-bold text-violet-700 mt-2 inline-block hover:underline"
              >
                Ver contrato actual (PDF)
              </button>
            )}
            <p className="text-xs text-slate-500 mt-2">
              Movimientos y bitácora: pestaña «Historial operativo». Cobranza y facturas: «Cobranza». Convenios:
              «Convenios». Mantenimiento: tickets ligados a este inmueble en el flujo de mantenimiento.
            </p>

            {(canOwnerStartVacancy || canStaffRequestVacancyStart) && (
              <div className="mt-4 pt-4 border-t border-slate-100 flex flex-wrap items-center gap-3 print:hidden">
                {canOwnerStartVacancy && (
                  <button
                    type="button"
                    onClick={handleStartVacancyOwner}
                    disabled={vacancyStartBusy}
                    className="inline-flex items-center gap-2 bg-sky-600 hover:bg-sky-700 text-white px-4 py-2 rounded-xl text-sm font-semibold transition-all shadow-sm shadow-sky-600/20 active:scale-95 disabled:opacity-60"
                    title="Abrir vacancia y notificar al primer agente inmobiliario según tus prioridades (72h por agente)"
                  >
                    {vacancyStartBusy
                      ? <Loader2 className="w-4 h-4 animate-spin" />
                      : <Megaphone className="w-4 h-4" />}
                    Poner en renta
                  </button>
                )}
                {canStaffRequestVacancyStart && (
                  <button
                    type="button"
                    onClick={() => setRequestVacancyStartOpen(true)}
                    className="inline-flex items-center gap-2 bg-amber-500 hover:bg-amber-600 text-white px-4 py-2 rounded-xl text-sm font-semibold transition-all shadow-sm shadow-amber-500/20 active:scale-95"
                    title="Solicitar al dueño autorizar el arranque de la cadena de agentes (requiere tu contraseña y MFA)"
                  >
                    <Megaphone className="w-4 h-4" />
                    Solicitar poner en renta
                  </button>
                )}
                <span className="text-xs text-slate-500">
                  {canOwnerStartVacancy
                    ? 'Arranca la cadena de agentes inmobiliarios configurada en tus prioridades.'
                    : 'El dueño recibirá tu solicitud en su bandeja y decidirá con reautenticación.'}
                </span>
              </div>
            )}
          </div>
        </div>
      </div>

      <div className="flex flex-wrap gap-2 border-b border-slate-200 pb-2 print:hidden">
        {tabs.map((t) => (
          <button
            key={t.id}
            onClick={() => setTab(t.id)}
            className={`flex items-center gap-2 px-4 py-2 rounded-xl text-sm font-semibold transition-colors ${
              tab === t.id ? 'bg-violet-600 text-white shadow-md' : 'bg-white text-slate-600 border border-slate-200 hover:bg-slate-50'
            }`}
          >
            {t.icon}
            {t.label}
          </button>
        ))}
      </div>

      {tab === 'RESUMEN' && (
        <div className="space-y-6">
          <div className="flex flex-wrap items-end justify-between gap-4 print:hidden">
            <label className="text-xs font-bold text-slate-500 uppercase tracking-wider flex flex-col gap-1">
              Reporte mensual (mes)
              <input
                type="month"
                min={reportBounds?.min}
                max={reportBounds?.max}
                value={reportMonth}
                onChange={(e) => {
                  const v = e.target.value;
                  if (reportBounds && v >= reportBounds.min && v <= reportBounds.max) setReportMonth(v);
                }}
                disabled={!reportBounds}
                className="px-3 py-2 border border-slate-300 rounded-lg text-sm font-semibold text-slate-800 disabled:opacity-50"
              />
              {reportBounds && (
                <span className="text-[10px] font-medium text-slate-400 normal-case">
                  Rango: {reportBounds.min} a {reportBounds.max}
                </span>
              )}
            </label>
            {/* Imprime este expediente usando el diálogo nativo del navegador. El
                usuario elige papel o "Guardar como PDF". La clase `print:hidden`
                oculta navegación y controles en el output impreso. Deuda técnica:
                Paso G (reporting IA) reemplazará esto con un XLSX por inmueble
                generado server-side. */}
            <button
              type="button"
              onClick={() => window.print()}
              className="inline-flex items-center gap-1.5 px-3 py-2 text-xs font-bold bg-slate-900 text-white rounded-lg hover:bg-slate-800"
              title="Imprime o exporta a PDF el reporte del inmueble usando el diálogo nativo del navegador."
            >
              <Printer className="w-3.5 h-3.5" />
              Imprimir / Guardar PDF
            </button>
          </div>

          {monthly && (
            <div className="grid grid-cols-2 md:grid-cols-4 gap-4">
              {(
                [
                  { k: 'Renta esperada', v: money(Number(monthly.expectedRent)), cls: 'text-slate-900' },
                  { k: 'Cobrado', v: money(Number(monthly.collected)), cls: 'text-emerald-600' },
                  { k: 'Pendiente', v: money(Number(monthly.outstanding)), cls: 'text-rose-600' },
                  { k: 'Crédito a favor', v: money(Number(monthly.creditBalance)), cls: 'text-teal-600' },
                  { k: 'Pagos parciales', v: String(monthly.partialPaymentsCount), cls: 'text-slate-900' },
                  {
                    k: 'Convenios activos / incumplidos',
                    v: `${monthly.activeAgreements} / ${monthly.breachedAgreements}`,
                    cls: 'text-slate-900',
                  },
                  { k: 'Diferido', v: money(Number(monthly.deferredAmount)), cls: 'text-slate-900' },
                  { k: 'Archivos nuevos (mes)', v: String(monthly.newFilesCount), cls: 'text-slate-900' },
                ] as const
              ).map((row) => (
                <div key={row.k} className="bg-white rounded-2xl border border-slate-200 p-4 shadow-sm">
                  <p className="text-[10px] font-bold text-slate-400 uppercase mb-1">{row.k}</p>
                  <p className={`text-lg font-extrabold ${row.cls}`}>{row.v}</p>
                </div>
              ))}
            </div>
          )}

          {monthly && monthly.alerts.length > 0 && (
            <div className="rounded-2xl border border-amber-200 bg-amber-50 p-4 text-sm text-amber-900">
              <p className="font-bold mb-2">Alertas del inmueble</p>
              <ul className="list-disc list-inside space-y-1">
                {monthly.alerts.map((a, i) => (
                  <li key={i}>{a}</li>
                ))}
              </ul>
            </div>
          )}

          <div className="bg-white rounded-2xl border border-slate-200 p-5 shadow-sm flex flex-wrap items-center gap-4">
            <LayoutGrid className="w-8 h-8 text-violet-500 shrink-0" />
            <div className="flex-1 min-w-[200px]">
              <p className="text-xs font-bold text-slate-400 uppercase">Reporte anual</p>
              <p className="text-sm text-slate-600">Agregados de cobranza, egresos y actividad por año calendario.</p>
            </div>
            <input
              type="number"
              className="w-28 px-3 py-2 border border-slate-300 rounded-lg text-sm font-bold"
              min={reportBounds?.minYear}
              max={reportBounds?.maxYear}
              value={annualYear}
              onChange={(e) => {
                const n = Number(e.target.value);
                if (!reportBounds) {
                  setAnnualYear(n);
                  return;
                }
                setAnnualYear(Math.min(Math.max(n, reportBounds.minYear), reportBounds.maxYear));
              }}
              disabled={!reportBounds}
            />
            <button
              type="button"
              onClick={loadAnnual}
              className="px-4 py-2 bg-slate-900 text-white rounded-xl text-sm font-bold hover:bg-slate-800"
            >
              Cargar anual
            </button>
          </div>

          {annual && (
            <div className="grid grid-cols-2 md:grid-cols-4 gap-3 text-sm">
              <div className="bg-slate-50 rounded-xl p-4 border border-slate-100">
                <p className="text-xs text-slate-500 font-bold uppercase">Esperado anual</p>
                <p className="text-lg font-extrabold">{money(Number(annual.expectedAnnual))}</p>
              </div>
              <div className="bg-slate-50 rounded-xl p-4 border border-slate-100">
                <p className="text-xs text-slate-500 font-bold uppercase">Cobrado anual</p>
                <p className="text-lg font-extrabold text-emerald-700">{money(Number(annual.collectedAnnual))}</p>
              </div>
              <div className="bg-slate-50 rounded-xl p-4 border border-slate-100">
                <p className="text-xs text-slate-500 font-bold uppercase">Egresos anual</p>
                <p className="text-lg font-extrabold">{money(Number(annual.expensesAnnual))}</p>
              </div>
              <div className="bg-slate-50 rounded-xl p-4 border border-slate-100">
                <p className="text-xs text-slate-500 font-bold uppercase">Vacancia (meses)</p>
                <p className="text-lg font-extrabold">{annual.monthsWithVacancy}</p>
              </div>
            </div>
          )}

          <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
            <div className="bg-white rounded-2xl border border-slate-200 p-5 shadow-sm">
              <p className="text-xs font-semibold text-slate-400 uppercase tracking-wider mb-1">Tipo</p>
              <p className="text-lg font-bold text-slate-800">{typeLabels[property.type || ''] || property.type || '—'}</p>
            </div>
            <div className="bg-white rounded-2xl border border-slate-200 p-5 shadow-sm">
              <p className="text-xs font-semibold text-slate-400 uppercase tracking-wider mb-1">Cuenta Predial</p>
              <p className="text-lg font-bold text-slate-800">{property.predial || '—'}</p>
            </div>
            <div className="bg-white rounded-2xl border border-slate-200 p-5 shadow-sm">
              <p className="text-xs font-semibold text-slate-400 uppercase tracking-wider mb-1">Direccion completa</p>
              <p className="text-sm font-bold text-slate-800 leading-snug">{property.address || '—'}</p>
            </div>
          </div>

          {property.description && (
            <div className="bg-white rounded-2xl border border-slate-200 p-5 shadow-sm">
              <p className="text-xs font-semibold text-slate-400 uppercase tracking-wider mb-2">Descripción</p>
              <p className="text-sm text-slate-700 leading-relaxed">{property.description}</p>
            </div>
          )}
        </div>
      )}

      {tab === 'GALERIA' && (
        <div className="space-y-4">
          <div className="flex justify-end">
            <button
              onClick={() => openUpload('PHOTO')}
              disabled={uploading}
              className="flex items-center gap-2 text-sm font-bold text-white bg-blue-600 hover:bg-blue-700 px-4 py-2 rounded-xl"
            >
              <Upload className="w-4 h-4" /> Subir evidencia / foto
            </button>
          </div>
          <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
            {photos.map((f) => (
              <div key={f.id} className="bg-white rounded-2xl border border-slate-200 p-4 shadow-sm flex flex-col gap-2">
                <div className="flex justify-between items-start gap-2">
                  <div className="min-w-0">
                    <p className="font-bold text-slate-800 truncate">{f.fileName}</p>
                    <p className="text-xs text-slate-500">
                      {(f.uploadedBy || '—') + (f.uploaderRole ? ` · ${f.uploaderRole}` : '')}
                    </p>
                    <p className="text-xs text-slate-400">{f.uploadedAt?.toString?.() || String(f.uploadedAt)}</p>
                    {f.label && (
                      <span className="inline-block mt-1 text-[10px] font-bold uppercase bg-blue-50 text-blue-700 px-2 py-0.5 rounded-md">
                        {f.label}
                      </span>
                    )}
                    {f.note && <p className="text-xs text-slate-600 mt-1">{f.note}</p>}
                  </div>
                  <div className="flex gap-1 shrink-0">
                    <button
                      type="button"
                      onClick={() => handleSecureOpen('property-file', f.id)}
                      className="p-2 text-slate-400 hover:text-blue-600 rounded-lg"
                      title="Ver archivo"
                    >
                      <Download className="w-4 h-4" />
                    </button>
                    <button
                      onClick={() => handleDeleteFile(f)}
                      className="p-2 text-slate-400 hover:text-rose-600 rounded-lg"
                      title={canDeleteDirectly ? 'Eliminar archivo' : 'Solicitar eliminación al dueño'}
                    >
                      <Trash2 className="w-4 h-4" />
                    </button>
                  </div>
                </div>
                <p className="text-[10px] text-slate-400">{((f.sizeBytes || 0) / 1024).toFixed(0)} KB</p>
              </div>
            ))}
          </div>
          {photos.length === 0 && <p className="text-center text-slate-500 py-8">Sin fotos en galería.</p>}

          <h4 className="text-sm font-bold text-slate-500 uppercase tracking-wider mt-8">Planos</h4>
          <div className="flex justify-end mb-2">
            <button
              onClick={() => openUpload('PLAN')}
              disabled={uploading}
              className="flex items-center gap-2 text-sm font-bold text-emerald-700 border border-emerald-200 bg-emerald-50 px-4 py-2 rounded-xl"
            >
              <Upload className="w-4 h-4" /> Subir plano
            </button>
          </div>
          <div className="space-y-2">
            {plans.map((f) => (
              <div key={f.id} className="flex items-center justify-between bg-slate-50 rounded-xl px-4 py-2 border border-slate-100">
                <span className="text-sm text-slate-700 truncate">{f.fileName}</span>
                <div className="flex gap-1">
                  <button
                    type="button"
                    onClick={() => handleSecureOpen('property-file', f.id)}
                    className="p-1.5 text-slate-400 hover:text-emerald-600"
                    title="Ver plano"
                  >
                    <Download className="w-4 h-4" />
                  </button>
                  <button
                    onClick={() => handleDeleteFile(f)}
                    className="p-1.5 text-slate-400 hover:text-rose-600"
                    title={canDeleteDirectly ? 'Eliminar archivo' : 'Solicitar eliminación al dueño'}
                  >
                    <Trash2 className="w-4 h-4" />
                  </button>
                </div>
              </div>
            ))}
          </div>
        </div>
      )}

      {tab === 'TIMELINE' && (
        <div className="space-y-3">
          <p className="text-xs text-slate-500 bg-slate-50 border border-slate-200 rounded-xl px-4 py-2">
            Fuente principal del historial del inmueble (cobranza con liquidacion, mantenimiento, vacancia, convenios,
            archivos). Las graficas de recibos por mes estan en metricas del inmueble.
          </p>
          {timeline.length === 0 ? (
            <p className="text-slate-500 text-sm">No hay eventos registrados.</p>
          ) : (
            timeline.map((m) => (
              <div key={m.id} className="bg-white border border-slate-200 rounded-2xl p-4 shadow-sm">
                <div className="flex justify-between gap-2">
                  <p className="font-bold text-slate-800">{m.title}</p>
                  <span className="text-[10px] font-mono text-slate-400 shrink-0">{m.eventType}</span>
                </div>
                {m.description && <p className="text-sm text-slate-600 mt-1">{m.description}</p>}
                <p className="text-xs text-slate-400 mt-2">
                  {m.occurredAt?.toString?.() || String(m.occurredAt)}
                  {m.actorRole && ` · ${m.actorRole}`}
                </p>
              </div>
            ))
          )}
        </div>
      )}

      {tab === 'COBRANZA' && (
        <div className="space-y-6">
          <div className="bg-white rounded-2xl border border-slate-200 overflow-hidden shadow-sm">
            <div className="px-5 py-3 border-b border-slate-100 bg-slate-50">
              <h4 className="text-sm font-bold text-slate-800">Facturas del inmueble</h4>
            </div>
            <div className="overflow-x-auto">
              <table className="w-full text-sm">
                <thead className="bg-slate-50 text-xs uppercase text-slate-500">
                  <tr>
                    <th className="p-3 text-left font-bold">Mes</th>
                    <th className="p-3 text-left font-bold">Inquilino</th>
                    <th className="p-3 text-right font-bold">Total</th>
                    <th className="p-3 text-right font-bold">Pagado</th>
                    <th className="p-3 text-right font-bold">Pendiente</th>
                    <th className="p-3 text-center font-bold">Liquidación</th>
                    <th className="p-3 text-center font-bold">Estado</th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-slate-100">
                  {invoices.map((inv) => (
                    <tr key={inv.id} className="hover:bg-slate-50/80">
                      <td className="p-3 font-bold text-slate-800">{inv.monthYear}</td>
                      <td className="p-3">
                        <div className="font-semibold text-slate-700">{inv.tenantName}</div>
                        <div className="text-xs text-slate-400">{inv.tenantEmail}</div>
                      </td>
                      <td className="p-3 text-right font-bold">${inv.totalAmount.toLocaleString('en-US')}</td>
                      <td className="p-3 text-right text-emerald-600 font-bold">${(inv.paidAmount || 0).toLocaleString('en-US')}</td>
                      <td className="p-3 text-right text-rose-600 font-bold">${(inv.outstandingAmount || 0).toLocaleString('en-US')}</td>
                      <td className="p-3 text-center text-xs font-bold text-slate-600">{inv.settlementStatus}</td>
                      <td className="p-3 text-center text-xs font-bold">{inv.status}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            {invoices.length === 0 && <p className="p-8 text-center text-slate-500">Sin facturas vinculadas a este inmueble.</p>}
          </div>

          {/* V65 — Historial de comprobantes del inmueble. Solo muestra estados
              terminales (validado, rechazado, expirado). El panel de pendientes
              sigue estando en la Bandeja de decisiones del dueño. */}
          <div className="bg-white rounded-2xl border border-slate-200 overflow-hidden shadow-sm">
            <div className="px-5 py-3 border-b border-slate-100 bg-slate-50">
              <h4 className="text-sm font-bold text-slate-800">
                Historial de comprobantes de pago <span className="text-slate-400 font-medium">({proofsHistory.length})</span>
              </h4>
              <p className="text-xs text-slate-500">
                Comprobantes SPEI y efectivo de los inquilinos de este inmueble, ya validados, rechazados o expirados.
              </p>
            </div>
            {proofsHistory.length === 0 ? (
              <p className="p-8 text-center text-slate-500 text-sm">
                Sin comprobantes terminados para este inmueble todavía.
              </p>
            ) : (
              <div className="p-4 space-y-2">
                {proofsHistory.map(p => (
                  <HistoryProofRow key={p.id} proof={p} onView={() => setProofDetail(p)} />
                ))}
              </div>
            )}
          </div>

          <div className="bg-white rounded-2xl border border-slate-200 overflow-hidden shadow-sm">
            <div className="px-5 py-3 border-b border-slate-100 bg-slate-50">
              <h4 className="text-sm font-bold text-slate-800">
                Archivos archivados de cobranza <span className="text-slate-400 font-medium">({archivedPaymentFiles.length})</span>
              </h4>
              <p className="text-xs text-slate-500">
                Comprobantes originales y CEP oficiales Banxico archivados automáticamente en el expediente del inmueble.
              </p>
            </div>
            {archivedPaymentFiles.length === 0 ? (
              <p className="p-8 text-center text-slate-500 text-sm">
                Todavía no hay comprobantes archivados en este inmueble.
              </p>
            ) : (
              <div className="p-4 space-y-2">
                {archivedPaymentFiles.map((file) => (
                  <div key={file.id} className="flex items-center justify-between gap-3 rounded-xl border border-slate-200 px-4 py-3">
                    <div className="min-w-0">
                      <p className="text-sm font-semibold text-slate-800 truncate">{file.fileName}</p>
                      {file.note && <p className="text-xs text-slate-500 mt-0.5">{file.note}</p>}
                    </div>
                    <button
                      type="button"
                      onClick={() => handleSecureOpen('property-file', file.id)}
                      className="inline-flex items-center gap-2 rounded-lg border border-slate-200 bg-white px-3 py-2 text-xs font-bold text-slate-700 hover:bg-slate-50"
                    >
                      <Download className="w-4 h-4" /> Abrir archivo
                    </button>
                  </div>
                ))}
              </div>
            )}
          </div>

          {proofDetail && (
            <ProofDetailModal proof={proofDetail} onClose={() => setProofDetail(null)} />
          )}
        </div>
      )}

      {tab === 'CONVENIOS' && (
        <div className="space-y-3">
          {propertyAgreements.length === 0 ? (
            <p className="text-slate-500 text-sm">No hay convenios asociados a facturas de este inmueble.</p>
          ) : (
            propertyAgreements.map((ag) => {
              // Mapa semántico de colores: los estados REQUESTED/APPROVED/ACTIVE necesitan que
              // el dueño los valide para ser vinculantes; CANCELLED/REJECTED/COMPLETED son
              // terminales. Si el SPEI o la evidencia no fueron validados, el convenio se
              // queda en REQUESTED (el operador debe aprobarlo explícitamente) y nunca salta
              // solo a ACTIVE. Los convenios cuya factura asociada haya sido anulada por baja
              // del inquilino pasan a CANCELLED vía self-heal en getAllAgreements.
              const statusStyles: Record<string, string> = {
                REQUESTED: 'bg-amber-50 text-amber-700',
                APPROVED: 'bg-blue-50 text-blue-700',
                ACTIVE: 'bg-emerald-50 text-emerald-700',
                COMPLETED: 'bg-slate-100 text-slate-700',
                BREACHED: 'bg-rose-50 text-rose-700',
                REJECTED: 'bg-slate-100 text-slate-500',
                CANCELLED: 'bg-slate-100 text-slate-500',
              };
              const badgeClass = statusStyles[ag.status] || 'bg-violet-50 text-violet-700';
              return (
                <div key={ag.id} className="bg-white border border-slate-200 rounded-2xl p-4 shadow-sm space-y-3">
                  <div className="flex justify-between items-start gap-3">
                    <div>
                      <p className="font-bold text-slate-800">{ag.monthYear || 'Periodo'}</p>
                      {ag.tenantName && (
                        <p className="text-xs text-slate-500 font-semibold">{ag.tenantName}</p>
                      )}
                    </div>
                    <span className={`text-xs font-bold px-2 py-1 rounded-lg ${badgeClass}`}>{ag.status}</span>
                  </div>

                  <p className="text-sm text-slate-600">
                    Solicitado: ${ag.requestedAmount.toLocaleString('en-US')}
                    {ag.approvedAmount != null && (
                      <span className="text-emerald-600 font-bold ml-3">Aprobado: ${ag.approvedAmount.toLocaleString('en-US')}</span>
                    )}
                    {ag.deferredAmount != null && ag.deferredAmount > 0 && (
                      <span className="text-amber-600 font-bold ml-3">Diferido: ${ag.deferredAmount.toLocaleString('en-US')}</span>
                    )}
                  </p>

                  {/* Razón: por qué el inquilino solicitó el convenio (categoría/resumen corto). */}
                  {ag.reason && (
                    <div className="bg-slate-50 border border-slate-200 rounded-lg p-3">
                      <p className="text-[11px] font-bold text-slate-500 uppercase tracking-wide">Razón del convenio</p>
                      <p className="text-sm text-slate-800 font-semibold mt-1">{ag.reason}</p>
                    </div>
                  )}

                  {/* Descripción / compromiso escrito. */}
                  {ag.description && (
                    <div>
                      <p className="text-[11px] font-bold text-slate-500 uppercase tracking-wide">Compromiso / detalle</p>
                      <p className="text-sm text-slate-700 whitespace-pre-line mt-1">{ag.description}</p>
                    </div>
                  )}

                  {/* Motivo de rechazo / cancelación automática (cuando aplica). */}
                  {ag.rejectionReason && (ag.status === 'REJECTED' || ag.status === 'CANCELLED') && (
                    <div className="bg-rose-50 border border-rose-100 rounded-lg p-3">
                      <p className="text-[11px] font-bold text-rose-600 uppercase tracking-wide">
                        {ag.status === 'REJECTED' ? 'Motivo de rechazo' : 'Motivo de cancelación'}
                      </p>
                      <p className="text-sm text-rose-800 font-semibold mt-1">{ag.rejectionReason}</p>
                    </div>
                  )}

                  {/* Evidencia PDF/imagen (opcional): el convenio es válido aun sin PDF, pero si
                      hay archivo firmado lo exponemos como expediente del compromiso. */}
                  <div className="flex items-center gap-3 pt-1">
                    {ag.evidenceFileUrl && ag.id ? (
                      <button
                        type="button"
                        onClick={() => handleSecureOpen('agreement-evidence', ag.id!)}
                        className="inline-flex items-center gap-1 text-xs text-blue-600 font-bold hover:underline"
                      >
                        Ver evidencia (PDF/imagen)
                      </button>
                    ) : (
                      <span className="text-xs text-slate-400 font-semibold italic">
                        Sin archivo firmado adjunto
                      </span>
                    )}
                    {ag.createdAt && (
                      <span className="text-[11px] text-slate-400 ml-auto">
                        Creado: {new Date(ag.createdAt).toLocaleString('es-MX', { timeZone: 'America/Mexico_City' })}
                      </span>
                    )}
                  </div>
                </div>
              );
            })
          )}
        </div>
      )}

      <input ref={fileInputRef} type="file" className="hidden" accept="image/*,application/pdf" onChange={onFileSelected} />

      {uploadModal && (
        <div className="fixed inset-0 z-[100] flex items-center justify-center p-4">
          <div className="absolute inset-0 bg-slate-900/60" onClick={() => !uploading && setUploadModal(null)} />
          <div className="relative bg-white rounded-2xl shadow-2xl border border-slate-200 max-w-md w-full p-6 space-y-4">
            <h3 className="text-lg font-bold text-slate-800">Subir archivo ({uploadModal.category})</h3>
            <div>
              <label className="text-xs font-bold text-slate-500 uppercase">Etiqueta</label>
              <select
                className="mt-1 w-full border border-slate-300 rounded-lg px-3 py-2 text-sm font-semibold"
                value={uploadLabel}
                onChange={(e) => setUploadLabel(e.target.value)}
              >
                {FILE_LABELS.map((l) => (
                  <option key={l} value={l}>
                    {l}
                  </option>
                ))}
              </select>
            </div>
            <div>
              <label className="text-xs font-bold text-slate-500 uppercase">Nota (opcional)</label>
              <textarea
                className="mt-1 w-full border border-slate-300 rounded-lg px-3 py-2 text-sm"
                rows={2}
                value={uploadNote}
                onChange={(e) => setUploadNote(e.target.value)}
              />
            </div>
            <div className="flex gap-2 justify-end">
              <button type="button" disabled={uploading} onClick={() => setUploadModal(null)} className="px-4 py-2 text-sm font-semibold text-slate-500">
                Cancelar
              </button>
              <button
                type="button"
                disabled={uploading}
                onClick={triggerFilePick}
                className="px-4 py-2 rounded-xl bg-violet-600 text-white text-sm font-bold"
              >
                {uploading ? 'Subiendo…' : 'Elegir archivo'}
              </button>
            </div>
          </div>
        </div>
      )}

      {/* File deletion — reauth path for OWNER / SUPER_ADMIN. Uses the property-delete
          modal but with file-specific copy (hard delete, not soft). */}
      <ReauthDeleteModal
        isOpen={deleteFileTarget !== null}
        onClose={() => setDeleteFileTarget(null)}
        onConfirm={confirmDirectFileDelete}
        title="Eliminar archivo"
        subtitle="Confirma con contraseña y MFA"
        confirmLabel="Eliminar archivo"
        warningMessage={
          deleteFileTarget ? (
            <span>
              Estás a punto de eliminar <strong>"{deleteFileTarget.fileName}"</strong> ({deleteFileTarget.category}).
              Esta acción borra el archivo físico y <strong>no se puede deshacer</strong>.
            </span>
          ) : null
        }
      />

      {/* File deletion — approval path for staff. Creates a PROPERTY_FILE_DELETE_REQUESTED
          ActionTask so the owner can approve/reject from the inbox. */}
      <StaffApprovalRequestModal
        isOpen={requestDeleteFileTarget !== null}
        onClose={() => setRequestDeleteFileTarget(null)}
        onConfirm={confirmRequestFileDelete}
        action="PROPERTY_FILE_DELETE"
        resourceLabel={
          requestDeleteFileTarget
            ? `${requestDeleteFileTarget.fileName} (${requestDeleteFileTarget.category})`
            : ''
        }
      />

      {/* V52 — Poner en renta: staff con VACANCY_START_CHAIN solicita al dueño. */}
      <StaffApprovalRequestModal
        isOpen={requestVacancyStartOpen}
        onClose={() => setRequestVacancyStartOpen(false)}
        onConfirm={confirmRequestVacancyStart}
        action="VACANCY_START"
        resourceLabel={property.name}
      />

      {vacancyStartFeedback && (
        <div className="fixed bottom-6 right-6 z-[110] bg-emerald-600 text-white px-5 py-3 rounded-xl shadow-lg text-sm font-semibold max-w-md">
          {vacancyStartFeedback}
        </div>
      )}

    </div>
  );
};
