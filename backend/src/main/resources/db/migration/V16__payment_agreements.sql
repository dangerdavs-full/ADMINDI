-- V16: Payment Agreements (Convenios)
CREATE TABLE IF NOT EXISTS payment_agreements (
    id VARCHAR(36) PRIMARY KEY,
    owner_id VARCHAR(36) NOT NULL,
    tenant_profile_id VARCHAR(36) NOT NULL,
    lease_id VARCHAR(36),
    invoice_id VARCHAR(36),
    requested_amount NUMERIC(12,2) NOT NULL,
    approved_amount NUMERIC(12,2),
    deferred_amount NUMERIC(12,2),
    reason TEXT,
    description TEXT,
    evidence_file_url VARCHAR(500),
    status VARCHAR(30) NOT NULL DEFAULT 'REQUESTED',
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    approved_at TIMESTAMP,
    approved_by VARCHAR(100),
    rejected_at TIMESTAMP,
    rejected_by VARCHAR(100),
    rejection_reason TEXT
);

CREATE TABLE IF NOT EXISTS agreement_installments (
    id VARCHAR(36) PRIMARY KEY,
    agreement_id VARCHAR(36) NOT NULL REFERENCES payment_agreements(id),
    due_date DATE NOT NULL,
    amount NUMERIC(12,2) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    paid_at TIMESTAMP,
    payment_id VARCHAR(36)
);

CREATE INDEX IF NOT EXISTS idx_agreements_owner ON payment_agreements(owner_id);
CREATE INDEX IF NOT EXISTS idx_agreements_tenant ON payment_agreements(tenant_profile_id);
CREATE INDEX IF NOT EXISTS idx_agreements_status ON payment_agreements(status);
CREATE INDEX IF NOT EXISTS idx_installments_agreement ON agreement_installments(agreement_id);
