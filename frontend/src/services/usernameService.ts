import api from './api';

/**
 * V67 — Verificación asíncrona de disponibilidad de username.
 *
 * Los formularios de creación de staff (property admin, contador), agentes
 * privados e inquilinos deben llamar a esto con debounce (~400ms) mientras
 * el dueño escribe, para darle feedback inmediato si el username ya está
 * ocupado (incluyendo tombstones previos que aún conservan el placeholder
 * renombrado).
 *
 * Contrato con el backend ({@code GET /api/auth/check-username}):
 *   - `{ available: true, normalized }` si está libre.
 *   - `{ available: false, normalized, suggestion }` si está ocupado.
 *
 * Acepta 400 cuando el username no cumple formato (letras/números/._-);
 * en ese caso devolvemos un error semántico para que el caller lo muestre.
 */

export interface UsernameCheckResult {
  available: boolean;
  normalized: string;
  suggestion?: string;
  /** Presente si el formato es inválido (longitud o caracteres). */
  formatError?: string;
}

export const usernameService = {
  check: async (username: string): Promise<UsernameCheckResult> => {
    if (!username || !username.trim()) {
      return { available: false, normalized: '', formatError: 'Captura un nombre de usuario.' };
    }
    try {
      const res = await api.get('/auth/check-username', {
        params: { username: username.trim() },
      });
      const data = res.data as { available: boolean; normalized: string; suggestion?: string };
      return {
        available: !!data.available,
        normalized: data.normalized,
        suggestion: data.suggestion,
      };
    } catch (e: any) {
      // El backend responde 400 si el username no cumple formato. En ese caso
      // tratamos el resultado como "no disponible con detalle de formato"
      // para no lanzar una excepción en el componente del formulario.
      const msg = e?.response?.data?.message
                || e?.response?.data?.error
                || e?.message
                || 'No se pudo validar el nombre de usuario.';
      return {
        available: false,
        normalized: username.trim(),
        formatError: typeof msg === 'string' ? msg : 'Formato inválido.',
      };
    }
  },
};
