import axios from 'axios';

const api = axios.create({
  baseURL: import.meta.env.VITE_API_URL || 'http://localhost:8080/api',
  // V58.1 — Timeout 120s: la consulta a Banxico CEP tarda 15-20s en condiciones
  // normales; el OCR de Claude ~3-5s. Dejamos margen amplio antes de dar por
  // colgado el request. Sin timeout, un backend congelado deja spinners hasta
  // el error de red del navegador (~minutos).
  timeout: 120_000,
});

// Request interceptor: inject access token
api.interceptors.request.use((config) => {
  const token = localStorage.getItem('token');
  if (token && config.headers) {
    config.headers.Authorization = `Bearer ${token}`;
  }
  return config;
});

// Response interceptor: auto-refresh on 401
let isRefreshing = false;
let failedQueue: Array<{ resolve: (token: string) => void; reject: (err: any) => void }> = [];

const processQueue = (error: any, token: string | null = null) => {
  failedQueue.forEach((prom) => {
    if (token) {
      prom.resolve(token);
    } else {
      prom.reject(error);
    }
  });
  failedQueue = [];
};

api.interceptors.response.use(
  (response) => response,
  async (error) => {
    const originalRequest = error.config;

    // Only attempt refresh for 401 errors, not on auth endpoints themselves
    if (error.response?.status === 401 && !originalRequest._retry && !originalRequest.url?.includes('/auth/')) {
      if (isRefreshing) {
        return new Promise((resolve, reject) => {
          failedQueue.push({ resolve, reject });
        }).then((token) => {
          originalRequest.headers.Authorization = `Bearer ${token}`;
          return api(originalRequest);
        });
      }

      originalRequest._retry = true;
      isRefreshing = true;

      const refreshToken = localStorage.getItem('refreshToken');
      if (!refreshToken) {
        // No refresh token — force logout
        localStorage.clear();
        window.location.href = '/login';
        return Promise.reject(error);
      }

      try {
        const contextId = localStorage.getItem('contextId');
        const response = await axios.post(`${api.defaults.baseURL}/auth/refresh`, {
          refreshToken: refreshToken,
          contextId: contextId || undefined,
        });

        if (response.data?.requiresOrgSelection) {
          localStorage.setItem('token', response.data.token);
          if (response.data.refreshToken) {
            localStorage.setItem('refreshToken', response.data.refreshToken);
          }
          localStorage.removeItem('contextId');
          if (response.data.organizations) {
            localStorage.setItem('pendingOrganizations', JSON.stringify(response.data.organizations));
          }
          localStorage.setItem('pendingOrgSelection', '1');
          window.location.href = '/login?orgPicker=1';
          processQueue(new Error('ORG_SELECTION'), null);
          return Promise.reject(new Error('ORG_SELECTION'));
        }

        const { token: newAccessToken, refreshToken: newRefreshToken } = response.data;
        localStorage.setItem('token', newAccessToken);
        if (newRefreshToken) {
          localStorage.setItem('refreshToken', newRefreshToken);
        }

        processQueue(null, newAccessToken);
        originalRequest.headers.Authorization = `Bearer ${newAccessToken}`;
        return api(originalRequest);
      } catch (refreshError) {
        processQueue(refreshError, null);
        localStorage.clear();
        window.location.href = '/login';
        return Promise.reject(refreshError);
      } finally {
        isRefreshing = false;
      }
    }

    return Promise.reject(error);
  }
);

export default api;
