import api from './api';

export interface NotificationPreferenceDTO {
  eventType: string;
  channel: string;
  enabled: boolean;
}

/**
 * Canales visibles al usuario (Etapa 1):
 *  - IN_APP: inbox interno. OBLIGATORIO, no editable, nunca apagable.
 *  - EMAIL:  correo automático enviado por el backend (no depende de n8n).
 *  - WHATSAPP: mensaje WhatsApp vía adapter interno. El usuario NO ve "n8n".
 *
 * No exponer N8N en este arreglo: el backend rechaza ese literal en /preferences.
 */
export const NOTIFICATION_CHANNEL_OPTIONS = ['IN_APP', 'EMAIL', 'WHATSAPP'] as const;
export type NotificationChannel = (typeof NOTIFICATION_CHANNEL_OPTIONS)[number];

export const MANDATORY_CHANNELS: NotificationChannel[] = ['IN_APP'];

/** Roles canónicos del dominio que pueden ser audiencia de un evento. */
export type NotificationAudience =
  | 'OWNER'
  | 'TENANT'
  | 'PROPERTY_ADMIN'
  | 'ACCOUNTANT'
  | 'REAL_ESTATE_AGENT'
  | 'MAINTENANCE_PROVIDER'
  | 'SUPER_ADMIN';

export interface NotificationCatalogEvent {
  eventType: string;
  label: string;
  group: string;
  /**
   * Roles destinatarios válidos. Si está vacío o ausente, la UI lo trata como
   * "visible para todos" por defensa (no rompe si el backend no lo envía).
   */
  audience?: NotificationAudience[];
}

export interface NotificationCatalogChannel {
  id: NotificationChannel;
  label: string;
  mandatory: boolean;
  editable: boolean;
}

export interface NotificationCatalog {
  events: NotificationCatalogEvent[];
  channels: NotificationCatalogChannel[];
}

/**
 * Fallback local del catálogo. El backend es la fuente de verdad; si
 * `/preferences/catalog` responde, usamos esa lista. Este fallback se mantiene
 * sincronizado con el catálogo del backend (ver NotificationEventCatalog.java).
 */
// V52 — SUPER_ADMIN sale de todas las audiencias operativas. Su dashboard tiene
// su propio panel de preferencias y el archivo trimestral cubre la visión global.
const PORTFOLIO_ADMIN: NotificationAudience[] = ['OWNER', 'PROPERTY_ADMIN', 'ACCOUNTANT'];
const OWNER_OPS: NotificationAudience[] = ['OWNER', 'PROPERTY_ADMIN'];

export const FALLBACK_EVENTS: NotificationCatalogEvent[] = [
  // Bienvenida / cuenta
  { eventType: 'OWNER_WELCOME', label: 'Bienvenida al crear tu cuenta de dueño', group: 'Cuenta', audience: ['OWNER'] },
  { eventType: 'TENANT_WELCOME', label: 'Bienvenida al crear expediente de arrendatario', group: 'Cuenta', audience: ['TENANT'] },
  { eventType: 'OWNER_PROFILE_UPDATED', label: 'Actualización de perfil del dueño (contacto/CLABE)', group: 'Cuenta', audience: ['OWNER'] },
  { eventType: 'OWNER_CONTACT_UPDATED', label: 'Actualización de datos de contacto', group: 'Cuenta', audience: ['OWNER'] },

  // Recordatorios de pago
  { eventType: 'TENANT_PAYMENT_REMINDER_5D', label: 'Recordatorio 5 días antes del pago', group: 'Recordatorios', audience: ['TENANT'] },
  { eventType: 'TENANT_PAYMENT_REMINDER_3D', label: 'Recordatorio 3 días antes del pago', group: 'Recordatorios', audience: ['TENANT'] },
  { eventType: 'TENANT_PAYMENT_REMINDER_2D', label: 'Recordatorio 2 días antes del pago', group: 'Recordatorios', audience: ['TENANT'] },
  { eventType: 'TENANT_PAYMENT_REMINDER_1D', label: 'Recordatorio 1 día antes del pago', group: 'Recordatorios', audience: ['TENANT'] },

  // Inmuebles
  { eventType: 'PROPERTY_CREATED', label: 'Alta de inmueble', group: 'Inmuebles', audience: PORTFOLIO_ADMIN },
  { eventType: 'PROPERTY_UPDATED', label: 'Actualización de inmueble', group: 'Inmuebles', audience: PORTFOLIO_ADMIN },
  { eventType: 'PROPERTY_DELETE_REQUESTED', label: 'Solicitud de eliminación de inmueble', group: 'Inmuebles', audience: OWNER_OPS },
  { eventType: 'PROPERTY_DELETE_APPROVED', label: 'Eliminación de inmueble aprobada', group: 'Inmuebles', audience: OWNER_OPS },
  { eventType: 'PROPERTY_DELETE_REJECTED', label: 'Eliminación de inmueble rechazada', group: 'Inmuebles', audience: OWNER_OPS },
  { eventType: 'PROPERTY_FILE_DELETE_REQUESTED', label: 'Solicitud de eliminación de archivo', group: 'Inmuebles', audience: OWNER_OPS },
  { eventType: 'PROPERTY_FILE_DELETE_APPROVED', label: 'Eliminación de archivo aprobada', group: 'Inmuebles', audience: OWNER_OPS },
  { eventType: 'PROPERTY_FILE_DELETE_REJECTED', label: 'Eliminación de archivo rechazada', group: 'Inmuebles', audience: OWNER_OPS },
  { eventType: 'PROPERTY_FILE_DELETED', label: 'Archivo del inmueble eliminado', group: 'Inmuebles', audience: OWNER_OPS },

  // Inquilinos
  { eventType: 'TENANT_CREATED', label: 'Nuevo expediente de inquilino', group: 'Inquilinos', audience: PORTFOLIO_ADMIN },
  { eventType: 'TENANT_UPDATED', label: 'Actualización de expediente de inquilino', group: 'Inquilinos', audience: PORTFOLIO_ADMIN },
  { eventType: 'TENANT_EXPEDIENTE_ARCHIVED', label: 'Expediente archivado (baja)', group: 'Inquilinos', audience: OWNER_OPS },
  { eventType: 'TENANT_ARCHIVE_REQUESTED', label: 'Solicitud de archivo de expediente', group: 'Inquilinos', audience: OWNER_OPS },
  { eventType: 'TENANT_ARCHIVE_APPROVED', label: 'Archivo de expediente aprobado', group: 'Inquilinos', audience: OWNER_OPS },
  { eventType: 'TENANT_ARCHIVE_REJECTED', label: 'Archivo de expediente rechazado', group: 'Inquilinos', audience: OWNER_OPS },

  // Contratos
  { eventType: 'LEASE_TERMINATE_REQUESTED', label: 'Solicitud de terminación de contrato', group: 'Contratos', audience: ['OWNER', 'PROPERTY_ADMIN', 'TENANT'] },
  { eventType: 'LEASE_TERMINATE_APPROVED', label: 'Terminación de contrato aprobada', group: 'Contratos', audience: ['OWNER', 'PROPERTY_ADMIN', 'TENANT'] },
  { eventType: 'LEASE_TERMINATE_REJECTED', label: 'Terminación de contrato rechazada', group: 'Contratos', audience: ['OWNER', 'PROPERTY_ADMIN', 'TENANT'] },

  // Pagos
  { eventType: 'PAYMENT_AUTO_VALIDATED', label: 'Pago validado automáticamente', group: 'Pagos', audience: ['OWNER', 'PROPERTY_ADMIN', 'ACCOUNTANT', 'TENANT'] },
  { eventType: 'PAYMENT_MANUAL_OVERRIDE', label: 'Pago con override manual', group: 'Pagos', audience: PORTFOLIO_ADMIN },
  { eventType: 'PAYMENT_CEP_REJECTED', label: 'CEP rechazó comprobante', group: 'Pagos', audience: ['OWNER', 'PROPERTY_ADMIN', 'ACCOUNTANT', 'TENANT'] },
  { eventType: 'TRANSFER_PROOF_INCOMPLETE', label: 'Comprobante SPEI incompleto', group: 'Pagos', audience: ['TENANT', 'OWNER', 'PROPERTY_ADMIN'] },
  { eventType: 'TRANSFER_CONFIRMED', label: 'Transferencia SPEI confirmada (para el dueño)', group: 'Pagos', audience: PORTFOLIO_ADMIN },

  // Resúmenes operativos (Bloque B)
  { eventType: 'OWNER_UNPAID_TENANTS_DIGEST', label: 'Resumen diario de inquilinos con pago vencido', group: 'Resúmenes', audience: ['OWNER'] },
  { eventType: 'OWNER_MONTHLY_REPORT', label: 'Reporte mensual del portafolio', group: 'Resúmenes', audience: ['OWNER'] },

  // Mantenimiento
  { eventType: 'MAINTENANCE_TICKET_ASSIGNED', label: 'Mantenimiento asignado a proveedor', group: 'Mantenimiento', audience: ['OWNER', 'PROPERTY_ADMIN', 'MAINTENANCE_PROVIDER'] },
  { eventType: 'MAINTENANCE_PROVIDER_NEEDED', label: 'Mantenimiento sin proveedor', group: 'Mantenimiento', audience: OWNER_OPS },
  { eventType: 'MAINTENANCE_QUOTE_APPROVED', label: 'Cotización de mantenimiento aprobada', group: 'Mantenimiento', audience: ['OWNER', 'PROPERTY_ADMIN', 'MAINTENANCE_PROVIDER'] },
  { eventType: 'MAINTENANCE_QUOTE_REJECTED', label: 'Cotización de mantenimiento rechazada', group: 'Mantenimiento', audience: ['OWNER', 'PROPERTY_ADMIN', 'MAINTENANCE_PROVIDER'] },

  // Comercial
  { eventType: 'VACANCY_AGENT_ASSIGNED', label: 'Vacancia con agente', group: 'Comercial', audience: ['OWNER', 'PROPERTY_ADMIN', 'REAL_ESTATE_AGENT'] },
  { eventType: 'VACANCY_AGENT_NEEDED', label: 'Vacancia sin agente', group: 'Comercial', audience: OWNER_OPS },
  { eventType: 'VACANCY_START_REQUESTED', label: 'Staff pidió poner inmueble en renta — requiere tu autorización', group: 'Comercial', audience: OWNER_OPS },
  { eventType: 'VACANCY_START_APPROVED', label: 'Solicitud de puesta en renta aprobada', group: 'Comercial', audience: OWNER_OPS },
  { eventType: 'VACANCY_START_REJECTED', label: 'Solicitud de puesta en renta rechazada', group: 'Comercial', audience: OWNER_OPS },
  { eventType: 'COMMERCIAL_ACTIVITY_LOGGED', label: 'Actividad comercial registrada', group: 'Comercial', audience: ['OWNER', 'PROPERTY_ADMIN', 'REAL_ESTATE_AGENT'] },
  { eventType: 'COMMISSION_APPROVED', label: 'Comisión comercial aprobada', group: 'Comercial', audience: ['OWNER', 'REAL_ESTATE_AGENT'] },
];

export const notificationPreferenceService = {
  catalog: async (): Promise<NotificationCatalog> => {
    try {
      const res = await api.get<NotificationCatalog>('/notifications/preferences/catalog');
      return res.data;
    } catch {
      return {
        events: FALLBACK_EVENTS,
        channels: [
          { id: 'IN_APP', label: 'En app', mandatory: true, editable: false },
          { id: 'EMAIL', label: 'Email', mandatory: false, editable: true },
          { id: 'WHATSAPP', label: 'WhatsApp', mandatory: false, editable: true },
        ],
      };
    }
  },

  list: async (): Promise<NotificationPreferenceDTO[]> => {
    const res = await api.get<NotificationPreferenceDTO[]>('/notifications/preferences');
    return res.data;
  },

  /**
   * El backend fuerza IN_APP=enabled=true y rechaza N8N. El filtro aquí es
   * defensivo: nunca mandar IN_APP con enabled=false ni N8N al servidor.
   */
  upsert: async (preferences: NotificationPreferenceDTO[]): Promise<NotificationPreferenceDTO[]> => {
    const clean = preferences
      .filter((p) => p.channel !== 'N8N')
      .map((p) => ({
        ...p,
        enabled: p.channel === 'IN_APP' ? true : p.enabled,
      }));
    const res = await api.put<NotificationPreferenceDTO[]>('/notifications/preferences', { preferences: clean });
    return res.data;
  },
};
