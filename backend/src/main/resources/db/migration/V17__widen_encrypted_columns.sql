-- V17: Widen columns for encrypted-at-rest storage
-- AES-256-GCM + Base64 encoding adds ~45-60% overhead, so we need wider VARCHAR columns.

ALTER TABLE users ALTER COLUMN mfa_secret TYPE VARCHAR(500);
ALTER TABLE users ALTER COLUMN phone TYPE VARCHAR(500);
ALTER TABLE users ALTER COLUMN contact_email TYPE VARCHAR(500);
ALTER TABLE users ALTER COLUMN contact_phone TYPE VARCHAR(500);
ALTER TABLE users ALTER COLUMN contact_country_code TYPE VARCHAR(500);
