import { useState, useEffect, useCallback } from 'react';
import { useAuth } from '../context/AuthContext';
import { useNavigate } from 'react-router-dom';
import { Building2, LayoutDashboard, Users, UserCog, LogOut, Inbox, ChevronDown, ArrowRightLeft, Handshake, Bell, FileSpreadsheet, IdCard, ClipboardCheck, AlertCircle, ArrowRight } from 'lucide-react';
import { OwnerOnboardingWizard } from './OwnerOnboardingWizard';
import { PropertyManager } from './PropertyManager';
import { TenantManager } from './TenantManager';
import { OwnerTeamHub } from './OwnerTeamHub';
import { OwnerWorkflowInbox } from './OwnerWorkflowInbox';
// PermissionManager removed from Owner sidebar — permissions are managed inline in StaffManager
import { ActionTaskInbox } from './ActionTaskInbox';
import { NotificationPreferencesPanel } from './NotificationPreferencesPanel';
import NotificationHistoryPanel from './NotificationHistoryPanel';
import { AgreementManager } from './AgreementManager';
import { MaintenanceBudgetManager } from './MaintenanceBudgetManager';
import { OwnerAccountingSummaryPanel } from './OwnerAccountingSummaryPanel';
import { OwnerProfilePanel } from './OwnerProfilePanel';
import { actionTaskService } from '../services/actionTaskService';
import { contextService, ContextOption } from '../services/contextService';
import { ownerWorkflowService } from '../services/ownerWorkflowService';
import { ownerProfileService } from '../services/ownerProfileService';
import { paymentService } from '../services/paymentService';

type OwnerTab = 'DASHBOARD' | 'PROPERTIES' | 'TENANTS' | 'TASKS' | 'WORKFLOW' | 'TEAM' | 'AGREEMENTS' | 'BUDGETS' | 'NOTIFS' | 'PROFILE';

interface TabDef {
    id: OwnerTab;
    label: string;
    icon: React.ReactNode;
    ownerOnly: boolean;
    badge?: number;
}

export const OwnerDashboardLayout = () => {
    const { user, logout, switchContext } = useAuth();
    const navigate = useNavigate();
    const [currentTab, setCurrentTab] = useState<OwnerTab>('DASHBOARD');
    // Sub-tab del apartado NOTIFS. 'PREFS' = preferencias del usuario (todos los roles);
    // 'HISTORY' = historial de envíos (Bloque C4, solo owner/admin con permiso manual).
    const [notifsSubTab, setNotifsSubTab] = useState<'PREFS' | 'HISTORY'>('PREFS');
    const [onboardingComplete, setOnboardingComplete] = useState(user?.onboardingCompleted === true);
    const [openTaskCount, setOpenTaskCount] = useState(0);
    // V65 — contador global del workflow (sin contar tasks). Se muestra como
    // badge en el sidebar y como banner superior si > 0.
    const [workflowPending, setWorkflowPending] = useState(0);
    const [rentProofsPending, setRentProofsPending] = useState(0);
    const [ownerHasReceiverAccount, setOwnerHasReceiverAccount] = useState<boolean | null>(null);

    // Multi-context state
    const [contexts, setContexts] = useState<ContextOption[]>([]);
    const [switchingContext, setSwitchingContext] = useState(false);
    const [contextDropdownOpen, setContextDropdownOpen] = useState(false);
    // Key to force re-mount of tab content when context changes
    const [contentKey, setContentKey] = useState(0);

    // V52 — el dashboard operativo es estrictamente del dueño. SUPER_ADMIN vive en
    // /dashboard (Plataforma Root: AdminOwners / AdminProviders / AdminAudit / etc.).
    const isOwnerOrAdmin = user?.role === 'OWNER';
    const isMultiContextRole = user?.role === 'PROPERTY_ADMIN' || user?.role === 'ACCOUNTANT';

    // Fetch available contexts on mount
    useEffect(() => {
        contextService.getContexts()
            .then(setContexts)
            .catch(() => setContexts([]));
    }, []);

    // Vuelta OAuth Mercado Pago → abrir Mi perfil
    useEffect(() => {
        const params = new URLSearchParams(window.location.search);
        if (params.get('tab') === 'PROFILE') {
            setCurrentTab('PROFILE');
        }
    }, []);

    // Fetch open task count for badge
    useEffect(() => {
        if (isOwnerOrAdmin) {
            actionTaskService.getOpenCount()
                .then(count => setOpenTaskCount(count))
                .catch(() => setOpenTaskCount(0));
        }
    }, [currentTab, contentKey]);

    // V65 — resumen consolidado de pendientes. Separamos comprobantes de renta
    // (viven en LedgerService) del resto del workflow para poder mostrar un
    // total global en el sidebar/banner. Si alguno falla, el contador cae a 0
    // sin romper el render.
    const refreshPending = useCallback(() => {
        if (!isOwnerOrAdmin) return;
        ownerWorkflowService.getPendingSummary()
            .then(s => setWorkflowPending(typeof s.total === 'number' ? s.total : 0))
            .catch(() => setWorkflowPending(0));
        paymentService.getPendingProofs()
            .then(list => setRentProofsPending(list.length))
            .catch(() => setRentProofsPending(0));
    }, [isOwnerOrAdmin]);

    useEffect(() => { refreshPending(); }, [refreshPending, currentTab, contentKey]);

    useEffect(() => {
        if (!isOwnerOrAdmin) {
            setOwnerHasReceiverAccount(null);
            return;
        }
        ownerProfileService.get()
            .then((profile) => {
                setOwnerHasReceiverAccount(Boolean(
                    profile.hasClabe &&
                    profile.bankName?.trim() &&
                    profile.accountHolderName?.trim()
                ));
            })
            .catch(() => setOwnerHasReceiverAccount(null));
    }, [isOwnerOrAdmin, currentTab, contentKey]);

    const handleLogout = async () => {
        await logout();
        navigate('/login');
    };

    const handleSwitchContext = async (ctxId: string) => {
        if (ctxId === user?.contextId || switchingContext) return;
        setSwitchingContext(true);
        setContextDropdownOpen(false);
        try {
            await switchContext(ctxId);
            // Force re-mount of all tab content to reload data for new context
            setContentKey(prev => prev + 1);
        } catch (err) {
            console.error('Error switching context', err);
        } finally {
            setSwitchingContext(false);
        }
    };

    if (!onboardingComplete && user?.role === 'OWNER') {
        return <OwnerOnboardingWizard onComplete={() => {
            localStorage.setItem('onboardingCompleted', 'true');
            setOnboardingComplete(true);
        }} />;
    }

    const activeContextName = contexts.find(c => c.id === user?.contextId)?.name
        || (contexts.length === 1 ? contexts[0].name : null);
    const hasMultipleContexts = isMultiContextRole && contexts.length > 1;

    // V65 — el badge del tab WORKFLOW suma el workflow service + los
    // comprobantes de renta (que viven en ledger). Para el banner y el badge
    // visual del sidebar usamos este total conjunto para no fragmentar la
    // atención del dueño.
    const totalPending = workflowPending + rentProofsPending;

    const allTabs: TabDef[] = [
        { id: 'DASHBOARD', label: 'Resumen', icon: <LayoutDashboard className="w-4 h-4" />, ownerOnly: false },
        { id: 'PROPERTIES', label: 'Inmuebles', icon: <Building2 className="w-4 h-4" />, ownerOnly: false },
        { id: 'TENANTS', label: 'Arrendatarios', icon: <Users className="w-4 h-4" />, ownerOnly: false },
        { id: 'TASKS', label: 'Pendientes', icon: <Inbox className="w-4 h-4" />, ownerOnly: true, badge: openTaskCount },
        { id: 'WORKFLOW', label: 'Bandeja de decisiones', icon: <ClipboardCheck className="w-4 h-4" />, ownerOnly: true, badge: totalPending },
        { id: 'AGREEMENTS', label: 'Convenios', icon: <Handshake className="w-4 h-4" />, ownerOnly: true },
        { id: 'BUDGETS', label: 'Presupuestos', icon: <FileSpreadsheet className="w-4 h-4" />, ownerOnly: false },
        { id: 'TEAM', label: 'Equipo y proveedores', icon: <UserCog className="w-4 h-4" />, ownerOnly: true },
        // Perfil del dueño (contacto + CLABE para SPEI). Solo OWNER puede editarlo.
        { id: 'PROFILE', label: 'Mi perfil', icon: <IdCard className="w-4 h-4" />, ownerOnly: true },
        // Preferencias de notificación: todo usuario operativo autenticado gestiona las suyas.
        { id: 'NOTIFS', label: 'Alertas y canales', icon: <Bell className="w-4 h-4" />, ownerOnly: false },
    ];

    const tabs = allTabs.filter(t => !t.ownerOnly || isOwnerOrAdmin);

    const renderContent = () => {
        switch (currentTab) {
            case 'DASHBOARD': return <OwnerAccountingSummaryPanel key={contentKey} />;
            case 'PROPERTIES': return <PropertyManager key={contentKey} />;
            case 'TENANTS': return <TenantManager key={contentKey} />;
            case 'TASKS': return isOwnerOrAdmin ? <ActionTaskInbox key={contentKey} /> : <div className="text-slate-500">Acceso denegado.</div>;
            case 'WORKFLOW': return isOwnerOrAdmin ? <OwnerWorkflowInbox key={contentKey} /> : <div className="text-slate-500">Acceso denegado.</div>;
            case 'AGREEMENTS': return isOwnerOrAdmin ? <AgreementManager key={contentKey} /> : <div className="text-slate-500">Acceso denegado.</div>;
            case 'BUDGETS': return <MaintenanceBudgetManager key={contentKey} />;
            case 'TEAM': return isOwnerOrAdmin ? <OwnerTeamHub key={contentKey} /> : <div className="text-slate-500">Acceso denegado.</div>;
            case 'PROFILE': return user?.role === 'OWNER' ? <OwnerProfilePanel key={contentKey} /> : <div className="text-slate-500">Acceso denegado.</div>;
            case 'NOTIFS': {
                // V52 — SUPER_ADMIN ya no llega aquí (solo OWNER/staff usa este layout),
                // pero mantenemos el guard para defensa en profundidad.
                const canSeeHistory = user?.role !== 'SUPER_ADMIN';
                return (
                    <div className="space-y-4" key={contentKey}>
                        {canSeeHistory && (
                            <div className="flex gap-2 border-b border-slate-200 pb-0">
                                <button
                                    onClick={() => setNotifsSubTab('PREFS')}
                                    className={`px-4 py-2 text-sm font-semibold border-b-2 -mb-px ${
                                        notifsSubTab === 'PREFS'
                                            ? 'text-indigo-600 border-indigo-500'
                                            : 'text-slate-500 border-transparent hover:text-slate-700'
                                    }`}
                                >
                                    Preferencias
                                </button>
                                <button
                                    onClick={() => setNotifsSubTab('HISTORY')}
                                    className={`px-4 py-2 text-sm font-semibold border-b-2 -mb-px ${
                                        notifsSubTab === 'HISTORY'
                                            ? 'text-indigo-600 border-indigo-500'
                                            : 'text-slate-500 border-transparent hover:text-slate-700'
                                    }`}
                                >
                                    Historial de envíos
                                </button>
                            </div>
                        )}
                        {(!canSeeHistory || notifsSubTab === 'PREFS') && <NotificationPreferencesPanel />}
                        {canSeeHistory && notifsSubTab === 'HISTORY' && <NotificationHistoryPanel mode="owner" />}
                    </div>
                );
            }
            default: return <OwnerAccountingSummaryPanel key={contentKey} />;
        }
    };

    return (
        <div className="flex h-screen bg-slate-50">
            {/* Sidebar. `print:hidden` permite que window.print() desde paneles
                como PropertyDetailView / OwnerAccountingSummaryPanel imprima solo
                el contenido central sin la navegación lateral. */}
            <aside className="w-64 bg-white border-r border-slate-200 flex flex-col print:hidden">
                <div className="p-6 border-b border-slate-200">
                    <h2 className="text-xl font-bold text-slate-900">ADMINDI</h2>
                    <p className="text-sm text-slate-500 mt-1">{user?.name}</p>
                    <span className="inline-flex mt-1 px-2 py-0.5 text-xs font-semibold rounded-full bg-slate-100 text-slate-600">
                        {user?.role === 'SUPER_ADMIN' ? 'Super Admin' :
                         user?.role === 'OWNER' ? 'Dueño' :
                         user?.role === 'PROPERTY_ADMIN' ? 'Administrador' :
                         user?.role === 'ACCOUNTANT' ? 'Contador' :
                         user?.role}
                    </span>
                </div>

                {/* Context Selector — visible when user has multiple memberships */}
                {(activeContextName || hasMultipleContexts) && (
                    <div className="px-4 py-3 border-b border-slate-100">
                        <p className="text-[10px] font-bold text-slate-400 uppercase tracking-wider mb-1.5 flex items-center gap-1">
                            <ArrowRightLeft className="w-3 h-3" /> Contexto activo
                        </p>
                        {hasMultipleContexts ? (
                            <div className="relative">
                                <button
                                    onClick={() => setContextDropdownOpen(!contextDropdownOpen)}
                                    disabled={switchingContext}
                                    className="w-full flex items-center justify-between gap-2 px-3 py-2 bg-indigo-50 border border-indigo-200 rounded-lg text-sm font-semibold text-indigo-700 hover:bg-indigo-100 transition-colors disabled:opacity-60"
                                >
                                    <span className="truncate">{switchingContext ? 'Cambiando...' : (activeContextName || 'Seleccionar...')}</span>
                                    <ChevronDown className={`w-4 h-4 shrink-0 transition-transform ${contextDropdownOpen ? 'rotate-180' : ''}`} />
                                </button>
                                {contextDropdownOpen && (
                                    <>
                                        <div className="fixed inset-0 z-10" onClick={() => setContextDropdownOpen(false)} />
                                        <div className="absolute left-0 right-0 top-full mt-1 bg-white border border-slate-200 rounded-lg shadow-xl z-20 overflow-hidden animate-in fade-in slide-in-from-top-2 duration-150">
                                            {contexts.map(ctx => (
                                                <button
                                                    key={ctx.id}
                                                    onClick={() => handleSwitchContext(ctx.id)}
                                                    className={`w-full text-left px-3 py-2.5 text-sm transition-colors flex items-center gap-2 ${
                                                        ctx.id === user?.contextId
                                                            ? 'bg-indigo-50 text-indigo-700 font-bold'
                                                            : 'text-slate-700 hover:bg-slate-50 font-medium'
                                                    }`}
                                                >
                                                    <Building2 className="w-4 h-4 shrink-0 text-slate-400" />
                                                    <span className="truncate">{ctx.name}</span>
                                                    {ctx.id === user?.contextId && (
                                                        <span className="ml-auto text-[10px] bg-indigo-100 text-indigo-600 px-1.5 py-0.5 rounded-full font-bold">Activo</span>
                                                    )}
                                                </button>
                                            ))}
                                        </div>
                                    </>
                                )}
                            </div>
                        ) : (
                            <div className="flex items-center gap-2 px-3 py-2 bg-slate-50 border border-slate-200 rounded-lg text-sm font-medium text-slate-600">
                                <Building2 className="w-4 h-4 shrink-0 text-slate-400" />
                                <span className="truncate">{activeContextName || 'Sin contexto'}</span>
                            </div>
                        )}
                    </div>
                )}

                <nav className="flex-1 p-4 space-y-1 overflow-y-auto">
                    {tabs.map((tab) => (
                        <button
                            key={tab.id}
                            onClick={() => setCurrentTab(tab.id)}
                            className={`flex items-center gap-3 w-full px-3 py-2 rounded-lg text-sm font-medium transition-colors ${
                                currentTab === tab.id
                                    ? 'bg-indigo-50 text-indigo-700'
                                    : 'text-slate-600 hover:bg-slate-50'
                            }`}
                        >
                            {tab.icon}
                            <span className="flex-1 text-left">{tab.label}</span>
                            {tab.badge !== undefined && tab.badge > 0 && (
                                <span className={`ml-auto bg-rose-500 text-white text-[10px] font-bold rounded-full min-w-[20px] h-5 px-1.5 flex items-center justify-center ${
                                    currentTab === tab.id ? '' : 'animate-pulse'
                                }`}>
                                    {tab.badge > 9 ? '9+' : tab.badge}
                                </span>
                            )}
                        </button>
                    ))}
                </nav>
                {/* Logout */}
                <div className="p-4 border-t border-slate-200">
                    <button
                        onClick={handleLogout}
                        className="flex items-center gap-3 w-full px-3 py-2 rounded-lg text-sm font-medium text-rose-600 hover:bg-rose-50 transition-colors"
                    >
                        <LogOut className="w-4 h-4" /> Cerrar Sesión
                    </button>
                </div>
            </aside>

            {/* Content */}
            <main className="flex-1 overflow-auto">
                <div className="max-w-7xl mx-auto p-8 space-y-4">
                    {isOwnerOrAdmin && ownerHasReceiverAccount === false && currentTab !== 'PROFILE' && (
                        <div className="w-full bg-gradient-to-r from-amber-50 to-orange-50 border border-amber-200 rounded-2xl p-4 flex items-center justify-between gap-4">
                            <div className="flex items-center gap-3 text-left">
                                <div className="w-10 h-10 rounded-full bg-white border border-amber-200 flex items-center justify-center text-amber-600 shrink-0">
                                    <AlertCircle className="w-5 h-5" />
                                </div>
                                <div>
                                    <p className="text-sm font-bold text-amber-900">
                                        La validacion automatica con Banxico esta desactivada para tu cuenta
                                    </p>
                                    <p className="text-xs text-amber-800">
                                        Registra tu cuenta receptora con CLABE, banco y titular en Mi perfil. Mientras no este completa, los SPEI de renta quedaran en validacion manual.
                                    </p>
                                </div>
                            </div>
                            <button
                                type="button"
                                onClick={() => setCurrentTab('PROFILE')}
                                className="inline-flex items-center gap-1 px-3 py-2 rounded-xl bg-amber-600 text-white text-sm font-bold hover:bg-amber-700 shrink-0"
                            >
                                Ir a Mi perfil <ArrowRight className="w-4 h-4" />
                            </button>
                        </div>
                    )}
                    {/* V65 — Banner superior: aparece cuando hay acciones pendientes
                        y el dueño no está ya en la Bandeja. Da un CTA directo a la
                        sección. Se colapsa sólo si el total vuelve a 0. */}
                    {isOwnerOrAdmin && totalPending > 0 && currentTab !== 'WORKFLOW' && (
                        <button
                            type="button"
                            onClick={() => setCurrentTab('WORKFLOW')}
                            className="w-full bg-gradient-to-r from-rose-50 to-amber-50 border border-rose-200 rounded-2xl p-4 flex items-center justify-between gap-4 hover:from-rose-100 hover:to-amber-100 transition-colors"
                        >
                            <div className="flex items-center gap-3 text-left">
                                <div className="w-10 h-10 rounded-full bg-white border border-rose-200 flex items-center justify-center text-rose-600 shrink-0">
                                    <AlertCircle className="w-5 h-5" />
                                </div>
                                <div>
                                    <p className="text-sm font-bold text-rose-900">
                                        Tienes {totalPending} {totalPending === 1 ? 'acción pendiente' : 'acciones pendientes'}
                                    </p>
                                    <p className="text-xs text-rose-700">
                                        Autorizaciones, comprobantes, cotizaciones y pagos esperan tu decisión.
                                    </p>
                                </div>
                            </div>
                            <span className="inline-flex items-center gap-1 text-sm font-bold text-rose-700">
                                Ir a bandeja <ArrowRight className="w-4 h-4" />
                            </span>
                        </button>
                    )}
                    {renderContent()}
                </div>
            </main>
        </div>
    );
};
