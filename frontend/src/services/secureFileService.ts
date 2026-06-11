import api from './api';

/**
 * Consumo de archivos sensibles servidos por `SecureFileController`.
 *
 * Contrato operativo:
 *   · Nunca se referencian URLs públicas del tipo `/uploads/...` (ya no existen).
 *   · Cada descarga pasa por axios → la instancia `api` agrega el header
 *     `Authorization: Bearer ...` automáticamente, cosa que un `<a href>` no hace.
 *   · Se obtiene el blob en memoria y se abre con `URL.createObjectURL` para
 *     preview nativo del navegador. El blob URL es efímero y local al tab:
 *     no se puede compartir externamente, no queda en logs de proxies, y se
 *     libera con `revokeObjectURL` poco después de abrir para no filtrar memoria.
 *   · Si el backend responde 403 (IDOR legítimo) o 404 (archivo ya borrado),
 *     el error se propaga al caller para que muestre el mensaje al usuario.
 *
 * Tipos soportados — cada uno corresponde a un endpoint en el backend con
 * autorización específica (ver SecureFileController javadoc).
 */
export type SecureFileKind =
    | 'lease-document'
    | 'lease-file'
    | 'property-file'
    | 'agreement-evidence'
    | 'transfer-proof'
    | 'transfer-proof-cep-xml'
    | 'transfer-proof-cep-pdf'
    | 'maintenance-budget';

export interface SecureFileOptions {
    /** Forzar descarga ("Guardar como...") en vez de preview inline. */
    download?: boolean;
    /** Nombre sugerido al guardar (sobrescribe el del backend). */
    suggestedName?: string;
}

/**
 * Descarga el archivo protegido y lo abre en una nueva pestaña.
 * Retorna el blob URL en caso de que el caller quiera manipularlo (p. ej.
 * embeberlo en un `<iframe>`); si no lo necesita, se revoca automáticamente.
 */
export async function openSecureFile(kind: SecureFileKind, resourceId: string,
                                     options: SecureFileOptions = {}): Promise<void> {
    const res = await api.get(`/secure-files/${kind}/${resourceId}`, {
        responseType: 'blob',
    });
    const contentType = (res.headers?.['content-type'] as string | undefined)
        || 'application/octet-stream';
    const blob = new Blob([res.data], { type: contentType });
    const url = URL.createObjectURL(blob);
    if (options.download) {
        // Descarga forzada: usamos un anchor temporal con `download` attribute.
        const a = document.createElement('a');
        a.href = url;
        a.download = options.suggestedName || `archivo-${resourceId}`;
        document.body.appendChild(a);
        a.click();
        a.remove();
    } else {
        // Preview inline (nueva pestaña).
        window.open(url, '_blank', 'noopener,noreferrer');
    }
    // Liberamos el blob URL tras un minuto: suficiente para que el browser lo abra
    // y no lo revoque antes de tiempo. Si el usuario lo recarga después, vuelve a
    // pasar por axios (y por auth).
    setTimeout(() => URL.revokeObjectURL(url), 60_000);
}

/**
 * V61 — Descarga un adjunto registrado como {@code file_upload_claims}
 * usando el path opaco que devolvió el upload. Autorización la resuelve el
 * backend según el recurso consumidor (ticket de mantenimiento, cotización,
 * etc.). Usa el mismo patrón blob + object URL que {@link openSecureFile}.
 */
export async function openFileAttachment(fileId: string,
                                         options: SecureFileOptions = {}): Promise<void> {
    const res = await api.get('/secure-files/file-attachment', {
        params: { fileId },
        responseType: 'blob',
    });
    const contentType = (res.headers?.['content-type'] as string | undefined)
        || 'application/octet-stream';
    const blob = new Blob([res.data], { type: contentType });
    const url = URL.createObjectURL(blob);
    if (options.download) {
        const a = document.createElement('a');
        a.href = url;
        a.download = options.suggestedName || extractFileName(fileId);
        document.body.appendChild(a);
        a.click();
        a.remove();
    } else {
        window.open(url, '_blank', 'noopener,noreferrer');
    }
    setTimeout(() => URL.revokeObjectURL(url), 60_000);
}

function extractFileName(pathLike: string): string {
    if (!pathLike) return 'archivo';
    const i = Math.max(pathLike.lastIndexOf('/'), pathLike.lastIndexOf('\\'));
    return i >= 0 ? pathLike.substring(i + 1) : pathLike;
}

/**
 * Traduce un error de descarga a un mensaje corto para la UI.
 */
export function describeSecureFileError(err: any): string {
    const status = err?.response?.status;
    if (status === 403) return 'No tienes acceso a este archivo.';
    if (status === 404) return 'El archivo ya no está disponible.';
    if (status === 401) return 'Sesión expirada. Vuelve a iniciar sesión.';
    if (status === 410) return 'Este enlace quedó obsoleto tras el refactor de seguridad. Recarga la página.';
    return err?.response?.data?.message || 'No se pudo abrir el archivo.';
}
