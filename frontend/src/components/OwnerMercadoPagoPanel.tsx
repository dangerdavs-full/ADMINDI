import React, { useCallback, useEffect, useState } from 'react';
import { CheckCircle2, AlertCircle, Loader2, Unlink, ExternalLink, Shield } from 'lucide-react';
import { ownerMercadoPagoService, OwnerMpStatus } from '../services/ownerMercadoPagoService';

/** Botón estilo «Continuar con Google» para Mercado Pago */
const MercadoPagoConnectButton: React.FC<{
  onClick: () => void;
  loading?: boolean;
  disabled?: boolean;
}> = ({ onClick, loading, disabled }) => (
  <button
    type="button"
    onClick={onClick}
    disabled={disabled || loading}
    className="w-full flex items-center justify-center gap-3 py-3.5 px-4 bg-[#009ee3] hover:bg-[#0089c7] text-white font-semibold rounded-xl shadow-md transition-all disabled:opacity-60 disabled:cursor-not-allowed"
  >
    {loading ? (
      <Loader2 className="w-5 h-5 animate-spin" />
    ) : (
      <span className="flex items-center justify-center w-8 h-8 rounded-lg bg-white/20 text-sm font-black tracking-tight">
        MP
      </span>
    )}
    <span>{loading ? 'Abriendo Mercado Pago…' : 'Continuar con Mercado Pago'}</span>
  </button>
);

export const OwnerMercadoPagoPanel: React.FC = () => {
  const [status, setStatus] = useState<OwnerMpStatus | null>(null);
  const [loading, setLoading] = useState(true);
  const [connecting, setConnecting] = useState(false);
  const [error, setError] = useState('');
  const [success, setSuccess] = useState('');

  const load = useCallback(async () => {
    setLoading(true);
    setError('');
    try {
      setStatus(await ownerMercadoPagoService.getStatus());
    } catch {
      setError('No se pudo cargar el estado de Mercado Pago.');
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  useEffect(() => {
    const params = new URLSearchParams(window.location.search);
    const mpOwner = params.get('mpOwner');
    if (!mpOwner) return;

    if (mpOwner === 'connected') {
      void (async () => {
        try {
          const s = await ownerMercadoPagoService.getStatus();
          setStatus(s);
          if (s.connected) {
            setSuccess('¡Listo! Tu cuenta de Mercado Pago quedó vinculada. Los inquilinos ya pueden pagarte la renta desde la app.');
            setError('');
          } else {
            setSuccess('');
            setError(
              'Mercado Pago no guardó la vinculación en el servidor. Pulsa «Continuar con Mercado Pago» otra vez. '
              + 'Si persiste, revisa la terminal del backend (busca [MercadoPago] OAuth OK).',
            );
          }
        } catch {
          setError('No se pudo confirmar la vinculación. Recarga la página e intenta de nuevo.');
        }
      })();
    } else if (mpOwner === 'cancelled') {
      setError('No vinculaste la cuenta. Puedes intentarlo de nuevo cuando quieras.');
    } else if (mpOwner === 'error') {
      setError('No pudimos completar la vinculación. Verifica que aceptaste los permisos en Mercado Pago e intenta otra vez.');
    }

    params.delete('mpOwner');
    const q = params.toString();
    window.history.replaceState({}, '', `${window.location.pathname}${q ? `?${q}` : ''}`);
  }, [load]);

  const handleConnect = async () => {
    setConnecting(true);
    setError('');
    setSuccess('');
    try {
      const url = await ownerMercadoPagoService.getOAuthUrl();
      window.location.href = url;
    } catch (e: unknown) {
      const err = e as { response?: { data?: { message?: string } } };
      setError(
        err.response?.data?.message
          || 'No se pudo abrir Mercado Pago. Si el problema continúa, contacta al soporte de ADMINDI.',
      );
      setConnecting(false);
    }
  };

  const handleDisconnect = async () => {
    if (!window.confirm('¿Desvincular Mercado Pago? Los inquilinos no podrán pagar con tarjeta/MP hasta que vuelvas a conectar.')) {
      return;
    }
    setConnecting(true);
    try {
      setStatus(await ownerMercadoPagoService.disconnect());
      setSuccess('');
    } catch {
      setError('No se pudo desvincular.');
    } finally {
      setConnecting(false);
    }
  };

  if (loading) {
    return (
      <div className="flex items-center gap-2 text-slate-500 text-sm py-4">
        <Loader2 className="w-4 h-4 animate-spin" /> Cargando…
      </div>
    );
  }

  return (
    <section className="rounded-2xl border border-slate-200 bg-gradient-to-b from-sky-50/80 to-white p-6 shadow-sm space-y-4">
      <div>
        <h3 className="font-bold text-slate-800 text-lg">Tu cuenta de Mercado Pago</h3>
        <p className="text-sm text-slate-600 mt-1">
          Vincula <strong>tu</strong> cuenta personal o de negocio en Mercado Pago. No es la cuenta de ADMINDI:
          cada arrendador conecta la suya y el dinero de la renta llega directo a ti.
        </p>
      </div>

      {error && (
        <div className="flex items-start gap-2 text-sm text-rose-700 bg-rose-50 border border-rose-200 rounded-lg p-3">
          <AlertCircle className="w-4 h-4 shrink-0 mt-0.5" />
          <span>{error}</span>
        </div>
      )}
      {success && (
        <div className="flex items-start gap-2 text-sm text-emerald-800 bg-emerald-50 border border-emerald-200 rounded-lg p-3">
          <CheckCircle2 className="w-4 h-4 shrink-0 mt-0.5" />
          <span>{success}</span>
        </div>
      )}

      {status?.connected ? (
        <div className="space-y-4">
          <div className="flex items-center gap-3 p-4 rounded-xl border border-emerald-200 bg-emerald-50/80">
            <CheckCircle2 className="w-8 h-8 text-emerald-600 shrink-0" />
            <div>
              <p className="font-semibold text-emerald-900">Cuenta conectada</p>
              <p className="text-xs text-emerald-800 mt-0.5">
                Los inquilinos pagan a tu cuenta de Mercado Pago vinculada (no a la plataforma).
              </p>
            </div>
          </div>
          <button
            type="button"
            onClick={() => void handleDisconnect()}
            disabled={connecting}
            className="inline-flex items-center gap-2 text-sm text-slate-600 hover:text-rose-600"
          >
            <Unlink className="w-4 h-4" /> Desvincular cuenta
          </button>
        </div>
      ) : status?.oauthReady ? (
        <div className="space-y-4">
          <ol className="text-sm text-slate-600 space-y-2 list-decimal list-inside">
            <li>Pulsa el botón azul (como «entrar con Google»).</li>
            <li>Inicia sesión con <strong>tu</strong> cuenta de Mercado Pago (la que ya usas para cobrar).</li>
            <li>Autoriza que ADMINDI genere cobros de renta hacia tu cuenta.</li>
            <li>Vuelves aquí automáticamente — ¡listo!</li>
          </ol>

          <MercadoPagoConnectButton onClick={() => void handleConnect()} loading={connecting} />

          <p className="flex items-start gap-2 text-xs text-slate-500">
            <Shield className="w-3.5 h-3.5 shrink-0 mt-0.5" />
            No pedimos tu contraseña de ADMINDI en Mercado Pago. Solo autorizas cobros de renta de tus inmuebles.
          </p>
        </div>
      ) : (
        <div className="rounded-xl border border-amber-200 bg-amber-50 p-4 text-sm text-amber-900 space-y-3">
          {status?.oauthCredentialsConfigured === false ? (
            <>
              <p className="font-semibold">Falta Client Secret en el servidor</p>
              <p>
                Pon el <strong>Client Secret</strong> en{' '}
                <code className="text-xs">backend/src/main/resources/application-secrets.yml</code>{' '}
                (sección <code className="text-xs">mercadopago.client-secret</code>) y reinicia el backend con{' '}
                <code className="text-xs">./scripts/run-backend-with-ngrok.sh</code>.
              </p>
              <p className="text-xs text-amber-800">
                Si en <code className="text-xs">.env</code> tienes <code className="text-xs">MP_CLIENT_SECRET=</code>{' '}
                vacío, bórralo — una variable vacía pisa el archivo de secrets.
              </p>
            </>
          ) : (
            <>
              <p className="font-semibold">Falta URL pública para Mercado Pago</p>
              <p>
                Mercado Pago no acepta <code className="text-xs">localhost</code>. Ejecuta{' '}
                <code className="text-xs">./scripts/start-ngrok-tunnel.sh</code> y arranca el backend con{' '}
                <code className="text-xs">./scripts/run-backend-with-ngrok.sh</code>.
              </p>
            </>
          )}
          {status?.oauthRedirectUri ? (
            <div className="bg-white/70 rounded-lg p-2 border border-amber-100">
              <p className="text-xs font-semibold text-amber-900 mb-1">URL para pegar en MP Developers → URL de redirección:</p>
              <code className="text-xs break-all select-all">{status.oauthRedirectUri}</code>
            </div>
          ) : (
            <p className="text-xs text-amber-800">
              Guía: <code className="bg-white/60 px-1 rounded">scripts/NGROK_LOCAL.md</code>
            </p>
          )}
        </div>
      )}

      <p className="text-xs text-slate-400 flex items-center gap-1">
        <ExternalLink className="w-3 h-3" />
        Serás redirigido al sitio seguro de mercadopago.com.mx
      </p>
    </section>
  );
};
