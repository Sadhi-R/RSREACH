CREATE TABLE audit_events (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    occurred_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    actor_realm     VARCHAR(32) NOT NULL,
    actor_id        UUID,
    tenant_id       UUID,
    workspace_id    UUID,
    action          VARCHAR(128) NOT NULL,
    resource_type   VARCHAR(128),
    resource_id     VARCHAR(128),
    details_json    JSONB NOT NULL DEFAULT '{}'::jsonb,
    correlation_id  VARCHAR(128),
    CONSTRAINT chk_audit_actor_realm CHECK (actor_realm IN ('customer', 'staff', 'system')),
    CONSTRAINT chk_audit_workspace_requires_tenant CHECK (workspace_id IS NULL OR tenant_id IS NOT NULL)
);

CREATE INDEX idx_audit_occurred ON audit_events (occurred_at DESC);
CREATE INDEX idx_audit_tenant ON audit_events (tenant_id, occurred_at DESC) WHERE tenant_id IS NOT NULL;
CREATE INDEX idx_audit_actor ON audit_events (actor_realm, actor_id, occurred_at DESC);

CREATE TABLE staff_access_grants (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    staff_user_id   UUID NOT NULL REFERENCES staff_users (id) ON DELETE CASCADE,
    tenant_id       UUID NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    workspace_id    UUID,
    justification   VARCHAR(1000) NOT NULL,
    status          VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    requested_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    approved_by     UUID REFERENCES staff_users (id),
    approved_at     TIMESTAMPTZ,
    expires_at      TIMESTAMPTZ NOT NULL,
    revoked_at      TIMESTAMPTZ,
    revoked_by      UUID REFERENCES staff_users (id),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_grant_status CHECK (status IN ('PENDING', 'ACTIVE', 'EXPIRED', 'REVOKED', 'DENIED')),
    CONSTRAINT chk_grant_workspace_tenant CHECK (workspace_id IS NULL OR tenant_id IS NOT NULL)
);

CREATE INDEX idx_staff_grants_staff ON staff_access_grants (staff_user_id, status, expires_at);
CREATE INDEX idx_staff_grants_tenant ON staff_access_grants (tenant_id, status, expires_at);
