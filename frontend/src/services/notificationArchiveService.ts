import api from './api';

/**
 * Cliente del panel de archivo de notificaciones del SUPER_ADMIN (Bloque C8).
 *
 * El backend mantiene los CSVs del archivador trimestral en un directorio local
 * ({@code admindi.notification-archive.path}). Este servicio es sólo para
 * SUPER_ADMIN — cualquier otro rol recibe 403 server-side.
 *
 * Las descargas se pipean como blob para respetar los headers Content-Disposition
 * que emite el backend.
 */

export interface ArchiveFolder {
  folder: string;         // slug raw ("david_cuernavaca_103_06140")
  tenantLabel: string;    // primer segmento del slug, como proxy de "inquilino"
  propertyLabel: string;  // resto del slug, como proxy de "propiedad"
  fileCount: number;
  totalSizeBytes: number;
}

export interface ArchiveFile {
  fileName: string;       // "2026Q1.csv"
  period: string;         // "2026Q1"
  sizeBytes: number;
  lastModified: string;   // ISO instant
}

const BASE = '/superadmin/notification-archive';

export async function searchFolders(query: string): Promise<ArchiveFolder[]> {
  const resp = await api.get<ArchiveFolder[]>(`${BASE}/search`, {
    params: query.trim() ? { q: query.trim() } : undefined,
  });
  return resp.data ?? [];
}

export async function listFiles(folder: string): Promise<ArchiveFile[]> {
  const resp = await api.get<ArchiveFile[]>(
    `${BASE}/folders/${encodeURIComponent(folder)}/files`
  );
  return resp.data ?? [];
}

/** Descarga un CSV específico como Blob y dispara un save-as en el navegador. */
export async function downloadFile(folder: string, fileName: string): Promise<void> {
  const resp = await api.get<Blob>(
    `${BASE}/folders/${encodeURIComponent(folder)}/files/${encodeURIComponent(fileName)}`,
    { responseType: 'blob' }
  );
  triggerDownload(resp.data, `${folder}_${fileName}`);
}

export async function downloadFolderZip(folder: string): Promise<void> {
  const resp = await api.get<Blob>(
    `${BASE}/folders/${encodeURIComponent(folder)}/download-zip`,
    { responseType: 'blob' }
  );
  triggerDownload(resp.data, `${folder}.zip`);
}

export async function deleteFile(
  folder: string,
  fileName: string,
  password: string,
  mfaCode: string
): Promise<void> {
  await api.post(
    `${BASE}/folders/${encodeURIComponent(folder)}/files/${encodeURIComponent(fileName)}/delete`,
    { password, mfaCode }
  );
}

export async function deleteFolder(
  folder: string,
  password: string,
  mfaCode: string
): Promise<void> {
  await api.post(
    `${BASE}/folders/${encodeURIComponent(folder)}/delete`,
    { password, mfaCode }
  );
}

function triggerDownload(blob: Blob, filename: string) {
  const url = window.URL.createObjectURL(blob);
  const link = document.createElement('a');
  link.href = url;
  link.download = filename;
  document.body.appendChild(link);
  link.click();
  link.remove();
  window.URL.revokeObjectURL(url);
}
