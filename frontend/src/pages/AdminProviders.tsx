import { useState, useEffect } from 'react';
import api from '../services/api';
import { Plus, Wrench, Trash, Phone, Mail, AtSign } from 'lucide-react';

interface ProviderDTO {
  id: string;
  /** V50 — identificador de login canónico. */
  username?: string;
  /** V50 — email queda como dato de contacto opcional. */
  email?: string;
  name: string;
  contactEmail: string;
  contactPhone: string;
  contactCountryCode: string;
  providerType: string;
  tempPassword?: string;
}

// V51 — username case-sensitive.
const USERNAME_PATTERN = /^[A-Za-z0-9][A-Za-z0-9._-]{2,63}$/;
const EMAIL_PATTERN = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;

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
];

const PROVIDER_TYPES = [
  { value: 'MAINTENANCE_PROVIDER', label: '🔧 Proveedor de Mantenimiento', color: 'bg-amber-100 text-amber-700' },
  { value: 'REAL_ESTATE_AGENT', label: '🏠 Agente Inmobiliario', color: 'bg-blue-100 text-blue-700' },
];

const INITIAL_FORM = { name: '', username: '', email: '', contactEmail: '', countryCode: '+52', rawPhone: '', providerType: 'MAINTENANCE_PROVIDER' };

export const AdminProviders = () => {
    const [providers, setProviders] = useState<ProviderDTO[]>([]);
    const [loading, setLoading] = useState(true);
    const [showForm, setShowForm] = useState(false);
    const [formData, setFormData] = useState(INITIAL_FORM);
    const [tempPassAlert, setTempPassAlert] = useState<{ identifier: string, pass: string } | null>(null);
    const [creating, setCreating] = useState(false);
    const [error, setError] = useState('');

    useEffect(() => {
        fetchProviders();
    }, []);

    const fetchProviders = async () => {
        setLoading(true);
        try {
            const res = await api.get('/admin/platform-providers');
            setProviders(res.data);
        } catch { setError('Error al listar proveedores.'); }
        setLoading(false);
    };

    const handleCreate = async () => {
        setError('');
        // V51 — username case-sensitive (solo trim). Email normalizado a lower.
        const username = formData.username.trim();
        const email = formData.email.trim().toLowerCase();
        const contactEmail = formData.contactEmail.trim().toLowerCase();
        if (formData.name.trim().length < 2) { setError('El nombre debe tener al menos 2 caracteres.'); return; }
        if (!USERNAME_PATTERN.test(username)) { setError('Usuario inválido. 3-64 caracteres: letras (mayúsculas o minúsculas), números, punto, guión o guión bajo; debe iniciar con letra o número.'); return; }
        if (!EMAIL_PATTERN.test(email)) { setError('El email es obligatorio y debe tener formato válido.'); return; }
        if (contactEmail && !EMAIL_PATTERN.test(contactEmail)) { setError('El correo de contacto alternativo no es válido.'); return; }
        const digits = formData.rawPhone.replace(/[^0-9]/g, '');
        if (digits.length < 7) { setError('El teléfono debe tener al menos 7 dígitos.'); return; }

        setCreating(true);
        try {
            const body = {
                name: formData.name.trim(),
                username,
                email,
                contactEmail: contactEmail || undefined,
                countryCode: formData.countryCode,
                rawPhone: digits,
                providerType: formData.providerType,
            };
            const res = await api.post('/admin/platform-providers', body);
            const identifier = res.data.username || res.data.email || username;
            setTempPassAlert({ identifier, pass: res.data.tempPassword });
            setFormData(INITIAL_FORM);
            setShowForm(false);
            fetchProviders();
        } catch (err: any) {
            setError(err.response?.data?.message || 'Error al crear proveedor.');
        }
        setCreating(false);
    };

    const handleDelete = async (id: string) => {
        if (!window.confirm('¿Desactivar este proveedor?')) return;
        try {
            await api.delete(`/admin/platform-providers/${id}`);
            fetchProviders();
        } catch { setError('Error al desactivar.'); }
    };

    const getTypeBadge = (type: string) => {
        const t = PROVIDER_TYPES.find(pt => pt.value === type);
        return t ? (
            <span className={`text-xs px-2 py-0.5 rounded-full font-semibold ${t.color}`}>
                {type === 'MAINTENANCE_PROVIDER' ? '🔧 Mantenimiento' : '🏠 Inmobiliario'}
            </span>
        ) : <span className="text-xs text-slate-400">{type}</span>;
    };

    return (
        <div>
            <div className="flex items-center justify-between mb-6">
                <div className="flex items-center gap-3">
                    <Wrench className="w-7 h-7 text-amber-600" />
                    <h2 className="text-2xl font-bold text-slate-800">Proveedores de Plataforma</h2>
                    <span className="text-sm bg-amber-100 text-amber-700 px-2 py-0.5 rounded-full font-semibold">{providers.length}</span>
                </div>
                <button onClick={() => setShowForm(!showForm)} className="flex items-center gap-2 bg-amber-500 text-white px-4 py-2 rounded-lg hover:bg-amber-600 transition-colors text-sm font-bold">
                    <Plus className="w-4 h-4" /> Nuevo Proveedor
                </button>
            </div>

            {tempPassAlert && (
                <div className="mb-4 p-4 bg-emerald-50 border border-emerald-200 rounded-xl">
                    <p className="text-sm font-bold text-emerald-800">✅ Proveedor creado exitosamente</p>
                    <p className="text-sm text-emerald-700 mt-1">Usuario de acceso: <strong>{tempPassAlert.identifier}</strong></p>
                    <p className="text-sm text-emerald-700">Contraseña temporal: <code className="bg-emerald-100 px-2 py-0.5 rounded font-mono text-emerald-900">{tempPassAlert.pass}</code></p>
                    <p className="text-xs text-emerald-500 mt-2">⚠ Guarda esta contraseña, no se mostrará de nuevo.</p>
                    <button onClick={() => setTempPassAlert(null)} className="mt-2 text-xs text-emerald-600 underline">Cerrar</button>
                </div>
            )}

            {error && <div className="mb-4 p-3 bg-red-50 border border-red-200 rounded-lg text-sm text-red-700">{error}</div>}

            {showForm && (
                <div className="mb-6 p-5 bg-white border border-slate-200 rounded-xl shadow-sm">
                    <h3 className="text-sm font-bold text-slate-700 mb-4">Alta de Proveedor</h3>

                    {/* Provider Type Selector */}
                    <div className="mb-4">
                        <label className="block text-xs font-semibold text-slate-500 mb-2">Tipo de Proveedor</label>
                        <div className="flex gap-3">
                            {PROVIDER_TYPES.map(pt => (
                                <button key={pt.value}
                                    type="button"
                                    onClick={() => setFormData({...formData, providerType: pt.value})}
                                    className={`flex-1 px-4 py-3 rounded-lg border-2 text-sm font-semibold transition-all ${
                                        formData.providerType === pt.value
                                            ? 'border-amber-500 bg-amber-50 text-amber-800 shadow-sm'
                                            : 'border-slate-200 bg-white text-slate-600 hover:border-slate-300'
                                    }`}>
                                    {pt.label}
                                </button>
                            ))}
                        </div>
                    </div>

                    <div className="grid grid-cols-1 md:grid-cols-2 gap-4 mb-4">
                        <div>
                            <label className="block text-xs font-semibold text-slate-500 mb-1">Nombre</label>
                            <input type="text" value={formData.name} onChange={e => setFormData({...formData, name: e.target.value})}
                                className="w-full px-3 py-2 border border-slate-200 rounded-lg text-sm" placeholder="Mantenimiento Express SA" />
                        </div>
                        <div>
                            <label className="block text-xs font-semibold text-slate-500 mb-1 flex items-center gap-1">
                                <AtSign className="w-3 h-3" /> Usuario de acceso *
                            </label>
                            <input type="text" autoComplete="off" value={formData.username}
                                onChange={e => setFormData({...formData, username: e.target.value.replace(/\s+/g, '')})}
                                className="w-full px-3 py-2 border border-slate-200 rounded-lg text-sm" placeholder="ej. Manto-Express-2026" />
                            <p className="text-[10px] text-slate-400 mt-0.5">Identificador único. 3-64 caracteres. <strong>Distingue mayúsculas y minúsculas.</strong></p>
                        </div>
                        <div>
                            <label className="block text-xs font-semibold text-slate-500 mb-1 flex items-center gap-1">
                                <Mail className="w-3 h-3" /> Email *
                            </label>
                            <input type="email" value={formData.email} onChange={e => setFormData({...formData, email: e.target.value})}
                                className="w-full px-3 py-2 border border-slate-200 rounded-lg text-sm" placeholder="contacto@proveedor.com" required />
                            <p className="text-[10px] text-slate-400 mt-0.5">Canal oficial de contacto del proveedor.</p>
                        </div>
                        <div>
                            <label className="block text-xs font-semibold text-slate-500 mb-1">Email contacto alternativo (opcional)</label>
                            <input type="email" value={formData.contactEmail} onChange={e => setFormData({...formData, contactEmail: e.target.value})}
                                className="w-full px-3 py-2 border border-slate-200 rounded-lg text-sm" placeholder="otro-correo@proveedor.com" />
                            <p className="text-[10px] text-slate-400 mt-0.5">Si se captura, tiene prioridad sobre el email principal.</p>
                        </div>
                        <div>
                            <label className="block text-xs font-semibold text-slate-500 mb-1">Teléfono</label>
                            <div className="flex gap-2">
                                <select value={formData.countryCode} onChange={e => setFormData({...formData, countryCode: e.target.value})}
                                    className="px-2 py-2 border border-slate-200 rounded-lg text-sm w-44">
                                    {COUNTRY_CODES.map(c => <option key={c.code} value={c.code}>{c.label}</option>)}
                                </select>
                                <input type="tel" value={formData.rawPhone} onChange={e => setFormData({...formData, rawPhone: e.target.value})}
                                    className="flex-1 px-3 py-2 border border-slate-200 rounded-lg text-sm" placeholder="8115554433" />
                            </div>
                        </div>
                    </div>
                    <div className="flex gap-2">
                        <button onClick={handleCreate} disabled={creating}
                            className="bg-amber-500 text-white px-5 py-2 rounded-lg hover:bg-amber-600 transition-colors text-sm font-bold disabled:opacity-50">
                            {creating ? 'Creando...' : 'Crear Proveedor'}
                        </button>
                        <button onClick={() => { setShowForm(false); setError(''); }}
                            className="text-slate-500 px-4 py-2 rounded-lg hover:bg-slate-100 text-sm">Cancelar</button>
                    </div>
                </div>
            )}

            {loading ? (
                <div className="text-center py-12 text-slate-400">Cargando proveedores...</div>
            ) : providers.length === 0 ? (
                <div className="text-center py-12 bg-white border border-slate-200 rounded-xl">
                    <Wrench className="w-12 h-12 text-slate-300 mx-auto mb-3" />
                    <p className="text-slate-500">No hay proveedores registrados aún.</p>
                </div>
            ) : (
                <div className="bg-white rounded-xl border border-slate-200 overflow-hidden">
                    <table className="w-full text-sm">
                        <thead>
                            <tr className="bg-slate-50 text-left text-xs text-slate-500 uppercase">
                                <th className="px-4 py-3">Nombre</th>
                                <th className="px-4 py-3">Tipo</th>
                                <th className="px-4 py-3">Usuario</th>
                                <th className="px-4 py-3">Contacto</th>
                                <th className="px-4 py-3">Teléfono</th>
                                <th className="px-4 py-3 w-20"></th>
                            </tr>
                        </thead>
                        <tbody>
                            {providers.map(p => (
                                <tr key={p.id} className="border-t border-slate-100 hover:bg-slate-50 transition-colors">
                                    <td className="px-4 py-3 font-medium text-slate-800">{p.name}</td>
                                    <td className="px-4 py-3">{getTypeBadge(p.providerType)}</td>
                                    <td className="px-4 py-3">
                                        <div className="flex items-center gap-1.5 text-slate-600">
                                            <AtSign className="w-3.5 h-3.5" /> {p.username || p.email || '—'}
                                        </div>
                                    </td>
                                    <td className="px-4 py-3 text-slate-600 flex items-center gap-1.5"><Mail className="w-3.5 h-3.5" />{p.contactEmail || '—'}</td>
                                    <td className="px-4 py-3">
                                        <div className="flex items-center gap-1.5 text-slate-600">
                                            <Phone className="w-3.5 h-3.5" /> {p.contactPhone || '-'}
                                        </div>
                                    </td>
                                    <td className="px-4 py-3">
                                        <button onClick={() => handleDelete(p.id)} className="p-1.5 text-red-400 hover:text-red-600 hover:bg-red-50 rounded transition-colors" title="Desactivar">
                                            <Trash className="w-4 h-4" />
                                        </button>
                                    </td>
                                </tr>
                            ))}
                        </tbody>
                    </table>
                </div>
            )}
        </div>
    );
};
