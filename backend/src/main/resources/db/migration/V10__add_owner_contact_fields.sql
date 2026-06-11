-- V10: Add contact fields for OWNER (separate from login credentials)
-- contactEmail = operational/commercial email (for n8n, comms)
-- contactPhone = normalized operational phone with country code
-- contactCountryCode = dial code (e.g. +52)
-- The existing 'email' column remains the loginEmail for authentication.

ALTER TABLE users ADD COLUMN IF NOT EXISTS contact_email VARCHAR(255);
ALTER TABLE users ADD COLUMN IF NOT EXISTS contact_phone VARCHAR(30);
ALTER TABLE users ADD COLUMN IF NOT EXISTS contact_country_code VARCHAR(10);

-- Copy current phone data to contact_phone for existing OWNERs
UPDATE users SET contact_phone = phone, contact_country_code = '+52'
WHERE role = 'OWNER' AND phone IS NOT NULL AND contact_phone IS NULL;

-- Copy current email to contact_email for existing OWNERs so n8n has a default
UPDATE users SET contact_email = email
WHERE role = 'OWNER' AND contact_email IS NULL;
