import React, { useCallback, useEffect, useMemo, useState } from 'react';
import {
  Wrench,
  AlertCircle,
  CheckCircle2,
  Clock,
  Loader2,
  Plus,
  RefreshCw,
  X,
  Upload,
  Image as ImageIcon,
  Trash2,
  Hammer,
  DollarSign,
  ShieldCheck,
  XCircle,
} from 'lucide-react';
import {
  tenantMaintenanceService,
  TenantMaintenanceTicketDTO,
  TenantMaintenanceQuoteDTO,
  MaintenanceUrgency,
} from '../services/tenantMaintenanceService';
import { openFileAttachment, describeSecureFileError } from '../services/secureFileService';

/**
 * Panel del inquilino para reportar problemas de mantenimiento y dar
 * seguimiento al estado (dueño autoriza → proveedor acepta → cotiza →
 * dueño aprueba pago → completado).
 *
 * Cubre el requisito de producto:
 *  - El inquilino puede adjuntar fotos y descripción.
 *  - El dueño y el property admin reciben la solicitud (notificación).
 *  - El inquilino ve el avance y recibe avisos en cada paso.
 */

interface Props {
  tenantProfileId: string;
  propertyId?: string;
}

type UrgencyOption = { value: MaintenanceUrgency; label: string; cls: string };

const URGENCY_OPTIONS: UrgencyOption[] = [
  { value: 'LOW',      label: 'Baja (no urgente)',      cls: 'bg-slate-100 text-slate-700' },
  { value: 'NORMAL',   label: 'Normal',                 cls: 'bg-blue-100 text-blue-700' },
  { value: 'HIGH',     label: 'Alta (lo antes posible)', cls: 'bg-amber-100 text-amber-800' },
  { value: 'CRITICAL', label: 'Crítica (emergencia)',   cls: 'bg-red-100 text-red-700' },
];

const STATUS_LABEL: Record<string, { label: string; cls: string; icon: React.ReactNode; hint: string }> = {
  AWAITING_OWNER_AUTH:       { label: 'Esperando a tu arrendador', cls: 'bg-amber-100 text-amber-800', icon: <Clock className="w-3.5 h-3.5" />, hint: 'Tu arrendador tiene 72 horas para autorizarlo.' },
  AWAITING_PROVIDER_ACCEPT:  { label: 'Asignado al proveedor',     cls: 'bg-blue-100 text-blue-700',   icon: <Wrench className="w-3.5 h-3.5" />, hint: 'Esperamos que el proveedor acepte (72h).' },
  ACCEPTED:                  { label: 'Proveedor en camino',       cls: 'bg-indigo-100 text-indigo-700', icon: <Hammer className="w-3.5 h-3.5" />, hint: 'El proveedor aceptó y se pondrá en contacto.' },
  QUOTED:                    { label: 'Cotización entregada',      cls: 'bg-purple-100 text-purple-700', icon: <DollarSign className="w-3.5 h-3.5" />, hint: 'El proveedor entregó una cotización a tu arrendador.' },
  APPROVED:                  { label: 'Reparación aprobada',       cls: 'bg-teal-100 text-teal-700',   icon: <ShieldCheck className="w-3.5 h-3.5" />, hint: 'Tu arrendador aprobó el pago. El proveedor procederá.' },
  COMPLETED:                 { label: 'Resuelto',                  cls: 'bg-emerald-100 text-emerald-700', icon: <CheckCircle2 className="w-3.5 h-3.5" />, hint: 'El trabajo se completó y se pagó.' },
  CANCELLED:                 { label: 'Cancelado',                 cls: 'bg-slate-100 text-slate-600', icon: <XCircle className="w-3.5 h-3.5" />, hint: '' },
  REJECTED_BY_OWNER:         { label: 'No autorizado',             cls: 'bg-rose-100 text-rose-700',   icon: <XCircle className="w-3.5 h-3.5" />, hint: 'Tu arrendador declinó este reporte.' },
  OPEN:                      { label: 'Abierto',                   cls: 'bg-slate-100 text-slate-600', icon: <Clock className="w-3.5 h-3.5" />, hint: '' },
};

const MAX_PHOTOS = 5;
const MAX_PHOTO_BYTES = 10 * 1024 * 1024;

export const TenantMaintenancePanel: React.FC<Props> = ({ tenantProfileId, propertyId }) => {
  const [tickets, setTickets] = useState<TenantMaintenanceTicketDTO[]>([]);
  const [loading, setLoading] = useState(false);
  const [showNew, setShowNew] = useState(false);
  const [selectedTicket, setSelectedTicket] = useState<TenantMaintenanceTicketDTO | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const res = await tenantMaintenanceService.listMyTickets(tenantProfileId);
      setTickets(res);
    } catch (e: any) {
      console.error('Error cargando tickets', e);
    } finally {
      setLoading(false);
    }
  }, [tenantProfileId]);

  useEffect(() => { load(); }, [load]);

  return (
    <div className="bg-white rounded-3xl p-6 md:p-8 border border-slate-200 shadow-sm">
      <div className="flex items-center justify-between gap-3 mb-6 flex-wrap">
        <div className="flex items-center gap-3">
          <div className="w-10 h-10 rounded-full bg-amber-100 text-amber-600 flex items-center justify-center">
            <Wrench className="w-5 h-5" />
          </div>
          <div>
            <h2 className="text-xl font-bold text-slate-800">Reportes de mantenimiento</h2>
            <p className="text-xs text-slate-500">
              Reporta problemas del inmueble. Tu arrendador y su administrador los reciben al instante.
            </p>
          </div>
        </div>
        <div className="flex items-center gap-2">
          <button
            type="button"
            onClick={load}
            disabled={loading}
            className="px-3 py-1.5 text-sm text-slate-500 hover:text-slate-900 flex items-center gap-1.5"
            title="Refrescar lista"
          >
            <RefreshCw className={`w-4 h-4 ${loading ? 'animate-spin' : ''}`} />
            <span className="hidden sm:inline">Refrescar</span>
          </button>
          <button
            type="button"
            onClick={() => setShowNew(true)}
            className="inline-flex items-center gap-1.5 px-4 py-2 bg-amber-600 hover:bg-amber-700 text-white font-bold rounded-xl text-sm transition-colors shadow-sm"
          >
            <Plus className="w-4 h-4" /> Reportar problema
          </button>
        </div>
      </div>

      {loading && tickets.length === 0 ? (
        <div className="flex items-center gap-2 text-sm text-slate-500 justify-center py-10">
          <Loader2 className="w-4 h-4 animate-spin" /> Cargando reportes…
        </div>
      ) : tickets.length === 0 ? (
        <div className="border-2 border-dashed border-slate-200 rounded-2xl py-10 text-center">
          <div className="inline-flex w-12 h-12 bg-slate-50 rounded-full items-center justify-center text-slate-400 mb-3">
            <Wrench className="w-5 h-5" />
          </div>
          <p className="text-sm font-medium text-slate-700">Sin reportes abiertos</p>
          <p className="text-xs text-slate-500 mt-1">
            Cuando algo no funcione en tu inmueble, crea un reporte y adjunta fotos. Tu arrendador autoriza el proveedor que lo repara.
          </p>
        </div>
      ) : (
        <ul className="space-y-3">
          {tickets.map(t => (
            <TicketRow key={t.id} ticket={t} onOpen={() => setSelectedTicket(t)} />
          ))}
        </ul>
      )}

      {showNew && (
        <NewTicketModal
          tenantProfileId={tenantProfileId}
          propertyId={propertyId}
          onClose={() => setShowNew(false)}
          onCreated={() => { setShowNew(false); load(); }}
        />
      )}

      {selectedTicket && (
        <TicketDetailModal
          ticket={selectedTicket}
          onClose={() => setSelectedTicket(null)}
        />
      )}
    </div>
  );
};

// ─── Row ─────────────────────────────────────────────────────────────────────

const TicketRow: React.FC<{ ticket: TenantMaintenanceTicketDTO; onOpen: () => void }> = ({ ticket, onOpen }) => {
  const st = STATUS_LABEL[ticket.status] || STATUS_LABEL.OPEN;
  const photos = parsePhotoIds(ticket.photoFileIdsJson);
  return (
    <li>
      <button
        type="button"
        onClick={onOpen}
        className="w-full text-left p-4 rounded-2xl border border-slate-100 bg-slate-50 hover:bg-slate-100/80 transition-colors flex flex-col gap-2"
      >
        <div className="flex items-start justify-between gap-3 flex-wrap">
          <div className="flex-1 min-w-0">
            <p className="font-bold text-slate-800 truncate">{ticket.title}</p>
            <p className="text-xs text-slate-500 mt-0.5">
              Reportado el {ticket.createdAt ? new Date(ticket.createdAt).toLocaleString('es-MX') : '—'}
              {photos.length > 0 && <> · {photos.length} foto(s)</>}
            </p>
          </div>
          <span className={`inline-flex items-center gap-1 text-xs font-semibold px-2.5 py-1 rounded-full ${st.cls}`}>
            {st.icon} {st.label}
          </span>
        </div>
        {st.hint && <p className="text-xs text-slate-500">{st.hint}</p>}
        {ticket.rejectionReason && (
          <p className="text-xs text-rose-600 bg-rose-50 border border-rose-100 rounded-lg p-2">
            <AlertCircle className="w-3 h-3 inline mr-1" />
            <strong>Motivo:</strong> {ticket.rejectionReason}
          </p>
        )}
      </button>
    </li>
  );
};

// ─── New Ticket Modal ───────────────────────────────────────────────────────

interface NewTicketModalProps {
  tenantProfileId: string;
  propertyId?: string;
  onClose: () => void;
  onCreated: () => void;
}

const NewTicketModal: React.FC<NewTicketModalProps> = ({ tenantProfileId, propertyId, onClose, onCreated }) => {
  const [title, setTitle] = useState('');
  const [description, setDescription] = useState('');
  const [urgency, setUrgency] = useState<MaintenanceUrgency>('NORMAL');
  const [photos, setPhotos] = useState<Array<{ file: File; preview: string; uploaded?: string; uploading?: boolean; error?: string }>>([]);
  const [submitting, setSubmitting] = useState(false);
  const [err, setErr] = useState<string | null>(null);

  const canSubmit = title.trim().length > 2 && !submitting;

  // Liberamos los object URLs al cerrar el modal para no filtrar memoria.
  useEffect(() => {
    return () => {
      photos.forEach(p => URL.revokeObjectURL(p.preview));
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const handleAddFiles = async (files: FileList | null) => {
    if (!files) return;
    const remaining = MAX_PHOTOS - photos.length;
    if (remaining <= 0) {
      setErr(`Máximo ${MAX_PHOTOS} fotos por reporte.`);
      return;
    }
    setErr(null);
    const newEntries: typeof photos = [];
    for (let i = 0; i < Math.min(files.length, remaining); i++) {
      const file = files[i];
      if (!file) continue;
      if (file.size > MAX_PHOTO_BYTES) {
        setErr(`La foto ${file.name} supera 10 MB y se omitió.`);
        continue;
      }
      newEntries.push({
        file,
        preview: URL.createObjectURL(file),
        uploading: true,
      });
    }
    setPhotos(prev => [...prev, ...newEntries]);

    // Subimos en paralelo.
    newEntries.forEach(entry => {
      tenantMaintenanceService
        .uploadFile(entry.file)
        .then(fileId => {
          setPhotos(prev => prev.map(p => p.file === entry.file
            ? { ...p, uploaded: fileId, uploading: false } : p));
        })
        .catch(e => {
          setPhotos(prev => prev.map(p => p.file === entry.file
            ? { ...p, uploading: false, error: e?.response?.data?.message || 'Error al subir' } : p));
        });
    });
  };

  const handleRemove = (idx: number) => {
    setPhotos(prev => {
      const removed = prev[idx];
      if (removed) URL.revokeObjectURL(removed.preview);
      return prev.filter((_, i) => i !== idx);
    });
  };

  const handleSubmit = async () => {
    if (!canSubmit) return;
    if (photos.some(p => p.uploading)) {
      setErr('Espera a que terminen de subir las fotos.');
      return;
    }
    const uploaded = photos.filter(p => p.uploaded).map(p => p.uploaded!);
    setSubmitting(true);
    setErr(null);
    try {
      await tenantMaintenanceService.createTicket({
        tenantProfileId,
        propertyId,
        title: title.trim(),
        description: description.trim() || undefined,
        urgency,
        photoFileIds: uploaded,
      });
      onCreated();
    } catch (e: any) {
      setErr(e?.response?.data?.message || e?.message || 'No se pudo crear el reporte.');
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <div className="fixed inset-0 z-[150] flex items-center justify-center p-4">
      <div className="absolute inset-0 bg-slate-900/70 backdrop-blur-sm" onClick={!submitting ? onClose : undefined} />
      <div className="relative bg-white rounded-2xl w-full max-w-lg shadow-2xl border border-slate-200 max-h-[92vh] flex flex-col">
        <div className="px-6 py-4 border-b border-slate-100 bg-amber-50 flex items-center justify-between">
          <div>
            <h3 className="text-lg font-bold text-amber-900 flex items-center gap-2">
              <Wrench className="w-5 h-5" /> Reportar un problema
            </h3>
            <p className="text-xs text-amber-800 mt-0.5">
              Tu arrendador recibirá la notificación con tu descripción y fotos.
            </p>
          </div>
          <button
            type="button"
            onClick={onClose}
            disabled={submitting}
            className="p-1.5 rounded-lg text-slate-400 hover:text-slate-700 hover:bg-slate-100 disabled:opacity-50"
            aria-label="Cerrar"
          >
            <X className="w-5 h-5" />
          </button>
        </div>

        <div className="p-6 space-y-4 overflow-y-auto">
          <div>
            <label className="block text-xs font-semibold text-slate-600 mb-1">Título del problema *</label>
            <input
              className="w-full text-sm p-2.5 border border-slate-300 rounded-lg focus:ring-2 focus:ring-amber-500 outline-none transition-all"
              placeholder="Ej: fuga de agua en el baño"
              value={title}
              maxLength={120}
              onChange={e => setTitle(e.target.value)}
              disabled={submitting}
            />
          </div>

          <div>
            <label className="block text-xs font-semibold text-slate-600 mb-1">Descripción (opcional)</label>
            <textarea
              className="w-full text-sm p-2.5 border border-slate-300 rounded-lg focus:ring-2 focus:ring-amber-500 outline-none transition-all min-h-[88px]"
              placeholder="Describe lo que pasa: desde cuándo, cómo de grave, qué has intentado..."
              value={description}
              maxLength={2000}
              onChange={e => setDescription(e.target.value)}
              disabled={submitting}
            />
          </div>

          <div>
            <label className="block text-xs font-semibold text-slate-600 mb-1">¿Qué tan urgente es? *</label>
            <div className="grid grid-cols-2 gap-2">
              {URGENCY_OPTIONS.map(opt => (
                <button
                  type="button"
                  key={opt.value}
                  onClick={() => setUrgency(opt.value)}
                  disabled={submitting}
                  className={`px-3 py-2 rounded-xl text-sm font-semibold border transition-colors text-left ${
                    urgency === opt.value
                      ? `${opt.cls} border-transparent ring-2 ring-offset-1 ring-amber-500`
                      : 'bg-white border-slate-200 text-slate-600 hover:bg-slate-50'
                  }`}
                >
                  {opt.label}
                </button>
              ))}
            </div>
          </div>

          <div>
            <label className="block text-xs font-semibold text-slate-600 mb-1">
              Fotos del problema (opcional, hasta {MAX_PHOTOS})
            </label>
            <div className="grid grid-cols-3 sm:grid-cols-4 gap-2 mb-2">
              {photos.map((p, i) => (
                <div key={i} className="relative group aspect-square rounded-lg overflow-hidden border border-slate-200 bg-slate-50">
                  <img src={p.preview} alt="" className="w-full h-full object-cover" />
                  {p.uploading && (
                    <div className="absolute inset-0 bg-slate-900/40 flex items-center justify-center">
                      <Loader2 className="w-5 h-5 text-white animate-spin" />
                    </div>
                  )}
                  {p.error && (
                    <div className="absolute inset-x-0 bottom-0 bg-rose-600/90 text-white text-[10px] px-1 py-0.5 text-center">
                      {p.error}
                    </div>
                  )}
                  {!p.uploading && !submitting && (
                    <button
                      type="button"
                      onClick={() => handleRemove(i)}
                      className="absolute top-1 right-1 w-6 h-6 rounded-full bg-slate-900/70 text-white flex items-center justify-center opacity-0 group-hover:opacity-100 transition-opacity"
                      aria-label="Quitar foto"
                    >
                      <Trash2 className="w-3 h-3" />
                    </button>
                  )}
                </div>
              ))}
              {photos.length < MAX_PHOTOS && (
                <label className={`aspect-square rounded-lg border-2 border-dashed border-slate-200 hover:border-amber-400 flex flex-col items-center justify-center text-slate-400 hover:text-amber-500 cursor-pointer text-xs transition-colors ${submitting ? 'opacity-50 cursor-not-allowed' : ''}`}>
                  <ImageIcon className="w-5 h-5 mb-1" />
                  Añadir
                  <input
                    type="file"
                    accept="image/*"
                    multiple
                    className="hidden"
                    disabled={submitting}
                    onChange={e => {
                      handleAddFiles(e.target.files);
                      e.target.value = '';
                    }}
                  />
                </label>
              )}
            </div>
            <p className="text-[11px] text-slate-400">Máx. 10 MB por foto. Formatos: JPG, PNG, WEBP, HEIC.</p>
          </div>

          {err && (
            <div className="p-3 rounded-lg bg-rose-50 border border-rose-200 text-sm text-rose-700 flex gap-2 items-start">
              <AlertCircle className="w-4 h-4 flex-shrink-0 mt-0.5" /> <span>{err}</span>
            </div>
          )}
        </div>

        <div className="px-6 py-4 border-t border-slate-100 bg-slate-50 flex gap-3 justify-end">
          <button
            type="button"
            onClick={onClose}
            disabled={submitting}
            className="px-4 py-2 text-sm font-semibold text-slate-600 hover:text-slate-900 disabled:opacity-50"
          >
            Cancelar
          </button>
          <button
            type="button"
            onClick={handleSubmit}
            disabled={!canSubmit}
            className="px-4 py-2 bg-amber-600 hover:bg-amber-700 text-white font-bold rounded-xl text-sm inline-flex items-center gap-2 disabled:opacity-50 disabled:cursor-not-allowed"
          >
            {submitting ? <Loader2 className="w-4 h-4 animate-spin" /> : <Upload className="w-4 h-4" />}
            {submitting ? 'Enviando…' : 'Enviar reporte'}
          </button>
        </div>
      </div>
    </div>
  );
};

// ─── Detail Modal ───────────────────────────────────────────────────────────

const TicketDetailModal: React.FC<{ ticket: TenantMaintenanceTicketDTO; onClose: () => void }> = ({ ticket, onClose }) => {
  const [quotes, setQuotes] = useState<TenantMaintenanceQuoteDTO[]>([]);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    let cancelled = false;
    (async () => {
      try {
        const q = await tenantMaintenanceService.getTicketQuotes(ticket.id);
        if (!cancelled) setQuotes(q);
      } catch {
        /* noop */
      } finally {
        if (!cancelled) setLoading(false);
      }
    })();
    return () => { cancelled = true; };
  }, [ticket.id]);

  const st = STATUS_LABEL[ticket.status] || STATUS_LABEL.OPEN;
  const photos = useMemo(() => parsePhotoIds(ticket.photoFileIdsJson), [ticket.photoFileIdsJson]);

  return (
    <div className="fixed inset-0 z-[150] flex items-center justify-center p-4">
      <div className="absolute inset-0 bg-slate-900/70 backdrop-blur-sm" onClick={onClose} />
      <div className="relative bg-white rounded-2xl w-full max-w-lg shadow-2xl border border-slate-200 max-h-[92vh] flex flex-col">
        <div className="px-6 py-4 border-b border-slate-100 bg-slate-50 flex items-start justify-between gap-3">
          <div>
            <h3 className="text-lg font-bold text-slate-800">{ticket.title}</h3>
            <span className={`inline-flex items-center gap-1 text-xs font-semibold px-2.5 py-1 rounded-full mt-1 ${st.cls}`}>
              {st.icon} {st.label}
            </span>
          </div>
          <button
            type="button"
            onClick={onClose}
            className="p-1.5 rounded-lg text-slate-400 hover:text-slate-700 hover:bg-slate-100"
            aria-label="Cerrar"
          >
            <X className="w-5 h-5" />
          </button>
        </div>

        <div className="p-6 overflow-y-auto space-y-4">
          {ticket.description && (
            <p className="text-sm text-slate-700 whitespace-pre-wrap bg-slate-50 border border-slate-100 rounded-lg p-3">
              {ticket.description}
            </p>
          )}

          <Timeline ticket={ticket} />

          {photos.length > 0 && (
            <div>
              <p className="text-xs font-semibold text-slate-500 uppercase tracking-wider mb-2">
                Fotos adjuntas ({photos.length})
              </p>
              <div className="flex flex-wrap gap-2">
                {photos.map((fileId, i) => (
                  <button
                    key={fileId}
                    type="button"
                    onClick={async () => {
                      try {
                        await openFileAttachment(fileId);
                      } catch (err) {
                        alert(describeSecureFileError(err));
                      }
                    }}
                    className="inline-flex items-center gap-1.5 px-3 py-1.5 rounded-lg border border-slate-200 bg-white hover:bg-slate-50 text-sm text-slate-700 font-medium"
                    title="Ver foto"
                  >
                    <ImageIcon className="w-4 h-4 text-amber-600" /> Foto {i + 1}
                  </button>
                ))}
              </div>
            </div>
          )}

          {ticket.rejectionReason && (
            <div className="p-3 rounded-lg bg-rose-50 border border-rose-200 text-sm text-rose-700">
              <p className="font-semibold">Motivo del arrendador</p>
              <p>{ticket.rejectionReason}</p>
            </div>
          )}

          {loading ? (
            <p className="text-xs text-slate-500">Cargando cotización…</p>
          ) : quotes.length > 0 && (
            <div>
              <p className="text-xs font-semibold text-slate-500 uppercase tracking-wider mb-2">
                Cotización del proveedor
              </p>
              <ul className="space-y-2">
                {quotes.map(q => (
                  <li key={q.id} className="p-3 rounded-lg border border-slate-200 bg-white text-sm">
                    <p className="font-semibold text-slate-800">
                      ${q.amount.toLocaleString('en-US', { minimumFractionDigits: 2 })} MXN
                      <span className={`ml-2 text-xs font-bold px-2 py-0.5 rounded-full ${
                        q.status === 'APPROVED' ? 'bg-emerald-100 text-emerald-700' :
                        q.status === 'REJECTED' ? 'bg-rose-100 text-rose-700' :
                        'bg-amber-100 text-amber-700'
                      }`}>
                        {q.status === 'APPROVED' ? 'Aprobada' : q.status === 'REJECTED' ? 'Rechazada' : 'Pendiente de tu arrendador'}
                      </span>
                    </p>
                    {q.description && <p className="text-slate-600 mt-1 whitespace-pre-wrap">{q.description}</p>}
                    {q.visitNotes && (
                      <p className="text-xs text-slate-500 mt-2">
                        <strong>Notas de visita:</strong> {q.visitNotes}
                      </p>
                    )}
                  </li>
                ))}
              </ul>
            </div>
          )}
        </div>
      </div>
    </div>
  );
};

// ─── Timeline ───────────────────────────────────────────────────────────────

const Timeline: React.FC<{ ticket: TenantMaintenanceTicketDTO }> = ({ ticket }) => {
  const steps = [
    { key: 'created',            label: 'Reportado',                done: true, date: ticket.createdAt },
    { key: 'owner_auth',         label: 'Autorizado por arrendador', done: !!ticket.authorizedAt, date: ticket.authorizedAt },
    { key: 'provider_accepted',  label: 'Aceptado por proveedor',    done: !!ticket.providerAcceptedAt, date: ticket.providerAcceptedAt },
    { key: 'resolved',           label: 'Resuelto',                  done: !!ticket.resolvedAt, date: ticket.resolvedAt },
  ];
  return (
    <ol className="border-l-2 border-slate-200 pl-4 space-y-3">
      {steps.map(s => (
        <li key={s.key} className="relative">
          <span className={`absolute -left-[21px] top-0.5 w-3 h-3 rounded-full border-2 ${
            s.done ? 'bg-emerald-500 border-emerald-500' : 'bg-white border-slate-300'
          }`} />
          <p className={`text-sm ${s.done ? 'text-slate-800 font-semibold' : 'text-slate-400'}`}>{s.label}</p>
          {s.date && <p className="text-xs text-slate-500">{new Date(s.date).toLocaleString('es-MX')}</p>}
        </li>
      ))}
    </ol>
  );
};

// ─── Utils ──────────────────────────────────────────────────────────────────

function parsePhotoIds(json: string | null | undefined): string[] {
  if (!json) return [];
  try {
    const parsed = JSON.parse(json);
    return Array.isArray(parsed) ? parsed.filter(v => typeof v === 'string') : [];
  } catch {
    return [];
  }
}

export default TenantMaintenancePanel;
