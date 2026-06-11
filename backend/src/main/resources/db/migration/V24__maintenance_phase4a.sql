-- Phase 4A: maintenance provider acceptance + expense settlement fields + permissions

ALTER TABLE maintenance_tickets
    ADD COLUMN IF NOT EXISTS provider_accepted_at TIMESTAMP;

UPDATE maintenance_tickets
SET status = 'AWAITING_PROVIDER_ACCEPT'
WHERE assigned_provider_id IS NOT NULL
  AND status = 'OPEN';

ALTER TABLE expenses
    ADD COLUMN IF NOT EXISTS approved_amount NUMERIC(12,2),
    ADD COLUMN IF NOT EXISTS paid_amount NUMERIC(12,2) NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS outstanding_amount NUMERIC(12,2),
    ADD COLUMN IF NOT EXISTS payment_settlement_status VARCHAR(32) NOT NULL DEFAULT 'UNPAID',
    ADD COLUMN IF NOT EXISTS payment_method VARCHAR(24),
    ADD COLUMN IF NOT EXISTS owner_confirmation_status VARCHAR(24) NOT NULL DEFAULT 'PENDING',
    ADD COLUMN IF NOT EXISTS provider_confirmation_status VARCHAR(24) NOT NULL DEFAULT 'PENDING',
    ADD COLUMN IF NOT EXISTS provider_user_id VARCHAR(36);

UPDATE expenses SET approved_amount = amount WHERE approved_amount IS NULL AND status IN ('APPROVED', 'PAID');
UPDATE expenses SET paid_amount = COALESCE(amount, 0) WHERE status = 'PAID' AND paid_amount = 0;
UPDATE expenses SET payment_settlement_status = 'PAID_IN_FULL' WHERE status = 'PAID';
UPDATE expenses SET payment_settlement_status = 'UNPAID' WHERE status = 'APPROVED';
UPDATE expenses SET outstanding_amount = GREATEST(
        COALESCE(approved_amount, amount, 0) - COALESCE(paid_amount, 0),
        0
    )
WHERE outstanding_amount IS NULL;

UPDATE permission_templates SET permissions =
'["properties:read","properties:write","properties:delete","units:read","units:write","units:delete","tenants:read","tenants:write","leases:read","leases:write","invoices:read","invoices:write","staff:read","staff:write","reports:read","maintenance:tickets:read","QUOTE_APPROVE","QUOTE_REJECT","EXPENSE_PAY","EXPENSE_SETTLEMENT_CONFIRM"]'::jsonb
WHERE id = 'tpl-full-access';

UPDATE permission_templates SET permissions =
'["properties:read","units:read","tenants:read","leases:read","invoices:read","reports:read","maintenance:tickets:read"]'::jsonb
WHERE id = 'tpl-read-only';

UPDATE permission_templates SET permissions =
'["invoices:read","invoices:write","reports:read","tenants:read","leases:read","maintenance:tickets:read"]'::jsonb
WHERE id = 'tpl-accountant';
