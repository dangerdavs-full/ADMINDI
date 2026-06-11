import api from './api';

/**
 * Helper para descargar archivos servidos por endpoints autenticados del backend.
 *
 * Reemplaza el anti-patrón histórico de pegar `?authorization=Bearer ${token}`
 * en la URL y abrir con `window.open`. Ese patrón tenía dos fallas graves:
 *   1. Spring Security NO lee ese query param como header, así que el backend
 *      respondía 401 en producción (solo funcionaba en entornos sin seguridad).
 *   2. El token quedaba en logs del navegador, historial, referrers y cualquier
 *      proxy intermedio — fuga de credenciales.
 *
 * Implementación: usamos la instancia `api` de axios, que ya inyecta el Bearer
 * token en el header `Authorization` e incluye el interceptor de refresh
 * automático para 401 expirado. El response viene como `blob` y se fuerza la
 * descarga creando un `<a download>` efímero.
 *
 * Si el servidor incluye `Content-Disposition: attachment; filename="..."` se
 * respeta ese nombre; si no, se usa `fallbackFilename`.
 */
export async function downloadAuthenticatedFile(
    path: string,
    fallbackFilename: string,
): Promise<void> {
    const response = await api.get(path, { responseType: 'blob' });

    // Content-Disposition puede venir como 'attachment; filename="Reporte_2026-03.xlsx"'
    // — respetamos lo que envía el backend si viene, sino usamos el fallback.
    const disposition = response.headers?.['content-disposition'] as string | undefined;
    const filename = extractFilename(disposition) || fallbackFilename;

    const blob = response.data as Blob;
    const blobUrl = URL.createObjectURL(blob);

    const link = document.createElement('a');
    link.href = blobUrl;
    link.download = filename;
    // Firefox requiere el link adjuntado al DOM para aceptar el click programático.
    document.body.appendChild(link);
    link.click();
    document.body.removeChild(link);

    // Liberamos el blob URL para no filtrar memoria (el navegador lo retiene
    // mientras el documento viva si no lo revocamos).
    URL.revokeObjectURL(blobUrl);
}

/**
 * Extrae el {@code filename} de un header `Content-Disposition`. Soporta ambos
 * formatos comunes: {@code filename="..."} (RFC 2616) y {@code filename*=UTF-8''...}
 * (RFC 5987) para nombres con caracteres no-ASCII.
 */
function extractFilename(disposition: string | undefined): string | null {
    if (!disposition) return null;

    const utf8Match = /filename\*=UTF-8''([^;]+)/i.exec(disposition);
    if (utf8Match) {
        try {
            return decodeURIComponent(utf8Match[1]);
        } catch {
            // Fallback al valor literal si la decodificación explota.
        }
    }

    const plainMatch = /filename="?([^";]+)"?/i.exec(disposition);
    if (plainMatch) return plainMatch[1];

    return null;
}
