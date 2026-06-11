import React from 'react';
import { Navigate, Outlet } from 'react-router-dom';
import { useAuth, Role } from '../context/AuthContext';

interface ProtectedRouteProps {
  allowedRoles: Role[];
}

export const ProtectedRoute: React.FC<ProtectedRouteProps> = ({ allowedRoles }) => {
  const { user, isAuthenticated } = useAuth();

  // Si no está logueado, lo patea al login inmediatamente (Sin filtración de pantalla)
  if (!isAuthenticated || !user) {
    return <Navigate to="/login" replace />;
  }

  // Si su nivel de acceso no es correcto (Ej. Inquilino queriendo entrar a Panel de Administrador)
  if (!allowedRoles.includes(user.role)) {
    return <Navigate to="/no-autorizado" replace />;
  }

  // Si todo es seguro, pinta los "hijos" de la ruta
  return <Outlet />;
};
