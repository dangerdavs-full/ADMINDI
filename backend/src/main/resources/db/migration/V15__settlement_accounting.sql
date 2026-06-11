-- V15: Settlement accounting fields for invoice
ALTER TABLE invoices ADD COLUMN IF NOT EXISTS paid_amount NUMERIC(12,2) DEFAULT 0;
ALTER TABLE invoices ADD COLUMN IF NOT EXISTS outstanding_amount NUMERIC(12,2);
ALTER TABLE invoices ADD COLUMN IF NOT EXISTS credit_balance NUMERIC(12,2) DEFAULT 0;

-- Settlement status: UNPAID, PARTIALLY_PAID, PAID, OVERPAID
ALTER TABLE invoices ADD COLUMN IF NOT EXISTS settlement_status VARCHAR(30) DEFAULT 'UNPAID';

-- Backfill existing PAID invoices
UPDATE invoices
SET paid_amount = total_amount,
    outstanding_amount = 0,
    settlement_status = 'PAID'
WHERE status = 'PAID';

-- Backfill existing unpaid invoices
UPDATE invoices
SET outstanding_amount = total_amount,
    settlement_status = 'UNPAID'
WHERE status != 'PAID' AND outstanding_amount IS NULL;

-- PaymentEntity: track how much was applied vs unapplied
ALTER TABLE payments ADD COLUMN IF NOT EXISTS applied_amount NUMERIC(12,2);
ALTER TABLE payments ADD COLUMN IF NOT EXISTS unapplied_amount NUMERIC(12,2) DEFAULT 0;
