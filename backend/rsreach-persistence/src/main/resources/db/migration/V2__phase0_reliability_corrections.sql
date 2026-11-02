-- Phase 0 verification corrections:
-- 1) Webhook uniqueness scoped to provider + connected account
-- 2) Secret key versions allow rotation (multiple versions per alias)
-- 3) Ciphertext store retains key_version for decrypt-after-rotation
-- 4) Outbox/webhook retry durability columns

-- ---------------------------------------------------------------------------
-- Webhooks: scope uniqueness to connected account
-- ---------------------------------------------------------------------------
ALTER TABLE provider_webhook_events
    ADD COLUMN IF NOT EXISTS connected_account_ref VARCHAR(255) NOT NULL DEFAULT 'platform';

ALTER TABLE provider_webhook_events
    ADD COLUMN IF NOT EXISTS attempt_count INTEGER NOT NULL DEFAULT 0;

ALTER TABLE provider_webhook_events
    ADD COLUMN IF NOT EXISTS available_at TIMESTAMPTZ NOT NULL DEFAULT NOW();

ALTER TABLE provider_webhook_events
    ADD COLUMN IF NOT EXISTS locked_until TIMESTAMPTZ;

ALTER TABLE provider_webhook_events
    ADD COLUMN IF NOT EXISTS locked_by VARCHAR(128);

ALTER TABLE provider_webhook_events
    DROP CONSTRAINT IF EXISTS uq_provider_webhook_event;

ALTER TABLE provider_webhook_events
    ADD CONSTRAINT uq_provider_webhook_event
        UNIQUE (provider, connected_account_ref, provider_event_id);

CREATE INDEX IF NOT EXISTS idx_webhook_claim
    ON provider_webhook_events (processing_status, available_at, received_at)
    WHERE processing_status IN ('RECEIVED', 'QUEUED', 'FAILED');

-- ---------------------------------------------------------------------------
-- Outbox: explicit max attempts for safe retry / dead-letter transition
-- ---------------------------------------------------------------------------
ALTER TABLE outbox_events
    ADD COLUMN IF NOT EXISTS max_attempts INTEGER NOT NULL DEFAULT 10;

ALTER TABLE outbox_events
    ADD COLUMN IF NOT EXISTS idempotency_key VARCHAR(128);

CREATE UNIQUE INDEX IF NOT EXISTS uq_outbox_tenant_idempotency
    ON outbox_events (tenant_id, idempotency_key)
    WHERE idempotency_key IS NOT NULL AND tenant_id IS NOT NULL;

CREATE UNIQUE INDEX IF NOT EXISTS uq_outbox_global_idempotency
    ON outbox_events (idempotency_key)
    WHERE idempotency_key IS NOT NULL AND tenant_id IS NULL;

-- ---------------------------------------------------------------------------
-- Secret keys: allow multiple versions per alias; only one ACTIVE
-- ---------------------------------------------------------------------------
ALTER TABLE integration_secret_keys
    DROP CONSTRAINT IF EXISTS integration_secret_keys_key_alias_key;

CREATE UNIQUE INDEX IF NOT EXISTS uq_secret_key_one_active
    ON integration_secret_keys (key_alias)
    WHERE status = 'ACTIVE';

-- Ciphertext records reference the key version used at encryption time.
CREATE TABLE IF NOT EXISTS integration_secrets (
    id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id            UUID,
    workspace_id         UUID,
    secret_name          VARCHAR(128) NOT NULL,
    key_alias            VARCHAR(128) NOT NULL,
    key_version          INTEGER      NOT NULL,
    ciphertext           BYTEA        NOT NULL,
    ciphertext_nonce     BYTEA        NOT NULL,
    encryption_algorithm VARCHAR(64)  NOT NULL DEFAULT 'AES-GCM',
    created_at           TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at           TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    rotated_at           TIMESTAMPTZ,
    CONSTRAINT chk_integration_secret_workspace_requires_tenant
        CHECK (workspace_id IS NULL OR tenant_id IS NOT NULL),
    CONSTRAINT fk_integration_secret_key_version
        FOREIGN KEY (key_alias, key_version)
        REFERENCES integration_secret_keys (key_alias, key_version)
);

-- Expression unique index so NULL tenant/workspace scopes still collide correctly.
CREATE UNIQUE INDEX IF NOT EXISTS uq_integration_secret_scope_name
    ON integration_secrets (
        COALESCE(tenant_id, '00000000-0000-0000-0000-000000000000'::uuid),
        COALESCE(workspace_id, '00000000-0000-0000-0000-000000000000'::uuid),
        secret_name
    );

CREATE INDEX IF NOT EXISTS idx_integration_secrets_key
    ON integration_secrets (key_alias, key_version);

COMMENT ON COLUMN provider_webhook_events.connected_account_ref IS
    'Provider-connected account identity; uniqueness is (provider, connected_account_ref, provider_event_id)';
COMMENT ON TABLE integration_secrets IS
    'Encrypted integration credentials; key_version enables decrypt after rotation';
