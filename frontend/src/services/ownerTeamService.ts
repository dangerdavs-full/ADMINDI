import api from "./api";

export interface OwnerProviderLink {
  assignmentId: string;
  providerUserId: string;
  name: string;
  /** V50 — identificador de login canónico. */
  username?: string;
  /** V50 — email queda como contacto opcional. */
  email?: string;
  contactPhone?: string;
  providerType: string;
  assignmentSource: string;
  assignmentActive: boolean;
  assignedAt?: string;
  /**
   * V63 — true si el agente completó su onboarding bancario (CLABE + banco +
   * titular). Sin esto el dueño no puede pagarle; los endpoints operativos del
   * agente devuelven 412 BANK_ACCOUNT_REQUIRED hasta que complete el wizard.
   */
  accountActive?: boolean;
  /** V63 — reservado para cuando UserEntity tenga campo de último sign-in. */
  lastSignInAt?: string | null;
}

export interface PlatformProviderRow {
  id: string;
  username?: string;
  email?: string;
  name: string;
  contactPhone?: string;
  providerType: string;
}

export interface CreatePrivateProviderPayload {
  name: string;
  /** V51 — identificador de login canónico (case-sensitive). Obligatorio. */
  username: string;
  /** V51 — email obligatorio (canal oficial de contacto + recuperación). */
  email: string;
  contactEmail?: string;
  countryCode: string;
  rawPhone: string;
  providerType: "MAINTENANCE_PROVIDER" | "REAL_ESTATE_AGENT";
}

export interface CreatedPrivateProvider {
  id: string;
  username?: string;
  email?: string;
  name: string;
  contactEmail?: string;
  contactPhone?: string;
  contactCountryCode?: string;
  providerType: string;
  tempPassword?: string;
  activationSent?: boolean;
  activationChannel?: "EMAIL" | "WHATSAPP" | "BOTH";
}

export interface ResendActivationResponse {
  activationSent: boolean;
  channel: "EMAIL" | "WHATSAPP" | "BOTH";
  expiresAt: string;
}

export const ownerTeamService = {
  getProviderLinks: async (): Promise<OwnerProviderLink[]> => {
    const res = await api.get("/owner/team/provider-links");
    return res.data;
  },

  getPlatformCatalog: async (type?: string): Promise<PlatformProviderRow[]> => {
    const res = await api.get("/owner/team/platform-catalog", { params: type ? { type } : {} });
    return res.data;
  },

  linkPlatform: async (providerUserId: string): Promise<void> => {
    await api.post("/owner/team/link-platform", { providerUserId });
  },

  linkPrivate: async (providerUserId: string): Promise<void> => {
    await api.post("/owner/team/link-private", { providerUserId });
  },

  createPrivate: async (payload: CreatePrivateProviderPayload): Promise<CreatedPrivateProvider> => {
    const res = await api.post("/owner/team/create-private", payload);
    return res.data;
  },

  unlink: async (providerUserId: string): Promise<void> => {
    await api.delete(`/owner/team/unlink/${encodeURIComponent(providerUserId)}`);
  },

  /**
   * Reenvía el link de activación al provider/agente privado o de plataforma
   * vinculado a este owner. Backend valida que el provider esté efectivamente
   * asignado a esta organización (corta IDOR entre owners).
   */
  resendActivation: async (providerUserId: string): Promise<ResendActivationResponse> => {
    const res = await api.post(`/owner/team/${encodeURIComponent(providerUserId)}/resend-activation`);
    return res.data;
  },
};