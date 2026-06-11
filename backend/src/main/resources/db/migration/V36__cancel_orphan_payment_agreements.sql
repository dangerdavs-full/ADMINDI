-- V36: Cancelar convenios (payment_agreements) sin soporte económico.
--
-- Contexto:
--   V35 archivó expedientes cuyo usuario estaba desactivado y anuló sus invoices abiertas.
--   Sin embargo, los convenios (payment_agreements) ligados a esas invoices quedaron vivos
--   en estado REQUESTED/APPROVED/ACTIVE/BREACHED, mostrándose en la pestaña "Convenios" del
--   detalle del inmueble con aspecto vinculante aunque la factura a la que se comprometían
--   ya estaba VOID.
--
--   Ejemplo reproducido:
--     · Inmueble "Test Delete Flow" · Convenio 2026-05 "ACTIVE" por $6,000
--     · Pero la invoice 2026-05 del mismo inquilino (QA Tenant Etapa3) ya estaba VOID
--       (Cobranza: "CANCELLED / VOID / Pendiente $0") por la baja del expediente.
--
-- Regla de negocio (V36+):
--   · Un convenio requiere una factura cobrable y un expediente vigente para ser exigible.
--   · Si la factura asociada está VOID/VOIDED/CANCELLED o ya no existe, o si el expediente
--     del inquilino está archivado / el usuario desactivado, el convenio pierde su soporte
--     económico y debe pasar a CANCELLED.
--   · La razón (reason), descripción (description) y evidencia (evidence_file_url) se
--     preservan INTACTAS: son parte del historial del compromiso (quedan legibles en la
--     UI con el badge CANCELLED y el motivo automático en rejection_reason).
--   · Estados terminales existentes (CANCELLED, REJECTED, COMPLETED) no se tocan.
--
--   IMPORTANTE: Esta migración NO promueve convenios a ACTIVE. Esa es decisión exclusiva
--   del dueño (PaymentAgreementService.approveAgreement). Aquí solo se cancelan convenios
--   huérfanos; si el dueño nunca valida un convenio, permanece en REQUESTED — nunca se
--   salta a ACTIVE por sí solo, aun si el SPEI viene de respaldo.
--
-- Migración idempotente: una segunda corrida sin datos sucios no cambia filas.

UPDATE payment_agreements pa
   SET status           = 'CANCELLED',
       rejection_reason = COALESCE(pa.rejection_reason,
                          'Cancelado automáticamente (V36): la factura o el expediente asociado ya no existe.')
 WHERE pa.status NOT IN ('CANCELLED','REJECTED','COMPLETED')
   AND (
        -- (a) Invoice inexistente o en estado terminal no-cobrable (VOID/CANCELLED).
        (pa.invoice_id IS NOT NULL AND NOT EXISTS (
             SELECT 1 FROM invoices i
              WHERE i.id = pa.invoice_id
                AND UPPER(COALESCE(i.status, '')) NOT IN ('VOID','VOIDED','CANCELLED','CANCELED')
        ))
        -- (b) Expediente archivado.
        OR EXISTS (
             SELECT 1 FROM tenant_profiles tp
              WHERE tp.id = pa.tenant_profile_id
                AND tp.archived_at IS NOT NULL
        )
        -- (c) Usuario del expediente desactivado.
        OR EXISTS (
             SELECT 1 FROM tenant_profiles tp
              JOIN users u ON u.id = tp.user_id
              WHERE tp.id = pa.tenant_profile_id
                AND u.active = false
        )
   );
