CREATE TABLE tenants (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name            VARCHAR(255) NOT NULL,
    slug            VARCHAR(128) NOT NULL,
    status          VARCHAR(32)  NOT NULL DEFAULT 'PENDING',
    owner_user_id   UUID NOT NULL REFERENCES customer_users (id),
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_tenant_slug UNIQUE (slug),
    CONSTRAINT chk_tenant_status CHECK (status IN ('PENDING', 'ACTIVE', 'SUSPENDED', 'REJECTED', 'CLOSED'))
);

CREATE TABLE workspaces (
    id              UUID NOT NULL DEFAULT gen_random_uuid(),
    tenant_id       UUID NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    name            VARCHAR(255) NOT NULL,
    slug            VARCHAR(128) NOT NULL,
    status          VARCHAR(32)  NOT NULL DEFAULT 'ACTIVE',
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    PRIMARY KEY (tenant_id, id),
    CONSTRAINT uq_workspace_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT uq_workspace_tenant_slug UNIQUE (tenant_id, slug),
    CONSTRAINT chk_workspace_status CHECK (status IN ('ACTIVE', 'ARCHIVED'))
);

CREATE INDEX idx_workspaces_tenant ON workspaces (tenant_id, created_at);

CREATE TABLE tenant_memberships (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    user_id         UUID NOT NULL REFERENCES customer_users (id) ON DELETE CASCADE,
    role_id         UUID NOT NULL REFERENCES roles (id),
    status          VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_tenant_membership UNIQUE (tenant_id, user_id),
    CONSTRAINT chk_tenant_membership_status CHECK (status IN ('ACTIVE', 'DISABLED'))
);

CREATE INDEX idx_tenant_memberships_user ON tenant_memberships (user_id, tenant_id);

CREATE TABLE workspace_memberships (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID NOT NULL,
    workspace_id    UUID NOT NULL,
    user_id         UUID NOT NULL REFERENCES customer_users (id) ON DELETE CASCADE,
    role_id         UUID NOT NULL REFERENCES roles (id),
    status          VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_workspace_membership UNIQUE (tenant_id, workspace_id, user_id),
    CONSTRAINT fk_workspace_membership_workspace
        FOREIGN KEY (tenant_id, workspace_id) REFERENCES workspaces (tenant_id, id) ON DELETE CASCADE,
    CONSTRAINT chk_workspace_membership_status CHECK (status IN ('ACTIVE', 'DISABLED'))
);

CREATE INDEX idx_workspace_memberships_user ON workspace_memberships (user_id, tenant_id, workspace_id);
CREATE INDEX idx_workspace_memberships_ws ON workspace_memberships (tenant_id, workspace_id);

CREATE TABLE tenant_invitations (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    email_normalized VARCHAR(320) NOT NULL,
    role_id         UUID NOT NULL REFERENCES roles (id),
    token_hash      VARCHAR(128) NOT NULL UNIQUE,
    invited_by      UUID NOT NULL REFERENCES customer_users (id),
    expires_at      TIMESTAMPTZ NOT NULL,
    accepted_at     TIMESTAMPTZ,
    revoked_at      TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_tenant_invite_pending UNIQUE (tenant_id, email_normalized)
);
