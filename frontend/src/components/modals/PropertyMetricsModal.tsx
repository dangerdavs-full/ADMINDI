import React, { useEffect, useState } from 'react';
import { X, TrendingUp, AlertCircle, PieChart, DollarSign, Activity, FileText } from 'lucide-react';
import { PropertyMetricsDTO, InvoiceHistoryDTO, metricsService } from '../../services/metricsService';
import { AreaChart, Area, XAxis, YAxis, CartesianGrid, Tooltip as RechartsTooltip, ResponsiveContainer } from 'recharts';

interface PropertyMetricsModalProps {
  propertyId: string;
  propertyName: string;
  isOpen: boolean;
  onClose: () => void;
}

type TabType = 'RESUMEN' | 'GRAFICA' | 'NOTAS';

export const PropertyMetricsModal: React.FC<PropertyMetricsModalProps> = ({
  propertyId, propertyName, isOpen, onClose
}) => {
  const [activeTab, setActiveTab] = useState<TabType>('RESUMEN');
  const [metrics, setMetrics] = useState<PropertyMetricsDTO | null>(null);
  const [history, setHistory] = useState<InvoiceHistoryDTO[]>([]);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    if (isOpen) {
      setLoading(true);
      Promise.all([
        metricsService.getPropertyMetrics(propertyId),
        metricsService.listPropertyInvoiceFinancialHistory(propertyId)
      ])
      .then(([metricsData, historyData]) => {
        setMetrics(metricsData);
        setHistory(historyData);
      })
      .finally(() => setLoading(false));
    } else {
        // Reset state on close
        setActiveTab('RESUMEN');
    }
  }, [propertyId, isOpen]);

  if (!isOpen) return null;

  const formatMoney = (amount: number) => {
    return new Intl.NumberFormat('es-MX', { style: 'currency', currency: 'MXN' }).format(amount);
  };

  const paymentRate = metrics?.totalInvoices && metrics.totalInvoices > 0
    ? Math.round((metrics.paidInvoices / metrics.totalInvoices) * 100) : 0;

  // Adaptador de Chart Data
  const chartData = history.map(item => ({
      name: item.monthYear,
      Ingresos: item.status === 'PAID' ? item.amountCollected : 0,
      tenant: item.tenantName,
      status: item.status
  }));

  // Custom Recharts Tooltip
  const CustomTooltip = ({ active, payload, label }: any) => {
    if (active && payload && payload.length) {
      return (
        <div className="bg-slate-900 border border-slate-700 text-white p-4 rounded-xl shadow-2xl">
          <p className="font-bold mb-2 border-b border-slate-700 pb-2">{label}</p>
          <p className="text-emerald-400 font-bold text-lg mb-1">{formatMoney(payload[0].value)}</p>
          <p className="text-slate-400 text-xs">Arrendatario: {payload[0].payload.tenant}</p>
          <p className="text-slate-400 text-xs text-transform: uppercase mt-1">Status: {payload[0].payload.status}</p>
        </div>
      );
    }
    return null;
  };

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center p-4 bg-slate-900/60 backdrop-blur-sm">
      <div className="bg-white rounded-3xl w-full max-w-4xl shadow-2xl overflow-hidden flex flex-col h-[85vh]">
        
        {/* Header Premium */}
        <div className="bg-slate-900 px-8 flex-shrink-0 pt-6 pb-0 flex flex-col relative overflow-hidden">
          <div className="absolute -right-10 -top-10 text-slate-800/50">
             <PieChart className="w-40 h-40" />
          </div>
          
          <div className="flex justify-between items-start relative z-10 w-full mb-6">
            <div>
                <h2 className="text-2xl font-bold text-white flex items-center gap-3">
                Metricas e historial financiero
                </h2>
                <p className="text-slate-400 mt-1">{propertyName}</p>
                <p className="text-slate-500 text-xs mt-2 max-w-xl">
                  Solo recibos y cobranza por inmueble. Mantenimiento, vacancia, convenios y demas operacion: pestana de
                  historial operativo en la ficha del inmueble.
                </p>
            </div>
            <button onClick={onClose} className="text-slate-400 hover:text-white transition-colors bg-slate-800/50 p-2 rounded-full">
                <X className="w-6 h-6" />
            </button>
          </div>

          {/* TABS Navigation */}
          <div className="flex gap-6 border-b border-slate-800 relative z-10">
              <button 
                onClick={() => setActiveTab('RESUMEN')}
                className={`pb-3 text-sm font-bold border-b-2 transition-all flex items-center gap-2 ${activeTab === 'RESUMEN' ? 'border-brand-500 text-brand-400' : 'border-transparent text-slate-400 hover:text-slate-300'}`}>
                  <PieChart className="w-4 h-4" /> Global LTV
              </button>
              <button 
                 onClick={() => setActiveTab('GRAFICA')}
                className={`pb-3 text-sm font-bold border-b-2 transition-all flex items-center gap-2 ${activeTab === 'GRAFICA' ? 'border-brand-500 text-brand-400' : 'border-transparent text-slate-400 hover:text-slate-300'}`}>
                  <Activity className="w-4 h-4" /> Recaudacion (recibos)
              </button>
              <button 
                 onClick={() => setActiveTab('NOTAS')}
                className={`pb-3 text-sm font-bold border-b-2 transition-all flex items-center gap-2 ${activeTab === 'NOTAS' ? 'border-brand-500 text-brand-400' : 'border-transparent text-slate-400 hover:text-slate-300'}`}>
                  <FileText className="w-4 h-4" /> Recibos y Acuerdos
              </button>
          </div>
        </div>

        {/* Content Body */}
        <div className="p-8 flex-1 overflow-y-auto bg-slate-50">
          {loading ? (
            <div className="flex justify-center items-center h-full">
              <div className="animate-spin rounded-full h-12 w-12 border-b-2 border-indigo-500"></div>
            </div>
          ) : (
            <div className="h-full">
                
              {/* TAB 1: RESUMEN (Lo que teníamos originalmente) */}
              {activeTab === 'RESUMEN' && metrics && (
                  <div className="flex flex-col gap-6 animate-in fade-in slide-in-from-bottom-2 duration-300">
                    <div className="grid grid-cols-2 gap-4">
                        <div className="bg-white border border-slate-200 rounded-2xl p-5 shadow-sm">
                        <div className="flex items-center gap-2 text-slate-500 mb-2 font-medium">
                            <AlertCircle className="w-4 h-4 text-indigo-500"/> Estado Actual
                        </div>
                        <div className="text-lg font-bold text-slate-800">
                            {metrics.status === 'OCCUPIED' ? 'Ocupado' : 'Disponible'}
                        </div>
                        <div className="text-sm text-slate-500 mt-1">
                            Inquilino: <span className="font-semibold text-slate-700">{metrics.currentTenantName}</span>
                        </div>
                        </div>

                        <div className="bg-gradient-to-br from-green-50 to-emerald-50 border border-emerald-100 rounded-2xl p-5 shadow-sm">
                        <div className="flex items-center gap-2 text-emerald-600 mb-2 font-medium">
                            <TrendingUp className="w-4 h-4"/> Renta Mensual Actual
                        </div>
                        <div className="text-2xl font-black text-emerald-700">
                            {formatMoney(metrics.monthlyRent)}
                        </div>
                        </div>
                    </div>

                    <div className="bg-white border text-center border-slate-200 rounded-2xl p-6 shadow-sm mt-2">
                        <h3 className="text-slate-800 font-bold flex items-center justify-center gap-2 mb-6">
                            <DollarSign className="w-5 h-5 text-indigo-500" /> Rendimiento Histórico (LTV)
                        </h3>
                        
                        <div className="text-4xl font-black text-slate-800 mb-2">
                            {formatMoney(metrics.lifetimeCollected)}
                        </div>
                        <p className="text-sm text-slate-400 font-medium mb-8">Ganancia total recaudada de todas las rentas de este Inmueble</p>

                        <div className="flex flex-col items-start w-full relative">
                            <div className="flex justify-between w-full mb-2 text-sm font-bold text-slate-600">
                            <span>Tasa de Cobro Exitoso</span>
                            <span className={paymentRate >= 80 ? 'text-green-500' : 'text-orange-500'}>{paymentRate}%</span>
                            </div>
                            <div className="w-full bg-slate-100 rounded-full h-4 mb-2 overflow-hidden flex shadow-inner">
                                <div className="bg-emerald-500 h-full" style={{ width: `${metrics.totalInvoices === 0 ? 0 : (metrics.paidInvoices / metrics.totalInvoices) * 100}%` }}></div>
                                <div className="bg-red-500 h-full" style={{ width: `${metrics.totalInvoices === 0 ? 0 : (metrics.lateInvoices / metrics.totalInvoices) * 100}%` }}></div>
                            </div>
                            <div className="flex justify-between w-full text-xs text-slate-400 font-medium px-1">
                                <div className="flex items-center gap-1.5"><div className="w-2.5 h-2.5 rounded-full bg-emerald-500"></div> {metrics.paidInvoices} meses pagados a tiempo</div>
                                <div className="flex items-center gap-1.5"><div className="w-2.5 h-2.5 rounded-full bg-red-500"></div> {metrics.lateInvoices} recibos morosos</div>
                            </div>
                        </div>
                    </div>
                  </div>
              )}

              {/* TAB 2: GRAFICA TEMPORAL */}
              {activeTab === 'GRAFICA' && (
                  <div className="h-full flex flex-col animate-in fade-in slide-in-from-bottom-2 duration-300">
                      <div className="mb-4">
                          <h3 className="font-bold text-slate-800 text-lg">Evolución de Recaudación</h3>
                          <p className="text-sm text-slate-500">Ingresos generados por meses. Puntos en 0 representan morosidad.</p>
                      </div>
                      <div className="bg-white border border-slate-200 rounded-2xl p-6 shadow-sm flex-1 min-h-[300px]">
                        {history.length > 0 ? (
                            <ResponsiveContainer width="100%" height="100%">
                                <AreaChart data={chartData} margin={{ top: 10, right: 10, left: 0, bottom: 0 }}>
                                    <defs>
                                        <linearGradient id="colorIngresos" x1="0" y1="0" x2="0" y2="1">
                                            <stop offset="5%" stopColor="#10b981" stopOpacity={0.4}/>
                                            <stop offset="95%" stopColor="#10b981" stopOpacity={0}/>
                                        </linearGradient>
                                    </defs>
                                    <XAxis dataKey="name" tick={{fill: '#94a3b8', fontSize: 12}} tickLine={false} axisLine={false} dy={10} />
                                    <YAxis tickFormatter={(value) => `$${value/1000}k`} tick={{fill: '#94a3b8', fontSize: 12}} tickLine={false} axisLine={false} dx={-10}/>
                                    <CartesianGrid strokeDasharray="3 3" vertical={false} stroke="#e2e8f0" />
                                    <RechartsTooltip content={<CustomTooltip />} />
                                    <Area type="monotone" dataKey="Ingresos" stroke="#10b981" strokeWidth={3} fillOpacity={1} fill="url(#colorIngresos)" activeDot={{r: 6, fill: '#059669', stroke: '#fff', strokeWidth: 2}} />
                                </AreaChart>
                            </ResponsiveContainer>
                        ) : (
                            <div className="h-full flex flex-col items-center justify-center text-slate-400">
                                <Activity className="w-12 h-12 mb-3 opacity-20" />
                                <p>No hay historial financiero suficiente para graficar.</p>
                            </div>
                        )}
                      </div>
                  </div>
              )}

              {/* TAB 3: NOTAS Y RECIBOS HISTORICOS */}
              {activeTab === 'NOTAS' && (
                  <div className="h-full flex flex-col animate-in fade-in slide-in-from-bottom-2 duration-300">
                      <div className="mb-4">
                          <h3 className="font-bold text-slate-800 text-lg">Acuerdos y recibos (financiero)</h3>
                          <p className="text-sm text-slate-500">
                            Lista de facturas y notas de cobro por temporada. No sustituye el historial operativo del
                            inmueble.
                          </p>
                      </div>
                      
                      <div className="bg-white border border-slate-200 rounded-2xl shadow-sm overflow-hidden flex-1 flex flex-col">
                          <div className="overflow-x-auto flex-1">
                              <table className="w-full text-left text-sm text-slate-600 whitespace-nowrap">
                                  <thead className="bg-slate-50 text-slate-800 border-b border-slate-200 uppercase text-xs tracking-wider font-bold">
                                      <tr>
                                          <th className="px-6 py-4">Mes Facturado</th>
                                          <th className="px-6 py-4">Arrendatario</th>
                                          <th className="px-6 py-4">Monto</th>
                                          <th className="px-6 py-4">Referencia</th>
                                          <th className="px-6 py-4 w-1/3">Nota Contable</th>
                                      </tr>
                                  </thead>
                                  <tbody className="divide-y divide-slate-100">
                                      {history.map((item, idx) => (
                                          <tr key={idx} className="hover:bg-slate-50/50 transition-colors">
                                              <td className="px-6 py-4 font-semibold text-slate-800">
                                                  {item.monthYear}
                                                  <div className="text-[10px] text-slate-400 font-normal">
                                                     {item.status === 'PAID' ? <span className="text-emerald-500">Pagado</span> : <span className="text-rose-500">Moroso</span>}
                                                  </div>
                                              </td>
                                              <td className="px-6 py-4 font-medium">{item.tenantName}</td>
                                              <td className="px-6 py-4">
                                                  <span className={item.status === 'PAID' ? 'text-emerald-600 font-bold' : 'text-slate-400'}>
                                                    {formatMoney(item.amountCollected)}
                                                  </span>
                                              </td>
                                              <td className="px-6 py-4">
                                                  {item.paymentReference ? (
                                                      <span className="font-mono text-xs bg-slate-100 px-2 py-1 rounded text-slate-600 border border-slate-200">
                                                          {item.paymentReference}
                                                      </span>
                                                  ) : <span className="text-slate-300">-</span>}
                                              </td>
                                              <td className="px-6 py-4 whitespace-normal min-w-[200px]">
                                                  {item.paymentNotes ? (
                                                      <p className="text-xs text-slate-600 leading-snug italic border-l-2 border-indigo-300 pl-2">
                                                          "{item.paymentNotes}"
                                                      </p>
                                                  ) : <span className="text-slate-300">-</span>}
                                              </td>
                                          </tr>
                                      ))}
                                      {history.length === 0 && (
                                          <tr><td colSpan={5} className="px-6 py-8 text-center text-slate-400">Sin historial de pagos en este Inmueble.</td></tr>
                                      )}
                                  </tbody>
                              </table>
                          </div>
                      </div>
                  </div>
              )}

            </div>
          )}
        </div>
      </div>
    </div>
  );
};
