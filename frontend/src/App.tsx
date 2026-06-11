import React from 'react';
import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom';
import { AuthProvider } from './context/AuthContext';
import { Login } from './pages/Login';
import { Dashboard } from './pages/Dashboard';
import ActivateAccount from './pages/ActivateAccount';
import { ProtectedRoute } from './components/ProtectedRoute';
import { useAuth } from './context/AuthContext';
import { ForcePasswordChangeModal } from './components/modals/ForcePasswordChangeModal';

const GlobalSecurityTrap = ({ children }: { children: React.ReactNode }) => {
  const { user } = useAuth();
  return (
    <>
      {children}
      {user?.mustChangePassword && (
        <ForcePasswordChangeModal onSuccess={() => {
          localStorage.setItem('mustChangePassword', 'false');
          window.location.reload();
        }} />
      )}
    </>
  );
};

function App() {
  return (
    <AuthProvider>
      <GlobalSecurityTrap>
        <BrowserRouter>
          <Routes>
            <Route path="/login" element={<Login />} />
            {/* Activación pública de cuentas nuevas: /activate?token=xxx
                El token llega por email/WhatsApp al user recién creado y es
                one-shot + TTL 24h; el backend valida sin autenticación previa. */}
            <Route path="/activate" element={<ActivateAccount />} />

            {/* Solo usuarios autenticados con los roles de abajo pueden pasar esta frontera */}
            <Route element={<ProtectedRoute allowedRoles={['SUPER_ADMIN', 'OWNER', 'PROPERTY_ADMIN', 'TENANT', 'ACCOUNTANT', 'REAL_ESTATE_AGENT', 'MAINTENANCE_PROVIDER']} />}>
              <Route path="/dashboard" element={<Dashboard />} />
            </Route>

            {/* Fallback route: si alguien escribe /hack, lo patea al login */}
            <Route path="*" element={<Navigate to="/login" replace />} />
          </Routes>
        </BrowserRouter>
      </GlobalSecurityTrap>
    </AuthProvider>
  );
}

export default App;
