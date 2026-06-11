-- V7: Onboarding Preferences

ALTER TABLE users
ADD COLUMN IF NOT EXISTS use_platform_maintenance BOOLEAN DEFAULT NULL,
ADD COLUMN IF NOT EXISTS use_platform_agents BOOLEAN DEFAULT NULL;
