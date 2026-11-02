CREATE TABLE resource_shares (
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id             UUID NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    source_workspace_id   UUID NOT NULL,
    target_workspace_id   UUID NOT NULL,
    resource_type         VARCHAR(128) NOT NULL,
    resource_id           UUID NOT NULL,
    permission            VARCHAR(64) NOT NULL DEFAULT 'READ',
    created_by            UUID NOT NULL REFERENCES customer_users (id),
    created_at            TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    revoked_at            TIMESTAMPTZ,
    CONSTRAINT fk_share_source_ws
        FOREIGN KEY (tenant_id, source_workspace_id) REFERENCES workspaces (tenant_id, id) ON DELETE CASCADE,
    CONSTRAINT fk_share_target_ws
        FOREIGN KEY (tenant_id, target_workspace_id) REFERENCES workspaces (tenant_id, id) ON DELETE CASCADE,
    CONSTRAINT chk_share_different_ws CHECK (source_workspace_id <> target_workspace_id),
    CONSTRAINT uq_resource_share UNIQUE (tenant_id, source_workspace_id, target_workspace_id, resource_type, resource_id)
);

CREATE INDEX idx_resource_shares_target ON resource_shares (tenant_id, target_workspace_id, resource_type);
