import api from './api';

export interface OwnerMpStatus {
  connected: boolean;
  mpUserId: string;
  connectedAt: string;
  /** OAuth listo en servidor (Client ID + Secret configurados) */
  oauthReady: boolean;
  oauthAvailable: boolean;
  oauthCredentialsConfigured?: boolean;
  oauthRedirectConfigured?: boolean;
  oauthRedirectUri?: string;
}

export const ownerMercadoPagoService = {
  async getStatus(): Promise<OwnerMpStatus> {
    const res = await api.get('/integrations/mercadopago/owner/status');
    return res.data;
  },

  async getOAuthUrl(): Promise<string> {
    const res = await api.get('/integrations/mercadopago/owner/oauth/authorize-url');
    return res.data.authorizationUrl;
  },

  async disconnect(): Promise<OwnerMpStatus> {
    const res = await api.delete('/integrations/mercadopago/owner/disconnect');
    return res.data;
  },
};
