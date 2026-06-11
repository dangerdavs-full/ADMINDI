import { useState } from 'react';
import { ArrowRight, CheckCircle2, Wrench, Building, Landmark, AlertTriangle } from 'lucide-react';
import api from '../services/api';

export const OwnerOnboardingWizard = ({ onComplete }: { onComplete: () => void }) => {
    const [step, setStep] = useState(1);
    const [usePlatformMaintenance, setUsePlatformMaintenance] = useState<boolean>(true);
    const [usePlatformAgents, setUsePlatformAgents] = useState<boolean>(true);

    return (
        <div className="min-h-screen bg-slate-50 flex items-center justify-center p-4">
            <div className="bg-white rounded-2xl shadow-xl border border-slate-100 p-8 max-w-2xl w-full">
                <h1 className="text-2xl font-bold text-slate-900 mb-2">Bienvenido a ADMINDI</h1>
                <p className="text-slate-500 mb-8">Vamos a configurar tu entorno administrativo en 3 sencillos pasos.</p>

                {step === 1 && (
                    <div className="space-y-6 animate-in fade-in slide-in-from-right-4">
                        <div className="p-6 bg-blue-50 text-blue-800 rounded-xl flex items-start gap-4 border border-blue-100">
                            <Wrench className="w-6 h-6 mt-1 flex-shrink-0" />
                            <div>
                                <h3 className="font-bold text-lg mb-1">Paso 1: Mantenimiento</h3>
                                <p className="text-sm opacity-90">Quieres usar nuestros servicios de mantenimiento de plataforma o prefieres gestionar tus propios contratistas?</p>
                            </div>
                        </div>
                        <div className="grid grid-cols-2 gap-4">
                            <button onClick={() => { setUsePlatformMaintenance(true); setStep(2); }} className="p-4 border rounded-xl hover:border-brand-500 hover:bg-brand-50 font-bold transition">Usar Plataforma</button>
                            <button onClick={() => { setUsePlatformMaintenance(false); setStep(2); }} className="p-4 border rounded-xl hover:bg-slate-50 font-medium transition">Mis propios contactos</button>
                        </div>
                    </div>
                )}

                {step === 2 && (
                    <div className="space-y-6 animate-in fade-in slide-in-from-right-4">
                        <div className="p-6 bg-purple-50 text-purple-800 rounded-xl flex items-start gap-4 border border-purple-100">
                            <Building className="w-6 h-6 mt-1 flex-shrink-0" />
                            <div>
                                <h3 className="font-bold text-lg mb-1">Paso 2: Comercializacion</h3>
                                <p className="text-sm opacity-90">Necesitaras agentes inmobiliarios para listar tus vacancias cuando un inquilino salga?</p>
                            </div>
                        </div>
                        <div className="grid grid-cols-2 gap-4">
                            <button onClick={() => { setUsePlatformAgents(true); setStep(3); }} className="p-4 border rounded-xl hover:border-brand-500 hover:bg-brand-50 font-bold transition">Si, me interesa</button>
                            <button onClick={() => { setUsePlatformAgents(false); setStep(3); }} className="p-4 border rounded-xl hover:bg-slate-50 font-medium transition">No por ahora</button>
                        </div>
                    </div>
                )}

                {step === 3 && (
                    <div className="space-y-6 animate-in fade-in slide-in-from-right-4 text-center">
                        <div className="inline-flex items-center justify-center w-16 h-16 bg-green-100 text-green-600 rounded-full mb-4">
                            <CheckCircle2 className="w-8 h-8" />
                        </div>
                        <h3 className="font-bold text-xl text-slate-900">Todo listo</h3>
                        <p className="text-slate-500">Ya puedes empezar a dar de alta tus inmuebles.</p>

                        <div className="text-left p-4 bg-amber-50 border border-amber-200 rounded-xl flex items-start gap-3">
                            <div className="w-10 h-10 rounded-xl bg-white border border-amber-200 text-amber-700 flex items-center justify-center shrink-0">
                                <Landmark className="w-5 h-5" />
                            </div>
                            <div className="text-sm text-amber-900">
                                <p className="font-bold flex items-center gap-1.5">
                                    <AlertTriangle className="w-4 h-4" /> Activa la validacion automatica Banxico
                                </p>
                                <p className="mt-1">
                                    En cuanto entres al panel, registra en <strong>Mi perfil</strong> la cuenta bancaria que recibe las transferencias:
                                    CLABE, banco y titular. Mientras no la captures completa, los SPEI se revisaran manualmente.
                                </p>
                            </div>
                        </div>

                        <button onClick={async () => {
                            try {
                                await api.post('/auth/complete-onboarding', {
                                    usePlatformMaintenance,
                                    usePlatformAgents
                                });
                                onComplete();
                            } catch (e) {
                                console.error('Error completando onboarding', e);
                            }
                        }} className="w-full mt-6 bg-brand-600 text-white font-bold py-3 rounded-xl hover:bg-brand-700 flex items-center justify-center gap-2">
                            Ir al Panel de Control <ArrowRight className="w-5 h-5" />
                        </button>
                    </div>
                )}
            </div>
        </div>
    );
};
