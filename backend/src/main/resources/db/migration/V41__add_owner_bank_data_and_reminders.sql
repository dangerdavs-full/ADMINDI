-- V41: Datos bancarios del dueño + tracking de recordatorios de pago (Fase 1 notificaciones).
--
-- Notas:
--  * Los tres campos bancarios se guardan cifrados en backend (EncryptedStringConverter);
--    por eso el tipo es VARCHAR(512), no 18. No forzamos NOT NULL: un dueño puede existir
--    antes de capturar su cuenta bancaria y la captura se hace desde el portal.
--  * payment_reminders_sent evita duplicar recordatorios si el scheduler corre dos veces
--    (reinicio, cron dual, doble job). Unicidad por (invoice_id, days_before, channel).

ALTER TABLE users
    ADD COLUMN IF NOT EXISTS clabe VARCHAR(512),
    ADD COLUMN IF NOT EXISTS bank_name VARCHAR(512),
    ADD COLUMN IF NOT EXISTS account_holder_name VARCHAR(512);

CREATE TABLE IF NOT EXISTS payment_reminders_sent (
    id                  VARCHAR(64)  PRIMARY KEY,
    invoice_id          VARCHAR(64)  NOT NULL,
    days_before         INT          NOT NULL,
    channel             VARCHAR(20)  NOT NULL,
    recipient_user_id   VARCHAR(64)  NOT NULL,
    sent_at             TIMESTAMP    NOT NULL,
    CONSTRAINT uk_payment_reminder UNIQUE (invoice_id, days_before, channel, recipient_user_id)
);

CREATE INDEX IF NOT EXISTS idx_payment_reminders_invoice ON payment_reminders_sent (invoice_id);
CREATE INDEX IF NOT EXISTS idx_payment_reminders_sent_at ON payment_reminders_sent (sent_at);
