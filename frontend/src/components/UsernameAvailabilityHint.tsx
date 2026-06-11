import React from 'react';
import { UsernameAvailabilityState } from '../hooks/useUsernameAvailability';

interface Props {
  state: UsernameAvailabilityState;
  onAcceptSuggestion?: (suggestion: string) => void;
}

/**
 * V67 — Indicador visual compacto que se coloca debajo del input de username.
 *
 * Muestra estado y, cuando el username está ocupado, un botón "Usar sugerencia"
 * que el caller puede opt-in cableando {@code onAcceptSuggestion}.
 */
export const UsernameAvailabilityHint: React.FC<Props> = ({ state, onAcceptSuggestion }) => {
  if (state.status === 'idle') return null;
  if (state.status === 'checking') {
    return (
      <p className="text-[10px] text-slate-500 mt-1 flex items-center gap-1.5">
        <span className="w-3 h-3 inline-block rounded-full bg-slate-300 animate-pulse" />
        Verificando disponibilidad…
      </p>
    );
  }
  if (state.status === 'available') {
    return (
      <p className="text-[10px] text-emerald-700 mt-1 flex items-center gap-1.5 font-semibold">
        <span className="w-3 h-3 inline-block rounded-full bg-emerald-500" />
        Disponible{state.normalized ? ` (${state.normalized})` : ''}
      </p>
    );
  }
  if (state.status === 'invalid') {
    return (
      <p className="text-[10px] text-amber-700 mt-1 flex items-center gap-1.5 font-semibold">
        <span className="w-3 h-3 inline-block rounded-full bg-amber-500" />
        {state.message ?? 'Formato inválido.'}
      </p>
    );
  }
  // taken
  return (
    <div className="text-[10px] text-rose-700 mt-1 flex items-center gap-2 flex-wrap">
      <span className="flex items-center gap-1.5 font-semibold">
        <span className="w-3 h-3 inline-block rounded-full bg-rose-500" />
        {state.message ?? 'Ya está ocupado.'}
      </span>
      {state.suggestion && onAcceptSuggestion && (
        <button
          type="button"
          onClick={() => onAcceptSuggestion(state.suggestion!)}
          className="px-2 py-0.5 bg-rose-100 hover:bg-rose-200 text-rose-800 rounded-full font-semibold transition-colors"
        >
          Usar "{state.suggestion}"
        </button>
      )}
    </div>
  );
};
