-- Etapa 3 QA minimal seed (PostgreSQL). Idempotent-ish: deletes prior etapa3-* rows then inserts.
-- Run: psql -f seed_etapa3_qa.sql
-- Password for new users = same bcrypt as superadmin@admindi.com (dev only).

BEGIN;

-- Cleanup previous seed (FK-safe order)
DELETE FROM property_movements WHERE id LIKE 'etapa3-mov-%';
DELETE FROM commercial_activities WHERE id = 'etapa3-comm-act-001';
DELETE FROM expenses WHERE id IN ('etapa3-exp-maint-001','etapa3-exp-comm-001');
DELETE FROM maintenance_quotes WHERE id = 'etapa3-quote-001';
DELETE FROM maintenance_tickets WHERE id = 'etapa3-ticket-001';
DELETE FROM agreement_installments WHERE agreement_id = 'etapa3-agr-001';
DELETE FROM payment_agreements WHERE id = 'etapa3-agr-001';
DELETE FROM invoices WHERE id = 'etapa3-inv-001';
DELETE FROM leases WHERE id = 'etapa3-lease-001';
DELETE FROM tenant_profiles WHERE id = 'etapa3-tp-001';
DELETE FROM vacancies WHERE id = 'etapa3-vac-001';
DELETE FROM units WHERE id IN ('etapa3-unit-a','etapa3-unit-b');
DELETE FROM platform_provider_assignments WHERE id IN ('etapa3-ppa-maint','etapa3-ppa-agent');
DELETE FROM owner_memberships WHERE user_id = 'etapa3-acct-001';
DELETE FROM users WHERE id IN ('etapa3-tenant-001','etapa3-acct-001','etapa3-maint-001','etapa3-agent-001');

-- Constants
-- Owner org
-- 5cccf543-2a2a-4ba5-8e3b-518719079a7b
-- Property QA
-- b3f9b48b-130b-457f-90af-2973a840a3a1

INSERT INTO users (id, email, password, name, role, owner_id, active, must_change_password, onboarding_completed, mfa_enabled)
SELECT 'etapa3-tenant-001', 'qa-tenant@etapa3.local', password, 'QA Tenant Etapa3', 'TENANT', '5cccf543-2a2a-4ba5-8e3b-518719079a7b', true, false, true, false
FROM users WHERE email = 'superadmin@admindi.com' LIMIT 1;

INSERT INTO users (id, email, password, name, role, owner_id, active, must_change_password, onboarding_completed, mfa_enabled)
SELECT 'etapa3-acct-001', 'qa-accountant@etapa3.local', password, 'QA Accountant', 'ACCOUNTANT', '5cccf543-2a2a-4ba5-8e3b-518719079a7b', true, false, true, false
FROM users WHERE email = 'superadmin@admindi.com' LIMIT 1;

INSERT INTO users (id, email, password, name, role, owner_id, active, must_change_password, onboarding_completed, mfa_enabled)
SELECT 'etapa3-maint-001', 'qa-maint@etapa3.local', password, 'QA Maintenance', 'MAINTENANCE_PROVIDER', NULL, true, false, true, false
FROM users WHERE email = 'superadmin@admindi.com' LIMIT 1;

INSERT INTO users (id, email, password, name, role, owner_id, active, must_change_password, onboarding_completed, mfa_enabled)
SELECT 'etapa3-agent-001', 'qa-agent@etapa3.local', password, 'QA Agent', 'REAL_ESTATE_AGENT', NULL, true, false, true, false
FROM users WHERE email = 'superadmin@admindi.com' LIMIT 1;

INSERT INTO owner_memberships (user_id, owner_id) VALUES ('etapa3-acct-001', '5cccf543-2a2a-4ba5-8e3b-518719079a7b');

INSERT INTO platform_provider_assignments (id, provider_id, owner_id, assigned_at, active, assignment_source)
VALUES
  ('etapa3-ppa-maint', 'etapa3-maint-001', '5cccf543-2a2a-4ba5-8e3b-518719079a7b', NOW(), true, 'PLATFORM'),
  ('etapa3-ppa-agent', 'etapa3-agent-001', '5cccf543-2a2a-4ba5-8e3b-518719079a7b', NOW(), true, 'PLATFORM');

INSERT INTO units (id, owner_id, property_id, name, type, status)
VALUES
  ('etapa3-unit-a', '5cccf543-2a2a-4ba5-8e3b-518719079a7b', 'b3f9b48b-130b-457f-90af-2973a840a3a1', 'QA Unit A', 'departamento', 'OCCUPIED'),
  ('etapa3-unit-b', '5cccf543-2a2a-4ba5-8e3b-518719079a7b', 'b3f9b48b-130b-457f-90af-2973a840a3a1', 'QA Unit B', 'departamento', 'VACANT');

INSERT INTO tenant_profiles (id, user_id, owner_id, property_id, rent_amount, payment_day, has_late_fee, grace_period_days)
VALUES ('etapa3-tp-001', 'etapa3-tenant-001', '5cccf543-2a2a-4ba5-8e3b-518719079a7b', 'b3f9b48b-130b-457f-90af-2973a840a3a1', 10000.00, 5, false, 0);

INSERT INTO leases (id, owner_id, unit_id, tenant_id, start_date, end_date, monthly_rent, deposit_amount, status, payment_day)
VALUES ('etapa3-lease-001', '5cccf543-2a2a-4ba5-8e3b-518719079a7b', 'etapa3-unit-a', 'etapa3-tenant-001', '2026-01-01', '2027-12-31', 10000.00, 10000.00, 'ACTIVE', 5);

INSERT INTO invoices (
  id, tenant_profile_id, owner_id, month_year, issue_date, due_date,
  base_amount, applied_late_fee, total_amount, status,
  lease_id, paid_amount, outstanding_amount, credit_balance, settlement_status,
  shortfall_reason, shortfall_description
) VALUES (
  'etapa3-inv-001', 'etapa3-tp-001', '5cccf543-2a2a-4ba5-8e3b-518719079a7b', '2026-05', '2026-05-01', '2026-05-10',
  10000.00, 0, 10000.00, 'LATE',
  'etapa3-lease-001', 4000.00, 6000.00, 0, 'PARTIALLY_PAID',
  'PARTIAL_NEXT_MONTH', 'QA seed partial payment'
);

INSERT INTO payment_agreements (
  id, owner_id, tenant_profile_id, lease_id, invoice_id,
  requested_amount, approved_amount, deferred_amount,
  reason, description, status, created_at, approved_at, approved_by
) VALUES (
  'etapa3-agr-001', '5cccf543-2a2a-4ba5-8e3b-518719079a7b', 'etapa3-tp-001', 'etapa3-lease-001', 'etapa3-inv-001',
  6000.00, 6000.00, 0,
  'QA', 'Approved agreement for outstanding', 'ACTIVE', NOW() - INTERVAL '3 days', NOW() - INTERVAL '2 days', 'owner@qa'
);

INSERT INTO agreement_installments (id, agreement_id, due_date, amount, status)
VALUES
  ('etapa3-inst-001', 'etapa3-agr-001', '2026-06-01', 3000.00, 'PENDING'),
  ('etapa3-inst-002', 'etapa3-agr-001', '2026-07-01', 3000.00, 'PENDING');

INSERT INTO maintenance_tickets (
  id, owner_id, property_id, tenant_profile_id, title, description, urgency, status, assigned_provider_id, created_at
) VALUES (
  'etapa3-ticket-001', '5cccf543-2a2a-4ba5-8e3b-518719079a7b', 'b3f9b48b-130b-457f-90af-2973a840a3a1', 'etapa3-tp-001',
  'QA faucet leak', 'Seed maintenance', 'NORMAL', 'OPEN', 'etapa3-maint-001', NOW() - INTERVAL '1 day'
);

INSERT INTO maintenance_quotes (id, ticket_id, provider_id, amount, description, status, submitted_at, approved_at)
VALUES (
  'etapa3-quote-001', 'etapa3-ticket-001', 'etapa3-maint-001', 1500.00, 'Replace valve', 'APPROVED', NOW() - INTERVAL '20 hours', NOW() - INTERVAL '18 hours'
);

INSERT INTO expenses (
  id, owner_id, property_id, type, description, amount, status,
  approved_by, approved_at, paid_at, linked_resource_type, linked_resource_id, created_at
) VALUES (
  'etapa3-exp-maint-001', '5cccf543-2a2a-4ba5-8e3b-518719079a7b', 'b3f9b48b-130b-457f-90af-2973a840a3a1',
  'MAINTENANCE', 'Replace valve', 1500.00, 'PAID',
  'qa-owner', NOW() - INTERVAL '18 hours', NOW() - INTERVAL '12 hours', 'MAINTENANCE_QUOTE', 'etapa3-quote-001', NOW() - INTERVAL '20 hours'
);

INSERT INTO vacancies (
  id, owner_id, property_id, unit_id, opened_at, status, assigned_agent_id, notes
) VALUES (
  'etapa3-vac-001', '5cccf543-2a2a-4ba5-8e3b-518719079a7b', 'b3f9b48b-130b-457f-90af-2973a840a3a1', 'etapa3-unit-b',
  NOW() - INTERVAL '2 days', 'OPEN', 'etapa3-agent-001', 'QA seed vacancy'
);

INSERT INTO commercial_activities (
  id, vacancy_id, agent_user_id, activity_type, notes, commission_amount, commission_status, created_at
) VALUES (
  'etapa3-comm-act-001', 'etapa3-vac-001', 'etapa3-agent-001', 'VISIT', 'Prospect visit', 2500.00, 'APPROVED', NOW() - INTERVAL '1 day'
);

INSERT INTO expenses (
  id, owner_id, property_id, type, description, amount, status,
  approved_by, approved_at, linked_resource_type, linked_resource_id, created_at
) VALUES (
  'etapa3-exp-comm-001', '5cccf543-2a2a-4ba5-8e3b-518719079a7b', 'b3f9b48b-130b-457f-90af-2973a840a3a1',
  'COMMERCIAL', 'Commission approved for activity etapa3-comm-act-001', 2500.00, 'APPROVED',
  'qa-owner', NOW() - INTERVAL '12 hours', 'COMMERCIAL_ACTIVITY', 'etapa3-comm-act-001', NOW() - INTERVAL '12 hours'
);

-- Timeline rows (mirror app event types)
INSERT INTO property_movements (id, property_id, owner_id, resource_type, resource_id, actor_user_id, actor_role, event_type, title, description, occurred_at, metadata_json)
VALUES
  ('etapa3-mov-001', 'b3f9b48b-130b-457f-90af-2973a840a3a1', '5cccf543-2a2a-4ba5-8e3b-518719079a7b', 'INVOICE', 'etapa3-inv-001', 'etapa3-tenant-001', 'TENANT', 'PAYMENT_PARTIAL', 'Partial payment', 'QA seed', NOW() - INTERVAL '4 days', '{"invoiceId":"etapa3-inv-001"}'),
  ('etapa3-mov-002', 'b3f9b48b-130b-457f-90af-2973a840a3a1', '5cccf543-2a2a-4ba5-8e3b-518719079a7b', 'AGREEMENT', 'etapa3-agr-001', '5cccf543-2a2a-4ba5-8e3b-518719079a7b', 'OWNER', 'AGREEMENT_APPROVED', 'Agreement approved', 'QA seed', NOW() - INTERVAL '2 days', NULL),
  ('etapa3-mov-003', 'b3f9b48b-130b-457f-90af-2973a840a3a1', '5cccf543-2a2a-4ba5-8e3b-518719079a7b', 'MAINTENANCE', 'etapa3-ticket-001', 'etapa3-maint-001', 'MAINTENANCE_PROVIDER', 'MAINTENANCE_QUOTE', 'Quote submitted', 'QA seed', NOW() - INTERVAL '20 hours', NULL),
  ('etapa3-mov-004', 'b3f9b48b-130b-457f-90af-2973a840a3a1', '5cccf543-2a2a-4ba5-8e3b-518719079a7b', 'EXPENSE', 'etapa3-exp-maint-001', '5cccf543-2a2a-4ba5-8e3b-518719079a7b', 'OWNER', 'MAINTENANCE_PAID', 'Maintenance paid', 'QA seed', NOW() - INTERVAL '12 hours', NULL),
  ('etapa3-mov-005', 'b3f9b48b-130b-457f-90af-2973a840a3a1', '5cccf543-2a2a-4ba5-8e3b-518719079a7b', 'VACANCY', 'etapa3-vac-001', NULL, 'SYSTEM', 'VACANCY_OPENED', 'Vacancy opened', 'QA seed', NOW() - INTERVAL '2 days', NULL),
  ('etapa3-mov-006', 'b3f9b48b-130b-457f-90af-2973a840a3a1', '5cccf543-2a2a-4ba5-8e3b-518719079a7b', 'COMMERCIAL', 'etapa3-comm-act-001', 'etapa3-agent-001', 'REAL_ESTATE_AGENT', 'COMMERCIAL_ACTIVITY', 'Commercial activity', 'QA seed', NOW() - INTERVAL '1 day', NULL);

COMMIT;
