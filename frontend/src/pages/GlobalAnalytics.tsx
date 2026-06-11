import React, { useEffect, useState } from 'react';
import { Building2, TrendingUp, PiggyBank, Users } from 'lucide-react';
import { GlobalMetricsDTO, metricsService } from '../services/metricsService';

export const GlobalAnalytics: React.FC = () => {
    const [metrics, setMetrics] = useState<GlobalMetricsDTO | null>(null);
    const [loading, setLoading] = useState(true);

    useEffect(() => {
        metricsService.getGlobalMetrics()
            .then(setMetrics)
            .catch(console.error)
            .finally(() => setLoading(false));
    }, []);

    if (loading) return <div className="animate-pulse h-32 bg-slate-100 rounded-2xl mb-6"></div>;
    if (!metrics) return null;

    const formatMoney = (amount: number) => {
        return new Intl.NumberFormat('es-MX', {
            style: 'currency',
            currency: 'MXN',
            maximumFractionDigits: 0
        }).format(amount);
    };

    return (
        <div className="grid grid-cols-2 md:grid-cols-4 gap-4 mb-6">
            <div className="bg-white border text-center border-slate-200 rounded-2xl p-5 shadow-sm hover:shadow-md transition-shadow">
                <div className="flex items-center justify-center gap-2 text-indigo-500 mb-2 font-bold text-sm">
                    <Building2 className="w-5 h-5" /> Propiedades
                </div>
                <div className="text-3xl font-black text-slate-800">{metrics.totalProperties}</div>
                <p className="text-xs text-slate-400 mt-1">{metrics.occupiedProperties} ocupadas</p>
            </div>
            
            <div className="bg-white border text-center border-slate-200 rounded-2xl p-5 shadow-sm hover:shadow-md transition-shadow">
                <div className="flex items-center justify-center gap-2 text-blue-500 mb-2 font-bold text-sm">
                    <TrendingUp className="w-5 h-5" /> ROI Esperado (Mes)
                </div>
                <div className="text-2xl font-black text-slate-800">{formatMoney(metrics.expectedMonthlyIncome)}</div>
                <p className="text-xs text-slate-400 mt-1">Suma de rentas ligadas</p>
            </div>
            
            <div className="bg-gradient-to-br from-emerald-500 text-center to-teal-400 rounded-2xl p-5 shadow-lg shadow-emerald-500/20 text-white">
                <div className="flex items-center justify-center gap-2 mb-2 font-bold text-sm text-emerald-50">
                    <PiggyBank className="w-5 h-5" /> Balance Cobrado
                </div>
                <div className="text-2xl font-black">{formatMoney(metrics.collectedMonthlyIncome)}</div>
                <p className="text-xs text-emerald-100 mt-1 rounded-full bg-black/10 inline-block px-2 py-0.5">Dinero en Banco</p>
            </div>
            
            <div className="bg-white border text-center border-slate-200 rounded-2xl p-5 shadow-sm hover:shadow-md transition-shadow">
                <div className="flex items-center justify-center gap-2 text-rose-500 mb-2 font-bold text-sm">
                    <Users className="w-5 h-5" /> Morosidad
                </div>
                <div className="text-3xl font-black text-rose-600">{metrics.delinquentTenants}</div>
                <p className="text-xs text-slate-400 mt-1">Inquilinos en Adeudo</p>
            </div>
        </div>
    );
};
