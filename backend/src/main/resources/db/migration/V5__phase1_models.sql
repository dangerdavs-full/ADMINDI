-- V5: Phase 1 Finalization Models

CREATE TABLE owner_memberships (
    user_id VARCHAR(255) NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    owner_id VARCHAR(255) NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    assigned_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    assigned_by VARCHAR(255),
    PRIMARY KEY (user_id, owner_id)
);

CREATE TABLE permission_templates (
    id VARCHAR(255) PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    description TEXT,
    is_system BOOLEAN DEFAULT FALSE,
    permissions JSONB NOT NULL
);

CREATE TABLE permission_grants (
    id VARCHAR(255) PRIMARY KEY,
    user_id VARCHAR(255) NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    owner_id VARCHAR(255) NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    template_id VARCHAR(255) REFERENCES permission_templates(id),
    override_permissions JSONB,
    granted_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    granted_by VARCHAR(255)
);

ALTER TABLE refresh_token_sessions 
ADD COLUMN ip_address VARCHAR(45),
ADD COLUMN user_agent VARCHAR(255);
