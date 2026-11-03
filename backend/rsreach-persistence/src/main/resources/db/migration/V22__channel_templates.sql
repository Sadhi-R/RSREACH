CREATE TABLE channel_templates (
    id                   UUID PRIMARY KEY,
    tenant_id            UUID NOT NULL REFERENCES tenants (id),
    workspace_id         UUID NOT NULL,
    connected_account_id UUID REFERENCES connected_accounts (id),
    channel              VARCHAR(32) NOT NULL,
    name                 VARCHAR(255) NOT NULL,
    language             VARCHAR(16) NOT NULL DEFAULT 'en',
    category             VARCHAR(64),
    status               VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    provider_template_id VARCHAR(255),
    body_preview         VARCHAR(1024),
    metadata_json        JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_channel_templates_workspace
        FOREIGN KEY (tenant_id, workspace_id) REFERENCES workspaces (tenant_id, id),
    CONSTRAINT chk_channel_templates_channel CHECK (channel IN ('WHATSAPP', 'EMAIL')),
    CONSTRAINT chk_channel_templates_status CHECK (status IN (
        'PENDING', 'APPROVED', 'REJECTED', 'PAUSED', 'DISABLED'
    ))
);

CREATE UNIQUE INDEX uq_channel_templates_name
    ON channel_templates (tenant_id, workspace_id, channel, name, language);
