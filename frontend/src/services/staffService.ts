import api from './api';
import { Role } from '../context/AuthContext';

export interface StaffDTO {
  id?: string;
  ownerId?: string;
  name: string;
  /** V51 — identificador de login canónico (case-sensitive). Obligatorio en alta nueva. */
  username?: string;
  /** V51 — email de contacto/login. Obligatorio al crear nuevo staff (también se
   *  usa como canal oficial de notificaciones y recuperación). El form envía el
   *  mismo valor a `loginEmail` y `contactEmail`. */
  loginEmail?: string;
  /** Email de contacto efectivo. Si no se envía, backend usa loginEmail. */
  contactEmail?: string;
  contactPhone: string;          // With country code
  contactCountryCode?: string;   // e.g. "+52"
  role: Role;
  /** Legado: ya NO se llena al crear — reemplazado por link de activación. */
  tempPassword?: string;
  /** true si el backend ya emitió y despachó un link de activación al user. */
  activationSent?: boolean;
  /** Canal por el que salió el link: EMAIL | WHATSAPP | BOTH */
  activationChannel?: "EMAIL" | "WHATSAPP" | "BOTH";
  permissionTemplateId?: string; // Template to assign on create/edit
  currentTemplateName?: string;  // Read-only: currently assigned template
  reuseExisting?: boolean;       // True if account was reused
}

export interface ResendActivationResponse {
  activationSent: boolean;
  channel: "EMAIL" | "WHATSAPP" | "BOTH";
  expiresAt: string;
}

export const staffService = {
  createStaff: async (data: StaffDTO): Promise<StaffDTO> => {
    const res = await api.post('/staff', data);
    return res.data;
  },

  getMyStaff: async (): Promise<StaffDTO[]> => {
    const res = await api.get('/staff');
    return res.data;
  },

  updateStaff: async (id: string, data: StaffDTO): Promise<StaffDTO> => {
    const res = await api.put(`/staff/${id}`, data);
    return res.data;
  },

  deleteStaff: async (id: string): Promise<void> => {
    await api.delete(`/staff/${id}`);
  },

  /**
   * Reenvía el link de activación al staff. Útil si el link previo expiró o si
   * el user no lo recibió. Cualquier token anterior del mismo user se revoca.
   */
  resendActivation: async (id: string): Promise<ResendActivationResponse> => {
    const res = await api.post(`/staff/${id}/resend-activation`);
    return res.data;
  },
};
