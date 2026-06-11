import { useEffect, useRef, useState } from 'react';
import { usernameService, UsernameCheckResult } from '../services/usernameService';

/**
 * V67 — Hook reutilizable para verificar disponibilidad de username en vivo.
 *
 * <p>Uso: en formularios de creación de staff/agente privado/tenant, pásale
 * el valor del input y úsalo para:
 *  - mostrar feedback inline (check verde, cross rojo con sugerencia).
 *  - deshabilitar el submit si el username está ocupado o formato inválido.</p>
 *
 * <p>Debouncing integrado (default 400ms) para no saturar el endpoint
 * mientras el usuario escribe. Si el input está vacío o es idéntico al
 * initialValue (modo edición), no se dispara fetch.</p>
 */
export interface UsernameAvailabilityState {
  status: 'idle' | 'checking' | 'available' | 'taken' | 'invalid';
  normalized?: string;
  suggestion?: string;
  message?: string;
}

export function useUsernameAvailability(
  value: string,
  options?: { debounceMs?: number; skipIfEqualTo?: string; disabled?: boolean }
): UsernameAvailabilityState {
  const debounceMs = options?.debounceMs ?? 400;
  const skipIfEqualTo = options?.skipIfEqualTo;
  const disabled = options?.disabled ?? false;
  const [state, setState] = useState<UsernameAvailabilityState>({ status: 'idle' });
  const abortRef = useRef<number>(0);

  useEffect(() => {
    if (disabled) {
      setState({ status: 'idle' });
      return;
    }
    const trimmed = (value ?? '').trim();
    if (!trimmed) {
      setState({ status: 'idle' });
      return;
    }
    if (skipIfEqualTo && trimmed === skipIfEqualTo.trim()) {
      setState({ status: 'idle' });
      return;
    }

    const token = ++abortRef.current;
    const timer = window.setTimeout(async () => {
      setState({ status: 'checking' });
      const result: UsernameCheckResult = await usernameService.check(trimmed);
      if (token !== abortRef.current) return;
      if (result.formatError) {
        setState({ status: 'invalid', message: result.formatError });
        return;
      }
      if (result.available) {
        setState({ status: 'available', normalized: result.normalized });
      } else {
        setState({
          status: 'taken',
          normalized: result.normalized,
          suggestion: result.suggestion,
          message: result.suggestion
            ? `Ya está ocupado. Sugerencia: ${result.suggestion}`
            : 'Ya está ocupado. Elige otro.',
        });
      }
    }, debounceMs);

    return () => window.clearTimeout(timer);
  }, [value, debounceMs, skipIfEqualTo, disabled]);

  return state;
}
