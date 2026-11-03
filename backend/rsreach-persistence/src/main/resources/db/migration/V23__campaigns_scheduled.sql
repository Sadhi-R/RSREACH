CREATE TABLE campaigns (
    id                   UUID PRIMARY KEY,
    tenant_id            UUID NOT NULL REFERENCES tenants (id),
    workspace_id         UUID NOT NULL,
    name                 VARCHAR(255) NOT NULL,
    channel              VARCHAR(32) NOT NULL,
    purpose              VARCHAR(32) NOT NULL DEFAULT 'MARKETING',
    status               VARCHAR(32) NOT NULL DEFAULT 'DRAFT',
    connected_account_id UUID REFERENCES connected_accounts (id),
    audience_group_id    UUID,
    template_id          UUID REFERENCES channel_templates (id),
    body_text            TEXT,
    scheduled_at         TIMESTAMPTZ,
    timezone             VARCHAR(64) DEFAULT 'UTC',
    started_at           TIMESTAMPTZ,
    completed_at         TIMESTAMPTZ,
    paused_at            TIMESTAMPTZ,
    cancelled_at         TIMESTAMPTZ,
    created_by           UUID,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_campaigns_workspace
        FOREIGN KEY (tenant_id, workspace_id) REFERENCES workspaces (tenant_id, id),
    CONSTRAINT chk_campaigns_channel CHECK (channel IN ('WHATSAPP', 'EMAIL')),
    CONSTRAINT chk_campaigns_purpose CHECK (purpose IN ('MARKETING', 'TRANSACTIONAL', 'SERVICE')),
    CONSTRAINT chk_campaigns_status CHECK (status IN (
        'DRAFT', 'SCHEDULED', 'RUNNING', 'PAUSED', 'COMPLETED', 'CANCELLED', 'FAILED'
    ))
);

CREATE INDEX idx_campaigns_workspace ON campaigns (tenant_id, workspace_id, updated_at DESC);

CREATE TABLE campaign_recipients (
    id              UUID PRIMARY KEY,
    tenant_id       UUID NOT NULL REFERENCES tenants (id),
    workspace_id    UUID NOT NULL,
    campaign_id     UUID NOT NULL REFERENCES campaigns (id),
    contact_id      UUID NOT NULL REFERENCES contacts (id),
    address         VARCHAR(320) NOT NULL,
    address_normalized VARCHAR(320) NOT NULL,
    status          VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    skip_reason     VARCHAR(64),
    message_id      UUID REFERENCES messages (id),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_campaign_recipients_workspace
        FOREIGN KEY (tenant_id, workspace_id) REFERENCES workspaces (tenant_id, id),
    CONSTRAINT chk_campaign_recipients_status CHECK (status IN (
        'PENDING', 'QUEUED', 'SENT', 'SKIPPED', 'FAILED'
    ))
);

CREATE UNIQUE INDEX uq_campaign_recipients_address
    ON campaign_recipients (campaign_id, address_normalized);

CREATE TABLE scheduled_messages (
    id                   UUID PRIMARY KEY,
    tenant_id            UUID NOT NULL REFERENCES tenants (id),
    workspace_id         UUID NOT NULL,
    conversation_id      UUID REFERENCES conversations (id),
    campaign_id          UUID REFERENCES campaigns (id),
    channel              VARCHAR(32) NOT NULL,
    due_at               TIMESTAMPTZ NOT NULL,
    timezone             VARCHAR(64) NOT NULL DEFAULT 'UTC',
    status               VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    payload_json         JSONB NOT NULL DEFAULT '{}'::jsonb,
    fired_at             TIMESTAMPTZ,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_scheduled_messages_workspace
        FOREIGN KEY (tenant_id, workspace_id) REFERENCES workspaces (tenant_id, id),
    CONSTRAINT chk_scheduled_messages_status CHECK (status IN (
        'PENDING', 'FIRED', 'CANCELLED', 'FAILED'
    ))
);

CREATE INDEX idx_scheduled_messages_due
    ON scheduled_messages (status, due_at)
    WHERE status = 'PENDING';
