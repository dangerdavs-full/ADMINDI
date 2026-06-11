import React, { useEffect, useState } from 'react';
import {
  Archive, Search, Download, FileDown, Trash2, Folder, FileText,
  AlertTriangle, RefreshCw, X, Info,
} from 'lucide-react';
import {
  ArchiveFolder, ArchiveFile,
  searchFolders, listFiles, downloadFile, downloadFolderZip,
  deleteFile, deleteFolder,
} from '../services/notificationArchiveService';
import { ReauthConfirmModal } from '../components/modals/ReauthConfirmModal';

/**
 * Panel SUPER_ADMIN para el archivo trimestral de notificaciones (Bloque C8).
 *
 * <p>Layout master-detail: columna izquierda lista carpetas filtradas, columna
 * derecha muestra los CSVs (trimestres) de la carpeta seleccionada con acciones
 * de descarga/borrado.
 *
 * <p>Operaciones sensibles (borrar archivo/carpeta) exigen reauth MFA+password
 * mediante el {@link ReauthConfirmModal} reutilizable.
 */
export const NotificationArchivePanel: React.FC = () => {
  const [query, setQuery] = useState('');
  const [folders, setFolders] = useState<ArchiveFolder[]>([]);
  const [loadingFolders, setLoadingFolders] = useState(false);
  const [selectedFolder, setSelectedFolder] = useState<ArchiveFolder | null>(null);
  const [files, setFiles] = useState<ArchiveFile[]>([]);
  const [loadingFiles, setLoadingFiles] = useState(false);
  const [errorMsg, setErrorMsg] = useState('');
  const [toast, setToast] = useState<{ kind: 'ok' | 'err'; msg: string } | null>(null);

  const [pendingDelete, setPendingDelete] = useState<
    | { mode: 'file'; folder: string; fileName: string }
    | { mode: 'folder'; folder: string }
    | null
  >(null);

  // Debounce del buscador → reduce llamadas cuando el usuario teclea rápido.
  useEffect(() => {
    const t = setTimeout(() => runSearch(query), 300);
    return () => clearTimeout(t);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [query]);

  useEffect(() => {
    if (!selectedFolder) { setFiles([]); return; }
    loadFiles(selectedFolder.folder);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [selectedFolder]);

  useEffect(() => {
    if (!toast) return;
    const t = setTimeout(() => setToast(null), 4000);
    return () => clearTimeout(t);
  }, [toast]);

  async function runSearch(q: string) {
    setLoadingFolders(true);
    setErrorMsg('');
    try {
      const list = await searchFolders(q);
      setFolders(list);
      if (selectedFolder && !list.find((f) => f.folder === selectedFolder.folder)) {
        setSelectedFolder(null);
      }
    } catch (e) {
      setErrorMsg(pickErr(e, 'No se pudo cargar el archivo.'));
      setFolders([]);
    } finally {
      setLoadingFolders(false);
    }
  }

  async function loadFiles(folder: string) {
    setLoadingFiles(true);
    setErrorMsg('');
    try {
      const list = await listFiles(folder);
      setFiles(list);
    } catch (e) {
      setErrorMsg(pickErr(e, 'No se pudieron listar los archivos.'));
      setFiles([]);
    } finally {
      setLoadingFiles(false);
    }
  }

  async function handleDownloadFile(file: ArchiveFile) {
    if (!selectedFolder) return;
    try {
      await downloadFile(selectedFolder.folder, file.fileName);
      setToast({ kind: 'ok', msg: `Descargado ${file.fileName}` });
    } catch (e) {
      setToast({ kind: 'err', msg: pickErr(e, 'Error al descargar.') });
    }
  }

  async function handleDownloadZip() {
    if (!selectedFolder) return;
    try {
      await downloadFolderZip(selectedFolder.folder);
      setToast({ kind: 'ok', msg: `Descargado ${selectedFolder.folder}.zip` });
    } catch (e) {
      setToast({ kind: 'err', msg: pickErr(e, 'Error al descargar ZIP.') });
    }
  }

  async function handleReauthConfirm(password: string, mfaCode: string) {
    if (!pendingDelete) return;
    try {
      if (pendingDelete.mode === 'file') {
        await deleteFile(pendingDelete.folder, pendingDelete.fileName, password, mfaCode);
        setToast({ kind: 'ok', msg: `Archivo ${pendingDelete.fileName} eliminado.` });
        if (selectedFolder) await loadFiles(selectedFolder.folder);
      } else {
        await deleteFolder(pendingDelete.folder, password, mfaCode);
        setToast({ kind: 'ok', msg: 'Carpeta eliminada.' });
        setSelectedFolder(null);
        await runSearch(query);
      }
      setPendingDelete(null);
    } catch (e) {
      // Dejamos que el modal muestre el error propagando el throw.
      throw new Error(pickErr(e, 'Reautenticación fallida.'));
    }
  }

  return (
    <div className="space-y-4">
      {/* Header */}
      <div className="bg-white border border-slate-200 rounded-xl p-5">
        <div className="flex items-start justify-between gap-4 flex-wrap">
          <div>
            <h2 className="text-xl font-bold text-slate-900 flex items-center gap-2">
              <Archive className="w-5 h-5 text-slate-600" />
              Archivo de notificaciones
            </h2>
            <p className="text-sm text-slate-500 mt-1 max-w-2xl">
              Historial trimestral fuera del panel operativo. Se archiva automáticamente
              después de <strong>90 días</strong>. Solo el rol SUPER_ADMIN puede consultar,
              descargar o eliminar estos CSVs.
            </p>
          </div>
          <button
            onClick={() => runSearch(query)}
            className="flex items-center gap-2 text-sm font-medium text-slate-600 hover:text-slate-900 px-3 py-1.5 rounded-lg hover:bg-slate-50"
            title="Recargar"
          >
            <RefreshCw className="w-4 h-4" /> Recargar
          </button>
        </div>
        <div className="mt-4 relative">
          <Search className="w-4 h-4 text-slate-400 absolute top-1/2 left-3 -translate-y-1/2" />
          <input
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            placeholder="Buscar por inquilino, dirección, CP, número…"
            className="w-full pl-10 pr-3 py-2.5 border border-slate-300 rounded-lg text-sm focus:border-indigo-500 focus:ring focus:ring-indigo-500/20"
          />
        </div>
        <div className="mt-3 flex items-start gap-2 text-xs text-slate-500 bg-slate-50 border border-slate-200 rounded-lg p-3">
          <Info className="w-4 h-4 mt-0.5 flex-shrink-0 text-slate-400" />
          <span>
            Las descargas y borrados quedan registrados en la auditoría del sistema con tu
            correo, IP y timestamp. Los borrados requieren contraseña + MFA.
          </span>
        </div>
      </div>

      {errorMsg && (
        <div className="bg-rose-50 border border-rose-200 rounded-lg p-3 text-sm text-rose-700 flex items-center gap-2">
          <AlertTriangle className="w-4 h-4" /> {errorMsg}
        </div>
      )}

      {/* Master-detail */}
      <div className="grid grid-cols-1 md:grid-cols-5 gap-4">
        {/* Carpetas */}
        <div className="md:col-span-2 bg-white border border-slate-200 rounded-xl overflow-hidden">
          <div className="px-4 py-3 border-b border-slate-200 bg-slate-50 flex items-center justify-between">
            <span className="text-xs font-bold text-slate-600 uppercase tracking-wide">
              Carpetas {folders.length > 0 && <span className="text-slate-400">({folders.length})</span>}
            </span>
          </div>
          {loadingFolders ? (
            <div className="p-6 text-center text-sm text-slate-400">Cargando…</div>
          ) : folders.length === 0 ? (
            <div className="p-6 text-center text-sm text-slate-400">
              {query
                ? 'Sin coincidencias. Prueba con otro término.'
                : 'Aún no hay archivos almacenados. Aparecerán tras el primer ciclo de archivado.'}
            </div>
          ) : (
            <ul className="divide-y divide-slate-100 max-h-[560px] overflow-y-auto">
              {folders.map((f) => {
                const isActive = selectedFolder?.folder === f.folder;
                return (
                  <li key={f.folder}>
                    <button
                      onClick={() => setSelectedFolder(f)}
                      className={`w-full text-left px-4 py-3 flex items-start gap-3 transition-colors ${
                        isActive ? 'bg-indigo-50 border-l-4 border-indigo-500' : 'hover:bg-slate-50 border-l-4 border-transparent'
                      }`}
                    >
                      <Folder className={`w-5 h-5 flex-shrink-0 mt-0.5 ${isActive ? 'text-indigo-500' : 'text-slate-400'}`} />
                      <div className="flex-1 min-w-0">
                        <div className="text-sm font-semibold text-slate-800 truncate">
                          {f.tenantLabel || '(sin inquilino)'}
                        </div>
                        <div className="text-xs text-slate-500 truncate">
                          {f.propertyLabel || '(sin propiedad)'}
                        </div>
                        <div className="text-[11px] text-slate-400 mt-1">
                          {f.fileCount} trimestre{f.fileCount === 1 ? '' : 's'} · {formatBytes(f.totalSizeBytes)}
                        </div>
                      </div>
                    </button>
                  </li>
                );
              })}
            </ul>
          )}
        </div>

        {/* Archivos */}
        <div className="md:col-span-3 bg-white border border-slate-200 rounded-xl overflow-hidden">
          {!selectedFolder ? (
            <div className="p-10 text-center text-sm text-slate-400">
              Selecciona una carpeta para ver sus CSVs trimestrales.
            </div>
          ) : (
            <>
              <div className="px-4 py-3 border-b border-slate-200 bg-slate-50 flex items-center justify-between gap-2 flex-wrap">
                <div className="min-w-0">
                  <div className="text-sm font-semibold text-slate-800 truncate">
                    {selectedFolder.tenantLabel} — {selectedFolder.propertyLabel}
                  </div>
                  <div className="text-[11px] text-slate-500 font-mono truncate">{selectedFolder.folder}</div>
                </div>
                <div className="flex items-center gap-2">
                  <button
                    onClick={handleDownloadZip}
                    disabled={files.length === 0}
                    className="flex items-center gap-1.5 text-xs font-semibold text-slate-700 border border-slate-300 hover:bg-slate-100 px-3 py-1.5 rounded-lg disabled:opacity-40 disabled:cursor-not-allowed"
                  >
                    <FileDown className="w-3.5 h-3.5" /> Descargar ZIP
                  </button>
                  <button
                    onClick={() =>
                      setPendingDelete({ mode: 'folder', folder: selectedFolder.folder })
                    }
                    className="flex items-center gap-1.5 text-xs font-semibold text-rose-700 border border-rose-200 hover:bg-rose-50 px-3 py-1.5 rounded-lg"
                  >
                    <Trash2 className="w-3.5 h-3.5" /> Borrar carpeta
                  </button>
                </div>
              </div>
              {loadingFiles ? (
                <div className="p-6 text-center text-sm text-slate-400">Cargando…</div>
              ) : files.length === 0 ? (
                <div className="p-6 text-center text-sm text-slate-400">Carpeta vacía.</div>
              ) : (
                <ul className="divide-y divide-slate-100">
                  {files.map((f) => (
                    <li key={f.fileName} className="px-4 py-3 flex items-center gap-3">
                      <FileText className="w-5 h-5 text-slate-400 flex-shrink-0" />
                      <div className="flex-1 min-w-0">
                        <div className="text-sm font-semibold text-slate-800">{f.period}</div>
                        <div className="text-[11px] text-slate-400">
                          {formatBytes(f.sizeBytes)} · modificado {formatDate(f.lastModified)}
                        </div>
                      </div>
                      <button
                        onClick={() => handleDownloadFile(f)}
                        className="text-xs font-semibold text-indigo-600 hover:text-indigo-800 flex items-center gap-1 px-2 py-1 rounded hover:bg-indigo-50"
                      >
                        <Download className="w-3.5 h-3.5" /> Descargar
                      </button>
                      <button
                        onClick={() =>
                          setPendingDelete({
                            mode: 'file',
                            folder: selectedFolder.folder,
                            fileName: f.fileName,
                          })
                        }
                        className="text-xs font-semibold text-rose-600 hover:text-rose-800 flex items-center gap-1 px-2 py-1 rounded hover:bg-rose-50"
                      >
                        <Trash2 className="w-3.5 h-3.5" /> Borrar
                      </button>
                    </li>
                  ))}
                </ul>
              )}
            </>
          )}
        </div>
      </div>

      {/* Toast */}
      {toast && (
        <div
          className={`fixed bottom-4 right-4 z-[60] px-4 py-3 rounded-lg shadow-lg border text-sm flex items-center gap-2 ${
            toast.kind === 'ok'
              ? 'bg-emerald-50 border-emerald-200 text-emerald-700'
              : 'bg-rose-50 border-rose-200 text-rose-700'
          }`}
        >
          {toast.kind === 'ok' ? <FileDown className="w-4 h-4" /> : <AlertTriangle className="w-4 h-4" />}
          <span>{toast.msg}</span>
          <button onClick={() => setToast(null)} className="ml-2 opacity-60 hover:opacity-100">
            <X className="w-3.5 h-3.5" />
          </button>
        </div>
      )}

      {/* Reauth modal */}
      <ReauthConfirmModal
        isOpen={!!pendingDelete}
        onClose={() => setPendingDelete(null)}
        onConfirm={handleReauthConfirm}
        title={
          pendingDelete?.mode === 'folder'
            ? 'Eliminar carpeta completa'
            : 'Eliminar archivo CSV'
        }
        description={
          pendingDelete?.mode === 'folder'
            ? 'Estás por borrar TODOS los CSVs trimestrales de esta carpeta. Esta acción es irreversible y queda auditada.'
            : `Estás por borrar el CSV ${
                pendingDelete && pendingDelete.mode === 'file' ? pendingDelete.fileName : ''
              }. Esta acción es irreversible y queda auditada.`
        }
        confirmLabel="Borrar definitivamente"
        accent="rose"
      />
    </div>
  );
};

// ────────────────────────────────────────────────────────────────────────────
//  Helpers
// ────────────────────────────────────────────────────────────────────────────

function formatBytes(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${(bytes / (1024 * 1024)).toFixed(2)} MB`;
}

function formatDate(iso: string): string {
  try {
    return new Date(iso).toLocaleString('es-MX', {
      year: 'numeric',
      month: 'short',
      day: '2-digit',
      hour: '2-digit',
      minute: '2-digit',
    });
  } catch {
    return iso;
  }
}

function pickErr(e: unknown, fallback: string): string {
  const anyE = e as { response?: { data?: { message?: string; error?: string } }; message?: string };
  return (
    anyE?.response?.data?.message ||
    anyE?.response?.data?.error ||
    anyE?.message ||
    fallback
  );
}
