-- Phase 2 M3: channel/purpose consent current state, append-only history, and suppressions.

CREATE TABLE contact_consents (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID NOT NULL,
    workspace_id    UUID NOT NULL,
    contact_id      UUID NOT NULL,
    channel_type    VARCHAR(32)  NOT NULL,
    purpose         VARCHAR(32)  NOT NULL,
    status          VARCHAR(32)  NOT NULL DEFAULT 'UNKNOWN',
    source          VARCHAR(32)  NOT NULL DEFAULT 'MANUAL',
    evidence        JSONB        NOT NULL DEFAULT '{}'::jsonb,
    captured_at     TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_by      UUID REFERENCES customer_users (id),
    CONSTRAINT fk_contact_consent_workspace
        FOREIGN KEY (tenant_id, workspace_id) REFERENCES workspaces (tenant_id, id) ON DELETE CASCADE,
    CONSTRAINT fk_contact_consent_contact_scope
        FOREIGN KEY (tenant_id, workspace_id, contact_id)
            REFERENCES contacts (tenant_id, home_workspace_id, id) ON DELETE CASCADE,
    CONSTRAINT uq_contact_consent UNIQUE (tenant_id, contact_id, channel_type, purpose),
    CONSTRAINT chk_contact_consent_channel CHECK (channel_type IN ('EMAIL', 'WHATSAPP')),
    CONSTRAINT chk_contact_consent_purpose CHECK (purpose IN ('MARKETING', 'TRANSACTIONAL', 'SERVICE')),
    CONSTRAINT chk_contact_consent_status CHECK (status IN ('OPTED_IN', 'OPTED_OUT', 'UNKNOWN')),
    CONSTRAINT chk_contact_consent_source
        CHECK (source IN ('MANUAL', 'IMPORT', 'FORM', 'API', 'PROVIDER_WEBHOOK'))
);

CREATE INDEX idx_contact_consents_contact
    ON contact_consents (tenant_id, workspace_id, contact_id);

CREATE INDEX idx_contact_consents_status
    ON contact_consents (tenant_id, workspace_id, channel_type, purpose, status);

CREATE TABLE contact_consent_events (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID NOT NULL,
    workspace_id    UUID NOT NULL,
    contact_id      UUID NOT NULL,
    channel_type    VARCHAR(32)  NOT NULL,
    purpose         VARCHAR(32)  NOT NULL,
    previous_status VARCHAR(32),
    new_status      VARCHAR(32)  NOT NULL,
    event_type      VARCHAR(32)  NOT NULL,
    source          VARCHAR(32)  NOT NULL,
    evidence        JSONB        NOT NULL DEFAULT '{}'::jsonb,
    actor_id        UUID REFERENCES customer_users (id),
    occurred_at     TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_contact_consent_event_workspace
        FOREIGN KEY (tenant_id, workspace_id) REFERENCES workspaces (tenant_id, id) ON DELETE CASCADE,
    CONSTRAINT fk_contact_consent_event_contact_scope
        FOREIGN KEY (tenant_id, workspace_id, contact_id)
            REFERENCES contacts (tenant_id, home_workspace_id, id) ON DELETE CASCADE,
    CONSTRAINT chk_contact_consent_event_channel CHECK (channel_type IN ('EMAIL', 'WHATSAPP')),
    CONSTRAINT chk_contact_consent_event_purpose CHECK (purpose IN ('MARKETING', 'TRANSACTIONAL', 'SERVICE')),
    CONSTRAINT chk_contact_consent_event_prev
        CHECK (previous_status IS NULL OR previous_status IN ('OPTED_IN', 'OPTED_OUT', 'UNKNOWN')),
    CONSTRAINT chk_contact_consent_event_new CHECK (new_status IN ('OPTED_IN', 'OPTED_OUT', 'UNKNOWN')),
    CONSTRAINT chk_contact_consent_event_type
        CHECK (event_type IN ('GRANTED', 'WITHDRAWN', 'RESET_UNKNOWN', 'SEEDED_UNKNOWN')),
    CONSTRAINT chk_contact_consent_event_source
        CHECK (source IN ('MANUAL', 'IMPORT', 'FORM', 'API', 'PROVIDER_WEBHOOK'))
);

CREATE INDEX idx_contact_consent_events_contact
    ON contact_consent_events (tenant_id, workspace_id, contact_id, occurred_at DESC);

CREATE TABLE contact_suppressions (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id         UUID NOT NULL,
    workspace_id      UUID NOT NULL,
    channel_type      VARCHAR(32)  NOT NULL,
    value_raw         VARCHAR(320) NOT NULL,
    value_normalized  VARCHAR(320) NOT NULL,
    reason            VARCHAR(255) NOT NULL,
    created_by        UUID NOT NULL REFERENCES customer_users (id),
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    released_at       TIMESTAMPTZ,
    released_by       UUID REFERENCES customer_users (id),
    CONSTRAINT fk_contact_suppression_workspace
        FOREIGN KEY (tenant_id, workspace_id) REFERENCES workspaces (tenant_id, id) ON DELETE CASCADE,
    CONSTRAINT chk_contact_suppression_channel CHECK (channel_type IN ('EMAIL', 'WHATSAPP')),
    CONSTRAINT chk_contact_suppression_reason CHECK (char_length(trim(reason)) > 0)
);

-- One active suppression per normalized address/channel in a workspace.
CREATE UNIQUE INDEX uq_contact_suppression_active
    ON contact_suppressions (tenant_id, workspace_id, channel_type, value_normalized)
    WHERE released_at IS NULL;

CREATE INDEX idx_contact_suppressions_ws
    ON contact_suppressions (tenant_id, workspace_id, channel_type, created_at DESC);
