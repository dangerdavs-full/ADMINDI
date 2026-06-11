-- V13: Phase 2 — Property Enhancements + File Metadata Tables

-- Enrich properties table
ALTER TABLE properties ADD COLUMN IF NOT EXISTS type VARCHAR(50);
ALTER TABLE properties ADD COLUMN IF NOT EXISTS predial VARCHAR(100);
ALTER TABLE properties ADD COLUMN IF NOT EXISTS description TEXT;
ALTER TABLE properties ADD COLUMN IF NOT EXISTS active BOOLEAN DEFAULT true;

-- Property files (photos, plans, etc.)
CREATE TABLE IF NOT EXISTS property_files (
    id VARCHAR(255) PRIMARY KEY,
    property_id VARCHAR(255) NOT NULL REFERENCES properties(id) ON DELETE CASCADE,
    category VARCHAR(50) NOT NULL,  -- PHOTO, PLAN, OTHER
    file_name VARCHAR(500) NOT NULL,
    file_path VARCHAR(1000) NOT NULL,
    content_type VARCHAR(100),
    size_bytes BIGINT,
    uploaded_by VARCHAR(255),
    uploaded_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- Lease files (contract documents, annexes)
CREATE TABLE IF NOT EXISTS lease_files (
    id VARCHAR(255) PRIMARY KEY,
    lease_id VARCHAR(255) NOT NULL REFERENCES leases(id) ON DELETE CASCADE,
    file_name VARCHAR(500) NOT NULL,
    file_path VARCHAR(1000) NOT NULL,
    content_type VARCHAR(100),
    size_bytes BIGINT,
    uploaded_by VARCHAR(255),
    uploaded_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_property_files_property ON property_files(property_id);
CREATE INDEX IF NOT EXISTS idx_lease_files_lease ON lease_files(lease_id);
CREATE INDEX IF NOT EXISTS idx_properties_owner_active ON properties(owner_id, active);
