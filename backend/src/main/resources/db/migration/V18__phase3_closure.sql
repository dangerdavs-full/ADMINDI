-- V18: Phase 3 closure — expenses, maintenance, vacancies, timeline, file enrichment, shortfall

-- Expenses
CREATE TABLE IF NOT EXISTS expenses (
    id VARCHAR(36) PRIMARY KEY,
    owner_id VARCHAR(36) NOT NULL,
    property_id VARCHAR(36) NOT NULL,
    type VARCHAR(30) NOT NULL,
    description TEXT,
    amount NUMERIC(12,2) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'QUOTED',
    approved_by VARCHAR(100),
    approved_at TIMESTAMP,
    paid_at TIMESTAMP,
    linked_resource_type VARCHAR(50),
    linked_resource_id VARCHAR(36),
    evidence_file_id VARCHAR(36),
    notes TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_expenses_owner ON expenses(owner_id);
CREATE INDEX IF NOT EXISTS idx_expenses_property ON expenses(property_id);

-- Maintenance tickets
CREATE TABLE IF NOT EXISTS maintenance_tickets (
    id VARCHAR(36) PRIMARY KEY,
    owner_id VARCHAR(36) NOT NULL,
    property_id VARCHAR(36) NOT NULL,
    tenant_profile_id VARCHAR(36),
    title VARCHAR(255) NOT NULL,
    description TEXT,
    urgency VARCHAR(20) DEFAULT 'NORMAL',
    status VARCHAR(30) NOT NULL DEFAULT 'OPEN',
    assigned_provider_id VARCHAR(36),
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    resolved_at TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_maint_tickets_owner ON maintenance_tickets(owner_id);
CREATE INDEX IF NOT EXISTS idx_maint_tickets_property ON maintenance_tickets(property_id);

-- Maintenance quotes
CREATE TABLE IF NOT EXISTS maintenance_quotes (
    id VARCHAR(36) PRIMARY KEY,
    ticket_id VARCHAR(36) NOT NULL REFERENCES maintenance_tickets(id),
    provider_id VARCHAR(36),
    amount NUMERIC(12,2) NOT NULL,
    description TEXT,
    evidence_file_id VARCHAR(36),
    status VARCHAR(20) NOT NULL DEFAULT 'SUBMITTED',
    submitted_at TIMESTAMP NOT NULL DEFAULT NOW(),
    approved_at TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_maint_quotes_ticket ON maintenance_quotes(ticket_id);

-- Vacancies
CREATE TABLE IF NOT EXISTS vacancies (
    id VARCHAR(36) PRIMARY KEY,
    owner_id VARCHAR(36) NOT NULL,
    property_id VARCHAR(36) NOT NULL,
    unit_id VARCHAR(36),
    opened_at TIMESTAMP NOT NULL DEFAULT NOW(),
    closed_at TIMESTAMP,
    status VARCHAR(20) NOT NULL DEFAULT 'OPEN',
    assigned_agent_id VARCHAR(36),
    notes TEXT
);
CREATE INDEX IF NOT EXISTS idx_vacancies_owner ON vacancies(owner_id);
CREATE INDEX IF NOT EXISTS idx_vacancies_property ON vacancies(property_id);

-- Commercial activities
CREATE TABLE IF NOT EXISTS commercial_activities (
    id VARCHAR(36) PRIMARY KEY,
    vacancy_id VARCHAR(36) NOT NULL REFERENCES vacancies(id),
    agent_user_id VARCHAR(36),
    activity_type VARCHAR(30) NOT NULL,
    notes TEXT,
    commission_amount NUMERIC(12,2),
    commission_status VARCHAR(20),
    evidence_file_id VARCHAR(36),
    created_at TIMESTAMP NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_commercial_vacancy ON commercial_activities(vacancy_id);

-- Property movements (timeline)
CREATE TABLE IF NOT EXISTS property_movements (
    id VARCHAR(36) PRIMARY KEY,
    property_id VARCHAR(36) NOT NULL,
    owner_id VARCHAR(36) NOT NULL,
    resource_type VARCHAR(50),
    resource_id VARCHAR(36),
    actor_user_id VARCHAR(36),
    actor_role VARCHAR(30),
    event_type VARCHAR(60) NOT NULL,
    title VARCHAR(255) NOT NULL,
    description TEXT,
    occurred_at TIMESTAMP NOT NULL DEFAULT NOW(),
    metadata_json JSONB,
    attachment_file_id VARCHAR(36)
);
CREATE INDEX IF NOT EXISTS idx_movements_property ON property_movements(property_id);
CREATE INDEX IF NOT EXISTS idx_movements_owner ON property_movements(owner_id);
CREATE INDEX IF NOT EXISTS idx_movements_event ON property_movements(event_type);

-- Enrich property_files
ALTER TABLE property_files ADD COLUMN IF NOT EXISTS uploader_role VARCHAR(30);
ALTER TABLE property_files ADD COLUMN IF NOT EXISTS label VARCHAR(50);
ALTER TABLE property_files ADD COLUMN IF NOT EXISTS note TEXT;

-- Shortfall reason on invoices
ALTER TABLE invoices ADD COLUMN IF NOT EXISTS shortfall_reason VARCHAR(40);
ALTER TABLE invoices ADD COLUMN IF NOT EXISTS shortfall_description TEXT;
ALTER TABLE invoices ADD COLUMN IF NOT EXISTS promised_completion_date DATE;
