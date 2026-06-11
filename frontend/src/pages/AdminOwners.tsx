import React, { useState, useEffect } from 'react';
import api from '../services/api';
import { Plus, Users, Trash, Phone, Mail, Pencil, X, Check, ShieldAlert, Lock, KeyRound, AlertTriangle, AtSign } from 'lucide-react';

interface OwnerDTO {
  id: string;
  /** V50 — identificador de login canónico. Las altas nuevas siempre lo traen. */
  username?: string;
  /** V50 — email quedó como canal de contacto opcional. Puede venir null desde backend. */
  email?: string;
  name: string;
  phone: string;
  contactEmail: string;
  contactPhone: string;
  contactCountryCode: string;
  tempPassword?: string;
}

interface ResidualOwner {
  id: string;
  name: string;
  username?: string;
  email?: string;
  active: boolean;
  deletedAt: string | null;
  phone: string;
}

const COUNTRY_CODES = [
  { code: '+52', label: '🇲🇽 México (+52)' },
  { code: '+1', label: '🇺🇸 EE.UU. (+1)' },
  { code: '+57', label: '🇨🇴 Colombia (+57)' },
  { code: '+54', label: '🇦🇷 Argentina (+54)' },
  { code: '+56', label: '🇨🇱 Chile (+56)' },
  { code: '+34', label: '🇪🇸 España (+34)' },
  { code: '+51', label: '🇵🇪 Perú (+51)' },
  { code: '+55', label: '🇧🇷 Brasil (+55)' },
  { code: '+593', label: '🇪🇨 Ecuador (+593)' },
  { code: '+502', label: '🇬🇹 Guatemala (+502)' },
  { code: '+503', label: '🇸🇻 El Salvador (+503)' },
  { code: '+504', label: '🇭🇳 Honduras (+504)' },
  { code: '+505', label: '🇳🇮 Nicaragua (+505)' },
  { code: '+506', label: '🇨🇷 Costa Rica (+506)' },
  { code: '+507', label: '🇵🇦 Panamá (+507)' },
  { code: '+58', label: '🇻🇪 Venezuela (+58)' },
  { code: '+591', label: '🇧🇴 Bolivia (+591)' },
  { code: '+595', label: '🇵🇾 Paraguay (+595)' },
  { code: '+598', label: '🇺🇾 Uruguay (+598)' },
  { code: '+809', label: '🇩🇴 R. Dominicana (+809)' },
];

const INITIAL_FORM = { name: '', username: '', email: '', contactEmail: '', countryCode: '+52', rawPhone: '' };
// V51 — username case-sensitive: admite A-Z además de a-z.
const USERNAME_PATTERN = /^[A-Za-z0-9][A-Za-z0-9._-]{2,63}$/;
const EMAIL_PATTERN = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;

export const AdminOwners = () => {
    const [owners, setOwners] = useState<OwnerDTO[]>([]);
    const [loading, setLoading] = useState(true);
    const [showForm, setShowForm] = useState(false);
    const [formData, setFormData] = useState(INITIAL_FORM);
    const [tempPassAlert, setTempPassAlert] = useState<{ identifier: string, pass: string } | null>(null);
    const [creating, setCreating] = useState(false);
    const [error, setError] = useState('');

    // Edit state
    const [editingId, setEditingId] = useState<string | null>(null);
    const [editData, setEditData] = useState({ name: '', contactEmail: '', contactCountryCode: '+52', rawPhone: '' });
    const [editError, setEditError] = useState('');
    const [saving, setSaving] = useState(false);

    // Delete reauth state — requiere password + MFA + motivo (≥10 chars) auditables.
    // Refleja la misma política del endpoint backend (ver OwnerController.deleteOwner).
    const [deleteModal, setDeleteModal] = useState<OwnerDTO | null>(null);
    const [delPassword, setDelPassword] = useState('');
    const [delMfa, setDelMfa] = useState('');
    const [delReason, setDelReason] = useState('');
    const [delLoading, setDelLoading] = useState(false);
    const [delError, setDelError] = useState('');
    const [delOutcome, setDelOutcome] = useState<{
        name: string;
        identifier: string;
        counters: Record<string, number>;
    } | null>(null);

    // Residuos: dueños soft-deleted que quedaron con datos en base. Los listamos
    // siempre para que el SUPERADMIN los vea y pueda purgarlos explícitamente
    // (nunca en creación como efecto lateral).
    const [residuals, setResiduals] = useState<ResidualOwner[]>([]);

    // Cuando la creación colisiona con un residuo, guardamos el payload del 409
    // para mostrarlo dentro del formulario con una acción que abre el modal
    // existente de reauth sobre el id residual.
    const [residualAlert, setResidualAlert] = useState<{
        identifier: string;
        residualOwnerId: string;
        residualOwnerName: string;
    } | null>(null);

    const fetchOwners = async () => {
        setLoading(true);
        try {
            const res = await api.get('/admin/owners');
            setOwners(res.data);
        } catch {}
        setLoading(false);
    };

    const fetchResiduals = async () => {
        try {
            const res = await api.get('/admin/owners/residual');
            setResiduals(res.data || []);
        } catch {
            // si el endpoint no existe en servidores viejos, dejamos vacío sin bloquear UI
            setResiduals([]);
        }
    };

    useEffect(() => { fetchOwners(); fetchResiduals(); }, []);

    const handleCreate = async (e: React.FormEvent) => {
        e.preventDefault();
        setError('');
        setResidualAlert(null);
        const digits = formData.rawPhone.replace(/[^0-9]/g, '');
        // V51 — username case-sensitive: solo trim.
        const username = formData.username.trim();
        const email = formData.email.trim().toLowerCase();
        const contactEmail = formData.contactEmail.trim().toLowerCase();
        if (formData.name.trim().length < 2) { setError('El nombre debe tener al menos 2 caracteres.'); return; }
        if (!USERNAME_PATTERN.test(username)) { setError('Usuario inválido. 3-64 caracteres: letras (mayúsculas o minúsculas), números, punto, guión o guión bajo; debe iniciar con letra o número.'); return; }
        if (!EMAIL_PATTERN.test(email)) { setError('El email es obligatorio y debe tener formato válido.'); return; }
        if (digits.length < 7) { setError('El teléfono debe tener al menos 7 dígitos.'); return; }
        if (contactEmail && !EMAIL_PATTERN.test(contactEmail)) { setError('Si capturas correo de contacto debe ser un email válido.'); return; }

        setCreating(true);
        try {
            const res = await api.post('/admin/owners', {
                name: formData.name.trim(),
                username,
                email,
                contactEmail: contactEmail || undefined,
                countryCode: formData.countryCode,
                rawPhone: digits,
            });
            setShowForm(false);
            const identifier = res.data.username || res.data.email || username;
            setTempPassAlert({ identifier, pass: res.data.tempPassword });
            setFormData(INITIAL_FORM);
            fetchOwners();
            fetchResiduals();
        } catch (err: any) {
            // V54: el email ya no es único, así que la antigua colisión
            // OWNER_EMAIL_RESIDUAL desapareció. Sólo queda OWNER_USERNAME_RESIDUAL
            // porque el username sí sigue siendo único.
            const data = err?.response?.data;
            if (err?.response?.status === 409 && data?.code === 'OWNER_USERNAME_RESIDUAL') {
                setResidualAlert({
                    identifier: data.username || username,
                    residualOwnerId: data.residualOwnerId,
                    residualOwnerName: data.residualOwnerName || '(sin nombre)',
                });
                setError('');
            } else {
                setError(data?.message || 'Error creando dueño.');
            }
        } finally {
            setCreating(false);
        }
    };

    // Abre el modal de reauth existente usando un "OwnerDTO sintético" contra el
    // residuo detectado. La cascada está centralizada en `DELETE /api/admin/owners/{id}`
    // y exige password + MFA + motivo — nunca borramos sin reauth.
    const openDeleteModalForResidual = (residualOwnerId: string, residualOwnerName: string, residualIdentifier: string) => {
        const synthetic: OwnerDTO = {
            id: residualOwnerId,
            username: residualIdentifier,
            email: residualIdentifier,
            name: residualOwnerName,
            phone: '',
            contactEmail: '',
            contactPhone: '',
            contactCountryCode: '',
        };
        openDeleteModal(synthetic);
    };

    const openDeleteModal = (o: OwnerDTO) => {
        setDeleteModal(o);
        setDelPassword('');
        setDelMfa('');
        setDelReason('');
        setDelError('');
    };

    const closeDeleteModal = () => {
        if (delLoading) return;
        setDeleteModal(null);
        setDelPassword('');
        setDelMfa('');
        setDelReason('');
        setDelError('');
    };

    const executeDelete = async () => {
        if (!deleteModal) return;
        if (!delPassword) {
            setDelError('Ingresa tu contraseña de SUPER_ADMIN.');
            return;
        }
        if (delMfa.trim().length > 0 && delMfa.trim().length < 6) {
            setDelError('El código MFA debe tener 6 dígitos (déjalo vacío si no tienes MFA activo).');
            return;
        }
        if (delReason.trim().length < 10) {
            setDelError('El motivo debe tener al menos 10 caracteres (queda en auditoría).');
            return;
        }
        setDelLoading(true);
        setDelError('');
        try {
            const res = await api.delete(`/admin/owners/${deleteModal.id}`, {
                data: {
                    password: delPassword,
                    mfaCode: delMfa.trim(),
                    reason: delReason.trim(),
                },
            });
            setDelOutcome({
                name: deleteModal.name,
                identifier: deleteModal.username || deleteModal.email || '—',
                counters: res.data?.cascade || {},
            });
            setDeleteModal(null);
            setDelPassword('');
            setDelMfa('');
            setDelReason('');
            setResidualAlert(null);
            fetchOwners();
            fetchResiduals();
        } catch (err: any) {
            setDelError(err.response?.data?.message || 'No se pudo eliminar el dueño.');
        } finally {
            setDelLoading(false);
        }
    };

    const startEdit = (owner: OwnerDTO) => {
        // Extract raw phone digits from contactPhone
        const phone = owner.contactPhone || owner.phone || '';
        const cc = owner.contactCountryCode || '+52';
        const raw = phone.startsWith(cc) ? phone.slice(cc.length) : phone.replace(/[^0-9]/g, '');
        setEditingId(owner.id);
        setEditData({
            name: owner.name,
            contactEmail: owner.contactEmail || '',
            contactCountryCode: cc,
            rawPhone: raw,
        });
        setEditError('');
    };

    const cancelEdit = () => {
        setEditingId(null);
        setEditError('');
    };

    const saveEdit = async () => {
        if (!editingId) return;
        const digits = editData.rawPhone.replace(/[^0-9]/g, '');
        if (editData.name.trim().length < 2) { setEditError('Nombre mínimo 2 caracteres.'); return; }
        if (digits.length < 7) { setEditError('Teléfono mínimo 7 dígitos.'); return; }

        setSaving(true);
        setEditError('');
        try {
            await api.put(`/admin/owners/${editingId}`, {
                name: editData.name.trim(),
                contactEmail: editData.contactEmail.trim() || undefined,
                contactCountryCode: editData.contactCountryCode,
                rawPhone: digits,
            });
            setEditingId(null);
            fetchOwners();
        } catch (err: any) {
            setEditError(err.response?.data?.message || 'Error actualizando dueño.');
        } finally {
            setSaving(false);
        }
    };

    const formatPhone = (phone: string) => {
        if (!phone) return '—';
        const match = phone.match(/^(\+\d{1,3})(\d{3})(\d{3})(\d{4,})$/);
        if (match) return `${match[1]} ${match[2]} ${match[3]} ${match[4]}`;
        return phone;
    };

    if (loading) return <div className="p-8 text-slate-500">Cargando dueños...</div>;

    return (
        <div className="bg-white rounded-xl border border-slate-200">
            <div className="p-6 border-b border-slate-200 flex justify-between items-center">
                <div className="flex items-center gap-3">
                    <Users className="w-5 h-5 text-brand-600" />
                    <h2 className="text-lg font-bold text-slate-900">Gestión de Dueños</h2>
                    <span className="text-sm bg-brand-100 text-brand-700 px-2 py-0.5 rounded-full font-semibold">{owners.length}</span>
                </div>
                <button onClick={() => { setShowForm(!showForm); setError(''); }} className="bg-brand-600 hover:bg-brand-700 text-white px-4 py-2 rounded-lg font-bold text-sm flex items-center gap-2 transition-colors">
                    <Plus className="w-4 h-4" /> Nuevo Dueño
                </button>
            </div>

            {tempPassAlert && (
                <div className="p-4 bg-yellow-50 border-b border-yellow-200 text-yellow-800">
                    <strong>¡Dueño Creado!</strong> Copie esta contraseña temporal (solo se muestra una vez):
                    <div className="mt-2 text-lg font-mono bg-yellow-100 px-3 py-1 inline-block rounded border border-yellow-300">
                        {tempPassAlert.pass}
                    </div>
                    <div className="text-xs mt-1 text-yellow-600">Usuario de acceso: {tempPassAlert.identifier}</div>
                    <button onClick={() => setTempPassAlert(null)} className="ml-4 text-sm underline text-yellow-700">Cerrar</button>
                </div>
            )}

            {showForm && (
                <form onSubmit={handleCreate} className="p-6 border-b border-slate-200 bg-slate-50 space-y-4">
                    <div>
                        <label className="block text-sm font-bold text-slate-700 mb-1">
                            Nombre / Empresa <span className="text-red-500">*</span>
                        </label>
                        <input className="w-full px-3 py-2 border border-slate-300 rounded-lg text-sm focus:ring-2 focus:ring-brand-500 outline-none"
                            placeholder="Ej: Inmobiliaria Del Norte S.A."
                            value={formData.name} onChange={e => setFormData({...formData, name: e.target.value})} required />
                    </div>
                    <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
                        <div>
                            <label className="block text-sm font-bold text-slate-700 mb-1">
                                <AtSign className="w-3.5 h-3.5 inline mr-1" />
                                Usuario para iniciar sesión <span className="text-red-500">*</span>
                            </label>
                            <input className="w-full px-3 py-2 border border-slate-300 rounded-lg text-sm focus:ring-2 focus:ring-brand-500 outline-none"
                                type="text" autoComplete="off" placeholder="ej. Inmobiliaria-Del-Norte"
                                value={formData.username}
                                onChange={e => setFormData({...formData, username: e.target.value.replace(/\s+/g, '')})}
                                required />
                            <p className="text-xs text-slate-400 mt-1">Identificador único global. 3-64 caracteres: letras (mayúsculas o minúsculas), números, punto, guión o guión bajo. <strong>Distingue mayúsculas y minúsculas.</strong></p>
                        </div>
                        <div>
                            <label className="block text-sm font-bold text-slate-700 mb-1">
                                <Mail className="w-3.5 h-3.5 inline mr-1" />
                                Email <span className="text-red-500">*</span>
                            </label>
                            <input className="w-full px-3 py-2 border border-slate-300 rounded-lg text-sm focus:ring-2 focus:ring-brand-500 outline-none"
                                type="email" placeholder="contacto@empresa.com"
                                value={formData.email} onChange={e => setFormData({...formData, email: e.target.value})} required />
                            <p className="text-xs text-slate-400 mt-1">Canal oficial de comunicación. Se usa para notificaciones y recuperación de cuenta.</p>
                        </div>
                    </div>
                    <div>
                        <label className="block text-sm font-bold text-slate-700 mb-1">
                            <Mail className="w-3.5 h-3.5 inline mr-1" />
                            Email de contacto alternativo (opcional)
                        </label>
                        <input className="w-full px-3 py-2 border border-slate-300 rounded-lg text-sm focus:ring-2 focus:ring-brand-500 outline-none"
                            type="email" placeholder="otro-correo@empresa.com"
                            value={formData.contactEmail} onChange={e => setFormData({...formData, contactEmail: e.target.value})} />
                        <p className="text-xs text-slate-400 mt-1">Si se captura, se usa para notificaciones por email. Si no, se usa el email principal.</p>
                    </div>
                    <div>
                        <label className="block text-sm font-bold text-slate-700 mb-1">
                            <Phone className="w-3.5 h-3.5 inline mr-1" />
                            Teléfono de Contacto <span className="text-red-500">*</span>
                        </label>
                        <div className="flex gap-2">
                            <select value={formData.countryCode} onChange={e => setFormData({...formData, countryCode: e.target.value})}
                                className="w-56 px-3 py-2 border border-slate-300 rounded-lg text-sm bg-white focus:ring-2 focus:ring-brand-500 outline-none">
                                {COUNTRY_CODES.map(cc => (
                                    <option key={cc.code} value={cc.code}>{cc.label}</option>
                                ))}
                            </select>
                            <input className="flex-1 px-3 py-2 border border-slate-300 rounded-lg text-sm focus:ring-2 focus:ring-brand-500 outline-none font-mono tracking-wider"
                                type="tel" placeholder="8112345678"
                                value={formData.rawPhone}
                                onChange={e => setFormData({...formData, rawPhone: e.target.value.replace(/[^0-9]/g, '')})}
                                maxLength={15} required />
                        </div>
                    </div>

                    {error && (
                        <div className="text-sm text-red-600 bg-red-50 border border-red-200 rounded-lg p-2">{error}</div>
                    )}

                    {/* El email coincidió con un dueño en estado residual (data legada previa al
                        hard-delete). La creación NO borra nada: muestra el detalle y pide purga
                        explícita con reauth. Tras purgar, el email queda libre y se puede reintentar. */}
                    {residualAlert && (
                        <div className="text-sm bg-rose-50 border border-rose-300 rounded-xl p-4 space-y-2">
                            <div className="flex items-start gap-2">
                                <AlertTriangle className="w-5 h-5 text-rose-600 flex-shrink-0 mt-0.5" />
                                <div className="flex-1">
                                    <div className="font-bold text-rose-900">
                                        Inconsistencia: el identificador coincide con un dueño residual en la base.
                                    </div>
                                    <div className="mt-1 text-xs text-rose-800">
                                        <div><strong>Identificador:</strong> {residualAlert.identifier}</div>
                                        <div><strong>Nombre residual:</strong> {residualAlert.residualOwnerName}</div>
                                        <div><strong>ID residual:</strong> <span className="font-mono">{residualAlert.residualOwnerId}</span></div>
                                    </div>
                                    <p className="mt-2 text-xs text-rose-900">
                                        Un hard-delete correcto no deja nada atrás. Este registro es legado.
                                        Purgar ejecuta la cascada completa (DB + archivos) con contraseña + MFA + motivo,
                                        y después el identificador queda libre definitivamente.
                                    </p>
                                </div>
                            </div>
                            <div className="flex gap-2">
                                <button type="button"
                                    onClick={() => openDeleteModalForResidual(residualAlert.residualOwnerId, residualAlert.residualOwnerName, residualAlert.identifier)}
                                    className="bg-rose-600 hover:bg-rose-700 text-white text-xs font-bold px-3 py-1.5 rounded-lg transition-colors flex items-center gap-1.5">
                                    <ShieldAlert className="w-3.5 h-3.5" />
                                    Purgar residuo y liberar identificador
                                </button>
                                <button type="button" onClick={() => setResidualAlert(null)}
                                    className="text-xs text-rose-800 hover:bg-rose-100 px-3 py-1.5 rounded-lg transition-colors">
                                    Cancelar
                                </button>
                            </div>
                        </div>
                    )}

                    <div className="flex gap-3">
                        <button type="submit" disabled={creating}
                            className="bg-brand-600 hover:bg-brand-700 disabled:bg-slate-300 text-white px-5 py-2 rounded-lg font-bold text-sm transition-colors">
                            {creating ? 'Creando...' : 'Crear Dueño'}
                        </button>
                        <button type="button" onClick={() => setShowForm(false)}
                            className="text-slate-500 hover:bg-slate-100 px-4 py-2 rounded-lg text-sm transition-colors">Cancelar</button>
                    </div>
                </form>
            )}

            {/* Safety-net de auditoría: dueños en estado "residual" — inconsistencia de base.
                Regla operativa: cuando el SUPERADMIN elimina un dueño con password + MFA + motivo,
                la cascada no debe dejar NADA atrás. Esta sección debería estar SIEMPRE VACÍA en un
                sistema sano. Si aparece algún registro aquí, significa que hay data legada (anterior
                a V37/V39) o que un crash dejó una cascada a medias — un incidente a investigar. */}
            {residuals.length > 0 && (
                <div className="p-6 border-b border-rose-200 bg-rose-50/50">
                    <div className="flex items-center gap-2 mb-3">
                        <AlertTriangle className="w-5 h-5 text-rose-600" />
                        <h3 className="font-bold text-rose-900 text-sm">
                            Inconsistencia detectada: dueños con datos residuales ({residuals.length})
                        </h3>
                    </div>
                    <p className="text-xs text-rose-800 mb-3 max-w-3xl">
                        <strong>Esto no debería ocurrir.</strong> Cuando el SUPERADMIN elimina a un dueño con
                        contraseña + MFA + motivo, toda su información desaparece de la base y del
                        almacenamiento de inmediato. Los registros listados abajo son legado de flujos
                        anteriores al hard-delete o de una cascada que se interrumpió. Purgarlos aquí
                        aplica la misma cascada final con reauth — después, el email queda libre y no
                        vuelve a haber rastro.
                    </p>
                    <div className="overflow-x-auto bg-white rounded-lg border border-rose-200">
                        <table className="w-full text-left text-sm">
                            <thead className="bg-rose-100/50 text-rose-900 text-xs uppercase tracking-wider">
                                <tr>
                                    <th className="px-3 py-2 font-medium">Nombre</th>
                                    <th className="px-3 py-2 font-medium">Email</th>
                                    <th className="px-3 py-2 font-medium">Estado</th>
                                    <th className="px-3 py-2 font-medium">Eliminado</th>
                                    <th className="px-3 py-2 font-medium text-right">Acción</th>
                                </tr>
                            </thead>
                            <tbody className="divide-y divide-rose-100">
                                {residuals.map(r => (
                                    <tr key={r.id} className="hover:bg-rose-50/50">
                                        <td className="px-3 py-2 font-medium text-slate-800">{r.name || '—'}</td>
                                        <td className="px-3 py-2 text-slate-600 text-xs font-mono">{r.username || r.email || '—'}</td>
                                        <td className="px-3 py-2 text-xs">
                                            {!r.active && <span className="inline-block bg-rose-100 text-rose-700 px-2 py-0.5 rounded-full text-[11px] font-semibold">inactivo</span>}
                                            {r.active && r.deletedAt && <span className="inline-block bg-amber-100 text-amber-700 px-2 py-0.5 rounded-full text-[11px] font-semibold ml-1">deleted_at</span>}
                                        </td>
                                        <td className="px-3 py-2 text-xs text-slate-500">
                                            {r.deletedAt ? new Date(r.deletedAt).toLocaleString('es-MX', { timeZone: 'America/Mexico_City' }) : '—'}
                                        </td>
                                        <td className="px-3 py-2 text-right">
                                            <button
                                                onClick={() => openDeleteModalForResidual(r.id, r.name || '(sin nombre)', r.username || r.email || '—')}
                                                className="bg-rose-600 hover:bg-rose-700 text-white text-xs font-bold px-3 py-1 rounded-lg transition-colors inline-flex items-center gap-1">
                                                <Trash className="w-3 h-3" />
                                                Purgar
                                            </button>
                                        </td>
                                    </tr>
                                ))}
                            </tbody>
                        </table>
                    </div>
                </div>
            )}

            <div className="overflow-x-auto">
                <table className="w-full text-left">
                    <thead>
                        <tr className="bg-slate-50 text-slate-500 text-xs uppercase tracking-wider">
                            <th className="px-4 py-3 font-medium">Nombre</th>
                                    <th className="px-4 py-3 font-medium">Usuario</th>
                            <th className="px-4 py-3 font-medium">Contacto</th>
                            <th className="px-4 py-3 font-medium">Teléfono</th>
                            <th className="px-4 py-3 font-medium text-right">Acciones</th>
                        </tr>
                    </thead>
                    <tbody className="divide-y divide-slate-100">
                        {owners.map(o => (
                            <tr key={o.id} className="hover:bg-slate-50">
                                {editingId === o.id ? (
                                    /* ---- EDIT MODE ---- */
                                    <>
                                        <td className="px-4 py-2">
                                            <input value={editData.name} onChange={e => setEditData({...editData, name: e.target.value})}
                                                className="w-full px-2 py-1.5 border border-blue-300 rounded text-sm focus:ring-2 focus:ring-blue-400 outline-none" />
                                        </td>
                                        <td className="px-4 py-2 text-slate-400 text-sm">
                                            {o.username || o.email || '—'}
                                            <div className="text-xs text-slate-300 mt-0.5">🔒 No editable</div>
                                        </td>
                                        <td className="px-4 py-2">
                                            <input type="email" value={editData.contactEmail}
                                                onChange={e => setEditData({...editData, contactEmail: e.target.value})}
                                                className="w-full px-2 py-1.5 border border-blue-300 rounded text-sm focus:ring-2 focus:ring-blue-400 outline-none"
                                                placeholder="contacto@empresa.com" />
                                        </td>
                                        <td className="px-4 py-2">
                                            <div className="flex gap-1">
                                                <select value={editData.contactCountryCode}
                                                    onChange={e => setEditData({...editData, contactCountryCode: e.target.value})}
                                                    className="px-1 py-1.5 border border-blue-300 rounded text-xs w-24">
                                                    {COUNTRY_CODES.map(cc => (
                                                        <option key={cc.code} value={cc.code}>{cc.code}</option>
                                                    ))}
                                                </select>
                                                <input type="tel" value={editData.rawPhone}
                                                    onChange={e => setEditData({...editData, rawPhone: e.target.value.replace(/[^0-9]/g, '')})}
                                                    className="w-28 px-2 py-1.5 border border-blue-300 rounded text-sm font-mono focus:ring-2 focus:ring-blue-400 outline-none"
                                                    placeholder="8112345678" />
                                            </div>
                                            {editError && <div className="text-xs text-red-500 mt-1">{editError}</div>}
                                        </td>
                                        <td className="px-4 py-2 text-right">
                                            <div className="flex justify-end gap-1">
                                                <button onClick={saveEdit} disabled={saving}
                                                    className="p-1.5 text-emerald-600 hover:bg-emerald-50 rounded-md transition-colors" title="Guardar">
                                                    <Check className="w-4 h-4" />
                                                </button>
                                                <button onClick={cancelEdit}
                                                    className="p-1.5 text-slate-400 hover:bg-slate-100 rounded-md transition-colors" title="Cancelar">
                                                    <X className="w-4 h-4" />
                                                </button>
                                            </div>
                                        </td>
                                    </>
                                ) : (
                                    /* ---- VIEW MODE ---- */
                                    <>
                                        <td className="px-4 py-3 font-medium text-slate-900">{o.name}</td>
                                        <td className="px-4 py-3 text-slate-500 text-sm">{o.username || o.email || '—'}</td>
                                        <td className="px-4 py-3 text-sm">
                                            <div className="text-slate-600">{o.contactEmail || '—'}</div>
                                        </td>
                                        <td className="px-4 py-3 text-slate-500 font-mono text-sm">{formatPhone(o.contactPhone || o.phone)}</td>
                                        <td className="px-4 py-3 text-right">
                                            <div className="flex justify-end gap-1">
                                                <button onClick={() => startEdit(o)} title="Editar dueño"
                                                    className="p-1.5 text-blue-400 hover:text-blue-600 hover:bg-blue-50 rounded-md transition-colors">
                                                    <Pencil className="w-4 h-4" />
                                                </button>
                                                <button onClick={() => openDeleteModal(o)} title="Eliminar dueño (cascade completo)"
                                                    className="p-1.5 text-slate-400 hover:text-red-600 hover:bg-red-50 rounded-md transition-colors">
                                                    <Trash className="w-4 h-4" />
                                                </button>
                                            </div>
                                        </td>
                                    </>
                                )}
                            </tr>
                        ))}
                        {owners.length === 0 && (
                            <tr><td colSpan={5} className="px-4 py-6 text-center text-slate-500">No hay dueños dados de alta.</td></tr>
                        )}
                    </tbody>
                </table>
            </div>

            {/* Modal de eliminación con reauth. Simétrico al de GlobalSearchManager:
                password + MFA + motivo (≥10 chars). Al confirmar, el backend corre la
                cascada completa y libera el email para reutilizarlo. */}
            {deleteModal && (
                <div className="fixed inset-0 z-[120] flex items-center justify-center p-4">
                    <div className="absolute inset-0 bg-slate-900/70 backdrop-blur-sm" onClick={closeDeleteModal} />
                    <div className="relative bg-white rounded-2xl w-full max-w-lg shadow-2xl overflow-hidden border border-rose-200">
                        <div className="px-6 py-4 border-b border-rose-100 flex justify-between items-center bg-rose-50">
                            <div className="flex items-center gap-3">
                                <div className="w-10 h-10 bg-rose-100 rounded-xl flex items-center justify-center text-rose-600">
                                    <ShieldAlert className="w-5 h-5" />
                                </div>
                                <div>
                                    <h3 className="text-lg font-bold text-slate-800">Eliminar Dueño</h3>
                                    <p className="text-xs text-slate-500">Hard-delete con cascade + reautenticación</p>
                                </div>
                            </div>
                            <button onClick={closeDeleteModal} className="p-2 text-slate-400 hover:bg-slate-200 rounded-full transition-colors">
                                <X className="w-5 h-5" />
                            </button>
                        </div>

                        <div className="p-6 space-y-4">
                            <div className="bg-rose-50 border border-rose-200 rounded-xl p-4 text-sm text-rose-800">
                                Vas a eliminar a <strong>{deleteModal.name}</strong> ({deleteModal.username || deleteModal.email || '—'}).
                                Se borrarán <strong>TODOS</strong> sus inmuebles, contratos, facturas, pagos,
                                convenios, expedientes, mantenimiento, notificaciones y auditoría. Los
                                arrendatarios y staff que solo tenían contexto con este dueño también se
                                eliminarán. Esta acción es <strong>irreversible</strong>.
                            </div>

                            <div>
                                <label className="flex items-center gap-2 text-sm font-semibold text-slate-700 mb-1.5">
                                    <Lock className="w-4 h-4 text-slate-400" /> Contraseña SUPER_ADMIN
                                </label>
                                <input
                                    type="password"
                                    value={delPassword}
                                    onChange={(e) => setDelPassword(e.target.value)}
                                    className="w-full rounded-xl border border-slate-300 px-4 py-2.5 text-sm focus:border-rose-500 focus:ring-2 focus:ring-rose-500/20 outline-none"
                                    placeholder="Tu contraseña actual"
                                    autoFocus
                                />
                            </div>

                            <div>
                                <label className="flex items-center gap-2 text-sm font-semibold text-slate-700 mb-1.5">
                                    <KeyRound className="w-4 h-4 text-slate-400" /> Código MFA
                                    <span className="text-xs text-slate-400 ml-auto">(si está habilitado)</span>
                                </label>
                                <input
                                    type="text"
                                    inputMode="numeric"
                                    maxLength={6}
                                    value={delMfa}
                                    onChange={(e) => setDelMfa(e.target.value.replace(/\D/g, ''))}
                                    className="w-full rounded-xl border border-slate-300 px-4 py-2.5 text-sm focus:border-rose-500 focus:ring-2 focus:ring-rose-500/20 outline-none"
                                    placeholder="6 dígitos"
                                />
                            </div>

                            <div>
                                <label className="block text-sm font-semibold text-slate-700 mb-1.5">
                                    Motivo (≥10 caracteres, queda en auditoría)
                                </label>
                                <textarea
                                    value={delReason}
                                    onChange={(e) => setDelReason(e.target.value)}
                                    rows={3}
                                    className="w-full rounded-xl border border-slate-300 px-4 py-2.5 text-sm focus:border-rose-500 focus:ring-2 focus:ring-rose-500/20 outline-none resize-none"
                                    placeholder="Ej: Baja por terminación contractual firmada el 15/Abr/2026 (#OP-1223)"
                                />
                            </div>

                            {delError && (
                                <div className="bg-rose-50 border border-rose-200 rounded-xl p-3 text-sm text-rose-700 font-medium">
                                    {delError}
                                </div>
                            )}

                            <div className="flex justify-end gap-3 pt-2 border-t border-slate-100">
                                <button
                                    type="button"
                                    onClick={closeDeleteModal}
                                    disabled={delLoading}
                                    className="px-5 py-2.5 text-sm font-semibold text-slate-600 hover:bg-slate-100 rounded-xl transition-colors"
                                >
                                    Cancelar
                                </button>
                                <button
                                    type="button"
                                    onClick={executeDelete}
                                    disabled={delLoading}
                                    className="px-6 py-2.5 text-sm font-bold text-white bg-rose-600 rounded-xl hover:bg-rose-700 transition-colors shadow-sm shadow-rose-500/30 disabled:opacity-70"
                                >
                                    {delLoading ? 'Eliminando...' : 'Eliminar dueño completo'}
                                </button>
                            </div>
                        </div>
                    </div>
                </div>
            )}

            {/* Resumen post-cascada: muestra contadores de lo borrado para que el
                operador vea el impacto real (útil para QA y para soporte). */}
            {delOutcome && (
                <div className="fixed inset-0 z-[120] flex items-center justify-center p-4">
                    <div className="absolute inset-0 bg-slate-900/70 backdrop-blur-sm" onClick={() => setDelOutcome(null)} />
                    <div className="relative bg-white rounded-2xl w-full max-w-lg shadow-2xl overflow-hidden border border-emerald-200">
                        <div className="px-6 py-4 border-b border-emerald-100 bg-emerald-50">
                            <h3 className="text-lg font-bold text-slate-800">Dueño eliminado</h3>
                            <p className="text-xs text-slate-500">{delOutcome.name} ({delOutcome.identifier})</p>
                        </div>
                        <div className="p-6 space-y-3 text-sm text-slate-700">
                            <p className="font-semibold">El identificador quedó libre para re-registrarse.</p>
                            <div className="grid grid-cols-2 gap-2">
                                {Object.entries(delOutcome.counters).map(([k, v]) => (
                                    <div key={k} className="flex justify-between bg-slate-50 border border-slate-200 rounded-lg px-3 py-1.5">
                                        <span className="text-slate-500 text-xs">{k}</span>
                                        <span className="font-bold text-slate-800 text-xs">{v}</span>
                                    </div>
                                ))}
                            </div>
                            <div className="flex justify-end pt-2">
                                <button
                                    onClick={() => setDelOutcome(null)}
                                    className="px-5 py-2 text-sm font-semibold text-emerald-700 hover:bg-emerald-50 rounded-xl transition-colors"
                                >
                                    Cerrar
                                </button>
                            </div>
                        </div>
                    </div>
                </div>
            )}
        </div>
    );
};
