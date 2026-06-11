import api from './api';

export interface MaintenanceBudget {
    id: string;
    ownerId: string;
    propertyId?: string | null;
    providerUserId?: string | null;
    title: string;
    description?: string | null;
    amount?: number | null;
    currency: string;
    status: 'SUBMITTED' | 'APPROVED' | 'REJECTED';
    fileUrl: string;
    fileName?: string | null;
    fileContentType?: string | null;
    fileSizeBytes?: number | null;
    submittedByUserId: string;
    submittedAt: string;
    decidedAt?: string | null;
    decidedByUserId?: string | null;
    decisionNote?: string | null;
}

const base = '/maintenance/budgets';

export const maintenanceBudgetService = {
    async list(): Promise<MaintenanceBudget[]> {
        const res = await api.get(base);
        return res.data;
    },
    async get(id: string): Promise<MaintenanceBudget> {
        const res = await api.get(`${base}/${id}`);
        return res.data;
    },
    async submit(params: {
        propertyId?: string;
        title: string;
        description?: string;
        amount?: number;
        currency?: string;
        file: File;
    }): Promise<MaintenanceBudget> {
        const form = new FormData();
        if (params.propertyId) form.append('propertyId', params.propertyId);
        form.append('title', params.title);
        if (params.description) form.append('description', params.description);
        if (params.amount !== undefined && params.amount !== null) form.append('amount', String(params.amount));
        if (params.currency) form.append('currency', params.currency);
        form.append('file', params.file);
        const res = await api.post(base, form, { headers: { 'Content-Type': 'multipart/form-data' } });
        return res.data;
    },
    async approve(id: string, password: string, mfaCode?: string, note?: string): Promise<MaintenanceBudget> {
        const res = await api.post(`${base}/${id}/approve`, { password, mfaCode, note });
        return res.data;
    },
    async reject(id: string, password: string, mfaCode?: string, note?: string): Promise<MaintenanceBudget> {
        const res = await api.post(`${base}/${id}/reject`, { password, mfaCode, note });
        return res.data;
    },
    downloadUrl(id: string): string {
        return `${(api.defaults.baseURL || '')}${base}/${id}/file`;
    },
};
