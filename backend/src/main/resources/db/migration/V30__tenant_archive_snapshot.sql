-- V30: Etapa 2 — snapshot contable al archivar expediente de inquilino.
-- Objetivo: dejar registro inmutable del estado (meses pagados, adeudo, evidencias)
-- al momento del archivo, para consulta posterior desde el inmueble.
-- Reglas:
--   * El snapshot se crea una vez por archive; no se edita después.
--   * actor_role queda crudo (SUPER_ADMIN / OWNER / PROPERTY_ADMIN) para trazabilidad.
--   * payload_json guarda el resumen extendido (evidencias, desglose por mes).

CREATE TABLE IF NOT EXISTS tenant_archive_snapshots (
    id VARCHAR(64) PRIMARY KEY,
    owner_id VARCHAR(64) NOT NULL,
    property_id VARCHAR(64),
    tenant_user_id VARCHAR(64) NOT NULL,
    tenant_profile_id VARCHAR(64) NOT NULL,
    tenant_name VARCHAR(255),
    tenant_email VARCHAR(255),
    months_paid_count INTEGER NOT NULL DEFAULT 0,
    months_with_debt_count INTEGER NOT NULL DEFAULT 0,
    total_paid_amount NUMERIC(14, 2) NOT NULL DEFAULT 0,
    total_owed_amount NUMERIC(14, 2) NOT NULL DEFAULT 0,
    applied_late_fee_total NUMERIC(14, 2) NOT NULL DEFAULT 0,
    active_agreements_count INTEGER NOT NULL DEFAULT 0,
    evidences_count INTEGER NOT NULL DEFAULT 0,
    payload_json TEXT,
    archived_by_user_id VARCHAR(64),
    archived_by_role VARCHAR(32),
    archived_at TIMESTAMP NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_tas_owner ON tenant_archive_snapshots(owner_id);
CREATE INDEX IF NOT EXISTS idx_tas_property ON tenant_archive_snapshots(property_id);
CREATE INDEX IF NOT EXISTS idx_tas_tenant_user ON tenant_archive_snapshots(tenant_user_id);
CREATE INDEX IF NOT EXISTS idx_tas_tenant_profile ON tenant_archive_snapshots(tenant_profile_id);
