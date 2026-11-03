-- Phase 2 M2: workspace-owned static and dynamic contact groups.

-- Enable composite FKs from group members onto contacts without editing V11.
CREATE UNIQUE INDEX IF NOT EXISTS uq_contacts_tenant_id
    ON contacts (tenant_id, id);

CREATE UNIQUE INDEX IF NOT EXISTS uq_contacts_tenant_workspace_id
    ON contacts (tenant_id, home_workspace_id, id);

CREATE TABLE contact_groups (
    id                       UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id                UUID NOT NULL,
    workspace_id             UUID NOT NULL,
    name                     VARCHAR(255) NOT NULL,
    slug                     VARCHAR(128) NOT NULL,
    group_type               VARCHAR(32)  NOT NULL,
    filter_definition        JSONB,
    status                   VARCHAR(32)  NOT NULL DEFAULT 'ACTIVE',
    materialization_status   VARCHAR(32)  NOT NULL DEFAULT 'IDLE',
    materialization_error    TEXT,
    materialized_at          TIMESTAMPTZ,
    created_by               UUID NOT NULL REFERENCES customer_users (id),
    updated_by               UUID REFERENCES customer_users (id),
    created_at               TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at               TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    archived_at              TIMESTAMPTZ,
    CONSTRAINT fk_contact_group_workspace
        FOREIGN KEY (tenant_id, workspace_id) REFERENCES workspaces (tenant_id, id) ON DELETE CASCADE,
    CONSTRAINT uq_contact_group_slug UNIQUE (tenant_id, workspace_id, slug),
    CONSTRAINT chk_contact_group_type CHECK (group_type IN ('STATIC', 'DYNAMIC')),
    CONSTRAINT chk_contact_group_status CHECK (status IN ('ACTIVE', 'ARCHIVED')),
    CONSTRAINT chk_contact_group_materialization_status
        CHECK (materialization_status IN ('IDLE', 'PENDING', 'RUNNING', 'FAILED')),
    CONSTRAINT chk_contact_group_name CHECK (char_length(trim(name)) > 0),
    CONSTRAINT chk_contact_group_filter_by_type CHECK (
        (group_type = 'STATIC' AND filter_definition IS NULL)
        OR (group_type = 'DYNAMIC' AND filter_definition IS NOT NULL)
    )
);

CREATE INDEX idx_contact_groups_ws_status_updated
    ON contact_groups (tenant_id, workspace_id, status, updated_at DESC);

CREATE INDEX idx_contact_groups_dynamic_refresh
    ON contact_groups (tenant_id, workspace_id, materialization_status, materialized_at)
    WHERE group_type = 'DYNAMIC' AND status = 'ACTIVE';

CREATE TABLE contact_group_members (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id    UUID NOT NULL,
    workspace_id UUID NOT NULL,
    group_id     UUID NOT NULL,
    contact_id   UUID NOT NULL,
    source       VARCHAR(32) NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_contact_group_member_group
        FOREIGN KEY (group_id) REFERENCES contact_groups (id) ON DELETE CASCADE,
    CONSTRAINT fk_contact_group_member_workspace
        FOREIGN KEY (tenant_id, workspace_id) REFERENCES workspaces (tenant_id, id) ON DELETE CASCADE,
    CONSTRAINT fk_contact_group_member_contact
        FOREIGN KEY (contact_id) REFERENCES contacts (id) ON DELETE CASCADE,
    CONSTRAINT fk_contact_group_member_contact_scope
        FOREIGN KEY (tenant_id, workspace_id, contact_id)
            REFERENCES contacts (tenant_id, home_workspace_id, id) ON DELETE CASCADE,
    CONSTRAINT uq_contact_group_member UNIQUE (tenant_id, workspace_id, group_id, contact_id),
    CONSTRAINT chk_contact_group_member_source CHECK (source IN ('MANUAL', 'DYNAMIC', 'IMPORT'))
);

CREATE INDEX idx_contact_group_members_group
    ON contact_group_members (tenant_id, workspace_id, group_id, created_at DESC);

CREATE INDEX idx_contact_group_members_contact
    ON contact_group_members (tenant_id, workspace_id, contact_id);
