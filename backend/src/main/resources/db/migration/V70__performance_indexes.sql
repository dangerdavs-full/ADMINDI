-- ============================================================================
-- V70 — Índices de rendimiento para hot paths detectados en auditoría.
--
-- 1) Duplicados de clave de rastreo: LedgerService valida en cada envío de
--    comprobante (web + WhatsApp) que la clave SPEI no haya sido aplicada ya
--    dentro de la organización. Antes cargaba TODOS los proofs del dueño en
--    memoria; ahora es un EXISTS respaldado por este índice funcional.
--
-- 2) Recordatorios de pago: PaymentReminderScheduler corre diario y buscaba
--    facturas con findAll() + filtro en memoria. Ahora consulta por due_date.
-- ============================================================================

CREATE INDEX IF NOT EXISTS idx_transfer_proof_owner_clave
    ON transfer_proof_submissions (owner_id, LOWER(clave_rastreo))
    WHERE clave_rastreo IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_invoices_due_date
    ON invoices (due_date);
