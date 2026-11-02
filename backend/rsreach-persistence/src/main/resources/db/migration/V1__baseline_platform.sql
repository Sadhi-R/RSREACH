-- RSReach Phase 0 baseline schema
-- Establishes platform housekeeping, transactional outbox, durable webhook ingestion,
-- and isolation-supporting primitives. Domain tables arrive in Phase 1+.

CREATE EXTENSION IF NOT EXISTS pgcrypto;

-- ---------------------------------------------------------------------------
-- Schema version / platform metadata
-- ---------------------------------------------------------------------------
CREATE TABLE platform_metadata (
    key         VARCHAR(128) PRIMARY KEY,
    value       TEXT         NOT NULL,
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

INSERT INTO platform_metadata (key, value)
VALUES ('schema.phase', '0'),
       ('schema.description', 'RSReach Phase 0 baseline');

-- ---------------------------------------------------------------------------
-- Transactional outbox (DB write + reliable enqueue)
-- Application code must insert business rows and outbox rows in the SAME transaction.
-- Workers claim outbox rows and publish/dispatch jobs idempotently.
-- ---------------------------------------------------------------------------
CREATE TABLE outbox_events (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    aggregate_type  VARCHAR(128) NOT NULL,
    aggregate_id    UUID         NOT NULL,
    event_type      VARCHAR(128) NOT NULL,
    payload_json    JSONB        NOT NULL,
    tenant_id       UUID,
    workspace_id    UUID,
    correlation_id  VARCHAR(128),
    status          VARCHAR(32)  NOT NULL DEFAULT 'PENDING',
    attempt_count   INTEGER      NOT NULL DEFAULT 0,
    available_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    locked_until    TIMESTAMPTZ,
    locked_by       VARCHAR(128),
    processed_at    TIMESTAMPTZ,
    last_error      TEXT,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_outbox_status CHECK (status IN ('PENDING', 'PROCESSING', 'PROCESSED', 'FAILED', 'DEAD')),
    CONSTRAINT chk_outbox_workspace_requires_tenant
        CHECK (workspace_id IS NULL OR tenant_id IS NOT NULL)
);

CREATE INDEX idx_outbox_claim
    ON outbox_events (status, available_at, created_at)
    WHERE status IN ('PENDING', 'PROCESSING');

CREATE INDEX idx_outbox_tenant
    ON outbox_events (tenant_id, created_at)
    WHERE tenant_id IS NOT NULL;

-- ---------------------------------------------------------------------------
-- Durable, idempotent provider webhook ingestion
-- Unique (provider, provider_event_id) prevents duplicate processing.
-- event_time + received_at support out-of-order detection/reconciliation.
-- ---------------------------------------------------------------------------
CREATE TABLE provider_webhook_events (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    provider            VARCHAR(64)  NOT NULL,
    provider_event_id   VARCHAR(255) NOT NULL,
    event_type          VARCHAR(128) NOT NULL,
    event_time          TIMESTAMPTZ,
    received_at         TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    tenant_id           UUID,
    workspace_id        UUID,
    signature_valid     BOOLEAN      NOT NULL DEFAULT FALSE,
    payload_json        JSONB        NOT NULL,
    payload_hash        VARCHAR(128) NOT NULL,
    processing_status   VARCHAR(32)  NOT NULL DEFAULT 'RECEIVED',
    processed_at        TIMESTAMPTZ,
    last_error          TEXT,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_provider_webhook_event UNIQUE (provider, provider_event_id),
    CONSTRAINT chk_webhook_status CHECK (
        processing_status IN ('RECEIVED', 'QUEUED', 'PROCESSED', 'IGNORED', 'FAILED', 'DEAD')
    ),
    CONSTRAINT chk_webhook_workspace_requires_tenant
        CHECK (workspace_id IS NULL OR tenant_id IS NOT NULL)
);

CREATE INDEX idx_webhook_processing
    ON provider_webhook_events (processing_status, received_at);

CREATE INDEX idx_webhook_tenant
    ON provider_webhook_events (tenant_id, received_at)
    WHERE tenant_id IS NOT NULL;

CREATE INDEX idx_webhook_event_time
    ON provider_webhook_events (provider, event_type, event_time);

-- ---------------------------------------------------------------------------
-- Integration secret metadata (ciphertext stored separately / in column later)
-- Key version supports envelope encryption rotation without rewriting domain logic.
-- ---------------------------------------------------------------------------
CREATE TABLE integration_secret_keys (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    key_alias       VARCHAR(128) NOT NULL UNIQUE,
    key_version     INTEGER      NOT NULL,
    algorithm       VARCHAR(64)  NOT NULL DEFAULT 'AES-GCM',
    status          VARCHAR(32)  NOT NULL DEFAULT 'ACTIVE',
    activated_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    retired_at      TIMESTAMPTZ,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_secret_key_status CHECK (status IN ('ACTIVE', 'RETIRED', 'COMPROMISED')),
    CONSTRAINT uq_secret_key_version UNIQUE (key_alias, key_version)
);

COMMENT ON TABLE outbox_events IS 'Transactional outbox for reliable DB-to-job processing';
COMMENT ON TABLE provider_webhook_events IS 'Idempotent provider webhook store with out-of-order support';
COMMENT ON TABLE integration_secret_keys IS 'Envelope encryption key versions for integration secrets';
