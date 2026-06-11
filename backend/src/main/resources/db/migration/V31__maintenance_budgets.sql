-- V31: Etapa 2 — presupuestos de mantenimiento (upload PDF/Excel, aprobación dueño).
-- Flujo:
--   * Proveedor de mantenimiento / admin de inmueble sube presupuesto (PDF/Excel).
--   * Dueño lo revisa y aprueba/rechaza (requiere reauth MFA+password en el controller).
--   * Estados: SUBMITTED -> APPROVED | REJECTED (terminal).
--   * file_content_type se valida en backend contra una whitelist (PDF/Excel/CSV/ODS).

CREATE TABLE IF NOT EXISTS maintenance_budgets (
    id VARCHAR(64) PRIMARY KEY,
    owner_id VARCHAR(64) NOT NULL,
    property_id VARCHAR(64),
    provider_user_id VARCHAR(64),
    title VARCHAR(255) NOT NULL,
    description TEXT,
    amount NUMERIC(14, 2),
    currency VARCHAR(8) NOT NULL DEFAULT 'MXN',
    status VARCHAR(32) NOT NULL DEFAULT 'SUBMITTED',
    file_url VARCHAR(512) NOT NULL,
    file_name VARCHAR(255),
    file_content_type VARCHAR(128),
    file_size_bytes BIGINT,
    submitted_by_user_id VARCHAR(64) NOT NULL,
    submitted_at TIMESTAMP NOT NULL,
    decided_at TIMESTAMP,
    decided_by_user_id VARCHAR(64),
    decision_note TEXT,
    CONSTRAINT maintenance_budgets_status_chk CHECK (status IN ('SUBMITTED','APPROVED','REJECTED'))
);

CREATE INDEX IF NOT EXISTS idx_mb_owner ON maintenance_budgets(owner_id);
CREATE INDEX IF NOT EXISTS idx_mb_property ON maintenance_budgets(property_id);
CREATE INDEX IF NOT EXISTS idx_mb_status ON maintenance_budgets(status);
CREATE INDEX IF NOT EXISTS idx_mb_provider ON maintenance_budgets(provider_user_id);
