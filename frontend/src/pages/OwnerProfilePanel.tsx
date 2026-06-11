import React, { useCallback, useEffect, useState } from 'react';
import { IdCard, Save, Loader2, Lock, CheckCircle2, AlertCircle } from 'lucide-react';
import {
  ownerProfileService,
  OwnerProfile,
  OwnerProfileUpdatePayload,
  isValidClabe,
} from '../services/ownerProfileService';
import {
  banxicoInstitutionService,
  BanxicoInstitution,
  findBanxicoInstitutionByClabe,
} from '../services/banxicoInstitutionService';
import { OwnerMercadoPagoPanel } from '../components/OwnerMercadoPagoPanel';

/**
 * Perfil del dueño: contacto + cuenta bancaria (CLABE) para recibir transferencias SPEI.
 *
 * UX:
 *  - La CLABE existente se muestra enmascarada (3 primeros + *** + 3 últimos); el
 *    usuario solo puede reemplazarla por una nueva, nunca verla en claro por seguridad.
 *  - Validación en cliente (18 dígitos + checksum módulo 10) antes de pedir reauth.
 *  - Guardar exige password + MFA (reutiliza el mecanismo de reauth del backend).
 */
export const OwnerProfilePanel: React.FC = () => {
  const [profile, setProfile] = useState<OwnerProfile | null>(null);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState('');
  const [success, setSuccess] = useState('');

  const [contactEmail, setContactEmail] = useState('');
  const [countryCode, setCountryCode] = useState('+52');
  const [phone, setPhone] = useState('');
  const [clabe, setClabe] = useState('');
  const [bankName, setBankName] = useState('');
  const [holder, setHolder] = useState('');
  const [receiverBanks, setReceiverBanks] = useState<BanxicoInstitution[]>([]);
  const [banksLoading, setBanksLoading] = useState(true);

  const [showReauth, setShowReauth] = useState(false);
  const [password, setPassword] = useState('');
  const [mfa, setMfa] = useState('');

  const load = useCallback(async () => {
    setLoading(true);
    setError('');
    try {
      const p = await ownerProfileService.get();
      setProfile(p);
      setContactEmail(p.contactEmail || '');
      setCountryCode(p.contactCountryCode || '+52');
      const normalizedPhone = (p.contactPhone || '').replace(
        (p.contactCountryCode || '+52').replace(/[^\d+]/g, ''),
        ''
      );
      setPhone(normalizedPhone.replace(/[^\d]/g, ''));
      setBankName(p.bankName || '');
      setHolder(p.accountHolderName || '');
      setClabe('');
    } catch (e) {
      console.error(e);
      setError('No se pudo cargar el perfil.');
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  useEffect(() => {
    let cancelled = false;
    banxicoInstitutionService
      .getCatalog()
      .then((catalog) => {
        if (!cancelled) setReceiverBanks(catalog.receivers || []);
      })
      .catch(() => {
        if (!cancelled) setReceiverBanks([]);
      })
      .finally(() => {
        if (!cancelled) setBanksLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, []);

  useEffect(() => {
    if (!clabe || receiverBanks.length === 0) return;
    const detected = findBanxicoInstitutionByClabe(clabe, receiverBanks);
    if (detected?.name && detected.name !== bankName) {
      setBankName(detected.name);
    }
  }, [clabe, receiverBanks, bankName]);

  const phoneDigits = phone.replace(/[^\d]/g, '');
  const phoneValid = phoneDigits === '' || phoneDigits.length >= 7;
  const emailValid = contactEmail === '' || /.+@.+\..+/.test(contactEmail);
  const clabeTouched = clabe.trim() !== '';
  const clabeValid = !clabeTouched || isValidClabe(clabe);
  const canSubmit = phoneValid && emailValid && clabeValid && !saving;

  const openReauth = () => {
    if (!canSubmit) return;
    setError('');
    setSuccess('');
    setPassword('');
    setMfa('');
    setShowReauth(true);
  };

  const handleSave = async () => {
    if (!password || password.length < 3) {
      setError('Ingresa tu contraseña.');
      return;
    }
    setSaving(true);
    setError('');
    try {
      const payload: OwnerProfileUpdatePayload = {
        password,
        mfaCode: mfa,
      };
      if (contactEmail !== (profile?.contactEmail || '')) {
        payload.contactEmail = contactEmail.trim().toLowerCase();
      }
      if (phoneDigits !== '' && (phoneDigits !== (profile?.contactPhone || '').replace(/\D/g, '').slice(-phoneDigits.length))) {
        payload.contactPhone = phoneDigits;
        payload.contactCountryCode = countryCode;
      }
      if (clabeTouched) {
        payload.clabe = clabe.replace(/\s/g, '');
      }
      if (bankName !== (profile?.bankName || '')) {
        payload.bankName = bankName.trim();
      }
      if (holder !== (profile?.accountHolderName || '')) {
        payload.accountHolderName = holder.trim();
      }
      const updated = await ownerProfileService.update(payload);
      setProfile(updated);
      setSuccess('Perfil actualizado correctamente.');
      setShowReauth(false);
      setPassword('');
      setMfa('');
      setClabe('');
    } catch (e: unknown) {
      const anyErr = e as { response?: { data?: { message?: string } } };
      setError(anyErr?.response?.data?.message || 'No se pudo guardar. Verifica tu contraseña y código MFA.');
    } finally {
      setSaving(false);
    }
  };

  if (loading) {
    return (
      <div className="flex items-center justify-center p-16 text-slate-500 gap-2">
        <Loader2 className="w-6 h-6 animate-spin" /> Cargando perfil…
      </div>
    );
  }

  return (
    <div className="space-y-6 animate-in fade-in slide-in-from-bottom-4 duration-500 max-w-3xl">
      <div>
        <h2 className="text-xl font-bold text-slate-800 flex items-center gap-2">
          <IdCard className="w-6 h-6 text-indigo-500" /> Mi perfil
        </h2>
        <p className="text-sm text-slate-500 mt-1">
          Datos de contacto y cuenta bancaria para recibir transferencias SPEI de tus arrendatarios.
        </p>
      </div>

      {error && (
        <div className="rounded-xl border border-rose-200 bg-rose-50 px-4 py-3 text-sm text-rose-800 flex items-center gap-2">
          <AlertCircle className="w-4 h-4 shrink-0" /> {error}
        </div>
      )}
      {success && (
        <div className="rounded-xl border border-emerald-200 bg-emerald-50 px-4 py-3 text-sm text-emerald-800 flex items-center gap-2">
          <CheckCircle2 className="w-4 h-4 shrink-0" /> {success}
        </div>
      )}

      <OwnerMercadoPagoPanel />

      {/* Datos de contacto */}
      <section className="bg-white rounded-2xl border border-slate-200 shadow-sm p-6 space-y-4">
        <h3 className="font-semibold text-slate-800">Datos de contacto</h3>
        <p className="text-xs text-slate-500">
          Estos datos se usan para enviarte notificaciones por email y WhatsApp. El email de inicio
          de sesión es: <span className="font-mono text-slate-700">{profile?.email}</span> (no
          editable aquí).
        </p>
        <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
          <div>
            <label className="block text-xs font-semibold text-slate-600 mb-1">Email de contacto</label>
            <input
              type="email"
              value={contactEmail}
              onChange={(e) => setContactEmail(e.target.value)}
              className="w-full px-3 py-2 border border-slate-300 rounded-lg text-sm focus:ring-2 focus:ring-indigo-500 focus:border-indigo-500"
              placeholder="notificaciones@dominio.com"
            />
            {!emailValid && (
              <p className="text-xs text-rose-600 mt-1">Email inválido.</p>
            )}
          </div>
          <div className="grid grid-cols-3 gap-2">
            <div>
              <label className="block text-xs font-semibold text-slate-600 mb-1">Lada</label>
              <input
                type="text"
                value={countryCode}
                onChange={(e) => setCountryCode(e.target.value)}
                className="w-full px-3 py-2 border border-slate-300 rounded-lg text-sm"
                placeholder="+52"
              />
            </div>
            <div className="col-span-2">
              <label className="block text-xs font-semibold text-slate-600 mb-1">Teléfono (WhatsApp)</label>
              <input
                type="tel"
                value={phone}
                onChange={(e) => setPhone(e.target.value)}
                className="w-full px-3 py-2 border border-slate-300 rounded-lg text-sm"
                placeholder="5512345678"
              />
              {!phoneValid && (
                <p className="text-xs text-rose-600 mt-1">Mínimo 7 dígitos.</p>
              )}
            </div>
          </div>
        </div>
      </section>

      {/* Datos bancarios */}
      <section className="bg-white rounded-2xl border border-slate-200 shadow-sm p-6 space-y-4">
        <h3 className="font-semibold text-slate-800">Cuenta bancaria para transferencias</h3>
        <p className="text-xs text-slate-500">
          Tu CLABE se guarda cifrada. Los arrendatarios la verán cuando les enviemos recordatorios
          de pago para que puedan hacer la transferencia SPEI a tu cuenta.
        </p>
        {profile?.hasClabe && profile.bankName && profile.accountHolderName ? (
          <div className="rounded-xl border border-emerald-200 bg-emerald-50 px-4 py-3 text-sm text-emerald-800 flex items-start gap-2">
            <CheckCircle2 className="w-4 h-4 shrink-0 mt-0.5" />
            <span>
              Tu cuenta receptora esta completa. Las transferencias SPEI ya pueden intentar validarse automaticamente con Banxico.
            </span>
          </div>
        ) : (
          <div className="rounded-xl border border-amber-200 bg-amber-50 px-4 py-3 text-sm text-amber-900 flex items-start gap-2">
            <AlertCircle className="w-4 h-4 shrink-0 mt-0.5" />
            <span>
              Si quieres validacion automatica con Banxico, registra aqui la cuenta que recibe las transferencias con
              <strong> CLABE, banco y titular</strong>. Mientras falte alguno de esos datos, los comprobantes SPEI quedaran en revision manual.
            </span>
          </div>
        )}
        {profile?.hasClabe && (
          <div className="rounded-lg bg-slate-50 border border-slate-200 px-3 py-2 text-sm text-slate-700">
            CLABE actual: <span className="font-mono">{profile.clabeMasked}</span>
            <span className="text-xs text-slate-500 ml-2">(escribe una nueva abajo para reemplazarla)</span>
          </div>
        )}
        <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
          <div className="md:col-span-2">
            <label className="block text-xs font-semibold text-slate-600 mb-1">
              {profile?.hasClabe ? 'Nueva CLABE (18 dígitos)' : 'CLABE (18 dígitos)'}
            </label>
            <input
              type="text"
              value={clabe}
              onChange={(e) => {
                const next = e.target.value.replace(/\D/g, '').slice(0, 18);
                setClabe(next);
                const detected = findBanxicoInstitutionByClabe(next, receiverBanks);
                setBankName(next ? (detected?.name || '') : (profile?.bankName || ''));
              }}
              className="w-full px-3 py-2 border border-slate-300 rounded-lg text-sm font-mono"
              placeholder="000000000000000000"
              maxLength={18}
            />
            {clabeTouched && !clabeValid && (
              <p className="text-xs text-rose-600 mt-1">CLABE inválida (18 dígitos + checksum).</p>
            )}
            {clabeTouched && clabeValid && (
              <p className="text-xs text-emerald-700 mt-1">Formato válido.</p>
            )}
          </div>
          <div>
            <label className="block text-xs font-semibold text-slate-600 mb-1">Banco Banxico detectado por CLABE</label>
            <input
              type="text"
              value={bankName}
              readOnly
              className="w-full px-3 py-2 border border-slate-300 rounded-lg text-sm bg-slate-50 text-slate-700"
              placeholder={banksLoading ? 'Cargando catálogo Banxico...' : 'Se detecta automáticamente'}
            />
            <p className="text-[11px] text-slate-500 mt-1">
              {clabeTouched
                ? (bankName
                    ? 'El banco se fija automáticamente según la CLABE oficial de Banxico.'
                    : 'Si la CLABE es válida, el banco se resolverá automáticamente al guardar.')
                : 'No editable: se determina desde la CLABE para evitar inconsistencias con Banxico.'}
            </p>
          </div>
          <div>
            <label className="block text-xs font-semibold text-slate-600 mb-1">Titular de la cuenta</label>
            <input
              type="text"
              value={holder}
              onChange={(e) => setHolder(e.target.value)}
              className="w-full px-3 py-2 border border-slate-300 rounded-lg text-sm"
              placeholder="Nombre completo del titular"
            />
          </div>
        </div>
      </section>

      <div className="flex justify-end">
        <button
          type="button"
          onClick={openReauth}
          disabled={!canSubmit}
          className="inline-flex items-center gap-2 px-5 py-2.5 rounded-xl bg-indigo-600 text-white text-sm font-bold hover:bg-indigo-700 disabled:opacity-60 shadow-sm"
        >
          <Save className="w-4 h-4" /> Guardar cambios
        </button>
      </div>

      {/* Reauth modal */}
      {showReauth && (
        <div className="fixed inset-0 z-40 bg-slate-900/40 flex items-center justify-center p-4">
          <div className="bg-white rounded-2xl shadow-2xl w-full max-w-md p-6 space-y-4">
            <div className="flex items-center gap-2 text-slate-800">
              <Lock className="w-5 h-5 text-indigo-500" />
              <h3 className="font-bold">Confirmar cambios</h3>
            </div>
            <p className="text-xs text-slate-500">
              Cambiar tu CLABE o datos de contacto es una operación sensible. Confirma con tu
              contraseña y tu código MFA (si lo tienes activado).
            </p>
            <div>
              <label className="block text-xs font-semibold text-slate-600 mb-1">Contraseña</label>
              <input
                type="password"
                value={password}
                onChange={(e) => setPassword(e.target.value)}
                className="w-full px-3 py-2 border border-slate-300 rounded-lg text-sm"
                autoComplete="current-password"
              />
            </div>
            <div>
              <label className="block text-xs font-semibold text-slate-600 mb-1">Código MFA (6 dígitos)</label>
              <input
                type="text"
                value={mfa}
                onChange={(e) => setMfa(e.target.value.replace(/\D/g, '').slice(0, 6))}
                className="w-full px-3 py-2 border border-slate-300 rounded-lg text-sm font-mono"
                placeholder="000000"
                maxLength={6}
              />
              <p className="text-[11px] text-slate-400 mt-1">Déjalo vacío si no tienes MFA activado.</p>
            </div>
            <div className="flex justify-end gap-2 pt-2">
              <button
                type="button"
                onClick={() => setShowReauth(false)}
                disabled={saving}
                className="px-4 py-2 rounded-lg text-sm font-medium text-slate-600 hover:bg-slate-100"
              >
                Cancelar
              </button>
              <button
                type="button"
                onClick={() => void handleSave()}
                disabled={saving}
                className="inline-flex items-center gap-2 px-4 py-2 rounded-lg bg-indigo-600 text-white text-sm font-bold hover:bg-indigo-700 disabled:opacity-60"
              >
                {saving ? <Loader2 className="w-4 h-4 animate-spin" /> : <Save className="w-4 h-4" />}
                Confirmar
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
};
