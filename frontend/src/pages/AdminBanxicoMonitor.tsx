import { useEffect, useState } from 'react';
import { Activity, AlertTriangle, CheckCircle2, RefreshCw, Upload, Clock, Database } from 'lucide-react';
import api from '../services/api';

/**
 * Panel SUPER_ADMIN para inspeccionar y operar el scraper adaptativo de
 * Banxico CEP (V55/V56).
 *
 * Muestra:
 *  - Versiones de schema con cuál está activa.
 *  - Historial de fallos y si fueron resueltos automáticamente por IA.
 *  - Formulario manual para forzar re-inferencia (pegar HTML).
 *  - Rollback: activar un schema anterior.
 */

interface BanxicoSchema {
  id: string;
  version: number;
  active: boolean;
  source: string;
  createdAt: string;
  lastSuccessAt: string | null;
  deactivatedAt: string | null;
  selectorsJson: string;
}

interface BanxicoFailure {
  id: string;
  url: string;
  htmlSnippetHash: string;
  htmlSnippetPreview: string;
  detectedAt: string;
  resolvedByAiAt: string | null;
  newSchemaId: string | null;
  aiError: string | null;
  notifiedSuperAdmin: boolean;
}

export const AdminBanxicoMonitor = () => {
  const [schemas, setSchemas] = useState<BanxicoSchema[]>([]);
  const [failures, setFailures] = useState<BanxicoFailure[]>([]);
  const [unresolvedCount, setUnresolvedCount] = useState(0);
  const [loading, setLoading] = useState(false);
  const [expandedSchema, setExpandedSchema] = useState<string | null>(null);
  const [reinferOpen, setReinferOpen] = useState(false);
  const [reinferHtml, setReinferHtml] = useState('');
  const [reinferUrl, setReinferUrl] = useState('');
  const [reinferResult, setReinferResult] = useState<string | null>(null);
  const [reinferLoading, setReinferLoading] = useState(false);

  const load = async () => {
    setLoading(true);
    try {
      const [schemasRes, failuresRes] = await Promise.all([
        api.get('/admin/banxico/schemas'),
        api.get('/admin/banxico/failures'),
      ]);
      setSchemas(schemasRes.data);
      setFailures(failuresRes.data.items || []);
      setUnresolvedCount(failuresRes.data.unresolvedCount || 0);
    } catch (e: any) {
      console.error('Error loading Banxico data:', e);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => { load(); }, []);

  const handleReinfer = async () => {
    if (!reinferHtml.trim()) {
      setReinferResult('Pega el HTML del CEP para poder analizarlo.');
      return;
    }
    setReinferLoading(true);
    setReinferResult(null);
    try {
      const res = await api.post('/admin/banxico/reinfer', {
        html: reinferHtml,
        url: reinferUrl || 'manual://super-admin',
      });
      if (res.data.ok) {
        setReinferResult(`Nuevo schema v${res.data.version} activo.`);
        setReinferHtml('');
        setReinferUrl('');
        await load();
      } else {
        setReinferResult('La IA no pudo generar selectores válidos para este HTML: ' +
            (res.data.error || 'razón desconocida'));
      }
    } catch (e: any) {
      setReinferResult(e.response?.data?.error || 'Error al ejecutar re-inferencia.');
    } finally {
      setReinferLoading(false);
    }
  };

  const handleActivate = async (schemaId: string) => {
    if (!confirm('¿Activar este schema? El actual se desactivará automáticamente.')) return;
    try {
      await api.post(`/admin/banxico/schemas/${schemaId}/activate`);
      await load();
    } catch (e: any) {
      alert(e.response?.data?.error || 'No se pudo activar.');
    }
  };

  const activeSchema = schemas.find(s => s.active);

  return (
    <div className="max-w-6xl mx-auto p-4 sm:p-6 space-y-6">
      <div className="flex items-start justify-between gap-4 flex-wrap">
        <div>
          <h1 className="text-2xl font-bold text-slate-800 flex items-center gap-2">
            <Activity className="w-6 h-6 text-teal-600" />
            Monitor Banxico CEP
          </h1>
          <p className="text-sm text-slate-500 mt-1">
            Versiones del scraper y adaptaciones automáticas con IA.
          </p>
        </div>
        <div className="flex items-center gap-2">
          <button
            onClick={load}
            disabled={loading}
            className="px-3 py-1.5 text-sm bg-white border border-slate-300 rounded-lg hover:bg-slate-50 flex items-center gap-1.5"
          >
            <RefreshCw className={`w-4 h-4 ${loading ? 'animate-spin' : ''}`} /> Refrescar
          </button>
          <button
            onClick={() => setReinferOpen(o => !o)}
            className="px-3 py-1.5 text-sm bg-indigo-600 hover:bg-indigo-700 text-white rounded-lg flex items-center gap-1.5"
          >
            <Upload className="w-4 h-4" /> Re-inferir con IA
          </button>
        </div>
      </div>

      {/* KPIs */}
      <div className="grid grid-cols-1 sm:grid-cols-3 gap-3">
        <div className="rounded-xl border border-slate-200 bg-white p-4">
          <div className="flex items-center gap-2 text-sm text-slate-500">
            <Database className="w-4 h-4" /> Schema activo
          </div>
          <p className="mt-2 text-xl font-bold text-slate-800">
            {activeSchema ? `v${activeSchema.version}` : 'ninguno'}
          </p>
          {activeSchema && (
            <p className="text-xs text-slate-500 mt-1">origen: {activeSchema.source}</p>
          )}
        </div>
        <div className="rounded-xl border border-slate-200 bg-white p-4">
          <div className="flex items-center gap-2 text-sm text-slate-500">
            <AlertTriangle className="w-4 h-4" /> Fallos sin resolver
          </div>
          <p className={`mt-2 text-xl font-bold ${unresolvedCount > 0 ? 'text-amber-600' : 'text-emerald-600'}`}>
            {unresolvedCount}
          </p>
        </div>
        <div className="rounded-xl border border-slate-200 bg-white p-4">
          <div className="flex items-center gap-2 text-sm text-slate-500">
            <Clock className="w-4 h-4" /> Versiones totales
          </div>
          <p className="mt-2 text-xl font-bold text-slate-800">{schemas.length}</p>
        </div>
      </div>

      {/* Re-inferir (manual) */}
      {reinferOpen && (
        <div className="rounded-xl border border-indigo-200 bg-indigo-50 p-4 space-y-3">
          <h3 className="font-bold text-indigo-900">Re-inferir schema manualmente</h3>
          <p className="text-xs text-indigo-700">
            Pega el HTML actual del formulario Banxico CEP y Claude intentará generar
            los selectores correctos. Si los nuevos selectores pasan validación,
            se crea una versión activa.
          </p>
          <input
            type="text"
            placeholder="URL de origen (opcional)"
            value={reinferUrl}
            onChange={e => setReinferUrl(e.target.value)}
            className="w-full text-sm p-2 border border-indigo-200 rounded-lg"
          />
          <textarea
            placeholder="Pega aquí el HTML..."
            value={reinferHtml}
            onChange={e => setReinferHtml(e.target.value)}
            rows={6}
            className="w-full text-xs font-mono p-2 border border-indigo-200 rounded-lg"
          />
          <div className="flex gap-2">
            <button
              onClick={handleReinfer}
              disabled={reinferLoading}
              className="px-3 py-1.5 bg-indigo-600 hover:bg-indigo-700 text-white text-sm rounded-lg disabled:opacity-60"
            >
              {reinferLoading ? 'Analizando...' : 'Re-inferir'}
            </button>
            <button
              onClick={() => setReinferOpen(false)}
              className="px-3 py-1.5 bg-white border border-indigo-200 text-sm rounded-lg"
            >
              Cerrar
            </button>
          </div>
          {reinferResult && (
            <p className="text-sm text-indigo-800 bg-white border border-indigo-200 rounded p-2">
              {reinferResult}
            </p>
          )}
        </div>
      )}

      {/* Failures */}
      <div>
        <h2 className="text-lg font-bold text-slate-800 mb-3">Historial de fallos</h2>
        {failures.length === 0 ? (
          <p className="text-sm text-slate-500 italic">Sin fallos registrados. El scraper funciona correctamente.</p>
        ) : (
          <div className="space-y-2">
            {failures.map(f => (
              <div key={f.id} className={`border rounded-lg p-3 ${f.resolvedByAiAt ? 'border-emerald-200 bg-emerald-50' : 'border-amber-200 bg-amber-50'}`}>
                <div className="flex items-start justify-between gap-3">
                  <div className="text-xs text-slate-600">
                    <p className="font-mono">{f.detectedAt}</p>
                    <p className="mt-1 text-slate-500 break-all">{f.url}</p>
                  </div>
                  <div>
                    {f.resolvedByAiAt ? (
                      <span className="inline-flex items-center gap-1 text-xs font-semibold text-emerald-700 bg-white border border-emerald-200 px-2 py-0.5 rounded">
                        <CheckCircle2 className="w-3 h-3" /> IA resolvió
                      </span>
                    ) : (
                      <span className="inline-flex items-center gap-1 text-xs font-semibold text-amber-700 bg-white border border-amber-200 px-2 py-0.5 rounded">
                        <AlertTriangle className="w-3 h-3" /> Pendiente
                      </span>
                    )}
                  </div>
                </div>
                {f.aiError && (
                  <p className="text-xs text-red-700 mt-2 font-mono">Error IA: {f.aiError}</p>
                )}
              </div>
            ))}
          </div>
        )}
      </div>

      {/* Schemas */}
      <div>
        <h2 className="text-lg font-bold text-slate-800 mb-3">Versiones de schema</h2>
        <div className="space-y-2">
          {schemas.map(s => (
            <div key={s.id} className={`border rounded-lg p-3 ${s.active ? 'border-teal-300 bg-teal-50' : 'border-slate-200 bg-white'}`}>
              <div className="flex items-start justify-between gap-3 flex-wrap">
                <div>
                  <p className="font-bold text-slate-800">
                    v{s.version}
                    {s.active && <span className="ml-2 text-xs font-semibold text-teal-700">ACTIVO</span>}
                  </p>
                  <p className="text-xs text-slate-500">origen: {s.source} · creado: {s.createdAt}</p>
                  {s.lastSuccessAt && (
                    <p className="text-xs text-slate-500">último éxito: {s.lastSuccessAt}</p>
                  )}
                </div>
                <div className="flex items-center gap-2">
                  <button
                    onClick={() => setExpandedSchema(prev => prev === s.id ? null : s.id)}
                    className="text-xs text-slate-600 hover:text-slate-800"
                  >
                    {expandedSchema === s.id ? 'Ocultar selectors' : 'Ver selectors'}
                  </button>
                  {!s.active && (
                    <button
                      onClick={() => handleActivate(s.id)}
                      className="text-xs px-2 py-1 bg-slate-800 text-white rounded hover:bg-slate-900"
                    >
                      Activar
                    </button>
                  )}
                </div>
              </div>
              {expandedSchema === s.id && (
                <pre className="mt-2 text-xs font-mono bg-slate-900 text-slate-100 p-2 rounded overflow-auto">
                  {typeof s.selectorsJson === 'string' ? s.selectorsJson : JSON.stringify(s.selectorsJson, null, 2)}
                </pre>
              )}
            </div>
          ))}
        </div>
      </div>
    </div>
  );
};

export default AdminBanxicoMonitor;
