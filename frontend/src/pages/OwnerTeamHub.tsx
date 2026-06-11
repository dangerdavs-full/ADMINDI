import React, { useCallback, useEffect, useState } from "react";
import { HardHat, Briefcase, UserCog, Link2, Unlink, Plus, Building2, UserPlus, Send, ListOrdered } from "lucide-react";
import { StaffManager } from "./StaffManager";
import { ownerTeamService, OwnerProviderLink, PlatformProviderRow, CreatedPrivateProvider } from "../services/ownerTeamService";
import { AgentPrioritiesPanel } from "../components/AgentPrioritiesPanel";
import { useUsernameAvailability } from "../hooks/useUsernameAvailability";
import { UsernameAvailabilityHint } from "../components/UsernameAvailabilityHint";

const TYPE_MAINT = "MAINTENANCE_PROVIDER";
const TYPE_AGENT = "REAL_ESTATE_AGENT";

const DEFAULT_COUNTRY_CODE = "+52";

function sourceBadge(src: string) {
  const s = (src || "").toUpperCase();
  if (s === "PLATFORM") return <span className="text-[10px] font-bold uppercase px-2 py-0.5 rounded-full bg-sky-100 text-sky-800 border border-sky-200">Plataforma</span>;
  if (s === "PRIVATE") return <span className="text-[10px] font-bold uppercase px-2 py-0.5 rounded-full bg-amber-100 text-amber-900 border border-amber-200">Privado</span>;
  return <span className="text-xs text-slate-500">{src || "-"}</span>;
}

/**
 * V63 — Chip "Cuenta activa" del agente:
 *  - true  → verde "Activa" (CLABE + banco + titular registrados, puede cobrar).
 *  - false → ámbar "Pendiente" (el agente aún no completó el onboarding
 *            bancario; el owner no debería asignarle tickets todavía).
 *  - undefined (backend viejo) → gris "—" para no inventar estado.
 */
function accountBadge(active: boolean | undefined) {
  if (active === true) {
    return (
      <span
        title="El agente registró CLABE, banco y titular — puede recibir pagos."
        className="text-[10px] font-bold uppercase px-2 py-0.5 rounded-full bg-emerald-100 text-emerald-800 border border-emerald-200"
      >
        Activa
      </span>
    );
  }
  if (active === false) {
    return (
      <span
        title="El agente aún no ha completado su CLABE, banco o titular. No podrá recibir pagos hasta hacerlo."
        className="text-[10px] font-bold uppercase px-2 py-0.5 rounded-full bg-amber-100 text-amber-900 border border-amber-200"
      >
        Pendiente
      </span>
    );
  }
  return <span className="text-xs text-slate-400">—</span>;
}

const ProviderTable: React.FC<{
  title: string;
  icon: React.ReactNode;
  rows: OwnerProviderLink[];
  catalog: PlatformProviderRow[];
  type: string;
  onRefresh: () => void;
}> = ({ title, icon, rows, catalog, type, onRefresh }) => {
  const [linkOpen, setLinkOpen] = useState(false);
  const [busy, setBusy] = useState<string | null>(null);

  // Alta integral privada: datos completos del contacto (el owner captura todo).
  const [privateName, setPrivateName] = useState("");
  const [privateUsername, setPrivateUsername] = useState("");
  const [privateEmail, setPrivateEmail] = useState("");
  const [privateContactEmail, setPrivateContactEmail] = useState("");
  const [privateCountryCode, setPrivateCountryCode] = useState(DEFAULT_COUNTRY_CODE);
  const [privatePhone, setPrivatePhone] = useState("");
  const [createdCred, setCreatedCred] = useState<CreatedPrivateProvider | null>(null);

  const filtered = rows.filter((r) => r.providerType === type);
  const activeIds = new Set(filtered.filter((r) => r.assignmentActive).map((r) => r.providerUserId));
  const catalogPick = catalog.filter((c) => c.providerType === type && !activeIds.has(c.id));

  const resetPrivateForm = () => {
    setPrivateName("");
    setPrivateUsername("");
    setPrivateEmail("");
    setPrivateContactEmail("");
    setPrivateCountryCode(DEFAULT_COUNTRY_CODE);
    setPrivatePhone("");
  };

  // V51 — username case-sensitive.
  const USERNAME_PATTERN = /^[A-Za-z0-9][A-Za-z0-9._-]{2,63}$/;
  const EMAIL_PATTERN = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;

  // V67 — verificación de disponibilidad en vivo para alta privada.
  const privateUsernameAvailability = useUsernameAvailability(privateUsername);

  const doUnlink = async (providerUserId: string) => {
    if (!window.confirm("Desvincular este contacto de su organizacion?")) return;
    setBusy(providerUserId);
    try {
      await ownerTeamService.unlink(providerUserId);
      await onRefresh();
    } catch (e: unknown) {
      const err = e as { response?: { data?: { message?: string } } };
      alert(err.response?.data?.message || "Error al desvincular");
    } finally {
      setBusy(null);
    }
  };

  const doResendActivation = async (providerUserId: string, name: string) => {
    if (!window.confirm(`Reenviar link de activación a "${name}"?\n\nEl link anterior dejará de funcionar.`)) return;
    setBusy(providerUserId);
    try {
      const res = await ownerTeamService.resendActivation(providerUserId);
      alert(`Link enviado por ${res.channel}. Expira: ${new Date(res.expiresAt).toLocaleString()}`);
    } catch (e: unknown) {
      const err = e as { response?: { data?: { message?: string } } };
      alert(err.response?.data?.message || "Error al reenviar link");
    } finally {
      setBusy(null);
    }
  };

  const doLinkPlatform = async (providerUserId: string) => {
    setBusy(providerUserId);
    try {
      await ownerTeamService.linkPlatform(providerUserId);
      setLinkOpen(false);
      await onRefresh();
    } catch (e: unknown) {
      const err = e as { response?: { data?: { message?: string } } };
      alert(err.response?.data?.message || "Error al vincular");
    } finally {
      setBusy(null);
    }
  };

  const doCreatePrivate = async () => {
    if (!privateName.trim()) { alert("El nombre completo es obligatorio."); return; }
    // V51 — username case-sensitive.
    const username = privateUsername.trim();
    if (!USERNAME_PATTERN.test(username)) { alert("Usuario inválido. 3-64 caracteres: letras (mayúsculas o minúsculas), números, punto, guión o guión bajo; debe iniciar con letra o número."); return; }
    if (privateUsernameAvailability.status === 'taken') {
      alert("El nombre de usuario ya está ocupado. Elige otro o usa la sugerencia.");
      return;
    }
    const email = privateEmail.trim().toLowerCase();
    if (!EMAIL_PATTERN.test(email)) { alert("El email es obligatorio y debe tener formato válido."); return; }
    const contactEmail = privateContactEmail.trim().toLowerCase();
    if (contactEmail && !EMAIL_PATTERN.test(contactEmail)) { alert("El correo de contacto alternativo no es válido."); return; }
    const phoneDigits = privatePhone.replace(/[^0-9]/g, "");
    if (phoneDigits.length < 7) { alert("El teléfono debe tener al menos 7 dígitos."); return; }
    if (!privateCountryCode.trim()) { alert("La lada es obligatoria."); return; }

    setBusy("priv-create");
    try {
      const created = await ownerTeamService.createPrivate({
        name: privateName.trim(),
        username,
        email,
        contactEmail: contactEmail || undefined,
        countryCode: privateCountryCode.trim(),
        rawPhone: phoneDigits,
        providerType: type as "MAINTENANCE_PROVIDER" | "REAL_ESTATE_AGENT",
      });
      setCreatedCred(created);
      resetPrivateForm();
      await onRefresh();
    } catch (e: unknown) {
      const err = e as { response?: { data?: { message?: string } } };
      alert(err.response?.data?.message || "Error al crear contacto privado");
    } finally {
      setBusy(null);
    }
  };

  return (
    <div className="bg-white rounded-2xl border border-slate-200 shadow-sm overflow-hidden">
      <div className="px-5 py-4 border-b border-slate-100 flex items-center justify-between gap-3 flex-wrap">
        <div className="flex items-center gap-2">
          <div className="text-indigo-600">{icon}</div>
          <h3 className="text-lg font-bold text-slate-800">{title}</h3>
        </div>
        <button type="button" onClick={() => setLinkOpen(!linkOpen)} className="inline-flex items-center gap-2 px-3 py-2 rounded-xl text-sm font-bold bg-slate-900 text-white hover:bg-slate-800">
          <Plus className="w-4 h-4" /> Vincular
        </button>
      </div>
      {linkOpen && (
        <div className="px-5 py-4 bg-slate-50 border-b border-slate-100 space-y-4">
          <div>
            <p className="text-xs font-bold text-slate-500 uppercase mb-2">Desde plataforma (catalogo)</p>
            <div className="max-h-40 overflow-y-auto space-y-1">
              {catalogPick.length === 0 ? (
                <p className="text-sm text-slate-500">No hay candidatos nuevos o ya estan vinculados.</p>
              ) : (
                catalogPick.map((c) => (
                  <div key={c.id} className="flex items-center justify-between gap-2 py-1.5 border-b border-slate-100 last:border-0">
                    <span className="text-sm text-slate-800">
                      {c.name} <span className="text-slate-500">({c.username || c.email || '—'})</span>
                    </span>
                    <button type="button" disabled={busy === c.id} onClick={() => doLinkPlatform(c.id)} className="text-xs font-bold text-indigo-600 hover:underline disabled:opacity-50">
                      <Link2 className="w-3.5 h-3.5 inline mr-1" />
                      Vincular
                    </button>
                  </div>
                ))
              )}
            </div>
          </div>
          <div>
            <p className="text-xs font-bold text-slate-500 uppercase mb-2 flex items-center gap-1.5">
              <UserPlus className="w-3.5 h-3.5" /> Alta privada (solo visible para ti)
            </p>
            <p className="text-[11px] text-slate-500 mb-3">Captura todos los datos del contacto. Al crearlo, el sistema le enviará un link de activación por email y WhatsApp para que él defina su propia contraseña (tú no la sabrás).</p>
            <div className="grid grid-cols-1 sm:grid-cols-2 gap-2">
              <input
                value={privateName}
                onChange={(e) => setPrivateName(e.target.value)}
                placeholder="Nombre completo"
                className="rounded-xl border border-slate-300 px-3 py-2 text-sm"
              />
              <div>
                <input
                  type="text"
                  autoComplete="off"
                  value={privateUsername}
                  onChange={(e) => setPrivateUsername(e.target.value.replace(/\s+/g, ''))}
                  placeholder="Usuario para iniciar sesión (distingue mayúsculas)"
                  className="w-full rounded-xl border border-slate-300 px-3 py-2 text-sm"
                />
                <UsernameAvailabilityHint
                  state={privateUsernameAvailability}
                  onAcceptSuggestion={(s) => setPrivateUsername(s)}
                />
              </div>
              <input
                type="email"
                value={privateEmail}
                onChange={(e) => setPrivateEmail(e.target.value)}
                placeholder="Email * (obligatorio)"
                required
                className="rounded-xl border border-slate-300 px-3 py-2 text-sm"
              />
              <input
                type="email"
                value={privateContactEmail}
                onChange={(e) => setPrivateContactEmail(e.target.value)}
                placeholder="Email de contacto alternativo (opcional)"
                className="rounded-xl border border-slate-300 px-3 py-2 text-sm sm:col-span-2"
              />
              <div className="flex gap-2 sm:col-span-2">
                <input
                  value={privateCountryCode}
                  onChange={(e) => setPrivateCountryCode(e.target.value)}
                  placeholder="+52"
                  className="w-20 rounded-xl border border-slate-300 px-3 py-2 text-sm"
                />
                <input
                  value={privatePhone}
                  onChange={(e) => setPrivatePhone(e.target.value.replace(/[^0-9]/g, ""))}
                  placeholder="Teléfono (solo dígitos)"
                  inputMode="numeric"
                  className="flex-1 rounded-xl border border-slate-300 px-3 py-2 text-sm"
                />
              </div>
            </div>
            <div className="mt-3 flex justify-end">
              <button
                type="button"
                disabled={busy === "priv-create" || privateUsernameAvailability.status === 'taken'}
                onClick={doCreatePrivate}
                className="px-4 py-2 rounded-xl text-sm font-bold bg-amber-600 text-white hover:bg-amber-700 disabled:opacity-50"
              >
                {busy === "priv-create" ? "Creando..." : "Crear y vincular"}
              </button>
            </div>
            {createdCred && (
              <div className="mt-3 bg-emerald-50 border border-emerald-200 rounded-xl p-3 text-sm">
                <div className="font-bold text-emerald-800 mb-1 flex items-center gap-1.5">
                  <Send className="w-4 h-4" /> Contacto creado: {createdCred.name}
                </div>
                <div className="text-xs text-emerald-700 mb-2">Usuario de acceso: <span className="font-mono">{createdCred.username || createdCred.email || '—'}</span></div>
                {createdCred.activationSent ? (
                  <div className="bg-white border border-emerald-200 rounded-lg px-3 py-2 text-xs text-slate-700">
                    Le enviamos un link de activación por <strong>{createdCred.activationChannel === 'BOTH' ? 'email y WhatsApp' : createdCred.activationChannel?.toLowerCase()}</strong>.
                    Con ese link él <strong>establecerá su propia contraseña</strong>; tú no la sabrás.
                    El link es de un solo uso y expira en 24 horas. Si no le llega, puedes reenviarlo desde la tabla.
                  </div>
                ) : createdCred.tempPassword ? (
                  <div className="bg-white border border-emerald-200 rounded-lg px-3 py-2 text-xs text-slate-600">
                    Contraseña temporal: <span className="font-mono font-bold text-slate-800">{createdCred.tempPassword}</span>
                  </div>
                ) : null}
                <button
                  type="button"
                  onClick={() => setCreatedCred(null)}
                  className="mt-2 text-[11px] text-slate-500 hover:underline"
                >
                  Ocultar
                </button>
              </div>
            )}
          </div>
        </div>
      )}
      <div className="overflow-x-auto">
        <table className="w-full text-sm">
          <thead>
            <tr className="bg-slate-50 text-left text-xs font-bold text-slate-500 uppercase">
              <th className="px-4 py-3">Contacto</th>
              <th className="px-4 py-3">Origen</th>
              <th className="px-4 py-3">Estado</th>
              <th className="px-4 py-3">Cuenta del agente</th>
              <th className="px-4 py-3 text-right">Acciones</th>
            </tr>
          </thead>
          <tbody>
            {filtered.length === 0 ? (
              <tr>
                <td colSpan={5} className="px-4 py-8 text-center text-slate-500">
                  Sin vinculos registrados.
                </td>
              </tr>
            ) : (
              filtered.map((r) => (
                <tr key={r.assignmentId} className="border-t border-slate-100">
                  <td className="px-4 py-3">
                    <div className="font-semibold text-slate-800">{r.name}</div>
                    <div className="text-xs text-slate-500">{r.username || r.email || '—'}</div>
                    {r.contactPhone && <div className="text-xs text-slate-400">{r.contactPhone}</div>}
                  </td>
                  <td className="px-4 py-3">{sourceBadge(r.assignmentSource)}</td>
                  <td className="px-4 py-3">{r.assignmentActive ? <span className="text-xs font-semibold text-emerald-700">Activo</span> : <span className="text-xs font-semibold text-slate-500">Inactivo</span>}</td>
                  <td className="px-4 py-3">{accountBadge(r.accountActive)}</td>
                  <td className="px-4 py-3 text-right">
                    <div className="inline-flex gap-1 items-center justify-end">
                      {r.assignmentActive && (r.assignmentSource || "").toUpperCase() === "PRIVATE" && (
                        <button
                          type="button"
                          disabled={busy === r.providerUserId}
                          onClick={() => doResendActivation(r.providerUserId, r.name)}
                          title="Reenviar link de activación"
                          className="inline-flex items-center gap-1 text-xs font-bold text-emerald-700 hover:bg-emerald-50 px-2 py-1 rounded-lg disabled:opacity-50"
                        >
                          <Send className="w-3.5 h-3.5" /> Reenviar link
                        </button>
                      )}
                      {r.assignmentActive && (
                        <button type="button" disabled={busy === r.providerUserId} onClick={() => doUnlink(r.providerUserId)} className="inline-flex items-center gap-1 text-xs font-bold text-rose-600 hover:bg-rose-50 px-2 py-1 rounded-lg disabled:opacity-50">
                          <Unlink className="w-3.5 h-3.5" /> Desvincular
                        </button>
                      )}
                    </div>
                  </td>
                </tr>
              ))
            )}
          </tbody>
        </table>
      </div>
    </div>
  );
};

export const OwnerTeamHub: React.FC = () => {
  const [links, setLinks] = useState<OwnerProviderLink[]>([]);
  const [catalog, setCatalog] = useState<PlatformProviderRow[]>([]);
  const [loading, setLoading] = useState(true);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const [l, c] = await Promise.all([ownerTeamService.getProviderLinks(), ownerTeamService.getPlatformCatalog()]);
      setLinks(l);
      setCatalog(c);
    } catch (e) {
      console.error(e);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    load();
  }, [load]);

  return (
    <div className="w-full space-y-10 animate-in fade-in duration-500">
      <div>
        <h2 className="text-2xl font-bold text-slate-800 flex items-center gap-2">
          <Building2 className="w-7 h-7 text-indigo-600" /> Equipo y proveedores
        </h2>
        <p className="text-sm text-slate-500 mt-1">Administradores, mantenimiento y agentes (plataforma o privados).</p>
      </div>
      <section className="space-y-3">
        <h3 className="text-sm font-bold text-slate-400 uppercase tracking-wider flex items-center gap-2">
          <UserCog className="w-4 h-4" /> Administradores
        </h3>
        <StaffManager embedded />
      </section>
      {loading ? (
        <p className="text-slate-500 text-sm">Cargando proveedores...</p>
      ) : (
        <div className="grid grid-cols-1 xl:grid-cols-2 gap-8">
          <ProviderTable title="Mantenimiento" icon={<HardHat className="w-5 h-5" />} rows={links} catalog={catalog} type={TYPE_MAINT} onRefresh={load} />
          <ProviderTable title="Agentes inmobiliarios" icon={<Briefcase className="w-5 h-5" />} rows={links} catalog={catalog} type={TYPE_AGENT} onRefresh={load} />
        </div>
      )}

      <section className="space-y-4">
        <div>
          <h3 className="text-sm font-bold text-slate-400 uppercase tracking-wider flex items-center gap-2">
            <ListOrdered className="w-4 h-4" /> Prioridades de agentes
          </h3>
          <p className="text-xs text-slate-500 mt-1">
            Define el orden en que tus agentes recibirán la notificación. Sólo se notifica a uno a la vez; si no responde en 72h (o rechaza), pasa al siguiente.
          </p>
        </div>
        <div className="grid grid-cols-1 xl:grid-cols-2 gap-6">
          <AgentPrioritiesPanel flowType="MAINTENANCE" />
          <AgentPrioritiesPanel flowType="VACANCY" />
        </div>
      </section>
    </div>
  );
};