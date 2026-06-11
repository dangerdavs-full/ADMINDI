-- V49: Gaps A-G contabilidad — referencias de archivo en expenses
--
-- Hasta V48 el expediente de un inmueble tenía dos fugas:
--   (a) Al aprobarse una cotización de mantenimiento no se creaba el egreso
--       con la URL del presupuesto (PDF/JPG) cargado por el proveedor. El dueño
--       veía el egreso pero no podía descargar el documento original.
--   (b) Al cerrarse el ticket con comprobante de pago, el archivo de soporte
--       se adjuntaba al payment/movement pero no al egreso, rompiendo el
--       join directo expediente → gasto → comprobante.
--
-- Esta migración añade dos columnas opcionales a expenses para cerrar ambos
-- huecos. No hay backfill: las filas históricas se quedan con NULL y el
-- frontend mostrará "presupuesto sin adjunto" / "pago sin comprobante" donde
-- corresponda hasta que el nuevo flujo las reemplace al operar el ticket.
--
-- FKs a file_upload_claims (tabla creada en V46). ON DELETE SET NULL para que
-- una purga futura del archivo no rompa el egreso — la auditoría del gasto
-- sobrevive al borrado del blob.
--
-- Ver docs/ARCHITECTURE_IDENTITY_AND_EXPEDIENTES.md §5 (expediente contable).

ALTER TABLE expenses
    ADD COLUMN IF NOT EXISTS budget_file_id       VARCHAR(64) NULL,
    ADD COLUMN IF NOT EXISTS payment_proof_file_id VARCHAR(64) NULL;

-- Índices para búsquedas del expediente del inmueble ("dame todos los egresos
-- con comprobante adjunto", "dame el egreso por file_id al abrir un link de
-- descarga"). Parciales porque la mayoría de filas históricas son NULL.
CREATE INDEX IF NOT EXISTS idx_expenses_budget_file_id
    ON expenses(budget_file_id)
    WHERE budget_file_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_expenses_payment_proof_file_id
    ON expenses(payment_proof_file_id)
    WHERE payment_proof_file_id IS NOT NULL;

-- FKs con ON DELETE SET NULL: si el archivo se purga (retención GDPR,
-- limpieza manual por SUPERADMIN) el gasto conserva todo su trail contable
-- sólo pierde la referencia al documento.
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.table_constraints
        WHERE constraint_name = 'fk_expenses_budget_file' AND table_name = 'expenses'
    ) THEN
        ALTER TABLE expenses
            ADD CONSTRAINT fk_expenses_budget_file
            FOREIGN KEY (budget_file_id)
            REFERENCES file_upload_claims(id)
            ON DELETE SET NULL;
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.table_constraints
        WHERE constraint_name = 'fk_expenses_payment_proof_file' AND table_name = 'expenses'
    ) THEN
        ALTER TABLE expenses
            ADD CONSTRAINT fk_expenses_payment_proof_file
            FOREIGN KEY (payment_proof_file_id)
            REFERENCES file_upload_claims(id)
            ON DELETE SET NULL;
    END IF;
END $$;
