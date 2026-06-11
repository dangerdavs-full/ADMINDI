-- V12: Platform provider assignments (auto-assign maintenance providers to owners)
CREATE TABLE IF NOT EXISTS platform_provider_assignments (
    id VARCHAR(36) PRIMARY KEY,
    provider_id VARCHAR(36) NOT NULL REFERENCES users(id),
    owner_id VARCHAR(36) NOT NULL REFERENCES users(id),
    assigned_at TIMESTAMP DEFAULT NOW(),
    active BOOLEAN DEFAULT TRUE,
    UNIQUE (provider_id, owner_id)
);

CREATE INDEX IF NOT EXISTS idx_ppa_owner ON platform_provider_assignments(owner_id);
CREATE INDEX IF NOT EXISTS idx_ppa_provider ON platform_provider_assignments(provider_id);
