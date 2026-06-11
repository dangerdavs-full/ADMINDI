import { useEffect, useState } from 'react';
import { maintenanceBudgetService, MaintenanceBudget } from '../services/maintenanceBudgetService';
import { useAuth } from '../context/AuthContext';
import { FileText, Upload, CheckCircle2, XCircle, Clock } from 'lucide-react';

const STATUS_LABEL: Record<MaintenanceBudget['status'], string> = {
    SUBMITTED: 'Pendiente',
    APPROVED: 'Aprobado',
    REJECTED: 'Rechazado',
};

const STATUS_COLOR: Record<MaintenanceBudget['status'], string> = {
    SUBMITTED: 'bg-amber-50 text-amber-700 border-amber-200',
    APPROVED: 'bg-emerald-50 text-emerald-700 border-emerald-200',
    REJECTED: 'bg-rose-50 text-rose-700 border-rose-200',
};

export const MaintenanceBudgetManager = () => {
    const { user } = useAuth();
    // Solo el dueño aprueba/rechaza (SUPER_ADMIN puede operar por soporte).
    const canApprove = user?.role === 'OWNER' || user?.role === 'SUPER_ADMIN';
    // Únicos autores de presupuestos: mantenimiento y agente inmobiliario.
    const canUpload = user?.role === 'MAINTENANCE_PROVIDER' || user?.role === 'REAL_ESTATE_AGENT';

    const [items, setItems] = useState<MaintenanceBudget[]>([]);
    const [loading, setLoading] = useState(true);
    const [error, setError] = useState<string | null>(null);

    // Upload form
    const [title, setTitle] = useState('');
    const [description, setDescription] = useState('');
    const [amount, setAmount] = useState<string>('');
    const [file, setFile] = useState<File | null>(null);
    const [uploading, setUploading] = useState(false);

    // Decision dialog
    const [decisionFor, setDecisionFor] = useState<{ id: string; kind: 'approve' | 'reject' } | null>(null);
    const [password, setPassword] = useState('');
    const [mfaCode, setMfaCode] = useState('');
    const [note, setNote] = useState('');
    const [deciding, setDeciding] = useState(false);

    const load = async () => {
        setLoading(true);
        try {
            const data = await maintenanceBudgetService.list();
            setItems(data.sort((a, b) => (b.submittedAt || '').localeCompare(a.submittedAt || '')));
        } catch (e: unknown) {
            const err = e as { response?: { data?: { message?: string } }; message?: string };
            setError(err.response?.data?.message || err.message || 'Error al cargar presupuestos.');
        } finally {
            setLoading(false);
        }
    };

    useEffect(() => { load(); }, []);

    const handleUpload = async () => {
        if (!file || !title.trim()) return;
        setUploading(true);
        try {
            await maintenanceBudgetService.submit({
                title: title.trim(),
                description: description.trim() || undefined,
                amount: amount ? Number(amount) : undefined,
                file,
            });
            setTitle('');
            setDescription('');
            setAmount('');
            setFile(null);
            await load();
        } catch (e: unknown) {
            const err = e as { response?: { data?: { message?: string } }; message?: string };
            setError(err.response?.data?.message || err.message || 'Error al subir presupuesto.');
        } finally {
            setUploading(false);
        }
    };

    const handleDecision = async () => {
        if (!decisionFor) return;
        setDeciding(true);
        try {
            if (decisionFor.kind === 'approve') {
                await maintenanceBudgetService.approve(decisionFor.id, password, mfaCode || undefined, note || undefined);
            } else {
                await maintenanceBudgetService.reject(decisionFor.id, password, mfaCode || undefined, note || undefined);
            }
            setDecisionFor(null);
            setPassword('');
            setMfaCode('');
            setNote('');
            await load();
        } catch (e: unknown) {
            const err = e as { response?: { data?: { message?: string } }; message?: string };
            setError(err.response?.data?.message || err.message || 'No se pudo procesar la decisión.');
        } finally {
            setDeciding(false);
        }
    };

    return (
        <div className="space-y-6">
            <div>
                <h1 className="text-2xl font-bold text-slate-900">Presupuestos</h1>
                <p className="text-sm text-slate-500 mt-1">
                    Mantenimiento y agentes inmobiliarios suben cotizaciones (PDF o Excel).
                    El dueño las revisa y aprueba o rechaza con MFA y contraseña.
                </p>
            </div>

            {error && (
                <div className="border border-rose-200 bg-rose-50 text-rose-700 text-sm rounded-lg px-4 py-3">
                    {error}
                </div>
            )}

            {canUpload && (
                <div className="bg-white rounded-xl border border-slate-200 p-5 space-y-3">
                    <h2 className="font-semibold text-slate-900 flex items-center gap-2">
                        <Upload className="w-4 h-4" /> Subir nuevo presupuesto
                    </h2>
                    <div className="grid md:grid-cols-2 gap-3">
                        <input
                            type="text"
                            placeholder="Título (p.ej. Reparación fuga cocina)"
                            value={title}
                            onChange={e => setTitle(e.target.value)}
                            className="border border-slate-200 rounded-lg px-3 py-2 text-sm"
                        />
                        <input
                            type="number"
                            placeholder="Monto estimado (MXN)"
                            value={amount}
                            onChange={e => setAmount(e.target.value)}
                            className="border border-slate-200 rounded-lg px-3 py-2 text-sm"
                        />
                    </div>
                    <textarea
                        placeholder="Descripción (opcional)"
                        value={description}
                        onChange={e => setDescription(e.target.value)}
                        className="border border-slate-200 rounded-lg px-3 py-2 text-sm w-full"
                        rows={2}
                    />
                    <input
                        type="file"
                        accept=".pdf,.xls,.xlsx,.csv,.ods,application/pdf,application/vnd.ms-excel,application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
                        onChange={e => setFile(e.target.files?.[0] || null)}
                        className="text-sm"
                    />
                    <button
                        onClick={handleUpload}
                        disabled={uploading || !file || !title.trim()}
                        className="bg-indigo-600 text-white px-4 py-2 rounded-lg text-sm font-semibold disabled:opacity-50 hover:bg-indigo-700"
                    >
                        {uploading ? 'Subiendo...' : 'Enviar presupuesto'}
                    </button>
                </div>
            )}

            <div className="bg-white rounded-xl border border-slate-200 overflow-hidden">
                <div className="px-5 py-3 border-b border-slate-100 flex items-center justify-between">
                    <h2 className="font-semibold text-slate-900">Historial</h2>
                    <span className="text-xs text-slate-500">{items.length} registro(s)</span>
                </div>
                {loading ? (
                    <div className="p-6 text-sm text-slate-500">Cargando...</div>
                ) : items.length === 0 ? (
                    <div className="p-6 text-sm text-slate-500">Sin presupuestos por ahora.</div>
                ) : (
                    <ul className="divide-y divide-slate-100">
                        {items.map(b => (
                            <li key={b.id} className="p-5 flex items-start gap-4">
                                <div className="shrink-0 w-10 h-10 rounded-lg bg-slate-100 flex items-center justify-center">
                                    <FileText className="w-5 h-5 text-slate-500" />
                                </div>
                                <div className="flex-1 min-w-0">
                                    <div className="flex items-center gap-2">
                                        <p className="font-semibold text-slate-900 truncate">{b.title}</p>
                                        <span className={`text-[10px] px-2 py-0.5 rounded-full border font-semibold ${STATUS_COLOR[b.status]}`}>
                                            {STATUS_LABEL[b.status]}
                                        </span>
                                    </div>
                                    {b.description && <p className="text-xs text-slate-600 mt-1 line-clamp-2">{b.description}</p>}
                                    <div className="flex items-center gap-3 mt-2 text-xs text-slate-500">
                                        <span className="flex items-center gap-1"><Clock className="w-3 h-3" /> {new Date(b.submittedAt).toLocaleString()}</span>
                                        {b.amount != null && <span>{b.currency} {Number(b.amount).toFixed(2)}</span>}
                                        <a
                                            href={maintenanceBudgetService.downloadUrl(b.id)}
                                            target="_blank"
                                            rel="noreferrer"
                                            className="text-indigo-600 hover:underline"
                                        >
                                            Ver archivo
                                        </a>
                                    </div>
                                </div>
                                {canApprove && b.status === 'SUBMITTED' && (
                                    <div className="flex flex-col gap-2">
                                        <button
                                            onClick={() => setDecisionFor({ id: b.id, kind: 'approve' })}
                                            className="text-xs bg-emerald-600 text-white px-3 py-1.5 rounded-lg font-semibold hover:bg-emerald-700 flex items-center gap-1"
                                        >
                                            <CheckCircle2 className="w-3.5 h-3.5" /> Aprobar
                                        </button>
                                        <button
                                            onClick={() => setDecisionFor({ id: b.id, kind: 'reject' })}
                                            className="text-xs bg-rose-600 text-white px-3 py-1.5 rounded-lg font-semibold hover:bg-rose-700 flex items-center gap-1"
                                        >
                                            <XCircle className="w-3.5 h-3.5" /> Rechazar
                                        </button>
                                    </div>
                                )}
                            </li>
                        ))}
                    </ul>
                )}
            </div>

            {decisionFor && (
                <div className="fixed inset-0 bg-black/50 flex items-center justify-center z-50">
                    <div className="bg-white rounded-xl p-6 w-full max-w-md space-y-3">
                        <h3 className="font-bold text-slate-900">
                            {decisionFor.kind === 'approve' ? 'Aprobar presupuesto' : 'Rechazar presupuesto'}
                        </h3>
                        <p className="text-xs text-slate-500">
                            Confirme con su contraseña y código MFA si lo tiene activo.
                        </p>
                        <input
                            type="password"
                            placeholder="Contraseña"
                            value={password}
                            onChange={e => setPassword(e.target.value)}
                            className="border border-slate-200 rounded-lg px-3 py-2 text-sm w-full"
                        />
                        <input
                            type="text"
                            placeholder="Código MFA (si está activo)"
                            value={mfaCode}
                            onChange={e => setMfaCode(e.target.value)}
                            className="border border-slate-200 rounded-lg px-3 py-2 text-sm w-full"
                        />
                        <textarea
                            placeholder="Nota (opcional)"
                            value={note}
                            onChange={e => setNote(e.target.value)}
                            className="border border-slate-200 rounded-lg px-3 py-2 text-sm w-full"
                            rows={2}
                        />
                        <div className="flex justify-end gap-2">
                            <button
                                onClick={() => setDecisionFor(null)}
                                className="px-4 py-2 rounded-lg text-sm text-slate-600 hover:bg-slate-100"
                            >
                                Cancelar
                            </button>
                            <button
                                onClick={handleDecision}
                                disabled={deciding || !password}
                                className={`px-4 py-2 rounded-lg text-sm font-semibold text-white disabled:opacity-50 ${
                                    decisionFor.kind === 'approve' ? 'bg-emerald-600 hover:bg-emerald-700' : 'bg-rose-600 hover:bg-rose-700'
                                }`}
                            >
                                {deciding ? 'Procesando...' : decisionFor.kind === 'approve' ? 'Aprobar' : 'Rechazar'}
                            </button>
                        </div>
                    </div>
                </div>
            )}
        </div>
    );
};
