import React, { useState, useEffect } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { useAuth } from '../context/AuthContext';
import { Shield, Loader2, Building, KeyRound, QrCode } from 'lucide-react';
import api from '../services/api';

export const Login = () => {
  const { completeLogin } = useAuth();
  const navigate = useNavigate();
  const [searchParams] = useSearchParams();

  useEffect(() => {
    if (searchParams.get('orgPicker') !== '1' || localStorage.getItem('pendingOrgSelection') !== '1') {
      return;
    }
    const raw = localStorage.getItem('pendingOrganizations');
    const token = localStorage.getItem('token');
    if (raw && token) {
      try {
        const orgs = JSON.parse(raw) as Record<string, string>;
        setTempToken(token);
        setOrganizations(orgs);
        setRequiresOrgSelection(true);
      } catch {
        /* ignore */
      }
    }
    localStorage.removeItem('pendingOrgSelection');
    localStorage.removeItem('pendingOrganizations');
  }, [searchParams]);

  // V50 — identidad por username. El campo de login es `username`. Dejamos un
  // fallback silencioso para pegar emails legacy: el backend los resuelve vía
  // findByLoginIdentifier durante la ventana de transición.
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState('');
  const [isLoading, setIsLoading] = useState(false);
  
  // Org selection state
  const [requiresOrgSelection, setRequiresOrgSelection] = useState(false);
  const [tempToken, setTempToken] = useState('');
  const [organizations, setOrganizations] = useState<Record<string, string>>({});

  // MFA states
  const [mfaMode, setMfaMode] = useState<'NONE' | 'VERIFY' | 'SETUP'>('NONE');
  const [mfaCode, setMfaCode] = useState('');
  const [mfaQrUri, setMfaQrUri] = useState('');
  const [mfaRawSecret, setMfaRawSecret] = useState('');
  const [mfaChallengeToken, setMfaChallengeToken] = useState('');

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setError('');
    setIsLoading(true);

    try {
      // V51 — case-sensitive: enviamos el username tal cual lo tipeó el
      // usuario (solo trim). Mayúsculas y minúsculas son significativas en el
      // índice único del servidor, así que normalizar acá rompería logins
      // legítimos. El email fallback se resuelve server-side.
      const response = await api.post('/auth/login', { username: username.trim(), password });
      const data = response.data;

      if (data.mfaSetupRequired) {
        // User must configure MFA before proceeding
        const challengeToken = data.mfaChallengeToken || data.token;
        setMfaChallengeToken(challengeToken);
        // Call setup endpoint to get QR code
        try {
          const setupRes = await api.post('/auth/mfa/setup', {}, {
            headers: { Authorization: `Bearer ${challengeToken}` }
          });
          setMfaQrUri(setupRes.data.secretImageUri);
          setMfaRawSecret(setupRes.data.rawSecret);
          setMfaMode('SETUP');
        } catch (err) {
          setError('Error al configurar MFA');
        }
        return;
      }

      if (data.mfaChallengeToken) {
        // User has MFA configured, backend sent a challenge token proving first factor passed
        setMfaChallengeToken(data.mfaChallengeToken);
        setMfaMode('VERIFY');
        return;
      }

      if (data.requiresOrgSelection) {
        setTempToken(data.token);
        setOrganizations(data.organizations);
        setRequiresOrgSelection(true);
        return;
      }
      
      completeLogin(data, username);
      navigate('/dashboard');
    } catch (err: any) {
      setError(err.response?.data?.message || 'Credenciales inválidas o servidor inalcanzable');
    } finally {
      setIsLoading(false);
    }
  };

  const handleMfaVerify = async () => {
    setIsLoading(true);
    setError('');
    try {
      const response = await api.post('/auth/mfa/verify', { username: username.trim(), code: mfaCode, challengeToken: mfaChallengeToken });
      const data = response.data;

      if (data.requiresOrgSelection) {
        setTempToken(data.token);
        setOrganizations(data.organizations);
        setRequiresOrgSelection(true);
        setMfaMode('NONE');
        return;
      }

      completeLogin(data, username);
      navigate('/dashboard');
    } catch (err: any) {
      setError(err.response?.data?.message || 'Código MFA inválido');
    } finally {
      setIsLoading(false);
    }
  };

  const handleMfaSetupVerify = async () => {
    setIsLoading(true);
    setError('');
    try {
      const response = await api.post('/auth/mfa/verify', { username: username.trim(), code: mfaCode, challengeToken: mfaChallengeToken });
      const data = response.data;

      if (data.requiresOrgSelection) {
        setTempToken(data.token);
        setOrganizations(data.organizations);
        setRequiresOrgSelection(true);
        setMfaMode('NONE');
        return;
      }

      completeLogin(data, username);
      navigate('/dashboard');
    } catch (err: any) {
      setError(err.response?.data?.message || 'Código MFA inválido. Escanea el QR e intenta de nuevo.');
    } finally {
      setIsLoading(false);
    }
  };

  const handleSelectOrg = async (orgId: string) => {
    setIsLoading(true);
    setError('');
    try {
      const response = await api.post('/auth/select-context', { contextId: orgId }, {
        headers: { Authorization: `Bearer ${tempToken}` }
      });
      const effectiveUsername = username || localStorage.getItem('username') || localStorage.getItem('email') || '';
      completeLogin(response.data, effectiveUsername);
      navigate('/dashboard');
    } catch (err: any) {
      setError(err.response?.data?.message || 'Error al conectar con la inmobiliaria');
    } finally {
      setIsLoading(false);
    }
  };

  // -- MFA Setup Screen --
  if (mfaMode === 'SETUP') {
    return (
      <div className="min-h-screen bg-slate-50 flex items-center justify-center p-4">
        <div className="max-w-md w-full space-y-6 bg-white p-8 rounded-2xl shadow-xl border border-slate-100">
          <div className="text-center">
            <div className="mx-auto h-16 w-16 bg-amber-500 rounded-xl flex items-center justify-center shadow-lg shadow-amber-500/30">
              <QrCode className="w-8 h-8 text-white" />
            </div>
            <h2 className="mt-4 text-2xl font-extrabold text-slate-900">Configura tu MFA</h2>
            <p className="mt-2 text-sm text-slate-500">La autenticación de dos factores es obligatoria para tu rol. Escanea el código QR con Google Authenticator o Authy.</p>
          </div>

          {error && (
            <div className="bg-red-50 text-red-600 p-3 rounded-lg text-sm font-semibold border border-red-100 text-center">{error}</div>
          )}

          <div className="flex justify-center">
            {mfaQrUri && <img src={mfaQrUri} alt="QR Code MFA" className="w-48 h-48 rounded-lg border border-slate-200" />}
          </div>

          <div className="bg-slate-50 p-3 rounded-lg border border-slate-200">
            <p className="text-xs text-slate-500 mb-1">Si no puedes escanear el QR, ingresa esta clave manualmente:</p>
            <code className="text-sm font-mono text-slate-900 break-all select-all">{mfaRawSecret}</code>
          </div>

          <div>
            <label className="block text-sm font-semibold text-slate-700 mb-1">Código de verificación (6 dígitos)</label>
            <input 
              value={mfaCode} 
              onChange={e => setMfaCode(e.target.value)} 
              type="text" 
              inputMode="numeric"
              maxLength={6}
              placeholder="000000" 
              className="block w-full px-4 py-3 border border-slate-300 rounded-lg focus:ring-2 focus:ring-amber-500 outline-none text-center text-2xl tracking-[0.5em] font-mono"
            />
          </div>

          <button 
            onClick={handleMfaSetupVerify} 
            disabled={isLoading || mfaCode.length !== 6}
            className="w-full py-3 bg-amber-600 hover:bg-amber-700 text-white font-bold rounded-lg disabled:opacity-50 transition-colors flex items-center justify-center gap-2"
          >
            {isLoading ? <Loader2 className="w-5 h-5 animate-spin" /> : <><KeyRound className="w-5 h-5" /> Verificar y Activar MFA</>}
          </button>
        </div>
      </div>
    );
  }

  // -- MFA Verify Screen --
  if (mfaMode === 'VERIFY') {
    return (
      <div className="min-h-screen bg-slate-50 flex items-center justify-center p-4">
        <div className="max-w-md w-full space-y-6 bg-white p-8 rounded-2xl shadow-xl border border-slate-100">
          <div className="text-center">
            <div className="mx-auto h-16 w-16 bg-brand-500 rounded-xl flex items-center justify-center shadow-lg shadow-brand-500/30">
              <KeyRound className="w-8 h-8 text-white" />
            </div>
            <h2 className="mt-4 text-2xl font-extrabold text-slate-900">Verificación MFA</h2>
            <p className="mt-2 text-sm text-slate-500">Ingresa el código de 6 dígitos de tu aplicación autenticadora.</p>
          </div>

          {error && (
            <div className="bg-red-50 text-red-600 p-3 rounded-lg text-sm font-semibold border border-red-100 text-center">{error}</div>
          )}

          <div>
            <input 
              value={mfaCode} 
              onChange={e => setMfaCode(e.target.value)} 
              type="text"
              inputMode="numeric"
              maxLength={6}
              placeholder="000000" 
              className="block w-full px-4 py-3 border border-slate-300 rounded-lg focus:ring-2 focus:ring-brand-500 outline-none text-center text-2xl tracking-[0.5em] font-mono"
              autoFocus
            />
          </div>

          <button 
            onClick={handleMfaVerify} 
            disabled={isLoading || mfaCode.length !== 6}
            className="w-full py-3 bg-slate-900 hover:bg-black text-white font-bold rounded-lg disabled:opacity-50 transition-colors flex items-center justify-center gap-2"
          >
            {isLoading ? <Loader2 className="w-5 h-5 animate-spin" /> : 'Verificar e Ingresar'}
          </button>

          <button onClick={() => { setMfaMode('NONE'); setMfaCode(''); setError(''); }} className="w-full text-sm text-slate-500 hover:text-slate-700">
            ← Volver al login
          </button>
        </div>
      </div>
    );
  }

  return (
    <div className="min-h-screen bg-slate-50 flex items-center justify-center p-4">
      <div className="max-w-md w-full space-y-8 bg-white p-8 rounded-2xl shadow-xl border border-slate-100">
        <div className="text-center">
          <div className="mx-auto h-16 w-16 bg-brand-500 rounded-xl flex items-center justify-center shadow-lg shadow-brand-500/30">
            <Shield className="w-8 h-8 text-white" />
          </div>
          <h2 className="mt-6 text-3xl font-extrabold text-slate-900 tracking-tight">
            ADMINDI SaaS
          </h2>
          <p className="mt-2 text-sm text-slate-500 font-medium">Autenticación Segura (JWT RS256 + MFA)</p>
        </div>

        {requiresOrgSelection ? (
          <div className="mt-8 space-y-4">
            <h3 className="text-lg font-bold text-slate-800 text-center">Selecciona tu Inmobiliaria</h3>
            <p className="text-sm text-slate-500 text-center mb-6">Tu usuario tiene acceso a múltiples paneles. ¿A cuál deseas entrar ahora?</p>
            
            <div className="space-y-3">
              {Object.entries(organizations).map(([orgId, orgName]) => (
                <button
                  key={orgId}
                  onClick={() => handleSelectOrg(orgId)}
                  disabled={isLoading}
                  className="w-full flex items-center justify-between p-4 border border-slate-200 rounded-xl hover:bg-brand-50 hover:border-brand-300 transition-all group disabled:opacity-50"
                >
                  <div className="flex items-center gap-3">
                    <div className="bg-brand-100 p-2 rounded-lg text-brand-600 transition-colors">
                      <Building className="w-5 h-5" />
                    </div>
                    <span className="font-bold text-slate-700">{orgName}</span>
                  </div>
                  <div className="text-brand-500 opacity-0 group-hover:opacity-100 transition-opacity">
                    &rarr;
                  </div>
                </button>
              ))}
            </div>
          </div>
        ) : (
          <>
            {error && (
          <div className="bg-red-50 text-red-600 p-3 rounded-lg text-sm font-semibold border border-red-100 text-center">
            {error}
          </div>
        )}

        <form className="mt-8 space-y-6" onSubmit={handleSubmit}>
          <div className="space-y-4">
            <div>
              <label htmlFor="username" className="block text-sm font-semibold text-slate-700"> Usuario </label>
              <input
                id="username"
                value={username}
                onChange={(e) => setUsername(e.target.value)}
                type="text"
                autoComplete="username"
                required
                className="mt-1 block w-full px-4 py-3 border border-slate-300 rounded-lg focus:ring-2 focus:ring-brand-500 outline-none transition-all"
                placeholder="Tu-Usuario"
              />
              <p className="mt-1 text-xs text-slate-500">Ingresa el nombre de usuario que te asignó tu administrador. <strong>Distingue mayúsculas y minúsculas.</strong></p>
            </div>
            <div>
              <label htmlFor="password" className="block text-sm font-semibold text-slate-700"> Contraseña Segura </label>
              <input id="password" value={password} onChange={(e) => setPassword(e.target.value)} type="password" required className="mt-1 block w-full px-4 py-3 border border-slate-300 rounded-lg focus:ring-2 focus:ring-brand-500 outline-none transition-all" placeholder="•••••••••" />
            </div>
          </div>

          <div className="flex justify-end">
            <a
              href={`https://wa.me/525559618425?text=${encodeURIComponent("Hola, he olvidado la contraseña de mi cuenta en la plataforma ADMINDI. Solicito un restablecimiento de clave, estoy de acuerdo con el cargo administrativo de $500 MXN por la gestión.")}`}
              target="_blank"
              rel="noreferrer"
              className="text-sm font-bold text-brand-600 hover:text-brand-700 hover:underline transition-colors"
            >
              ¿Olvidaste tu contraseña?
            </a>
          </div>

          <div className="pt-2">
            <button type="submit" disabled={isLoading} className="w-full flex items-center justify-center gap-2 py-3 px-4 border border-transparent text-sm font-bold rounded-lg text-white bg-slate-900 hover:bg-black focus:ring-2 focus:ring-offset-2 focus:ring-slate-900 shadow-md transition-all disabled:opacity-70 disabled:cursor-not-allowed">
              {isLoading ? <Loader2 className="w-5 h-5 animate-spin" /> : 'Verificar e Ingresar'}
            </button>
          </div>
        </form>
        </>
        )}
      </div>
    </div>
  );
};

