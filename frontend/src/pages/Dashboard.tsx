import React, { Suspense } from 'react';
import { useAuth } from '../context/AuthContext';
import { LogOut, HardHat, Briefcase } from 'lucide-react';
import { useNavigate } from 'react-router-dom';

// Code-splitting por rol: cada portal se descarga solo cuando ese rol inicia
// sesión, en lugar de cargar los 7 dashboards en el bundle inicial.
const OwnerDashboardLayout = React.lazy(() => import('./OwnerDashboardLayout').then(m => ({ default: m.OwnerDashboardLayout })));
const TenantDashboardLayout = React.lazy(() => import('./TenantDashboardLayout').then(m => ({ default: m.TenantDashboardLayout })));
const AccountantDashboardLayout = React.lazy(() => import('./AccountantDashboardLayout').then(m => ({ default: m.AccountantDashboardLayout })));
const RealEstateAgentDashboardLayout = React.lazy(() => import('./RealEstateAgentDashboardLayout').then(m => ({ default: m.RealEstateAgentDashboardLayout })));
const MaintenanceProviderDashboardLayout = React.lazy(() => import('./MaintenanceProviderDashboardLayout').then(m => ({ default: m.MaintenanceProviderDashboardLayout })));
const AdminOwners = React.lazy(() => import('./AdminOwners').then(m => ({ default: m.AdminOwners })));
const AdminProviders = React.lazy(() => import('./AdminProviders').then(m => ({ default: m.AdminProviders })));
const AdminAudit = React.lazy(() => import('./AdminAudit').then(m => ({ default: m.AdminAudit })));
const GlobalSearchManager = React.lazy(() => import('./GlobalSearchManager').then(m => ({ default: m.GlobalSearchManager })));
const AdminBanxicoMonitor = React.lazy(() => import('./AdminBanxicoMonitor').then(m => ({ default: m.AdminBanxicoMonitor })));
const NotificationPreferencesPanel = React.lazy(() => import('./NotificationPreferencesPanel').then(m => ({ default: m.NotificationPreferencesPanel })));
const NotificationArchivePanel = React.lazy(() => import('./NotificationArchivePanel').then(m => ({ default: m.NotificationArchivePanel })));

const DashboardLoading = () => (
  <div className="min-h-screen bg-slate-50 flex items-center justify-center">
    <div className="flex flex-col items-center gap-3">
      <div className="w-10 h-10 border-4 border-brand-500 border-t-transparent rounded-full animate-spin" />
      <span className="text-sm font-medium text-slate-500">Cargando tu portal…</span>
    </div>
  </div>
);

/** Placeholder shell for roles whose portal is not yet built */
const RolePlaceholder: React.FC<{ roleName: string; icon: React.ReactNode; color: string }> = ({ roleName, icon, color }) => {
  const { user, logout } = useAuth();
  const navigate = useNavigate();

  const handleLogout = async () => {
    await logout();
    navigate('/login');
  };

  return (
    <div className="min-h-screen bg-slate-50 flex flex-col">
      <header className="bg-white border-b border-slate-200">
        <div className="max-w-3xl mx-auto px-4 h-16 flex items-center justify-between">
          <div className="flex items-center gap-3">
            <div className={`w-8 h-8 ${color} rounded flex items-center justify-center text-white`}>
              {icon}
            </div>
            <h1 className="text-lg font-bold text-slate-900">ADMINDI</h1>
            <span className="text-xs font-bold bg-slate-100 text-slate-600 px-2 py-0.5 rounded-full">{roleName}</span>
          </div>
          <div className="flex items-center gap-4">
            <span className="text-sm text-slate-500">{user?.name}</span>
            <button onClick={handleLogout} className="flex items-center gap-2 text-sm font-bold text-rose-600 hover:bg-rose-50 px-3 py-1.5 rounded-lg transition-colors">
              <LogOut className="w-4 h-4" /> Salir
            </button>
          </div>
        </div>
      </header>
      <main className="flex-1 flex items-center justify-center p-8">
        <div className="text-center max-w-md">
          <div className={`w-20 h-20 ${color} rounded-2xl flex items-center justify-center mx-auto mb-6 text-white shadow-lg`}>
            {React.cloneElement(icon as React.ReactElement, { className: 'w-10 h-10' })}
          </div>
          <h2 className="text-2xl font-bold text-slate-800 mb-3">Portal de {roleName}</h2>
          <p className="text-slate-500 mb-6">
            Este portal está en construcción. Pronto tendrás acceso a tus herramientas y funciones específicas.
          </p>
          <div className="inline-flex items-center px-4 py-2 bg-amber-50 border border-amber-200 rounded-xl text-sm text-amber-700 font-medium">
            <HardHat className="w-4 h-4 mr-2" /> En desarrollo — próximamente
          </div>
        </div>
      </main>
    </div>
  );
};

const ROLE_CONFIG: Record<string, { name: string; icon: React.ReactNode; color: string }> = {
  REAL_ESTATE_AGENT: { name: 'Agente Inmobiliario', icon: <Briefcase className="w-5 h-5" />, color: 'bg-purple-500' },
  MAINTENANCE_PROVIDER: { name: 'Proveedor de Mantenimiento', icon: <HardHat className="w-5 h-5" />, color: 'bg-orange-500' },
};

export const Dashboard = () => {
  const { user, logout } = useAuth();
  const navigate = useNavigate();

  const handleLogout = async () => {
    await logout();
    navigate('/login');
  };

  const [rootTab, setRootTab] = React.useState<'B2B' | 'PROVIDERS' | 'AUDIT' | 'RECOVERY' | 'BANXICO' | 'ARCHIVE' | 'NOTIFS'>('B2B');

  // Inquilino B2C
  if (user?.role === 'TENANT') {
    return <Suspense fallback={<DashboardLoading />}><TenantDashboardLayout /></Suspense>;
  }

  // Dueño o Staff administrador
  if (user?.role === 'OWNER' || user?.role === 'PROPERTY_ADMIN') {
    return <Suspense fallback={<DashboardLoading />}><OwnerDashboardLayout /></Suspense>;
  }

  // Contador
  if (user?.role === 'ACCOUNTANT') {
    return <Suspense fallback={<DashboardLoading />}><AccountantDashboardLayout /></Suspense>;
  }

  // Agente inmobiliario
  if (user?.role === 'REAL_ESTATE_AGENT') {
    return <Suspense fallback={<DashboardLoading />}><RealEstateAgentDashboardLayout /></Suspense>;
  }

  // Proveedor de mantenimiento
  if (user?.role === 'MAINTENANCE_PROVIDER') {
    return <Suspense fallback={<DashboardLoading />}><MaintenanceProviderDashboardLayout /></Suspense>;
  }

  // Roles con portal pendiente → placeholder con logout
  const roleConfig = user?.role ? ROLE_CONFIG[user.role] : null;
  if (roleConfig) {
    return <RolePlaceholder roleName={roleConfig.name} icon={roleConfig.icon} color={roleConfig.color} />;
  }

  const tabs = [
    { key: 'B2B' as const, label: 'Gestión de Dueños', activeClass: 'bg-slate-100 text-slate-900' },
    { key: 'PROVIDERS' as const, label: 'Proveedores', activeClass: 'bg-amber-50 text-amber-700' },
    { key: 'AUDIT' as const, label: 'Auditoría', activeClass: 'bg-indigo-50 text-indigo-700' },
    { key: 'RECOVERY' as const, label: 'Búsqueda / Recovery', activeClass: 'bg-red-50 text-red-700' },
    { key: 'BANXICO' as const, label: 'Monitor Banxico', activeClass: 'bg-teal-50 text-teal-700' },
    { key: 'ARCHIVE' as const, label: 'Archivo', activeClass: 'bg-emerald-50 text-emerald-700' },
    { key: 'NOTIFS' as const, label: 'Mis Preferencias', activeClass: 'bg-violet-50 text-violet-700' },
  ];

  // SUPER_ADMIN (Plataforma Root)
  return (
    <div className="min-h-screen bg-slate-50 flex flex-col">
       <header className="bg-white border-b border-slate-200">
         <div className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8 h-16 flex items-center justify-between">
           <div className="flex items-center gap-6">
             <div className="flex items-center gap-2">
               <div className="w-8 h-8 bg-brand-500 rounded flex items-center justify-center">
                 <span className="text-white font-bold text-lg">A</span>
               </div>
               <h1 className="text-xl font-bold text-slate-900">ADMINDI Root</h1>
             </div>
             
             <div className="hidden md:flex items-center space-x-1 border-l border-slate-200 pl-6">
                {tabs.map(t => (
                    <button key={t.key}
                      onClick={() => setRootTab(t.key)} 
                      className={`px-4 py-2 rounded-lg text-sm font-bold transition-colors ${rootTab === t.key ? t.activeClass : 'text-slate-500 hover:bg-slate-50 hover:text-slate-700'}`}
                    >
                      {t.label}
                    </button>
                ))}
             </div>
           </div>
           
           <div className="flex items-center gap-4">
             <span className="text-sm font-medium text-slate-500">
               SysAdmin: {user?.email}
             </span>
             <button onClick={handleLogout} className="flex items-center gap-2 text-sm font-bold text-red-600 hover:bg-red-50 px-3 py-1.5 rounded-lg transition-colors">
               <LogOut className="w-4 h-4" /> Salir
             </button>
           </div>
         </div>
       </header>

       <main className="flex-1 max-w-7xl mx-auto px-4 sm:px-6 lg:px-8 py-8 w-full">
          <Suspense fallback={<DashboardLoading />}>
          {rootTab === 'B2B' ? (
             <AdminOwners />
          ) : rootTab === 'PROVIDERS' ? (
             <AdminProviders />
          ) : rootTab === 'AUDIT' ? (
             <AdminAudit />
          ) : rootTab === 'RECOVERY' ? (
             <GlobalSearchManager />
          ) : rootTab === 'BANXICO' ? (
             <AdminBanxicoMonitor />
          ) : rootTab === 'ARCHIVE' ? (
             <NotificationArchivePanel />
          ) : (
             <NotificationPreferencesPanel />
          )}
          </Suspense>
       </main>
    </div>
  );
};
