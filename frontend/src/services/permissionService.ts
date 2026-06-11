import api from './api';

export interface PermissionTemplate {
  id: string;
  name: string;
  description?: string;
  permissions: string[];
  isSystem: boolean;
}

export interface PermissionGrant {
  id: string;
  userId: string;
  ownerId: string;
  templateId: string;
  grantedBy: string;
  grantedAt: string;
}

/**
 * Normaliza el campo `permissions` de un template.
 *
 * Motivación: versiones antiguas del backend (antes del DTO) serializaban
 * la columna JSONB como un string que contenía JSON (ej. `"[\"a\",\"b\"]"`)
 * porque la entidad JPA la modelaba como `String`. Aunque el backend actual
 * ya devuelve un array real, un cliente con respuesta cacheada o en medio
 * de un despliegue podría seguir recibiendo la forma vieja y crashear al
 * hacer `.slice().map()` sobre un string.
 *
 * Esta función acepta array, string JSON o cualquier basura y devuelve
 * siempre `string[]`. Nunca lanza.
 */
function normalizePermissions(raw: unknown): string[] {
  if (Array.isArray(raw)) {
    return raw.filter((p): p is string => typeof p === 'string');
  }
  if (typeof raw === 'string' && raw.trim().length > 0) {
    try {
      const parsed = JSON.parse(raw);
      if (Array.isArray(parsed)) {
        return parsed.filter((p): p is string => typeof p === 'string');
      }
    } catch {
      // CSV fallback: "a,b,c" → ["a","b","c"]
      return raw.split(',').map(s => s.trim()).filter(Boolean);
    }
  }
  return [];
}

function normalizeTemplate(raw: any): PermissionTemplate {
  return {
    id: String(raw?.id ?? ''),
    name: String(raw?.name ?? ''),
    description: typeof raw?.description === 'string' ? raw.description : undefined,
    permissions: normalizePermissions(raw?.permissions),
    isSystem: Boolean(raw?.isSystem),
  };
}

export const permissionService = {
  // Templates
  async listTemplates(): Promise<PermissionTemplate[]> {
    const res = await api.get('/permissions/templates');
    const list = Array.isArray(res.data) ? res.data : [];
    return list.map(normalizeTemplate);
  },

  async getTemplate(id: string): Promise<PermissionTemplate> {
    const res = await api.get(`/permissions/templates/${id}`);
    return normalizeTemplate(res.data);
  },

  // Grants
  async getGrants(ownerId: string): Promise<PermissionGrant[]> {
    const res = await api.get(`/permissions/grants?ownerId=${ownerId}`);
    return res.data;
  },

  async grantPermission(userId: string, ownerId: string, templateId: string, grantedBy: string): Promise<PermissionGrant> {
    const res = await api.post('/permissions/grants', { userId, ownerId, templateId, grantedBy });
    return res.data;
  },

  async revokeGrant(id: string): Promise<void> {
    await api.delete(`/permissions/grants/${id}`);
  },
};
