import { createContext, useContext, useState, ReactNode, useEffect } from 'react';
import api from '../services/api';

export type Role = 'SUPER_ADMIN' | 'OWNER' | 'PROPERTY_ADMIN' | 'ACCOUNTANT' | 'TENANT' | 'REAL_ESTATE_AGENT' | 'MAINTENANCE_PROVIDER' | null;

interface UserPayload {
  /** V50 — identificador de login del user autenticado (username). Se mantiene
   *  el nombre del campo `email` en memoria/localStorage sólo por compatibilidad
   *  con el resto de la UI que ya lo consumía; el valor que transporta es
   *  SIEMPRE el username normalizado. */
  email: string;
  role: Role;
  name: string;
  mustChangePassword?: boolean;
  onboardingCompleted?: boolean;
  contextId?: string;
}

interface AuthContextType {
  user: UserPayload | null;
  login: (username: string, pass: string) => Promise<{ requiresOrgSelection?: boolean; tempToken?: string; organizations?: Record<string, string>; }>;
  completeLogin: (authData: any, username: string) => void;
  logout: () => Promise<void>;
  switchContext: (contextId: string) => Promise<void>;
  isAuthenticated: boolean;
  isLoadingSession: boolean;
}

const AuthContext = createContext<AuthContextType | undefined>(undefined);

export const AuthProvider = ({ children }: { children: ReactNode }) => {
  const [user, setUser] = useState<UserPayload | null>(null);
  const [isLoadingSession, setIsLoadingSession] = useState(true);

  // Al recargar la página, verificamos si hay un token válido guardado
  useEffect(() => {
    const token = localStorage.getItem('token');
    const role = localStorage.getItem('role') as Role;
    const email = localStorage.getItem('email');
    const mustChangeStr = localStorage.getItem('mustChangePassword');
    const onboardingStr = localStorage.getItem('onboardingCompleted');
    const name = localStorage.getItem('name');
    const contextId = localStorage.getItem('contextId');
    
    if (token && role && email && name) {
      setUser({ 
        email, 
        role, 
        name, 
        mustChangePassword: mustChangeStr === 'true',
        onboardingCompleted: onboardingStr === 'true',
        contextId: contextId || undefined
      });
    }
    setIsLoadingSession(false);
  }, []);

  const login = async (username: string, pass: string) => {
    const response = await api.post('/auth/login', {
      // V51 — case-sensitive: preservar el case tipeado por el usuario.
      username: username.trim(),
      password: pass,
    });

    if (response.data.requiresOrgSelection) {
      return {
        requiresOrgSelection: true,
        tempToken: response.data.token,
        organizations: response.data.organizations
      };
    }

    completeLogin(response.data, username);
    return {};
  };

  const completeLogin = (authData: any, email: string) => {
    const { token, refreshToken, role, mustChangePassword, name, onboardingCompleted, contextId: rawContextId } = authData;
    
    localStorage.setItem('token', token);
    if (refreshToken) localStorage.setItem('refreshToken', refreshToken);
    localStorage.setItem('role', role);
    localStorage.setItem('email', email);
    localStorage.setItem('name', name);
    localStorage.setItem('mustChangePassword', String(mustChangePassword === true));
    localStorage.setItem('onboardingCompleted', String(onboardingCompleted === true));
    
    // Persist context (contextId from backend AuthResponse DTO)
    const contextId = rawContextId || null;
    if (contextId && contextId !== 'null') {
      localStorage.setItem('contextId', contextId);
    } else {
      localStorage.removeItem('contextId');
    }

    setUser({
      email,
      role: role as Role,
      name,
      mustChangePassword: mustChangePassword === true,
      onboardingCompleted: onboardingCompleted === true,
      contextId: (contextId && contextId !== 'null') ? contextId : undefined
    });
  };

  const switchContext = async (newContextId: string) => {
    try {
      const res = await api.post('/auth/select-context', { contextId: newContextId });
      const email = user?.email || localStorage.getItem('email') || '';
      completeLogin(res.data, email);
    } catch (err: any) {
      console.error('Error switching context', err);
      throw err;
    }
  };

  const logout = async () => {
    try {
      if (localStorage.getItem('token')) {
        await api.post('/auth/logout', {});
      }
    } catch {
      // Red o token ya invalido: igual limpiar cliente
    } finally {
      localStorage.removeItem('token');
      localStorage.removeItem('refreshToken');
      localStorage.removeItem('role');
      localStorage.removeItem('email');
      localStorage.removeItem('name');
      localStorage.removeItem('mustChangePassword');
      localStorage.removeItem('onboardingCompleted');
      localStorage.removeItem('contextId');
      setUser(null);
    }
  };

  return (
    <AuthContext.Provider value={{ user, login, completeLogin, logout, switchContext, isAuthenticated: !!user, isLoadingSession }}>
      {!isLoadingSession && children}
    </AuthContext.Provider>
  );
};

export const useAuth = () => {
  const context = useContext(AuthContext);
  if (!context) {
    throw new Error('useAuth debe ser usado dentro de un AuthProvider');
  }
  return context;
};
