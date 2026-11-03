-- Phase 2 M1: workspace-owned contacts, channels, and custom field definitions.
-- Ownership is home_workspace_id; sharing remains via resource_shares (later milestones).

CREATE TABLE contact_custom_field_defs (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID NOT NULL,
    workspace_id    UUID NOT NULL,
    field_key       VARCHAR(64)  NOT NULL,
    label           VARCHAR(128) NOT NULL,
    data_type       VARCHAR(32)  NOT NULL,
    required        BOOLEAN      NOT NULL DEFAULT FALSE,
    active          BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_contact_field_def_workspace
        FOREIGN KEY (tenant_id, workspace_id) REFERENCES workspaces (tenant_id, id) ON DELETE CASCADE,
    CONSTRAINT uq_contact_field_def_key UNIQUE (tenant_id, workspace_id, field_key),
    CONSTRAINT chk_contact_field_def_type CHECK (data_type IN ('STRING', 'NUMBER', 'DATE', 'BOOLEAN')),
    CONSTRAINT chk_contact_field_def_key CHECK (field_key ~ '^[a-z][a-z0-9_]{0,63}$')
);

CREATE INDEX idx_contact_field_defs_ws
    ON contact_custom_field_defs (tenant_id, workspace_id, active);

CREATE TABLE contacts (
    id                         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id                  UUID NOT NULL,
    home_workspace_id          UUID NOT NULL,
    display_name               VARCHAR(255) NOT NULL,
    status                     VARCHAR(32)  NOT NULL DEFAULT 'ACTIVE',
    primary_email_normalized   VARCHAR(320),
    primary_phone_e164         VARCHAR(32),
    custom_fields              JSONB        NOT NULL DEFAULT '{}'::jsonb,
    linked_customer_user_id    UUID REFERENCES customer_users (id),
    created_by                 UUID NOT NULL REFERENCES customer_users (id),
    updated_by                 UUID REFERENCES customer_users (id),
    created_at                 TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at                 TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    archived_at                TIMESTAMPTZ,
    CONSTRAINT fk_contact_home_workspace
        FOREIGN KEY (tenant_id, home_workspace_id) REFERENCES workspaces (tenant_id, id) ON DELETE CASCADE,
    CONSTRAINT chk_contact_status CHECK (status IN ('ACTIVE', 'ARCHIVED')),
    CONSTRAINT chk_contact_display_name CHECK (char_length(trim(display_name)) > 0)
);

CREATE INDEX idx_contacts_ws_status_updated
    ON contacts (tenant_id, home_workspace_id, status, updated_at DESC);

CREATE INDEX idx_contacts_ws_email
    ON contacts (tenant_id, home_workspace_id, primary_email_normalized)
    WHERE primary_email_normalized IS NOT NULL;

CREATE INDEX idx_contacts_ws_phone
    ON contacts (tenant_id, home_workspace_id, primary_phone_e164)
    WHERE primary_phone_e164 IS NOT NULL;

CREATE TABLE contact_channels (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id         UUID NOT NULL,
    workspace_id      UUID NOT NULL,
    contact_id        UUID NOT NULL REFERENCES contacts (id) ON DELETE CASCADE,
    channel_type      VARCHAR(32)  NOT NULL,
    value_raw         VARCHAR(320) NOT NULL,
    value_normalized  VARCHAR(320) NOT NULL,
    is_primary        BOOLEAN      NOT NULL DEFAULT FALSE,
    label             VARCHAR(64),
    status            VARCHAR(32)  NOT NULL DEFAULT 'ACTIVE',
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_contact_channel_workspace
        FOREIGN KEY (tenant_id, workspace_id) REFERENCES workspaces (tenant_id, id) ON DELETE CASCADE,
    CONSTRAINT chk_contact_channel_type CHECK (channel_type IN ('EMAIL', 'PHONE')),
    CONSTRAINT chk_contact_channel_status CHECK (status IN ('ACTIVE', 'INVALID', 'ARCHIVED'))
);

CREATE INDEX idx_contact_channels_contact
    ON contact_channels (tenant_id, workspace_id, contact_id);

-- One active normalized identifier per workspace (prevents unintended duplicates).
CREATE UNIQUE INDEX uq_contact_channel_active_normalized
    ON contact_channels (tenant_id, workspace_id, channel_type, value_normalized)
    WHERE status = 'ACTIVE';

-- At most one primary channel per type on a contact among active rows.
CREATE UNIQUE INDEX uq_contact_channel_primary
    ON contact_channels (contact_id, channel_type)
    WHERE is_primary = TRUE AND status = 'ACTIVE';
