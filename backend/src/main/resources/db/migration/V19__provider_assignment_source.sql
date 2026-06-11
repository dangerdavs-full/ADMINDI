-- V19: assignment_source distinguishes platform catalog vs owner-linked private provider/agent.
-- UTF-8 (no BOM). Safe for Flyway on PostgreSQL.

ALTER TABLE platform_provider_assignments
    ADD COLUMN IF NOT EXISTS assignment_source VARCHAR(20) NOT NULL DEFAULT 'PLATFORM';

COMMENT ON COLUMN platform_provider_assignments.assignment_source IS 'PLATFORM=catalog; PRIVATE=owner-linked';

UPDATE platform_provider_assignments SET assignment_source = 'PLATFORM' WHERE assignment_source IS NULL OR trim(assignment_source) = '';