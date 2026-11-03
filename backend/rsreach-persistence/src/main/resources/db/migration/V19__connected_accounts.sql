CREATE TABLE connected_accounts (
    id                    UUID PRIMARY KEY,
    tenant_id             UUID NOT NULL REFERENCES tenants (id),
    workspace_id          UUID NOT NULL,
    provider              VARCHAR(64) NOT NULL,
    ownership_mode        VARCHAR(32) NOT NULL DEFAULT 'CUSTOMER',
    display_name          VARCHAR(255) NOT NULL,
    external_account_ref  VARCHAR(255),
    status                VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    secret_name           VARCHAR(128),
    metadata_json         JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_by            UUID,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    disconnected_at       TIMESTAMPTZ,
    CONSTRAINT fk_connected_accounts_workspace
        FOREIGN KEY (tenant_id, workspace_id) REFERENCES workspaces (tenant_id, id),
    CONSTRAINT chk_connected_accounts_provider CHECK (provider IN (
        'whatsapp', 'gmail', 'microsoft_graph', 'sendgrid', 'ses', 'mailgun', 'mock'
    )),
    CONSTRAINT chk_connected_accounts_ownership CHECK (ownership_mode IN ('PLATFORM', 'CUSTOMER')),
    CONSTRAINT chk_connected_accounts_status CHECK (status IN ('ACTIVE', 'DISCONNECTED', 'ERROR'))
);

CREATE INDEX idx_connected_accounts_workspace
    ON connected_accounts (tenant_id, workspace_id, provider)
    WHERE disconnected_at IS NULL;
