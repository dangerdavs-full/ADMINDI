import { useEffect, useState } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { useAuth } from '../context/AuthContext';
import { LogOut, Home, Navigation, DollarSign, CreditCard, ShieldCheck, FileText, AlertCircle, Clock, Upload, ArrowRight, CheckCircle2, Handshake, Building2, ChevronDown, ArrowRightLeft, Bell, X, Sparkles } from 'lucide-react';
import { contextService, ContextOption } from '../services/contextService';
import { tenantExpedienteService, TenantExpedienteSummary, TenantExpedienteListItem } from '../services/tenantExpedienteService';
import { ledgerService, InvoiceDTO, ShortfallReason } from '../services/ledgerService';
import { paymentService } from '../services/paymentService';
import { agreementService, PaymentAgreementDTO } from '../services/agreementService';
import { openSecureFile, describeSecureFileError } from '../services/secureFileService';
import { NotificationPreferencesPanel } from './NotificationPreferencesPanel';
import NotificationHistoryPanel from './NotificationHistoryPanel';
import { TenantMaintenancePanel } from './TenantMaintenancePanel';
import { TenantPaymentsHistory } from '../components/TenantPaymentsHistory';
import {
  banxicoInstitutionService,
  BanxicoInstitution,
  resolveBanxicoInstitutionName,
} from '../services/banxicoInstitutionService';

type PayModal = { invoiceId: string; monthYear: string; totalAmount: number } | null;

export const TenantDashboardLayout: React.FC = () => {
  const navigate = useNavigate();
  const [searchParams, setSearchParams] = useSearchParams();
  const { user, logout, switchContext } = useAuth();

  const handleLogout = async () => {
    await logout();
    navigate('/login');
  };
  const [expedientes, setExpedientes] = useState<TenantExpedienteListItem[]>([]);
  const [selectedProfileId, setSelectedProfileId] = useState<string | null>(null);
  const [summary, setSummary] = useState<TenantExpedienteSummary | null>(null);
  const [invoices, setInvoices] = useState<InvoiceDTO[]>([]);
  const [loading, setLoading] = useState(true);
  const [contexts, setContexts] = useState<ContextOption[]>([]);
  const [ctxDropdown, setCtxDropdown] = useState(false);
  const [switchingCtx, setSwitchingCtx] = useState(false);
  const [contentKey, setContentKey] = useState(0);

  // Payment modal
  const [payModal, setPayModal] = useState<PayModal>(null);
  const [payMethod, setPayMethod] = useState<'MP' | 'SPEI' | 'CASH' | null>(null);
  const [mpLoading, setMpLoading] = useState(false);
  const [mpOwnerConnected, setMpOwnerConnected] = useState<boolean | null>(null);
  const [mpAmountMode, setMpAmountMode] = useState<'full' | 'custom'>('full');
  const [mpCustomAmount, setMpCustomAmount] = useState('');

  // SPEI upload
  // V58 — El inquilino ya NO captura cuenta receptora: la CLABE viene del dueño.
  const [speiData, setSpeiData] = useState({ claveRastreo: '', bankEmitter: '', amount: '', transferDate: '' });
  const [emitterBanks, setEmitterBanks] = useState<BanxicoInstitution[]>([]);
  const [speiFile, setSpeiFile] = useState<File | null>(null);
  const [ocrLoading, setOcrLoading] = useState(false);
  const [ocrMessage, setOcrMessage] = useState<string | null>(null);
  const [ocrConfidence, setOcrConfidence] = useState<number | null>(null);
  // V58 — Rastrea si el user utilizó el botón "Foto + IA" en este flujo, para
  // enviar captureMethod='AI_OCR' al backend y aplicar límite 6/mes.
  const [usedOcr, setUsedOcr] = useState(false);
  // V57 — pago en efectivo
  const [cashFile, setCashFile] = useState<File | null>(null);
  const [cashAmount, setCashAmount] = useState('');
  const [cashNote, setCashNote] = useState('');
  const [cashLoading, setCashLoading] = useState(false);
  const [cashResult, setCashResult] = useState<string | null>(null);
  // V57 — mensaje detallado de validación Banxico (15-20s)
  const [banxicoValidating, setBanxicoValidating] = useState(false);
  const [attemptsLeft, setAttemptsLeft] = useState<number | null>(null);
  const [forceManual, setForceManual] = useState(false);
  // V58.1 — tipo de resultado para el modal: success | pending | error
  type ResultKind = 'success' | 'pending' | 'error';
  const [resultKind, setResultKind] = useState<ResultKind>('success');
  const [speiLoading, setSpeiLoading] = useState(false);
  const [speiResult, setSpeiResult] = useState<string | null>(null);

  // Agreements
  const [agreements, setAgreements] = useState<PaymentAgreementDTO[]>([]);
  const [agModal, setAgModal] = useState<{ invoiceId: string; monthYear: string; outstanding: number } | null>(null);
  const [agForm, setAgForm] = useState({ requestedAmount: '', reason: '', description: '' });
  const [agLoading, setAgLoading] = useState(false);

  const [shortfallModal, setShortfallModal] = useState<InvoiceDTO | null>(null);
  const [shortfallForm, setShortfallForm] = useState<{
    shortfallReason: ShortfallReason;
    shortfallDescription: string;
    promisedCompletionDate: string;
  }>({ shortfallReason: 'OTHER', shortfallDescription: '', promisedCompletionDate: '' });
  const [shortfallLoading, setShortfallLoading] = useState(false);

  // Modal de preferencias de notificación (canales/eventos) — todos los usuarios autenticados pueden abrirlo.
  const [prefsOpen, setPrefsOpen] = useState(false);
  // Sub-tab del modal de notificaciones. Preferencias o historial (C6).
  const [prefsTab, setPrefsTab] = useState<'PREFS' | 'HISTORY'>('PREFS');

  const loadData = () => {
    setLoading(true);
    tenantExpedienteService
      .getExpedientes()
      .then((ex) => {
        setExpedientes(ex);
        let sid = localStorage.getItem('activeTenantProfileId');
        if (!sid || !ex.some((e) => e.tenantProfileId === sid)) {
          sid = ex.length === 1 ? ex[0].tenantProfileId : null;
        }
        setSelectedProfileId(sid);
        if (!sid) {
          setSummary(null);
          setInvoices([]);
          setAgreements([]);
          return Promise.resolve();
        }
        localStorage.setItem('activeTenantProfileId', sid);
        return Promise.all([
          tenantExpedienteService.getSummary(sid),
          ledgerService.getTenantInvoices(sid),
          agreementService.getMyAgreements(sid),
        ]).then(([sum, invoiceData, agData]) => {
          setSummary(sum);
          setInvoices(invoiceData);
          setAgreements(agData);
        });
      })
      .catch(console.error)
      .finally(() => setLoading(false));
  };

  useEffect(() => {
    contextService.getContexts().then(setContexts).catch(() => setContexts([]));
  }, []);

  useEffect(() => {
    banxicoInstitutionService.getCatalog()
      .then((catalog) => setEmitterBanks(catalog.emitters || []))
      .catch(() => setEmitterBanks([]));
  }, []);

  useEffect(() => { loadData(); }, [contentKey]);

  useEffect(() => {
    if (!payModal?.invoiceId) {
      setMpOwnerConnected(null);
      return;
    }
    paymentService.getMPCheckoutStatus(payModal.invoiceId)
      .then((s) => setMpOwnerConnected(s.canPayWithMp))
      .catch(() => setMpOwnerConnected(false));
  }, [payModal?.invoiceId]);

  /** Vuelta desde Checkout Pro: ?mp=success&invoiceId=... */
  useEffect(() => {
    const mp = searchParams.get('mp');
    const invoiceId = searchParams.get('invoiceId');
    if (!mp || !invoiceId) return;

    const done = () => {
      searchParams.delete('mp');
      searchParams.delete('invoiceId');
      setSearchParams(searchParams, { replace: true });
    };

    if (mp === 'success' || mp === 'pending') {
      paymentService.getMPPaymentStatus(invoiceId)
        .then((st) => {
          if (st.paid === 'true') {
            alert(mp === 'pending'
              ? 'Pago registrado. Mercado Pago lo confirmó en la plataforma.'
              : '¡Pago confirmado! Tu renta quedó registrada.');
            setContentKey((k) => k + 1);
          } else if (st.invoiceStatus === 'PARTIALLY_PAID') {
            alert('¡Abono registrado! Aún tienes saldo pendiente en esta renta. Puedes completar el pago cuando quieras.');
            setContentKey((k) => k + 1);
          } else if (mp === 'pending') {
            alert('Pago pendiente en Mercado Pago. Te avisaremos cuando se confirme.');
          } else {
            alert('El pago aún no aparece confirmado. Si ya pagaste, espera unos minutos y recarga.');
          }
        })
        .catch(() => {
          alert('No pudimos verificar el pago. Recarga la página en un momento.');
        })
        .finally(done);
    } else if (mp === 'failure') {
      alert('El pago no se completó en Mercado Pago. Puedes intentar de nuevo.');
      done();
    }
  }, [searchParams, setSearchParams]);

  const handleSwitchCtx = async (ctxId: string) => {
    if (ctxId === user?.contextId || switchingCtx) return;
    setSwitchingCtx(true);
    setCtxDropdown(false);
    try {
      localStorage.removeItem('activeTenantProfileId');
      await switchContext(ctxId);
      setContentKey((k) => k + 1);
    } catch (e) {
      console.error(e);
    } finally {
      setSwitchingCtx(false);
    }
  };

  const handleSelectExpediente = (profileId: string) => {
    if (profileId === selectedProfileId) return;
    localStorage.setItem('activeTenantProfileId', profileId);
    setSelectedProfileId(profileId);
    setContentKey((k) => k + 1);
  };

  const activeCtxName = contexts.find((c) => c.id === user?.contextId)?.name
    || (contexts.length === 1 ? contexts[0]?.name : null);
  const hasMultiCtx = contexts.length > 1;
  const hasMultiExpediente = expedientes.length > 1;

  const resetMpAmount = () => {
    setMpAmountMode('full');
    setMpCustomAmount('');
  };

  const handlePayMP = async (invoiceId: string, outstanding: number) => {
    let payAmount: number | undefined;
    if (mpAmountMode === 'custom') {
      const v = Number(mpCustomAmount);
      if (!mpCustomAmount || Number.isNaN(v) || v <= 0) {
        alert('Indica el monto a abonar.');
        return;
      }
      if (v < 1) {
        alert('El monto mínimo a abonar es $1.00 MXN.');
        return;
      }
      if (v > outstanding) {
        alert(`El monto no puede superar el pendiente ($${outstanding.toLocaleString('en-US', { minimumFractionDigits: 2 })} MXN).`);
        return;
      }
      payAmount = v;
    }

    setMpLoading(true);
    try {
      const profileId = selectedProfileId
        || localStorage.getItem('activeTenantProfileId')
        || undefined;
      const pref = await paymentService.createMPPreference(
        invoiceId,
        user?.email,
        profileId ?? undefined,
        payAmount,
      );
      const url = pref.checkoutUrl;
      if (!url) {
        alert('Mercado Pago no devolvió enlace de pago.');
        return;
      }
      // Nueva pestaña: aísla cookies Safari y evita bucle si MP redirige varias veces.
      const opened = window.open(url, '_blank', 'noopener,noreferrer');
      if (!opened) {
        window.location.href = url;
      }
    } catch (e: any) {
      alert(e.response?.data?.message || 'Error al crear preferencia de pago.');
    } finally {
      setMpLoading(false);
    }
  };

  const handleSPEISubmit = async (invoiceId: string) => {
    if (!speiData.claveRastreo && !speiFile) {
      alert('Ingresa al menos la clave de rastreo o un comprobante.');
      return;
    }
    if (!speiData.bankEmitter) {
      alert('Selecciona el banco emisor desde el catálogo Banxico.');
      return;
    }
    setSpeiLoading(true);
    // V57 — mostramos spinner explícito de "Validando con Banxico" porque la
    // consulta CEP real tarda 15-20 segundos. Mensaje claro para que el
    // inquilino no crea que se colgó.
    setBanxicoValidating(true);
    setSpeiResult(null);
    try {
      const result = await paymentService.submitTransferProof(invoiceId, {
        claveRastreo: speiData.claveRastreo || undefined,
        bankEmitter: speiData.bankEmitter || undefined,
        amount: speiData.amount ? Number(speiData.amount) : undefined,
        transferDate: speiData.transferDate || undefined,
        // V58 — si el user usó el botón de IA marcamos AI_OCR, si no MANUAL
        captureMethod: usedOcr ? 'AI_OCR' : 'MANUAL',
      }, speiFile || undefined);

      // V57/V58 — contador de intentos restantes (solo aplica a AI_OCR; MANUAL es ilimitado)
      if (typeof result.attemptsRemaining === 'number') {
        setAttemptsLeft(result.attemptsRemaining);
      }

      if (result.status === 'VALIDATED') {
        setResultKind('success');
        setSpeiResult('¡Muchas gracias! Ya validamos tu transferencia con Banxico. Tu arrendador ha sido notificado automáticamente.');
        setTimeout(() => { setPayModal(null); setPayMethod(null); setSpeiResult(null); resetSpei(); loadData(); }, 4000);
      } else if (result.status === 'PENDING_OWNER_VALIDATION') {
        // V58 — Banxico no disponible o dueño sin CLABE: validación manual del dueño
        setResultKind('pending');
        const notes = result.ownerValidationNotes || '';
        const reason = notes.includes('Banxico no disponible')
          ? 'No pudimos validar con Banxico en este momento (servicio intermitente). '
          : notes.includes('sin CLABE')
          ? 'Tu arrendador aún no ha configurado su CLABE para recibir transferencias. '
          : '';
        setSpeiResult(`${reason}Tu comprobante quedó guardado y tu arrendador tiene 120 horas para validarlo manualmente. Te notificaremos cuando lo haga.`);
        setTimeout(() => { setPayModal(null); setPayMethod(null); setSpeiResult(null); resetSpei(); loadData(); }, 6000);
      } else if (result.status === 'INCOMPLETE_DATA') {
        setResultKind('pending');
        setSpeiResult('Recibimos tu comprobante, pero Banxico necesita algunos datos para confirmar. Completa los campos faltantes (clave de rastreo, monto, fecha, etc.) y reintentamos automáticamente sin consumir intento.');
      } else if (result.status === 'REJECTED_BY_CEP') {
        setResultKind('error');
        const rem = result.attemptsRemaining ?? Infinity;
        if (usedOcr && rem !== Infinity && rem <= 0) {
          setSpeiResult('Llegaste al límite de 6 validaciones con foto este mes. Sigue con captura manual (sin límite): clave de rastreo, banco emisor, monto y fecha.');
          setForceManual(true);
        } else if (usedOcr && rem !== Infinity) {
          setSpeiResult(`Banxico no pudo validar esta transferencia. Te quedan ${rem} intento${rem === 1 ? '' : 's'} con foto este mes, o captura los datos manualmente sin límite.`);
        } else {
          setSpeiResult('Banxico rechazó la validación. Verifica los datos (la cuenta receptora debe ser del dueño) y vuelve a intentar.');
        }
      } else {
        setResultKind('pending');
        setSpeiResult('Comprobante enviado. Validación automática en proceso.');
      }
    } catch (e: any) {
      const msg = e.response?.data?.message || e.response?.data?.error || e.message || 'Error al enviar comprobante.';
      if (typeof msg === 'string' && msg.toLowerCase().includes('6 validaciones')) {
        // Límite mensual AI_OCR alcanzado → forzar manual
        setSpeiResult(msg);
        setForceManual(true);
      } else if (typeof msg === 'string' && msg.toLowerCase().includes('no corresponde al')) {
        // Cuenta receptora no coincide con el dueño
        setSpeiResult(msg);
      } else {
        alert(msg);
      }
    } finally {
      setSpeiLoading(false);
      setBanxicoValidating(false);
    }
  };

  // V57 — handler para pago en EFECTIVO
  const handleCashSubmit = async (invoiceId: string) => {
    if (!cashFile) {
      alert('Adjunta la foto o PDF del comprobante de pago en efectivo.');
      return;
    }
    if (!cashAmount || Number(cashAmount) <= 0) {
      alert('Indica el monto del pago.');
      return;
    }
    setCashLoading(true);
    setCashResult(null);
    try {
      const result = await paymentService.submitCashProof(
        invoiceId,
        Number(cashAmount),
        cashFile,
        cashNote || undefined
      );
      if (result.status === 'PENDING_OWNER_VALIDATION') {
        setResultKind('pending');
        const h = result.hoursRemaining ?? 120;
        setCashResult(`¡Listo! Tu arrendador recibió una notificación. Tiene ${h} horas para revisar el comprobante y validar el pago. Te avisaremos en cuanto lo apruebe.`);
        setTimeout(() => { setPayModal(null); setPayMethod(null); setCashResult(null); resetCash(); loadData(); }, 5000);
      } else {
        setResultKind('pending');
        setCashResult('Comprobante enviado correctamente. El arrendador lo revisará pronto.');
        // V58.1 — cerrar modal para evitar doble envío si el status no es el esperado
        setTimeout(() => { setPayModal(null); setPayMethod(null); setCashResult(null); resetCash(); loadData(); }, 4000);
      }
    } catch (e: any) {
      const msg = e.response?.data?.message || e.response?.data?.error || e.message || 'Error al enviar comprobante.';
      alert(msg);
    } finally {
      setCashLoading(false);
    }
  };

  const resetCash = () => {
    setCashFile(null);
    setCashAmount('');
    setCashNote('');
    setCashResult(null);
  };

  const resetSpei = () => {
    setSpeiData({ claveRastreo: '', bankEmitter: '', amount: '', transferDate: '' });
    setSpeiFile(null);
    setSpeiResult(null);
    setOcrMessage(null);
    setOcrConfidence(null);
    setBanxicoValidating(false);
    setAttemptsLeft(null);
    setForceManual(false);
    setUsedOcr(false);
  };

  /**
   * V56 — sube la foto al endpoint OCR, rellena los campos detectados y deja
   * que el usuario confirme/corrija antes de enviar al flujo CEP.
   *
   * Nunca sobrescribe un campo que el usuario ya capturó manualmente — solo
   * rellena los vacíos. Esto protege si el OCR lee algo mal y el usuario ya
   * corrigió a mano.
   */
  const handleOcrExtraction = async (file: File) => {
    setOcrLoading(true);
    setOcrMessage(null);
    setOcrConfidence(null);
    try {
      const result = await paymentService.extractReceiptWithAi(file);
      setSpeiFile(file);
      if (!result.ok) {
        // V58.1 — NO marcar usedOcr si el OCR falló. Si el user luego envía con
        // captura manual, no debe consumir su cuota mensual AI.
        setUsedOcr(false);
        setOcrMessage(result.errorMessage || 'No pude leer el comprobante. Captura los datos manualmente.');
        return;
      }
      // OCR exitoso → AI_OCR. El submit consumirá 1 de los 6 intentos del mes.
      setUsedOcr(true);
      setOcrConfidence(result.confidence);
      // V58 — ya NO pedimos accountReceiver al inquilino (viene del dueño)
      setSpeiData(prev => ({
        claveRastreo: prev.claveRastreo || result.claveRastreo || '',
        bankEmitter: prev.bankEmitter || resolveBanxicoInstitutionName(result.bankEmitter, emitterBanks) || '',
        amount: prev.amount || result.amount || '',
        transferDate: prev.transferDate || result.transferDate || '',
      }));
      if (result.confidence < 0.7) {
        setOcrMessage('Extraje los datos pero con confianza baja — revísalos antes de enviar.');
      } else {
        setOcrMessage('Datos extraídos con IA. Revisa y confirma antes de enviar.');
      }
    } catch (e: any) {
      setOcrMessage(e.response?.data?.message || 'Error al extraer datos del comprobante.');
    } finally {
      setOcrLoading(false);
    }
  };

  const statusLabel = (s: string) => {
    switch (s) {
      case 'PAID': return { text: 'PAGADO', cls: 'bg-emerald-100 text-emerald-700 border-emerald-200' };
      case 'LATE': return { text: 'VENCIDO', cls: 'bg-rose-100 text-rose-700 border-rose-200' };
      case 'PARTIALLY_PAID': return { text: 'PAGO PARCIAL', cls: 'bg-orange-100 text-orange-700 border-orange-200' };
      default: return { text: 'PENDIENTE', cls: 'bg-amber-100 text-amber-800 border-amber-200' };
    }
  };

  const inputCls = "w-full text-sm p-2.5 border border-slate-300 rounded-lg focus:ring-2 focus:ring-teal-500 outline-none transition-all";

  const needsShortfall = (inv: InvoiceDTO) =>
    inv.settlementStatus === 'PARTIALLY_PAID' &&
    (inv.outstandingAmount || 0) > 0 &&
    !inv.shortfallReason;

  const handleSubmitShortfall = async () => {
    if (!shortfallModal) return;
    setShortfallLoading(true);
    try {
      const res = await ledgerService.submitShortfallReason(shortfallModal.id, {
        shortfallReason: shortfallForm.shortfallReason,
        shortfallDescription: shortfallForm.shortfallDescription || undefined,
        promisedCompletionDate: shortfallForm.promisedCompletionDate || undefined,
      });
      setShortfallModal(null);
      setShortfallForm({ shortfallReason: 'OTHER', shortfallDescription: '', promisedCompletionDate: '' });
      loadData();
      if (res.agreementRequired) {
        setAgModal({
          invoiceId: res.invoice.id,
          monthYear: res.invoice.monthYear,
          outstanding: res.invoice.outstandingAmount || res.invoice.totalAmount,
        });
        alert(res.message || 'Debes solicitar un convenio para el saldo restante.');
      }
    } catch (e: any) {
      alert(e.response?.data?.message || e.message || 'Error al registrar motivo de pago parcial.');
    } finally {
      setShortfallLoading(false);
    }
  };

  const handleRequestAgreement = async () => {
    if (!agModal || !agForm.requestedAmount) return;
    setAgLoading(true);
    try {
      await agreementService.requestAgreement(agModal.invoiceId, Number(agForm.requestedAmount), agForm.reason || undefined, agForm.description || undefined);
      setAgModal(null);
      setAgForm({ requestedAmount: '', reason: '', description: '' });
      loadData();
    } catch (e: any) {
      alert(e.response?.data?.message || 'Error al solicitar convenio.');
    } finally {
      setAgLoading(false);
    }
  };

  const agStatusLabel = (s: string) => {
    switch (s) {
      case 'REQUESTED': return { text: 'SOLICITADO', cls: 'bg-amber-100 text-amber-700' };
      case 'APPROVED': case 'ACTIVE': return { text: s === 'ACTIVE' ? 'ACTIVO' : 'APROBADO', cls: 'bg-emerald-100 text-emerald-700' };
      case 'REJECTED': return { text: 'RECHAZADO', cls: 'bg-rose-100 text-rose-700' };
      case 'COMPLETED': return { text: 'COMPLETADO', cls: 'bg-blue-100 text-blue-700' };
      case 'BREACHED': return { text: 'INCUMPLIDO', cls: 'bg-red-100 text-red-700' };
      default: return { text: s, cls: 'bg-slate-100 text-slate-600' };
    }
  };

  return (
    <div className="min-h-screen bg-slate-50 font-sans flex flex-col">
      {/* Top Navbar */}
      <header className="bg-slate-900 border-b border-slate-800 text-white">
        <div className="max-w-5xl mx-auto px-4 sm:px-6 lg:px-8 h-16 flex items-center justify-between">
          <div className="flex items-center gap-2">
            <div className="w-8 h-8 rounded-lg bg-teal-500/20 text-teal-400 flex items-center justify-center">
              <Home className="w-5 h-5" />
            </div>
            <span className="font-bold tracking-wide text-lg">Portal Inquilino</span>
          </div>
          <div className="flex items-center gap-3 flex-wrap justify-end">
            {hasMultiCtx && (
              <div className="relative">
                <button
                  type="button"
                  onClick={() => setCtxDropdown(!ctxDropdown)}
                  disabled={switchingCtx}
                  className="flex items-center gap-2 px-3 py-1.5 rounded-lg text-xs font-bold bg-slate-800 text-teal-300 border border-slate-700 hover:bg-slate-700 max-w-[220px]"
                >
                  <ArrowRightLeft className="w-3.5 h-3.5 shrink-0" />
                  <span className="truncate">{switchingCtx ? '…' : activeCtxName || 'Organización'}</span>
                  <ChevronDown className={`w-3.5 h-3.5 shrink-0 ${ctxDropdown ? 'rotate-180' : ''}`} />
                </button>
                {ctxDropdown && (
                  <>
                    <div className="fixed inset-0 z-40" onClick={() => setCtxDropdown(false)} />
                    <div className="absolute right-0 top-full mt-1 w-64 bg-white text-slate-800 rounded-xl border border-slate-200 shadow-xl z-50 overflow-hidden">
                      <p className="text-[10px] font-bold text-slate-400 uppercase px-3 py-2 bg-slate-50 border-b border-slate-100 flex items-center gap-1">
                        <Building2 className="w-3 h-3" /> Inmobiliaria (contexto)
                      </p>
                      {contexts.map((ctx) => (
                        <button
                          key={ctx.id}
                          type="button"
                          onClick={() => handleSwitchCtx(ctx.id)}
                          className={`w-full text-left px-3 py-2.5 text-sm font-medium border-b border-slate-50 last:border-0 hover:bg-teal-50 ${
                            ctx.id === user?.contextId ? 'bg-teal-50 text-teal-800 font-bold' : ''
                          }`}
                        >
                          {ctx.name}
                        </button>
                      ))}
                    </div>
                  </>
                )}
              </div>
            )}
            <span className="text-sm font-medium text-slate-300 hidden sm:block">{user?.name}</span>
            <button
              type="button"
              onClick={() => setPrefsOpen(true)}
              className="flex items-center gap-2 px-3 py-1.5 rounded-lg text-sm font-bold text-teal-300 hover:bg-slate-800 transition-colors"
              title="Configurar qué notificaciones recibes por email y WhatsApp"
            >
              <Bell className="w-4 h-4" /> <span className="hidden sm:inline">Notificaciones</span>
            </button>
            <button type="button" onClick={() => void handleLogout()} className="flex items-center gap-2 px-3 py-1.5 rounded-lg text-sm font-bold text-rose-400 hover:bg-slate-800 transition-colors">
              <LogOut className="w-4 h-4" /> <span className="hidden sm:inline">Salir</span>
            </button>
          </div>
        </div>
      </header>

      <main className="flex-1 max-w-5xl mx-auto px-4 sm:px-6 lg:px-8 py-8 w-full space-y-6">
        {loading ? (
          <div className="animate-pulse flex flex-col gap-4">
            <div className="bg-slate-200 h-32 rounded-2xl w-full" />
            <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
              <div className="bg-slate-200 h-64 rounded-2xl w-full" />
              <div className="bg-slate-200 h-64 rounded-2xl w-full" />
            </div>
          </div>
        ) : expedientes.length === 0 ? (
          <div className="text-center py-20">
            <p className="text-slate-500">No hay expedientes activos en esta organizacion.</p>
          </div>
        ) : hasMultiExpediente && !selectedProfileId ? (
          <div className="bg-white rounded-3xl p-8 border border-slate-200 shadow-sm max-w-lg mx-auto">
            <h2 className="text-lg font-bold text-slate-800 mb-2">Selecciona un expediente</h2>
            <p className="text-sm text-slate-500 mb-4">
              Tienes varios contratos con esta inmobiliaria. Elige uno para ver facturas y datos.
            </p>
            <select
              className="w-full border border-slate-300 rounded-xl p-3 text-sm font-medium bg-white"
              value=""
              onChange={(e) => e.target.value && handleSelectExpediente(e.target.value)}
            >
              <option value="">— Elige expediente —</option>
              {expedientes.map((ex) => (
                <option key={ex.tenantProfileId} value={ex.tenantProfileId}>
                  {ex.propertyName || ex.propertyAddress || ex.tenantProfileId}
                </option>
              ))}
            </select>
          </div>
        ) : summary ? (
          <>
            {hasMultiExpediente && (
              <div className="bg-white rounded-2xl p-4 border border-slate-200 shadow-sm flex flex-col sm:flex-row sm:items-center gap-3">
                <span className="text-xs font-bold text-slate-500 uppercase">Expediente</span>
                <select
                  className="flex-1 border border-slate-300 rounded-xl p-2.5 text-sm font-medium bg-white max-w-md"
                  value={selectedProfileId || ''}
                  onChange={(e) => handleSelectExpediente(e.target.value)}
                >
                  {expedientes.map((ex) => (
                    <option key={ex.tenantProfileId} value={ex.tenantProfileId}>
                      {ex.propertyName || ex.propertyAddress || ex.tenantProfileId}
                    </option>
                  ))}
                </select>
              </div>
            )}
            {/* Property Header */}
            <div className="bg-white rounded-3xl p-8 border border-slate-200 shadow-sm relative overflow-hidden">
              <div className="absolute top-0 right-0 w-64 h-64 bg-teal-50 rounded-full blur-3xl -mr-20 -mt-20 opacity-50 z-0 pointer-events-none" />
              <div className="relative z-10 flex flex-col md:flex-row items-start md:items-center justify-between gap-6">
                <div>
                  <h3 className="text-sm font-bold text-teal-600 uppercase tracking-widest mb-1">Su Inmueble Asignado</h3>
                  <h1 className="text-3xl font-extrabold text-slate-900 mb-2">{summary.propertyName}</h1>
                  <p className="flex items-center text-slate-500 font-medium"><Navigation className="w-4 h-4 mr-1.5" />{summary.propertyAddress}</p>
                </div>
                <div className="bg-slate-50 rounded-2xl p-4 border border-slate-100 flex items-center gap-4 min-w-[250px]">
                  <div className="w-12 h-12 bg-indigo-100 text-indigo-600 rounded-xl flex flex-col items-center justify-center font-bold">
                    <span className="text-xs -mb-1 opacity-70">Día</span>
                    <span className="text-lg">{summary.paymentDay}</span>
                  </div>
                  <div>
                    <p className="text-xs font-bold text-slate-400 uppercase tracking-wider mb-0.5">Día de Corte</p>
                    <p className="text-sm font-semibold text-slate-700">De cada mes</p>
                  </div>
                </div>
              </div>
            </div>

            {summary.leaseDocumentUrl && summary.leaseId && (
              <div className="bg-white rounded-2xl p-4 border border-slate-200 shadow-sm flex flex-wrap items-center gap-3">
                <FileText className="w-5 h-5 text-teal-600 shrink-0" />
                <div className="flex-1 min-w-0">
                  <p className="text-xs font-bold text-slate-500 uppercase">Expediente / contrato</p>
                  <p className="text-sm text-slate-700">Documento registrado en su alta.</p>
                </div>
                <button
                  type="button"
                  onClick={async () => {
                    try {
                      await openSecureFile('lease-document', summary.leaseId!);
                    } catch (err) {
                      window.alert(describeSecureFileError(err));
                    }
                  }}
                  className="inline-flex items-center gap-2 px-4 py-2 rounded-xl bg-teal-600 text-white text-sm font-bold hover:bg-teal-700"
                >
                  {summary.leaseDocumentFileName || 'Ver contrato (PDF)'}
                </button>
              </div>
            )}

            {/* Grid */}
            <div className="grid grid-cols-1 md:grid-cols-2 gap-6">
              {/* Financial Summary */}
              <div className="bg-white rounded-3xl p-6 md:p-8 border border-slate-200 shadow-sm flex flex-col">
                <div className="flex items-center gap-3 mb-6">
                  <div className="w-10 h-10 rounded-full bg-emerald-100 text-emerald-600 flex items-center justify-center"><DollarSign className="w-5 h-5" /></div>
                  <h2 className="text-xl font-bold text-slate-800">Estado de Cuenta</h2>
                </div>
                <div className="flex-1 bg-slate-50 rounded-2xl p-6 border border-slate-100 mb-6 flex flex-col justify-center items-center text-center">
                  <p className="text-sm font-bold text-slate-400 uppercase tracking-widest mb-2">Monto de Renta Fijo</p>
                  <h3 className="text-4xl md:text-5xl font-extrabold text-slate-900 tracking-tight">
                    ${Number(summary.rentAmount).toLocaleString('en-US', { minimumFractionDigits: 2 })}
                  </h3>
                  <span className="inline-flex items-center px-2.5 py-1 rounded-full text-xs font-bold bg-slate-200 text-slate-600 mt-3">MXN / Mensual</span>
                </div>
              </div>

              {/* Contact & Admin */}
              <div className="bg-slate-900 rounded-3xl p-6 md:p-8 border border-slate-800 text-white shadow-xl">
                <div className="flex items-center gap-3 mb-8">
                  <div className="w-10 h-10 rounded-full bg-slate-800 text-blue-400 flex items-center justify-center"><ShieldCheck className="w-5 h-5" /></div>
                  <h2 className="text-xl font-bold">Administración</h2>
                </div>
                <div className="space-y-6">
                  <div><p className="text-xs font-bold text-slate-500 uppercase tracking-widest mb-1.5">Empresa Arrendadora</p><p className="text-lg font-medium text-white">{summary.organizationName}</p></div>
                  <div className="h-px bg-slate-800 w-full" />
                  <div>
                    <p className="text-xs font-bold text-slate-500 uppercase tracking-widest mb-1.5">Sus Datos de Identidad</p>
                    <div className="space-y-2">
                      <p className="text-sm font-medium text-slate-300"><span className="text-slate-500">A nombre de:</span> {summary.tenantName}</p>
                      <p className="text-sm font-medium text-slate-300"><span className="text-slate-500">Correo:</span> {summary.tenantEmail}</p>
                      <p className="text-sm font-medium text-slate-300"><span className="text-slate-500">Celular:</span> {summary.tenantPhone ?? '—'}</p>
                    </div>
                  </div>
                </div>
              </div>
            </div>

            {/* Invoice Table */}
            <div className="bg-white rounded-3xl p-6 md:p-8 border border-slate-200 shadow-sm">
              <div className="flex items-center justify-between mb-6">
                <div className="flex items-center gap-3">
                  <div className="w-10 h-10 rounded-full bg-slate-100 text-slate-600 flex items-center justify-center"><FileText className="w-5 h-5" /></div>
                  <h2 className="text-xl font-bold text-slate-800">Historial de Recibos</h2>
                </div>
              </div>

              {invoices.length === 0 ? (
                <p className="text-slate-500 text-sm">No hay facturas registradas en su historial.</p>
              ) : (
                <div className="space-y-4">
                  {invoices.map(inv => {
                    const st = statusLabel(inv.status);
                    return (
                      <div key={inv.id} className="flex flex-col p-4 rounded-2xl border border-slate-100 bg-slate-50 hover:bg-slate-100/50 transition-colors gap-4">
                        <div className="flex flex-col md:flex-row md:items-center justify-between gap-4">
                          <div className="flex items-start gap-4">
                            <div className={`mt-1 w-2 h-2 rounded-full ${inv.status === 'PAID' ? 'bg-emerald-500' : inv.status === 'LATE' ? 'bg-rose-500 animate-pulse' : 'bg-amber-400'}`} />
                            <div>
                              <p className="font-bold text-slate-800 uppercase tracking-wide text-sm">{inv.monthYear}</p>
                              <p className="text-xs text-slate-500 flex items-center gap-1 mt-1"><Clock className="w-3 h-3" /> Válido hasta: {inv.dueDate}</p>
                            </div>
                          </div>
                          <div className="flex items-center gap-6 justify-between md:justify-end flex-1">
                            <div className="text-right">
                              <p className="text-xs text-slate-400 font-medium">Concepto</p>
                              <p className="text-sm font-semibold text-slate-700">${inv.baseAmount.toLocaleString('en-US')}</p>
                              {inv.appliedLateFee > 0 && (
                                <p className="text-xs text-rose-500 font-bold flex items-center justify-end gap-1 mt-0.5"><AlertCircle className="w-3 h-3" /> +${inv.appliedLateFee} Recargo</p>
                              )}
                            </div>
                            <div className="text-right w-[100px]">
                              <p className="text-xs text-slate-400 font-medium">Total</p>
                              <p className="text-lg font-extrabold text-slate-900">${inv.totalAmount.toLocaleString('en-US')}</p>
                            </div>
                            <div className="w-[120px] text-right">
                              <span className={`inline-block px-3 py-1.5 rounded-lg text-xs font-bold border ${st.cls}`}>{st.text}</span>
                              {inv.paidDate && <p className="text-[10px] text-slate-400 mt-1">Pagado: {inv.paidDate}</p>}
                            </div>
                          </div>
                        </div>

                        {/* Settlement details for partial/overpaid */}
                        {(inv.paidAmount > 0 && inv.status !== 'PAID') && (
                          <div className="border-t border-dashed border-slate-200 pt-3 flex items-center gap-4 text-sm">
                            <span className="text-emerald-600 font-bold">Pagado: ${(inv.paidAmount || 0).toLocaleString('en-US')}</span>
                            <span className="text-rose-600 font-bold">Pendiente: ${(inv.outstandingAmount || 0).toLocaleString('en-US')}</span>
                          </div>
                        )}
                        {needsShortfall(inv) && (
                          <div className="border-t border-amber-200 bg-amber-50/80 rounded-xl p-3 mt-2">
                            <p className="text-xs font-bold text-amber-900 mb-2">
                              Registra el motivo de tu pago parcial (obligatorio para continuar con la cobranza).
                            </p>
                            <button
                              type="button"
                              onClick={() => {
                                setShortfallForm({
                                  shortfallReason: 'OTHER',
                                  shortfallDescription: '',
                                  promisedCompletionDate: '',
                                });
                                setShortfallModal(inv);
                              }}
                              className="text-sm font-bold text-amber-900 underline decoration-amber-600"
                            >
                              Completar información →
                            </button>
                          </div>
                        )}
                        {inv.shortfallReason && (
                          <p className="text-[11px] text-slate-500 border-t border-slate-100 pt-2">
                            Motivo registrado: <strong>{inv.shortfallReason}</strong>
                            {inv.promisedCompletionDate && <> · Compromiso: {inv.promisedCompletionDate}</>}
                          </p>
                        )}
                        {(inv.creditBalance || 0) > 0 && (
                          <div className="border-t border-dashed border-teal-200 pt-2 text-sm text-teal-600 font-bold">
                            Saldo a favor: ${inv.creditBalance.toLocaleString('en-US')}
                          </div>
                        )}

                        {(inv.status === 'PENDING' || inv.status === 'LATE' || inv.status === 'PARTIALLY_PAID') && (
                          <div className="border-t border-slate-200 pt-4 flex flex-col sm:flex-row gap-3">
                            <button
                              onClick={() => { setPayModal({ invoiceId: inv.id, monthYear: inv.monthYear, totalAmount: inv.outstandingAmount || inv.totalAmount }); setPayMethod(null); resetSpei(); resetMpAmount(); }}
                              className="flex-1 py-3 bg-slate-900 hover:bg-slate-800 text-white font-bold rounded-xl flex items-center justify-center gap-2 transition-all active:scale-[0.98] shadow-lg shadow-slate-900/20"
                            >
                              <CreditCard className="w-5 h-5" /> {inv.status === 'PARTIALLY_PAID' ? 'Completar Pago' : 'Proceder al Pago'}
                            </button>
                            <button
                              onClick={() => setAgModal({ invoiceId: inv.id, monthYear: inv.monthYear, outstanding: inv.outstandingAmount || inv.totalAmount })}
                              className="px-4 py-3 bg-violet-600 hover:bg-violet-700 text-white font-bold rounded-xl flex items-center justify-center gap-2 transition-all text-sm"
                            >
                              <Handshake className="w-4 h-4" /> Solicitar Convenio
                            </button>
                          </div>
                        )}
                      </div>
                    );
                  })}
                </div>
              )}
            </div>

            {selectedProfileId && (
              <div className="bg-white rounded-3xl border border-slate-200 shadow-sm overflow-hidden">
                <div className="px-6 md:px-8 py-5 border-b border-slate-100 bg-emerald-50/60">
                  <h2 className="text-xl font-bold text-slate-800">Historial de comprobantes enviados</h2>
                  <p className="text-sm text-slate-600 mt-1">
                    Aqui veras cada intento de pago que subiste: validado por Banxico, validado manualmente o rechazado.
                  </p>
                </div>
                <TenantPaymentsHistory tenantProfileId={selectedProfileId} />
              </div>
            )}

            {/* ─── Maintenance Tickets ─── */}
            {selectedProfileId && (
              <TenantMaintenancePanel
                key={selectedProfileId}
                tenantProfileId={selectedProfileId}
                propertyId={summary.propertyId}
              />
            )}

            {/* ─── Agreements Section ─── */}
            {agreements.length > 0 && (
              <div className="bg-white rounded-3xl p-6 md:p-8 border border-slate-200 shadow-sm">
                <div className="flex items-center gap-3 mb-6">
                  <div className="w-10 h-10 rounded-full bg-violet-100 text-violet-600 flex items-center justify-center"><Handshake className="w-5 h-5" /></div>
                  <h2 className="text-xl font-bold text-slate-800">Mis Convenios</h2>
                </div>
                <div className="space-y-3">
                  {agreements.map(ag => {
                    const ast = agStatusLabel(ag.status);
                    return (
                      <div key={ag.id} className="p-4 rounded-2xl border border-slate-100 bg-slate-50 space-y-2">
                        <div className="flex items-center justify-between">
                          <div>
                            <p className="font-bold text-slate-800">{ag.monthYear || 'Sin periodo'}</p>
                            <p className="text-xs text-slate-500">Solicitado: {ag.createdAt?.split('T')[0]}</p>
                          </div>
                          <span className={`px-3 py-1 rounded-lg text-xs font-bold ${ast.cls}`}>{ast.text}</span>
                        </div>
                        <div className="flex flex-wrap gap-4 text-sm">
                          <span><strong className="text-slate-600">Solicitado:</strong> ${ag.requestedAmount.toLocaleString('en-US')}</span>
                          {ag.approvedAmount != null && <span className="text-emerald-600 font-bold">Aprobado: ${ag.approvedAmount.toLocaleString('en-US')}</span>}
                          {ag.deferredAmount != null && ag.deferredAmount > 0 && <span className="text-amber-600 font-bold">Diferido: ${ag.deferredAmount.toLocaleString('en-US')}</span>}
                        </div>
                        {ag.rejectionReason && <p className="text-xs text-rose-600"><strong>Motivo rechazo:</strong> {ag.rejectionReason}</p>}
                        {ag.installments && ag.installments.length > 0 && (
                          <div className="mt-2 space-y-1">
                            <p className="text-xs font-bold text-slate-500 uppercase">Parcialidades:</p>
                            {ag.installments.map(inst => (
                              <div key={inst.id} className="flex items-center justify-between text-sm bg-white rounded-lg p-2 border border-slate-100">
                                <span className="text-slate-600">{inst.dueDate}</span>
                                <span className="font-bold text-slate-800">${inst.amount.toLocaleString('en-US')}</span>
                                <span className={`px-2 py-0.5 text-[10px] font-bold rounded-full ${inst.status === 'PAID' ? 'bg-emerald-100 text-emerald-700' : inst.status === 'LATE' ? 'bg-rose-100 text-rose-700' : 'bg-amber-100 text-amber-700'}`}>
                                  {inst.status === 'PAID' ? 'PAGADO' : inst.status === 'LATE' ? 'VENCIDO' : 'PENDIENTE'}
                                </span>
                              </div>
                            ))}
                          </div>
                        )}
                      </div>
                    );
                  })}
                </div>
              </div>
            )}
          </>
        ) : (
          <div className="text-center py-20"><p className="text-slate-500">No se pudo cargar el resumen del expediente.</p></div>
        )}
      </main>

      {/* ─── Payment Modal ──────────────────────────────────────── */}
      {payModal && (
        <div className="fixed inset-0 z-[120] flex items-center justify-center p-4">
          <div className="absolute inset-0 bg-slate-900/70 backdrop-blur-sm" onClick={() => { setPayModal(null); setPayMethod(null); }} />
          <div className="relative bg-white rounded-2xl w-full max-w-lg shadow-2xl overflow-hidden border border-slate-200 animate-in fade-in zoom-in-95 duration-200">

            {/* Header */}
            <div className="px-6 py-4 border-b border-slate-100 bg-slate-50">
              <h3 className="text-lg font-bold text-slate-800">Pagar {payModal.monthYear}</h3>
              <p className="text-sm text-slate-500">Pendiente: <strong>${payModal.totalAmount.toLocaleString('en-US', { minimumFractionDigits: 2 })}</strong> MXN</p>
            </div>

            <div className="p-6">
              {/* V57 — Spinner EXPLÍCITO durante validación Banxico (15-20s) */}
              {banxicoValidating ? (
                <div className="text-center py-8 space-y-4">
                  <div className="relative w-16 h-16 mx-auto">
                    <div className="absolute inset-0 rounded-full border-4 border-teal-100"></div>
                    <div className="absolute inset-0 rounded-full border-4 border-teal-600 border-t-transparent animate-spin"></div>
                  </div>
                  <div className="space-y-1">
                    <p className="text-base font-bold text-slate-800">Validando con Banxico...</p>
                    <p className="text-sm text-slate-500">Esto tarda entre 15 y 20 segundos.</p>
                    <p className="text-xs text-slate-400">No cierres esta ventana.</p>
                  </div>
                </div>
              ) : speiResult || cashResult ? (
                <div className="text-center py-6 space-y-3">
                  {/* V58.1 — Ícono condicional según el tipo de resultado */}
                  {resultKind === 'success' ? (
                    <CheckCircle2 className="w-12 h-12 text-emerald-500 mx-auto" />
                  ) : resultKind === 'pending' ? (
                    <Clock className="w-12 h-12 text-amber-500 mx-auto" />
                  ) : (
                    <AlertCircle className="w-12 h-12 text-rose-500 mx-auto" />
                  )}
                  <p className={`text-sm font-semibold ${
                    resultKind === 'success' ? 'text-emerald-800' :
                    resultKind === 'pending' ? 'text-amber-800' :
                    'text-rose-800'
                  }`}>{speiResult || cashResult}</p>
                  {attemptsLeft !== null && attemptsLeft > 0 && payMethod === 'SPEI' && usedOcr && (
                    <p className="text-xs text-slate-500">Te quedan {attemptsLeft} intento{attemptsLeft === 1 ? '' : 's'} con foto este mes.</p>
                  )}
                </div>
              ) : !payMethod ? (
                /* Method Selection */
                <div className="space-y-3">
                  <p className="text-sm text-slate-600 mb-4">Selecciona tu método de pago:</p>
                  <button
                    onClick={() => mpOwnerConnected && setPayMethod('MP')}
                    disabled={mpOwnerConnected === false}
                    className={`w-full flex items-center justify-between p-4 border rounded-xl transition-all group ${
                      mpOwnerConnected === false
                        ? 'border-slate-100 bg-slate-50 opacity-60 cursor-not-allowed'
                        : 'border-slate-200 hover:border-blue-300 hover:bg-blue-50'
                    }`}
                  >
                    <div className="flex items-center gap-3">
                      <div className="w-10 h-10 bg-blue-100 rounded-lg flex items-center justify-center text-blue-600"><CreditCard className="w-5 h-5" /></div>
                      <div className="text-left">
                        <p className="font-bold text-slate-700">Mercado Pago</p>
                        <p className="text-xs text-slate-500">
                          {mpOwnerConnected === false
                            ? 'Tu arrendador debe vincular su cuenta de Mercado Pago'
                            : 'Tarjeta, OXXO, transferencia'}
                        </p>
                      </div>
                    </div>
                    {mpOwnerConnected !== false && (
                      <ArrowRight className="w-5 h-5 text-slate-400 group-hover:text-blue-500 transition-colors" />
                    )}
                  </button>
                  <button onClick={() => setPayMethod('SPEI')} className="w-full flex items-center justify-between p-4 border border-slate-200 rounded-xl hover:border-teal-300 hover:bg-teal-50 transition-all group">
                    <div className="flex items-center gap-3">
                      <div className="w-10 h-10 bg-teal-100 rounded-lg flex items-center justify-center text-teal-600"><Upload className="w-5 h-5" /></div>
                      <div className="text-left">
                        <p className="font-bold text-slate-700">Transferencia SPEI</p>
                        <p className="text-xs text-slate-500">Validación automática con Banxico</p>
                      </div>
                    </div>
                    <ArrowRight className="w-5 h-5 text-slate-400 group-hover:text-teal-500 transition-colors" />
                  </button>
                  <button onClick={() => setPayMethod('CASH')} className="w-full flex items-center justify-between p-4 border border-slate-200 rounded-xl hover:border-amber-300 hover:bg-amber-50 transition-all group">
                    <div className="flex items-center gap-3">
                      <div className="w-10 h-10 bg-amber-100 rounded-lg flex items-center justify-center text-amber-600"><DollarSign className="w-5 h-5" /></div>
                      <div className="text-left">
                        <p className="font-bold text-slate-700">Pago en efectivo</p>
                        <p className="text-xs text-slate-500">Tu arrendador valida el recibo en 120 h</p>
                      </div>
                    </div>
                    <ArrowRight className="w-5 h-5 text-slate-400 group-hover:text-amber-500 transition-colors" />
                  </button>
                </div>
              ) : payMethod === 'MP' ? (
                /* Mercado Pago */
                <div className="space-y-4 py-2">
                  <div className="w-14 h-14 bg-blue-100 rounded-2xl flex items-center justify-center text-blue-600 mx-auto"><CreditCard className="w-7 h-7" /></div>
                  <p className="text-sm text-slate-600 text-center">
                    El pago va a la cuenta de Mercado Pago de tu arrendador (no a ADMINDI). Elige cuánto abonar; el checkout se abre en una pestaña nueva (usa Chrome incógnito si Safari muestra error de redirecciones).
                  </p>

                  <div className="space-y-2">
                    <label className="flex items-start gap-3 p-3 border border-slate-200 rounded-xl cursor-pointer hover:border-blue-300 has-[:checked]:border-blue-400 has-[:checked]:bg-blue-50/50">
                      <input
                        type="radio"
                        name="mpAmountMode"
                        className="mt-1"
                        checked={mpAmountMode === 'full'}
                        onChange={() => setMpAmountMode('full')}
                      />
                      <div>
                        <p className="font-bold text-slate-800 text-sm">Pagar total pendiente</p>
                        <p className="text-xs text-slate-500">
                          ${payModal.totalAmount.toLocaleString('en-US', { minimumFractionDigits: 2 })} MXN
                        </p>
                      </div>
                    </label>
                    <label className="flex items-start gap-3 p-3 border border-slate-200 rounded-xl cursor-pointer hover:border-blue-300 has-[:checked]:border-blue-400 has-[:checked]:bg-blue-50/50">
                      <input
                        type="radio"
                        name="mpAmountMode"
                        className="mt-1"
                        checked={mpAmountMode === 'custom'}
                        onChange={() => setMpAmountMode('custom')}
                      />
                      <div className="flex-1">
                        <p className="font-bold text-slate-800 text-sm">Abonar otro monto</p>
                        <p className="text-xs text-slate-500 mb-2">Parcialidad; puedes completar el resto después</p>
                        {mpAmountMode === 'custom' && (
                          <input
                            className={inputCls}
                            type="number"
                            step="0.01"
                            min="1"
                            max={payModal.totalAmount}
                            placeholder="0.00"
                            value={mpCustomAmount}
                            onChange={e => setMpCustomAmount(e.target.value)}
                          />
                        )}
                      </div>
                    </label>
                  </div>

                  <button
                    onClick={() => handlePayMP(payModal.invoiceId, payModal.totalAmount)}
                    disabled={mpLoading}
                    className="w-full py-3 bg-blue-600 hover:bg-blue-700 text-white font-bold rounded-xl transition-colors disabled:opacity-60 flex items-center justify-center gap-2"
                  >
                    {mpLoading ? 'Generando enlace...' : (
                      <>
                        <CreditCard className="w-5 h-5" />
                        Ir a Mercado Pago
                        {mpAmountMode === 'custom' && mpCustomAmount && Number(mpCustomAmount) > 0
                          ? ` ($${Number(mpCustomAmount).toLocaleString('en-US', { minimumFractionDigits: 2 })})`
                          : ` ($${payModal.totalAmount.toLocaleString('en-US', { minimumFractionDigits: 2 })})`}
                      </>
                    )}
                  </button>
                  <button onClick={() => { setPayMethod(null); resetMpAmount(); }} className="w-full text-sm text-slate-500 hover:text-slate-700">← Cambiar método</button>
                </div>
              ) : payMethod === 'CASH' ? (
                /* V57 — Pago en efectivo */
                <div className="space-y-4">
                  <div className="rounded-xl border border-amber-200 bg-amber-50 p-3 text-xs text-amber-800">
                    <p className="font-semibold mb-1">¿Cómo funciona?</p>
                    <p>Sube la foto del recibo de pago en efectivo. Tu arrendador tiene <strong>120 horas</strong> para revisar y aprobarlo. Te notificaremos en cuanto lo valide o rechace.</p>
                  </div>

                  <div>
                    <label className="block text-xs font-semibold text-slate-600 mb-1">Monto pagado (MXN) *</label>
                    <input
                      className={inputCls}
                      type="number"
                      step="0.01"
                      placeholder="0.00"
                      value={cashAmount}
                      onChange={e => setCashAmount(e.target.value)}
                    />
                  </div>

                  <div>
                    <label className="block text-xs font-semibold text-slate-600 mb-1">
                      Foto o PDF del recibo *
                      {cashFile && <span className="text-green-600 ml-2">✓ {cashFile.name}</span>}
                    </label>
                    <input
                      type="file"
                      accept="image/*,.pdf"
                      className="text-sm text-slate-500 file:mr-4 file:py-2 file:px-4 file:rounded-full file:border-0 file:text-sm file:font-semibold file:bg-amber-50 file:text-amber-700 hover:file:bg-amber-100"
                      onChange={e => setCashFile(e.target.files ? e.target.files[0] : null)}
                    />
                  </div>

                  <div>
                    <label className="block text-xs font-semibold text-slate-600 mb-1">Nota para el arrendador (opcional)</label>
                    <textarea
                      className={inputCls}
                      rows={2}
                      maxLength={280}
                      placeholder="Ej: pago del mes de abril, entregado en efectivo el 15 abril"
                      value={cashNote}
                      onChange={e => setCashNote(e.target.value)}
                    />
                  </div>

                  <div className="flex gap-3 pt-2">
                    <button onClick={() => setPayMethod(null)} className="px-4 py-2.5 text-sm text-slate-500 hover:text-slate-700 font-semibold">← Atrás</button>
                    <button
                      onClick={() => handleCashSubmit(payModal.invoiceId)}
                      disabled={cashLoading}
                      className="flex-1 py-2.5 bg-amber-600 hover:bg-amber-700 text-white font-bold rounded-xl transition-colors disabled:opacity-60"
                    >
                      {cashLoading ? 'Enviando...' : 'Enviar comprobante de efectivo'}
                    </button>
                  </div>
                </div>
              ) : (
                /* SPEI Upload */
                <div className="space-y-4">
                  {/* V58 — Info sobre los 2 métodos y sus límites */}
                  <div className="rounded-xl border border-teal-200 bg-teal-50 p-3 text-xs text-teal-900">
                    <p className="font-semibold mb-1">Dos formas de validar tu SPEI:</p>
                    <ul className="space-y-0.5 pl-4 list-disc">
                      <li><strong>Foto con IA:</strong> 6 intentos por mes — extraemos los datos automáticamente.</li>
                      <li><strong>Captura manual:</strong> sin límite — tecleas tú mismo los datos.</li>
                    </ul>
                    <p className="mt-1 text-teal-700 text-[11px]">
                      La cuenta de tu arrendador se toma de su perfil; <strong>no la captures tú</strong>.
                    </p>
                  </div>

                  {/* V58 — Banner si alcanzó límite AI_OCR este mes */}
                  {forceManual && (
                    <div className="rounded-xl border border-amber-200 bg-amber-50 p-3 text-xs text-amber-800">
                      Llegaste al límite mensual de 6 validaciones con foto. Continúa con captura manual (sin límite).
                    </div>
                  )}

                  {/* V56/V57/V58 — Botón "Foto + IA" (deshabilitado si alcanzó límite mensual) */}
                  {!forceManual && (
                    <div className="rounded-xl border-2 border-dashed border-purple-200 bg-purple-50/60 p-4">
                      <div className="flex items-center gap-3 mb-2">
                        <div className="w-8 h-8 bg-purple-100 rounded-lg flex items-center justify-center text-purple-600">
                          <Sparkles className="w-5 h-5" />
                        </div>
                        <div>
                          <p className="text-sm font-bold text-purple-900">Subir foto con IA</p>
                          <p className="text-xs text-purple-700">
                            Extraemos los datos automáticamente. Tú confirmas.
                            {attemptsLeft !== null && attemptsLeft !== Number.MAX_SAFE_INTEGER && (
                              <span className="ml-2 text-amber-700 font-semibold">
                                Te quedan {attemptsLeft} intento{attemptsLeft === 1 ? '' : 's'} este mes.
                              </span>
                            )}
                          </p>
                        </div>
                      </div>
                      <label className="inline-flex items-center gap-2 px-3 py-2 bg-white border border-purple-200 rounded-lg cursor-pointer hover:bg-purple-50 text-sm font-semibold text-purple-700">
                        <Upload className="w-4 h-4" />
                        {ocrLoading ? 'Analizando...' : 'Foto / PDF del comprobante'}
                        <input
                          type="file"
                          className="hidden"
                          accept="image/*,.pdf"
                          disabled={ocrLoading}
                          onChange={async (e) => {
                            const f = e.target.files?.[0];
                            if (f) await handleOcrExtraction(f);
                          }}
                        />
                      </label>
                      {ocrMessage && (
                        <p className={`mt-2 text-xs ${ocrConfidence !== null && ocrConfidence < 0.7 ? 'text-amber-700' : 'text-purple-700'}`}>
                          {ocrMessage}
                          {ocrConfidence !== null && <span className="ml-2 text-slate-500">(confianza {Math.round(ocrConfidence * 100)}%)</span>}
                        </p>
                      )}
                    </div>
                  )}

                  {!forceManual && <div className="text-center text-xs text-slate-400">o captura los datos manualmente (sin límite)</div>}

                  <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
                    <div>
                      <label className="block text-xs font-semibold text-slate-600 mb-1">Clave de Rastreo *</label>
                      <input className={inputCls} placeholder="Ej: 2026041500001234" value={speiData.claveRastreo} onChange={e => { setSpeiData(p => ({ ...p, claveRastreo: e.target.value })); setUsedOcr(false); }} />
                    </div>
                    <div>
                      <label className="block text-xs font-semibold text-slate-600 mb-1">Banco Emisor *</label>
                      <select
                        className={inputCls}
                        value={speiData.bankEmitter}
                        onChange={e => { setSpeiData(p => ({ ...p, bankEmitter: e.target.value })); setUsedOcr(false); }}
                      >
                        <option value="">Selecciona banco Banxico</option>
                        {emitterBanks.map((bank) => (
                          <option key={bank.code} value={bank.name}>{bank.name}</option>
                        ))}
                      </select>
                    </div>
                    <div>
                      <label className="block text-xs font-semibold text-slate-600 mb-1">Monto Transferido *</label>
                      <input className={inputCls} type="number" step="0.01" placeholder="0.00" value={speiData.amount} onChange={e => { setSpeiData(p => ({ ...p, amount: e.target.value })); setUsedOcr(false); }} />
                    </div>
                    <div>
                      <label className="block text-xs font-semibold text-slate-600 mb-1">Fecha de Transferencia *</label>
                      <input className={inputCls} type="date" value={speiData.transferDate} onChange={e => { setSpeiData(p => ({ ...p, transferDate: e.target.value })); setUsedOcr(false); }} />
                    </div>
                  </div>
                  {!forceManual && (
                    <div>
                      <label className="block text-xs font-semibold text-slate-600 mb-1">Comprobante (PDF/Imagen) {speiFile && <span className="text-green-600 ml-2">✓ {speiFile.name}</span>}</label>
                      <input type="file" accept="image/*,.pdf" className="text-sm text-slate-500 file:mr-4 file:py-2 file:px-4 file:rounded-full file:border-0 file:text-sm file:font-semibold file:bg-teal-50 file:text-teal-700 hover:file:bg-teal-100" onChange={e => { setSpeiFile(e.target.files ? e.target.files[0] : null); setUsedOcr(false); }} />
                    </div>
                  )}
                  <div className="flex gap-3 pt-2">
                    <button onClick={() => setPayMethod(null)} className="px-4 py-2.5 text-sm text-slate-500 hover:text-slate-700 font-semibold">← Atrás</button>
                    <button
                      onClick={() => handleSPEISubmit(payModal.invoiceId)}
                      disabled={speiLoading}
                      className="flex-1 py-2.5 bg-teal-600 hover:bg-teal-700 text-white font-bold rounded-xl transition-colors disabled:opacity-60"
                    >
                      {speiLoading ? 'Enviando...' : forceManual ? 'Validar con datos manuales' : 'Enviar Comprobante'}
                    </button>
                  </div>
                </div>
              )}
            </div>
          </div>
        </div>
      )}

      {/* ─── Agreement Request Modal ──────────────────────────────── */}
      {shortfallModal && (
        <div className="fixed inset-0 z-[125] flex items-center justify-center p-4">
          <div className="absolute inset-0 bg-slate-900/70 backdrop-blur-sm" onClick={() => !shortfallLoading && setShortfallModal(null)} />
          <div className="relative bg-white rounded-2xl w-full max-w-md shadow-2xl border border-slate-200">
            <div className="px-6 py-4 border-b border-amber-100 bg-amber-50">
              <h3 className="text-lg font-bold text-amber-900">Motivo de pago parcial</h3>
              <p className="text-xs text-amber-800 mt-1">
                {shortfallModal.monthYear} — Pendiente: ${(shortfallModal.outstandingAmount || 0).toLocaleString('en-US')}
              </p>
            </div>
            <div className="p-6 space-y-4">
              <div>
                <label className="block text-xs font-semibold text-slate-600 mb-1">Razón</label>
                <select
                  className={inputCls}
                  value={shortfallForm.shortfallReason}
                  onChange={(e) =>
                    setShortfallForm((p) => ({ ...p, shortfallReason: e.target.value as ShortfallReason }))
                  }
                >
                  <option value="PARTIAL_SAME_MONTH">Parcial — mismo mes</option>
                  <option value="PARTIAL_NEXT_MONTH">Parcial — completaré el siguiente mes</option>
                  <option value="REQUESTING_AGREEMENT">Solicitaré / estoy en convenio</option>
                  <option value="BANK_ISSUE">Problema bancario</option>
                  <option value="OTHER">Otro</option>
                </select>
              </div>
              <div>
                <label className="block text-xs font-semibold text-slate-600 mb-1">Descripción (opcional)</label>
                <textarea
                  className={inputCls + ' min-h-[72px]'}
                  value={shortfallForm.shortfallDescription}
                  onChange={(e) => setShortfallForm((p) => ({ ...p, shortfallDescription: e.target.value }))}
                />
              </div>
              <div>
                <label className="block text-xs font-semibold text-slate-600 mb-1">Fecha compromiso de pago (opcional)</label>
                <input
                  type="date"
                  className={inputCls}
                  value={shortfallForm.promisedCompletionDate}
                  onChange={(e) => setShortfallForm((p) => ({ ...p, promisedCompletionDate: e.target.value }))}
                />
                <p className="text-[10px] text-slate-400 mt-1">
                  Si indicas una fecha y vence sin convenio activo, puede aplicarse mora según políticas del arrendador.
                </p>
              </div>
              <div className="flex gap-3 pt-2">
                <button
                  type="button"
                  onClick={() => setShortfallModal(null)}
                  disabled={shortfallLoading}
                  className="px-4 py-2.5 text-sm text-slate-500 font-semibold"
                >
                  Cancelar
                </button>
                <button
                  type="button"
                  onClick={handleSubmitShortfall}
                  disabled={shortfallLoading}
                  className="flex-1 py-2.5 bg-amber-600 hover:bg-amber-700 text-white font-bold rounded-xl text-sm disabled:opacity-60"
                >
                  {shortfallLoading ? 'Guardando…' : 'Guardar'}
                </button>
              </div>
            </div>
          </div>
        </div>
      )}

      {/* ─── Modal Preferencias de Notificación ───────────────────── */}
      {prefsOpen && (
        <div className="fixed inset-0 z-[140] flex items-center justify-center p-4">
          <div className="absolute inset-0 bg-slate-900/70 backdrop-blur-sm" onClick={() => setPrefsOpen(false)} />
          <div className="relative bg-white rounded-2xl w-full max-w-3xl max-h-[90vh] shadow-2xl border border-slate-200 overflow-hidden flex flex-col">
            <div className="px-6 pt-4 pb-0 border-b border-slate-100 bg-slate-50 flex items-center justify-between gap-4">
              <div>
                <h3 className="text-lg font-bold text-slate-800 flex items-center gap-2">
                  <Bell className="w-5 h-5 text-indigo-500" /> Mis notificaciones
                </h3>
                <div className="flex gap-2 mt-2">
                  <button
                    type="button"
                    onClick={() => setPrefsTab('PREFS')}
                    className={`px-3 py-1.5 text-sm font-semibold border-b-2 -mb-px ${
                      prefsTab === 'PREFS'
                        ? 'text-indigo-600 border-indigo-500'
                        : 'text-slate-500 border-transparent hover:text-slate-700'
                    }`}
                  >
                    Preferencias
                  </button>
                  <button
                    type="button"
                    onClick={() => setPrefsTab('HISTORY')}
                    className={`px-3 py-1.5 text-sm font-semibold border-b-2 -mb-px ${
                      prefsTab === 'HISTORY'
                        ? 'text-indigo-600 border-indigo-500'
                        : 'text-slate-500 border-transparent hover:text-slate-700'
                    }`}
                  >
                    Historial
                  </button>
                </div>
              </div>
              <button
                type="button"
                onClick={() => setPrefsOpen(false)}
                className="p-1.5 rounded-lg text-slate-400 hover:text-slate-700 hover:bg-slate-100"
                aria-label="Cerrar"
              >
                <X className="w-5 h-5" />
              </button>
            </div>
            <div className="p-6 overflow-y-auto">
              {prefsTab === 'PREFS' ? (
                <NotificationPreferencesPanel embedded />
              ) : (
                <NotificationHistoryPanel mode="me" embedded />
              )}
            </div>
          </div>
        </div>
      )}

      {agModal && (
        <div className="fixed inset-0 z-[130] flex items-center justify-center p-4">
          <div className="absolute inset-0 bg-slate-900/70 backdrop-blur-sm" onClick={() => setAgModal(null)} />
          <div className="relative bg-white rounded-2xl w-full max-w-md shadow-2xl border border-slate-200">
            <div className="px-6 py-4 border-b border-slate-100 bg-violet-50">
              <h3 className="text-lg font-bold text-violet-800 flex items-center gap-2"><Handshake className="w-5 h-5" /> Solicitar Convenio</h3>
              <p className="text-sm text-violet-600">{agModal.monthYear} — Pendiente: ${agModal.outstanding.toLocaleString('en-US')}</p>
            </div>
            <div className="p-6 space-y-4">
              <div>
                <label className="block text-xs font-semibold text-slate-600 mb-1">Monto que puedes pagar ahora *</label>
                <input className={inputCls} type="number" step="0.01" placeholder="0.00" value={agForm.requestedAmount} onChange={e => setAgForm(p => ({ ...p, requestedAmount: e.target.value }))} />
              </div>
              <div>
                <label className="block text-xs font-semibold text-slate-600 mb-1">Propuesta / Descripción</label>
                <textarea className={inputCls + ' min-h-[60px]'} placeholder="Ej: Puedo pagar el resto en 2 parcialidades..." value={agForm.description} onChange={e => setAgForm(p => ({ ...p, description: e.target.value }))} />
              </div>
              <div>
                <label className="block text-xs font-semibold text-slate-600 mb-1">Motivo (opcional)</label>
                <input className={inputCls} placeholder="Ej: Reducción temporal de ingresos" value={agForm.reason} onChange={e => setAgForm(p => ({ ...p, reason: e.target.value }))} />
              </div>
              <div className="flex gap-3 pt-2">
                <button onClick={() => setAgModal(null)} className="px-4 py-2.5 text-sm text-slate-500 hover:text-slate-700 font-semibold">Cancelar</button>
                <button onClick={handleRequestAgreement} disabled={agLoading || !agForm.requestedAmount} className="flex-1 py-2.5 bg-violet-600 hover:bg-violet-700 text-white font-bold rounded-xl transition-colors disabled:opacity-60">
                  {agLoading ? 'Enviando...' : 'Enviar Solicitud'}
                </button>
              </div>
            </div>
          </div>
        </div>
      )}
    </div>
  );
};
