import api from './api';

/**
 * Perfil del dueño (Fase 1 notificaciones).
 *
 * Endpoints:
 *  - GET  /api/owner/profile → datos de contacto + CLABE enmascarada (sin exponer valor).
 *  - PUT  /api/owner/profile → requiere password + mfaCode (reauth). Valida CLABE
 *    en backend antes de persistir cifrada. Dispara OWNER_PROFILE_UPDATED.
 */

export interface OwnerProfile {
  id: string;
  name: string;
  email: string;
  contactEmail: string;
  contactPhone: string;
  contactCountryCode: string;
  clabeMasked: string;
  hasClabe: boolean;
  bankName: string;
  accountHolderName: string;
}

export interface OwnerProfileUpdatePayload {
  contactEmail?: string;
  contactPhone?: string;
  contactCountryCode?: string;
  clabe?: string;
  bankName?: string;
  accountHolderName?: string;
  password: string;
  mfaCode: string;
}

export const ownerProfileService = {
  get: async (): Promise<OwnerProfile> => {
    const res = await api.get<OwnerProfile>('/owner/profile');
    return res.data;
  },
  update: async (payload: OwnerProfileUpdatePayload): Promise<OwnerProfile> => {
    const res = await api.put<OwnerProfile>('/owner/profile', payload);
    return res.data;
  },
};

/**
 * Valida CLABE mexicana en el cliente (18 dígitos + check dígito módulo 10 ponderado).
 * Reduce errores de captura antes de pedir reauth. El backend repite la validación.
 */
export function isValidClabe(raw: string): boolean {
  if (!raw) return false;
  const c = raw.replace(/\s/g, '');
  if (c.length !== 18) return false;
  if (!/^\d{18}$/.test(c)) return false;
  const weights = [3, 7, 1, 3, 7, 1, 3, 7, 1, 3, 7, 1, 3, 7, 1, 3, 7];
  let sum = 0;
  for (let i = 0; i < 17; i++) {
    sum += (Number(c[i]) * weights[i]) % 10;
  }
  const expected = (10 - (sum % 10)) % 10;
  return expected === Number(c[17]);
}
