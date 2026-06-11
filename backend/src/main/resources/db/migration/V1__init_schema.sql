-- V1: Initial ADMINDI Schema

CREATE TABLE audit_events (
    id VARCHAR(255) PRIMARY KEY,
    timestamp TIMESTAMP NOT NULL,
    actor_id VARCHAR(255),
    actor_role VARCHAR(50),
    event_type VARCHAR(100) NOT NULL,
    resource_type VARCHAR(100),
    resource_id VARCHAR(255),
    owner_id VARCHAR(255),
    old_values JSONB,
    new_values JSONB,
    ip_address VARCHAR(45),
    user_agent VARCHAR(255),
    request_id VARCHAR(255)
);

CREATE TABLE users (
    id VARCHAR(255) PRIMARY KEY,
    email VARCHAR(255) NOT NULL UNIQUE,
    password VARCHAR(255),
    name VARCHAR(255),
    phone VARCHAR(50),
    role VARCHAR(50) NOT NULL,
    must_change_password BOOLEAN DEFAULT false,
    mfa_enabled BOOLEAN DEFAULT false,
    mfa_secret VARCHAR(255),
    provider_type VARCHAR(50),
    provider_id VARCHAR(255),
    owner_id VARCHAR(255)
);

CREATE TABLE user_permissions (
    user_id VARCHAR(255) REFERENCES users(id) ON DELETE CASCADE,
    permission VARCHAR(100)
);

CREATE TABLE properties (
    id VARCHAR(255) PRIMARY KEY,
    owner_id VARCHAR(255) NOT NULL,
    name VARCHAR(255) NOT NULL,
    address VARCHAR(500),
    status VARCHAR(50),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE units (
    id VARCHAR(255) PRIMARY KEY,
    owner_id VARCHAR(255) NOT NULL,
    property_id VARCHAR(255) NOT NULL REFERENCES properties(id) ON DELETE CASCADE,
    name VARCHAR(100) NOT NULL,
    type VARCHAR(50),
    status VARCHAR(50),
    square_meters DOUBLE PRECISION,
    bedrooms INTEGER,
    bathrooms INTEGER,
    floor_code VARCHAR(50),
    notes TEXT
);

CREATE TABLE tenant_profiles (
    id VARCHAR(255) PRIMARY KEY,
    user_id VARCHAR(255) NOT NULL REFERENCES users(id),
    owner_id VARCHAR(255) NOT NULL,
    property_id VARCHAR(255),
    rent_amount NUMERIC(15, 2) NOT NULL,
    payment_day INTEGER NOT NULL,
    has_late_fee BOOLEAN DEFAULT false,
    late_fee_type VARCHAR(50),
    late_fee_value NUMERIC(15, 2),
    grace_period_days INTEGER DEFAULT 0
);

CREATE TABLE leases (
    id VARCHAR(255) PRIMARY KEY,
    owner_id VARCHAR(255) NOT NULL,
    unit_id VARCHAR(255) NOT NULL REFERENCES units(id) ON DELETE CASCADE,
    tenant_id VARCHAR(255) NOT NULL REFERENCES users(id),
    start_date DATE,
    end_date DATE,
    monthly_rent NUMERIC(15, 2),
    deposit_amount NUMERIC(15, 2),
    status VARCHAR(50),
    payment_day INTEGER,
    document_url VARCHAR(1000)
);

CREATE TABLE invoices (
    id VARCHAR(255) PRIMARY KEY,
    tenant_profile_id VARCHAR(255) NOT NULL REFERENCES tenant_profiles(id),
    owner_id VARCHAR(255) NOT NULL,
    month_year VARCHAR(50) NOT NULL,
    issue_date DATE NOT NULL,
    due_date DATE NOT NULL,
    base_amount NUMERIC(15, 2) NOT NULL,
    applied_late_fee NUMERIC(15, 2) DEFAULT 0,
    total_amount NUMERIC(15, 2) NOT NULL,
    status VARCHAR(50) NOT NULL,
    paid_date DATE,
    payment_notes TEXT,
    payment_reference VARCHAR(255),
    proof_of_payment_url VARCHAR(1000),
    tenant_upload_date TIMESTAMP
);
