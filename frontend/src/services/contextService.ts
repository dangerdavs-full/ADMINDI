import api from './api';

export interface ContextOption {
  id: string;
  name: string;
}

export const contextService = {
  /** Fetch available contexts for the authenticated user */
  async getContexts(): Promise<ContextOption[]> {
    const res = await api.get('/auth/contexts');
    return res.data.contexts || [];
  },
};
