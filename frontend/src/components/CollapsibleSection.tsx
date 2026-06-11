import React, { useState } from 'react';
import { ChevronDown, ChevronRight } from 'lucide-react';

/**
 * V67 — Sección con encabezado clickeable que expande/colapsa su contenido.
 *
 * <p>Uso: envolver bloques largos (historiales de notificaciones, pagos,
 * convenios, etc.) para evitar listas kilométricas en expedientes. Por
 * defecto arranca colapsada; el caller puede forzar {@code defaultOpen}.
 * </p>
 *
 * <p>Contrato UX: el contenido sólo se monta cuando la sección está abierta
 * (lazy), así los fetch caros sólo se disparan cuando el usuario realmente
 * quiere ver la sección. Si necesitas prefetch o mantener estado entre
 * aperturas, pásale {@code keepMounted}.</p>
 */
interface Props {
  title: string;
  subtitle?: string;
  icon?: React.ReactNode;
  /** Inicia abierta si true. Por defecto false. */
  defaultOpen?: boolean;
  /** Si true, el contenido se monta siempre (incluso colapsado). */
  keepMounted?: boolean;
  /** Contador pequeño al lado derecho del título. */
  count?: number | null;
  /** Acción secundaria renderizada en la esquina derecha del header. */
  headerRight?: React.ReactNode;
  /** Tono visual. */
  tone?: 'neutral' | 'violet' | 'emerald' | 'indigo';
  children: React.ReactNode;
}

const TONES: Record<NonNullable<Props['tone']>, string> = {
  neutral: 'bg-slate-50 border-slate-100',
  violet: 'bg-violet-50/50 border-violet-100',
  emerald: 'bg-emerald-50/50 border-emerald-100',
  indigo: 'bg-indigo-50/50 border-indigo-100',
};

export const CollapsibleSection: React.FC<Props> = ({
  title,
  subtitle,
  icon,
  defaultOpen = false,
  keepMounted = false,
  count,
  headerRight,
  tone = 'neutral',
  children,
}) => {
  const [open, setOpen] = useState(defaultOpen);
  return (
    <div className="border-t border-slate-100">
      <div className={`flex items-center gap-3 px-6 py-3 ${TONES[tone]} border-b border-slate-100`}>
        <button
          type="button"
          onClick={() => setOpen((v) => !v)}
          className="flex items-center gap-2 flex-1 text-left"
          aria-expanded={open}
        >
          {open ? (
            <ChevronDown className="w-4 h-4 text-slate-600 shrink-0" />
          ) : (
            <ChevronRight className="w-4 h-4 text-slate-500 shrink-0" />
          )}
          {icon && <span className="text-slate-600 shrink-0">{icon}</span>}
          <div className="min-w-0">
            <p className="text-sm font-bold text-slate-800 truncate">{title}</p>
            {subtitle && <p className="text-[11px] text-slate-500 truncate">{subtitle}</p>}
          </div>
          {typeof count === 'number' && (
            <span className="ml-2 inline-flex items-center justify-center min-w-[22px] h-[22px] px-2 rounded-full bg-slate-200 text-slate-700 text-[11px] font-bold">
              {count}
            </span>
          )}
        </button>
        {headerRight && <div className="flex items-center gap-2">{headerRight}</div>}
      </div>
      {(open || keepMounted) && (
        <div className={open ? '' : 'hidden'}>
          {children}
        </div>
      )}
    </div>
  );
};

export default CollapsibleSection;
