-- V14: Phase 3 — Payments, Transfer Proofs, CEP Validation + Invoice Enhancements

-- Enrich invoices table
ALTER TABLE invoices ADD COLUMN IF NOT EXISTS lease_id VARCHAR(255) REFERENCES leases(id);
ALTER TABLE invoices ADD COLUMN IF NOT EXISTS concept VARCHAR(255) DEFAULT 'RENTA_MENSUAL';
ALTER TABLE invoices ADD COLUMN IF NOT EXISTS payment_method VARCHAR(50); -- CASH, TRANSFER_SPEI, MERCADO_PAGO, OTHER
ALTER TABLE invoices ADD COLUMN IF NOT EXISTS payment_gateway_ref VARCHAR(500);

-- Payments: real record per payment event
CREATE TABLE IF NOT EXISTS payments (
    id VARCHAR(255) PRIMARY KEY,
    invoice_id VARCHAR(255) NOT NULL REFERENCES invoices(id),
    owner_id VARCHAR(255) NOT NULL,
    tenant_profile_id VARCHAR(255),
    amount NUMERIC(12,2) NOT NULL,
    payment_method VARCHAR(50) NOT NULL, -- CASH, TRANSFER_SPEI, MERCADO_PAGO, OTHER
    gateway_reference VARCHAR(500),
    status VARCHAR(30) NOT NULL DEFAULT 'PENDING', -- PENDING, CONFIRMED, REJECTED
    paid_at TIMESTAMP,
    confirmed_by VARCHAR(255),
    confirmed_at TIMESTAMP,
    notes TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- Transfer proof submissions (SPEI evidence)
CREATE TABLE IF NOT EXISTS transfer_proof_submissions (
    id VARCHAR(255) PRIMARY KEY,
    invoice_id VARCHAR(255) NOT NULL REFERENCES invoices(id),
    owner_id VARCHAR(255) NOT NULL,
    tenant_profile_id VARCHAR(255),
    file_url VARCHAR(1000),
    clave_rastreo VARCHAR(255),
    bank_emitter VARCHAR(255),
    account_receiver VARCHAR(255),
    amount NUMERIC(12,2),
    transfer_date DATE,
    status VARCHAR(30) NOT NULL DEFAULT 'RECEIVED', -- RECEIVED, PENDING_VALIDATION, VALIDATED, REJECTED, INCOMPLETE_DATA
    rejection_reason TEXT,
    missing_fields JSONB,
    submitted_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    reviewed_at TIMESTAMP,
    reviewed_by VARCHAR(255)
);

-- CEP validation attempts (Banxico)
CREATE TABLE IF NOT EXISTS cep_validation_attempts (
    id VARCHAR(255) PRIMARY KEY,
    transfer_proof_id VARCHAR(255) NOT NULL REFERENCES transfer_proof_submissions(id),
    request_payload JSONB,
    response_payload JSONB,
    status VARCHAR(30) NOT NULL, -- SUCCESS, FAILED, INCOMPLETE_DATA
    missing_fields JSONB,
    attempted_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- Indexes
CREATE INDEX IF NOT EXISTS idx_payments_invoice ON payments(invoice_id);
CREATE INDEX IF NOT EXISTS idx_payments_owner ON payments(owner_id);
CREATE INDEX IF NOT EXISTS idx_payments_status ON payments(status);
CREATE INDEX IF NOT EXISTS idx_transfer_proofs_invoice ON transfer_proof_submissions(invoice_id);
CREATE INDEX IF NOT EXISTS idx_transfer_proofs_owner ON transfer_proof_submissions(owner_id);
CREATE INDEX IF NOT EXISTS idx_transfer_proofs_status ON transfer_proof_submissions(status);
CREATE INDEX IF NOT EXISTS idx_cep_attempts_proof ON cep_validation_attempts(transfer_proof_id);
CREATE INDEX IF NOT EXISTS idx_invoices_lease ON invoices(lease_id);
