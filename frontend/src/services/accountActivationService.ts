import api from "./api";

export interface ActivationInfo {
  usable: boolean;
  userName: string | null;
  userEmail: string | null; // ya viene enmascarado por el backend (a***@dom.com)
  expiresAt: string | null;
}

export interface ActivateResponse {
  ok: boolean;
  message?: string;
  error?: string;
  at?: string;
}

/**
 * Endpoints públicos bajo /api/auth/** (permitAll). Se usan desde la pantalla
 * /activate?token=xxx que abre el nuevo agente / staff / provider al recibir
 * su email o WhatsApp. No requieren autenticación previa — la autorización
 * sale del propio token one-shot (SHA-256 + TTL + consumo único).
 */
export const accountActivationService = {
  inspect: async (token: string): Promise<ActivationInfo> => {
    const res = await api.get("/auth/activation/info", { params: { token } });
    return res.data;
  },

  activate: async (token: string, newPassword: string): Promise<ActivateResponse> => {
    const res = await api.post("/auth/activate", { token, newPassword });
    return res.data;
  },
};
