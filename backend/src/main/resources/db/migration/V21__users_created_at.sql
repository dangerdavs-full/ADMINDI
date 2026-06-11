-- Registration date for reporting period bounds (owner cannot query months before account creation)
ALTER TABLE users ADD COLUMN IF NOT EXISTS created_at TIMESTAMP NOT NULL DEFAULT NOW();