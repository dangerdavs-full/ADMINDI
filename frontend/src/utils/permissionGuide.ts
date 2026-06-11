/**
 * Guías en lenguaje humano para plantillas de permisos.
 *
 * El objetivo es que un dueño no técnico pueda decidir qué nivel dar a un
 * colaborador sin tener que leer códigos como `QUOTE_APPROVE` o
 * `properties:write`. Se mapea por id de plantilla (estable) con fallback por
 * nombre y, como última red, por la propia `description` que trae el backend.
 *
 * Si el cliente crea una plantilla custom sin entrada en este mapa, el modal
 * mostrará una guía genérica construida a partir de los permisos traducidos a
 * texto humano vía {@link humanizePermission}.
 */

export interface TemplateGuide {
  /** Resumen de una línea, estilo titular. */
  headline: string;
  /** Cosas concretas que este nivel habilita. */
  canDo: string[];
  /** Cosas concretas que este nivel NO habilita (opcional, ayuda a decidir). */
  cannotDo?: string[];
  /** Tono visual: define color/acento en el modal. */
  tone: 'danger' | 'warning' | 'info' | 'success';
}

/**
 * Mapa por id estable de plantilla del sistema.
 *
 * Los ids están definidos en las migraciones V9, V24, V27 y V28. Si el
 * backend agrega más plantillas del sistema, añadir su guía aquí.
 */
const GUIDE_BY_ID: Record<string, TemplateGuide> = {
  'tpl-full-access': {
    tone: 'danger',
    headline: 'Como un segundo dueño. Control completo de todo.',
    canDo: [
      'Ver, crear, editar y eliminar inmuebles y unidades',
      'Dar de alta, editar, archivar y eliminar inquilinos',
      'Gestionar contratos y rutear unidades vacantes',
      'Registrar, editar y pagar facturas y gastos',
      'Aprobar o rechazar cotizaciones de mantenimiento',
      'Administrar al equipo (altas, bajas, permisos)',
      'Ver y exportar todos los reportes',
    ],
    cannotDo: [
      'Eliminar tu propia cuenta de dueño',
      'Cambiar la configuración de seguridad de tu cuenta',
    ],
  },
  'tpl-read-only': {
    tone: 'info',
    headline: 'Solo consulta. No puede modificar absolutamente nada.',
    canDo: [
      'Ver inmuebles, unidades, inquilinos y contratos',
      'Consultar facturas y reportes',
      'Ver tickets de mantenimiento',
    ],
    cannotDo: [
      'Crear, editar o eliminar cualquier registro',
      'Aprobar cotizaciones o pagar gastos',
      'Gestionar al equipo',
    ],
  },
  'tpl-accountant': {
    tone: 'info',
    headline: 'Enfocado en finanzas. Registra facturas y consulta reportes.',
    canDo: [
      'Ver y registrar facturas',
      'Consultar reportes financieros',
      'Ver inquilinos y contratos (solo como contexto)',
      'Ver tickets de mantenimiento',
    ],
    cannotDo: [
      'Editar inmuebles o unidades',
      'Modificar datos de inquilinos o contratos',
      'Aprobar cotizaciones o pagar gastos',
      'Gestionar al equipo',
    ],
  },
  // Nota: tpl-property-admin-operational fue eliminado por V40 y consolidado
  // dentro de tpl-full-access. Si aparece algún grant huérfano (no debería),
  // el modal caerá al fallback genérico basado en humanizePermission().
};

/** Heurística por nombre cuando el id no coincide (plantillas renombradas). */
function guessByName(name?: string | null): TemplateGuide | null {
  if (!name) return null;
  const n = name.toLowerCase();
  if (n.includes('total') || n.includes('full')) return GUIDE_BY_ID['tpl-full-access'];
  if (n.includes('lectura') || n.includes('read')) return GUIDE_BY_ID['tpl-read-only'];
  if (n.includes('contador') || n.includes('account')) return GUIDE_BY_ID['tpl-accountant'];
  // "property admin" y "operacional" se absorbieron en tpl-full-access (ver V40).
  // Un grant legacy con ese nombre debe verse como Acceso Total.
  if (n.includes('property admin') || n.includes('operaci')) return GUIDE_BY_ID['tpl-full-access'];
  return null;
}

/**
 * Resuelve la guía para una plantilla. Si no hay match conocido, construye
 * una guía genérica a partir de los permisos traducidos. Nunca devuelve null.
 */
export function resolveTemplateGuide(template: {
  id?: string;
  name?: string;
  description?: string;
  permissions?: string[];
}): TemplateGuide {
  const byId = template.id ? GUIDE_BY_ID[template.id] : undefined;
  if (byId) return byId;

  const byName = guessByName(template.name);
  if (byName) return byName;

  const perms = Array.isArray(template.permissions) ? template.permissions : [];
  const canDo = perms.map(humanizePermission);

  return {
    tone: 'info',
    headline: template.description?.trim() || 'Plantilla personalizada',
    canDo: canDo.length > 0
      ? canDo
      : ['Sin permisos específicos. El usuario tendrá acceso base mínimo.'],
  };
}

/**
 * Traduce un código de permiso técnico a una frase humana.
 * Si el código es desconocido, devuelve el propio código (no rompe nada).
 */
const PERMISSION_LABELS: Record<string, string> = {
  // CRUD clásicos
  'properties:read': 'Ver inmuebles',
  'properties:write': 'Editar inmuebles',
  'properties:delete': 'Eliminar inmuebles',
  'units:read': 'Ver unidades',
  'units:write': 'Editar unidades',
  'units:delete': 'Eliminar unidades',
  'tenants:read': 'Ver inquilinos',
  'tenants:write': 'Editar inquilinos',
  'leases:read': 'Ver contratos',
  'leases:write': 'Editar contratos',
  'invoices:read': 'Ver facturas',
  'invoices:write': 'Registrar y editar facturas',
  'staff:read': 'Ver equipo',
  'staff:write': 'Gestionar equipo',
  'reports:read': 'Ver reportes',
  'maintenance:tickets:read': 'Ver tickets de mantenimiento',

  // Acciones sensibles
  QUOTE_APPROVE: 'Aprobar cotizaciones',
  QUOTE_REJECT: 'Rechazar cotizaciones',
  EXPENSE_PAY: 'Pagar gastos',
  EXPENSE_SETTLEMENT_CONFIRM: 'Conciliar pagos de gastos',
  VACANCY_ROUTE: 'Rutear unidades vacantes',
  TEAM_MANAGE: 'Administrar al equipo',
  PROPERTY_ARCHIVE_TENANT: 'Archivar inquilinos',
  REPORT_EXPORT: 'Exportar reportes',

  // Scope inquilino explícito
  TENANT_VIEW: 'Ver inquilinos',
  TENANT_CREATE: 'Dar de alta inquilinos',
  TENANT_UPDATE: 'Editar inquilinos',
  TENANT_DELETE: 'Eliminar inquilinos',
  PROPERTY_VIEW: 'Ver inmuebles',
};

export function humanizePermission(code: string): string {
  return PERMISSION_LABELS[code] || code;
}

/** Clases Tailwind según el tono de la guía. */
export function toneClasses(tone: TemplateGuide['tone']): {
  card: string;
  badge: string;
  accent: string;
} {
  switch (tone) {
    case 'danger':
      return {
        card: 'bg-rose-50 border-rose-200',
        badge: 'bg-rose-100 text-rose-700',
        accent: 'text-rose-700',
      };
    case 'warning':
      return {
        card: 'bg-amber-50 border-amber-200',
        badge: 'bg-amber-100 text-amber-700',
        accent: 'text-amber-700',
      };
    case 'success':
      return {
        card: 'bg-emerald-50 border-emerald-200',
        badge: 'bg-emerald-100 text-emerald-700',
        accent: 'text-emerald-700',
      };
    case 'info':
    default:
      return {
        card: 'bg-indigo-50 border-indigo-200',
        badge: 'bg-indigo-100 text-indigo-700',
        accent: 'text-indigo-700',
      };
  }
}
