import { useEffect, useMemo, useState } from "react";
import { useLocation, useNavigate } from "react-router-dom";
import {
  accountActivationService,
  ActivationInfo,
} from "../services/accountActivationService";

/**
 * Pantalla pública de activación de cuentas recién creadas (staff / agente
 * inmobiliario / proveedor de mantenimiento). La ruta es `/activate?token=xxx`
 * y el token viene del email / WhatsApp que el usuario recibió al ser dado
 * de alta por el SUPER_ADMIN o por un OWNER.
 *
 * Flujo:
 *   1. Al montar, se llama a GET /api/auth/activation/info?token=xxx para
 *      validar el link sin exponer información sensible. El backend devuelve
 *      solo userName y userEmail enmascarado.
 *   2. Si el link es válido, se muestra un formulario con la nueva contraseña
 *      (con validador de fortaleza mínima) y confirmación.
 *   3. El POST a /api/auth/activate consume el token, fija la contraseña y
 *      redirige al /login para iniciar sesión normalmente.
 */
export default function ActivateAccount() {
  const navigate = useNavigate();
  const location = useLocation();
  const token = useMemo(() => {
    const qs = new URLSearchParams(location.search);
    return qs.get("token") ?? "";
  }, [location.search]);

  const [loadingInfo, setLoadingInfo] = useState(true);
  const [info, setInfo] = useState<ActivationInfo | null>(null);
  const [password, setPassword] = useState("");
  const [confirm, setConfirm] = useState("");
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [success, setSuccess] = useState(false);

  useEffect(() => {
    if (!token) {
      setLoadingInfo(false);
      return;
    }
    (async () => {
      try {
        const res = await accountActivationService.inspect(token);
        setInfo(res);
      } catch {
        setInfo({ usable: false, userName: null, userEmail: null, expiresAt: null });
      } finally {
        setLoadingInfo(false);
      }
    })();
  }, [token]);

  const passwordTooShort = password.length > 0 && password.length < 8;
  const passwordsMismatch =
    password.length > 0 && confirm.length > 0 && password !== confirm;
  const canSubmit =
    !submitting &&
    password.length >= 8 &&
    confirm.length >= 8 &&
    password === confirm;

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!canSubmit) return;
    setSubmitting(true);
    setError(null);
    try {
      const res = await accountActivationService.activate(token, password);
      if (res.ok) {
        setSuccess(true);
        setTimeout(() => navigate("/login", { replace: true }), 1800);
      } else {
        setError(res.error ?? "No se pudo activar la cuenta.");
      }
    } catch (err: unknown) {
      const maybe = err as { response?: { data?: { error?: string } } };
      setError(maybe?.response?.data?.error ?? "Error al activar la cuenta. Intenta de nuevo.");
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <div style={styles.page}>
      <div style={styles.card}>
        <h1 style={styles.title}>Activar cuenta</h1>
        <p style={styles.subtitle}>ADMINDI · Plataforma de administración inmobiliaria</p>

        {!token && (
          <Message tone="error">
            El enlace de activación no es válido. Solicita a tu administrador que te reenvíe el link.
          </Message>
        )}

        {token && loadingInfo && <Message tone="info">Verificando link...</Message>}

        {token && !loadingInfo && info && !info.usable && (
          <Message tone="error">
            Este enlace expiró o ya fue usado. Pide a tu administrador que te reenvíe el link.
          </Message>
        )}

        {token && !loadingInfo && info && info.usable && !success && (
          <>
            <div style={styles.identityBlock}>
              <div style={styles.identityRow}>
                <span style={styles.identityLabel}>Cuenta:</span>
                <span style={styles.identityValue}>{info.userName ?? "(sin nombre)"}</span>
              </div>
              <div style={styles.identityRow}>
                <span style={styles.identityLabel}>Correo:</span>
                <span style={styles.identityValue}>{info.userEmail ?? "—"}</span>
              </div>
              {info.expiresAt && (
                <div style={styles.identityRow}>
                  <span style={styles.identityLabel}>Expira:</span>
                  <span style={styles.identityValue}>
                    {new Date(info.expiresAt).toLocaleString()}
                  </span>
                </div>
              )}
            </div>

            <form onSubmit={handleSubmit} style={styles.form}>
              <label style={styles.label}>
                Nueva contraseña
                <input
                  type="password"
                  value={password}
                  onChange={(e) => setPassword(e.target.value)}
                  minLength={8}
                  required
                  autoComplete="new-password"
                  style={styles.input}
                />
              </label>
              <label style={styles.label}>
                Confirmar contraseña
                <input
                  type="password"
                  value={confirm}
                  onChange={(e) => setConfirm(e.target.value)}
                  minLength={8}
                  required
                  autoComplete="new-password"
                  style={styles.input}
                />
              </label>

              {passwordTooShort && (
                <div style={styles.hintError}>La contraseña debe tener al menos 8 caracteres.</div>
              )}
              {passwordsMismatch && (
                <div style={styles.hintError}>Las contraseñas no coinciden.</div>
              )}

              {error && <Message tone="error">{error}</Message>}

              <button type="submit" disabled={!canSubmit} style={canSubmit ? styles.btnPrimary : styles.btnDisabled}>
                {submitting ? "Activando..." : "Activar cuenta"}
              </button>
            </form>

            <p style={styles.security}>
              Este enlace es de un solo uso. Si sospechas que alguien más lo recibió, no lo uses
              y pide a tu administrador que lo reemita.
            </p>
          </>
        )}

        {success && (
          <Message tone="success">
            ¡Cuenta activada! Te llevamos al inicio de sesión...
          </Message>
        )}
      </div>
    </div>
  );
}

function Message({
  tone,
  children,
}: {
  tone: "info" | "error" | "success";
  children: React.ReactNode;
}) {
  const bg =
    tone === "error" ? "#fee2e2" : tone === "success" ? "#dcfce7" : "#dbeafe";
  const fg =
    tone === "error" ? "#991b1b" : tone === "success" ? "#166534" : "#1e3a8a";
  return (
    <div
      style={{
        background: bg,
        color: fg,
        padding: "12px 14px",
        borderRadius: 8,
        fontSize: 14,
        margin: "8px 0",
      }}
    >
      {children}
    </div>
  );
}

const styles: Record<string, React.CSSProperties> = {
  page: {
    minHeight: "100vh",
    background: "linear-gradient(135deg, #0f172a 0%, #1e293b 100%)",
    display: "flex",
    alignItems: "center",
    justifyContent: "center",
    padding: 24,
  },
  card: {
    background: "#fff",
    borderRadius: 16,
    padding: "32px 28px",
    boxShadow: "0 10px 30px rgba(0,0,0,0.25)",
    width: "100%",
    maxWidth: 440,
  },
  title: { margin: 0, fontSize: 26, fontWeight: 700, color: "#0f172a" },
  subtitle: { marginTop: 4, marginBottom: 20, fontSize: 13, color: "#64748b" },
  identityBlock: {
    background: "#f8fafc",
    border: "1px solid #e2e8f0",
    borderRadius: 10,
    padding: 14,
    marginBottom: 18,
  },
  identityRow: { display: "flex", gap: 8, fontSize: 14, padding: "2px 0" },
  identityLabel: { color: "#475569", minWidth: 64, fontWeight: 600 },
  identityValue: { color: "#0f172a" },
  form: { display: "flex", flexDirection: "column", gap: 12 },
  label: { display: "flex", flexDirection: "column", fontSize: 14, color: "#334155", fontWeight: 500, gap: 6 },
  input: {
    padding: "10px 12px",
    borderRadius: 8,
    border: "1px solid #cbd5e1",
    fontSize: 15,
    outlineColor: "#2563eb",
  },
  hintError: { color: "#b91c1c", fontSize: 13 },
  btnPrimary: {
    marginTop: 6,
    padding: "12px",
    background: "#2563eb",
    color: "#fff",
    border: "none",
    borderRadius: 8,
    fontSize: 15,
    fontWeight: 600,
    cursor: "pointer",
  },
  btnDisabled: {
    marginTop: 6,
    padding: "12px",
    background: "#94a3b8",
    color: "#fff",
    border: "none",
    borderRadius: 8,
    fontSize: 15,
    fontWeight: 600,
    cursor: "not-allowed",
  },
  security: { marginTop: 18, fontSize: 12, color: "#64748b", textAlign: "center" as const },
};
