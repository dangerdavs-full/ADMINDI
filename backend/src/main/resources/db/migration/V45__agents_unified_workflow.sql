-- =====================================================================================
--  V45__agents_unified_workflow.sql
--
--  Fase 2 — Flujo unificado de agente inmobiliario (REAL_ESTATE_AGENT) y agente de
--  mantenimiento (MAINTENANCE_PROVIDER). Introduce:
--
--  1. owner_agent_priorities      — orden explícito que el dueño fija para notificar a sus
--                                   agentes (MAINTENANCE y VACANCY cada uno con su orden).
--  2. agent_notification_chain    — histórico de la cadena de notificaciones uno-a-uno que
--                                   recibe cada agente cuando se abre una vacancia o un
--                                   ticket; permite auto-rechazo por timeout (72h) y
--                                   trazabilidad completa de quién rechazó qué.
--  3. prospect_submissions        — prospectos de arrendatario propuestos por el agente.
--  4. agent_bank_accounts         — CLABEs interbancarias configuradas por el agente (con
--                                   resultado de la validación Banxico).
--  5. agent_commission_invoices   — "facturas" de comisión generadas cuando se firma un
--                                   contrato con lease; owner paga SPEI, se valida y se
--                                   asienta en contabilidad.
--  6. maintenance_ticket columnas nuevas — gate de autorización del dueño antes de
--                                   despachar al proveedor + elección explícita del
--                                   proveedor + 15% de descuento cuando es de plataforma.
--  7. vacancy columnas nuevas     — cadena activa, índice de prioridad y timestamps de
--                                   ciclo de vida (fotos subidas, contrato firmado).
--
--  Diseño defensivo:
--  • Todas las FKs usan ON DELETE SET NULL en lugar de CASCADE para no arrastrar el borrado
--    de un user hacia sus vacancias históricas (el OwnerCascadeDeletionService tombstonea
--    staff/provider users, y al hacerlo los links quedan con provider_user_id apuntando a
--    un user tombstoneado, nunca null).
--  • Los status son VARCHAR para no atarnos a un enum rígido (los enums se validan en
--    el código Java; la BD acepta estados futuros sin migración).
--  • Todos los JSON van como JSONB para poder indexar por claves si en el futuro hace
--    falta (ej. filtrar prospectos por teléfono).
-- =====================================================================================

-- ─── 1) owner_agent_priorities ───────────────────────────────────────────────────────
-- Un owner puede tener varios agentes vinculados (via platform_provider_assignments).
-- Esta tabla materializa el ORDEN en que deben ser notificados. Se separa por "flow"
-- porque el dueño podría querer a Juan primero para mantenimiento pero a María primero
-- para captación inmobiliaria.
CREATE TABLE IF NOT EXISTS owner_agent_priorities (
    id                 VARCHAR(64)  PRIMARY KEY,
    owner_id           VARCHAR(64)  NOT NULL,
    flow_type          VARCHAR(16)  NOT NULL,  -- MAINTENANCE | VACANCY
    agent_user_id      VARCHAR(64)  NOT NULL,
    priority_order     INTEGER      NOT NULL,  -- 1 = primero en la cadena
    created_at         TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at         TIMESTAMP    NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_owner_agent_priority UNIQUE (owner_id, flow_type, agent_user_id),
    CONSTRAINT fk_priorities_owner    FOREIGN KEY (owner_id)      REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT fk_priorities_agent    FOREIGN KEY (agent_user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT ck_priorities_flow     CHECK (flow_type IN ('MAINTENANCE', 'VACANCY'))
);

CREATE INDEX IF NOT EXISTS idx_priorities_owner_flow_order
    ON owner_agent_priorities(owner_id, flow_type, priority_order);

-- ─── 2) agent_notification_chain ─────────────────────────────────────────────────────
-- Una fila por cada intento de notificar a un agente. La cadena activa es la que tiene
-- decision = 'PENDING' más reciente (dentro de la misma resource_id); al rechazar o
-- caducar, se crea la siguiente fila con priority_order +1.
CREATE TABLE IF NOT EXISTS agent_notification_chain (
    id                 VARCHAR(64)  PRIMARY KEY,
    flow_type          VARCHAR(16)  NOT NULL,  -- MAINTENANCE | VACANCY
    resource_type      VARCHAR(32)  NOT NULL,  -- MAINTENANCE_TICKET | VACANCY
    resource_id        VARCHAR(64)  NOT NULL,  -- ticket.id o vacancy.id
    owner_id           VARCHAR(64)  NOT NULL,
    agent_user_id      VARCHAR(64)  NOT NULL,
    priority_order     INTEGER      NOT NULL,
    notified_at        TIMESTAMP    NOT NULL DEFAULT NOW(),
    expires_at         TIMESTAMP    NOT NULL,  -- notified_at + 72h (configurable por ADMIN)
    decision           VARCHAR(24)  NOT NULL DEFAULT 'PENDING',
                       -- PENDING | ACCEPTED | REJECTED | AUTO_REJECTED_TIMEOUT | SUPERSEDED
    responded_at       TIMESTAMP,
    reason             TEXT,                   -- motivo que el agente da al rechazar
    CONSTRAINT fk_chain_owner FOREIGN KEY (owner_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT fk_chain_agent FOREIGN KEY (agent_user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT ck_chain_flow  CHECK (flow_type IN ('MAINTENANCE', 'VACANCY'))
);

CREATE INDEX IF NOT EXISTS idx_chain_resource
    ON agent_notification_chain(resource_type, resource_id, priority_order);

CREATE INDEX IF NOT EXISTS idx_chain_agent_pending
    ON agent_notification_chain(agent_user_id, decision);

-- Necesario para el scheduler que busca PENDING expirados.
CREATE INDEX IF NOT EXISTS idx_chain_expires_pending
    ON agent_notification_chain(expires_at) WHERE decision = 'PENDING';

-- ─── 3) prospect_submissions ─────────────────────────────────────────────────────────
-- El agente inmobiliario propone un prospecto al owner. Owner ACEPTA → flujo de firma.
-- Owner RECHAZA → vuelve a PENDING_RENT sin penalización.
CREATE TABLE IF NOT EXISTS prospect_submissions (
    id                 VARCHAR(64)  PRIMARY KEY,
    vacancy_id         VARCHAR(64)  NOT NULL,
    property_id        VARCHAR(64)  NOT NULL,
    owner_id           VARCHAR(64)  NOT NULL,
    agent_user_id      VARCHAR(64)  NOT NULL,
    prospect_name      VARCHAR(255) NOT NULL,
    prospect_phone     VARCHAR(32),
    prospect_email     VARCHAR(255),
    notes              TEXT,
    submitted_at       TIMESTAMP    NOT NULL DEFAULT NOW(),
    owner_decision     VARCHAR(16)  NOT NULL DEFAULT 'PENDING',  -- PENDING|ACCEPTED|REJECTED
    decided_at         TIMESTAMP,
    decided_by         VARCHAR(64),
    rejection_reason   TEXT,
    last_reminder_at   TIMESTAMP,                                -- recordatorio cada 24h
    CONSTRAINT fk_prospect_vacancy FOREIGN KEY (vacancy_id) REFERENCES vacancies(id) ON DELETE CASCADE,
    CONSTRAINT fk_prospect_agent   FOREIGN KEY (agent_user_id) REFERENCES users(id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_prospect_vacancy ON prospect_submissions(vacancy_id);
CREATE INDEX IF NOT EXISTS idx_prospect_owner_pending
    ON prospect_submissions(owner_id, owner_decision)
    WHERE owner_decision = 'PENDING';

-- ─── 4) agent_bank_accounts ──────────────────────────────────────────────────────────
-- CLABE interbancaria que el agente (REAL_ESTATE_AGENT o MAINTENANCE_PROVIDER) configura
-- en su panel para recibir pagos del owner. Validación Banxico vive en el code (3 intentos).
CREATE TABLE IF NOT EXISTS agent_bank_accounts (
    id                     VARCHAR(64)  PRIMARY KEY,
    agent_user_id          VARCHAR(64)  NOT NULL UNIQUE,
    clabe                  VARCHAR(18)  NOT NULL,
    bank_name              VARCHAR(128),
    account_holder         VARCHAR(255),
    validation_status      VARCHAR(16)  NOT NULL DEFAULT 'PENDING', -- PENDING|VALIDATED|FAILED
    validation_attempts    INTEGER      NOT NULL DEFAULT 0,
    last_validation_error  TEXT,
    validated_at           TIMESTAMP,
    created_at             TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at             TIMESTAMP    NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_agent_bank_account FOREIGN KEY (agent_user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT ck_agent_bank_clabe_len CHECK (LENGTH(clabe) = 18)
);

-- ─── 5) agent_commission_invoices ────────────────────────────────────────────────────
-- Factura de comisión owner → agente inmobiliario al firmar un contrato.
-- 3% de (renta_mensual × meses_contrato) para ambos tipos (plataforma y privado).
CREATE TABLE IF NOT EXISTS agent_commission_invoices (
    id                     VARCHAR(64)  PRIMARY KEY,
    owner_id               VARCHAR(64)  NOT NULL,
    agent_user_id          VARCHAR(64)  NOT NULL,
    agent_source           VARCHAR(16)  NOT NULL,  -- PLATFORM | PRIVATE
    lease_id               VARCHAR(64),            -- null si aún no se creó el lease
    property_id            VARCHAR(64)  NOT NULL,
    vacancy_id             VARCHAR(64),
    monthly_rent           NUMERIC(14,2) NOT NULL,
    contract_months        INTEGER      NOT NULL,
    commission_pct         NUMERIC(6,4) NOT NULL,  -- ej. 0.0300 = 3%
    commission_amount      NUMERIC(14,2) NOT NULL, -- redondeado a 2 decimales
    status                 VARCHAR(24)  NOT NULL DEFAULT 'PENDING',
                           -- PENDING | AWAITING_SPEI | PENDING_MANUAL_CONFIRM | PAID | VOIDED
    spei_proof_file_id     VARCHAR(64),
    spei_validation_attempts INTEGER    NOT NULL DEFAULT 0,
    spei_last_error        TEXT,
    paid_at                TIMESTAMP,
    created_at             TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at             TIMESTAMP    NOT NULL DEFAULT NOW(),
    expense_id             VARCHAR(64),            -- link al ExpenseEntity (contabilidad del owner)
    CONSTRAINT fk_commission_owner FOREIGN KEY (owner_id)      REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT fk_commission_agent FOREIGN KEY (agent_user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT ck_commission_source CHECK (agent_source IN ('PLATFORM', 'PRIVATE')),
    CONSTRAINT ck_commission_pct_range CHECK (commission_pct > 0 AND commission_pct <= 1)
);

CREATE INDEX IF NOT EXISTS idx_commission_owner ON agent_commission_invoices(owner_id, status);
CREATE INDEX IF NOT EXISTS idx_commission_agent ON agent_commission_invoices(agent_user_id, status);
CREATE INDEX IF NOT EXISTS idx_commission_lease ON agent_commission_invoices(lease_id);

-- ─── 6) maintenance_tickets: gate de autorización del dueño + 15% platform discount ──
-- El flujo hoy es "tenant crea ticket → routeMaintenanceTicketAfterCreation lo asigna
-- directo al provider". La fase 2 pide que el DUEÑO AUTORICE antes del dispatch; por eso
-- se agregan estas columnas y un estado nuevo AWAITING_OWNER_AUTH que el tenant ve
-- primero. Se deja compatibilidad con el flujo legado: si awaiting_owner_auth = false el
-- ticket sigue la ruta antigua (no se rompen tickets existentes).
ALTER TABLE maintenance_tickets
    ADD COLUMN IF NOT EXISTS awaiting_owner_auth   BOOLEAN,
    ADD COLUMN IF NOT EXISTS authorized_at         TIMESTAMP,
    ADD COLUMN IF NOT EXISTS authorized_by         VARCHAR(64),
    ADD COLUMN IF NOT EXISTS owner_chosen_provider_id VARCHAR(64),
    ADD COLUMN IF NOT EXISTS rejection_reason      TEXT,
    ADD COLUMN IF NOT EXISTS platform_discount_pct NUMERIC(6,4),  -- 0.15 al aprobar quote de PLATFORM
    ADD COLUMN IF NOT EXISTS platform_discount_amount NUMERIC(14,2),
    ADD COLUMN IF NOT EXISTS photo_file_ids        JSONB;          -- array de PropertyFileEntity.id subidos por el tenant

-- Backfill: los tickets existentes mantienen awaiting_owner_auth = false para no forzar
-- autorización retroactiva en tickets ya despachados.
UPDATE maintenance_tickets
   SET awaiting_owner_auth = FALSE
 WHERE awaiting_owner_auth IS NULL;

-- ─── 7) vacancies: cadena activa, fotos, índice de prioridad ─────────────────────────
ALTER TABLE vacancies
    ADD COLUMN IF NOT EXISTS chain_state          VARCHAR(32),
    -- AWAITING_AGENT | AGENT_ACCEPTED | PHOTOS_UPLOADED | PROSPECT_PROPOSED |
    -- AWAITING_CONTRACT | CONTRACT_SIGNED | CLOSED | CHAIN_EXHAUSTED
    ADD COLUMN IF NOT EXISTS current_priority_order INTEGER,
    ADD COLUMN IF NOT EXISTS photos_uploaded_at   TIMESTAMP,
    ADD COLUMN IF NOT EXISTS contract_signed_at   TIMESTAMP,
    ADD COLUMN IF NOT EXISTS contract_evidence_file_id VARCHAR(64),
    ADD COLUMN IF NOT EXISTS contract_months      INTEGER,
    ADD COLUMN IF NOT EXISTS contract_monthly_rent NUMERIC(14,2),
    ADD COLUMN IF NOT EXISTS contract_deposit     NUMERIC(14,2);

-- Backfill chain_state para vacancias viejas: si ya tienen assigned_agent_id → AGENT_ACCEPTED,
-- sino → AWAITING_AGENT. CLOSED si closed_at ya está seteado.
UPDATE vacancies
   SET chain_state = CASE
       WHEN closed_at IS NOT NULL       THEN 'CLOSED'
       WHEN assigned_agent_id IS NOT NULL THEN 'AGENT_ACCEPTED'
       ELSE 'AWAITING_AGENT'
   END
 WHERE chain_state IS NULL;
