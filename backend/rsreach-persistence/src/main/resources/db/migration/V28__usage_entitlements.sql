CREATE TABLE usage_event (
    id               UUID PRIMARY KEY,
    tenant_id        UUID NOT NULL REFERENCES tenants (id),
    workspace_id     UUID,
    meter_code       VARCHAR(64) NOT NULL,
    quantity         BIGINT NOT NULL DEFAULT 1,
    idempotency_key  VARCHAR(255) NOT NULL,
    resource_type    VARCHAR(64),
    resource_id      VARCHAR(128),
    occurred_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_usage_event_idempotency UNIQUE (tenant_id, idempotency_key),
    CONSTRAINT chk_usage_quantity CHECK (quantity > 0)
);

CREATE INDEX idx_usage_event_meter ON usage_event (tenant_id, meter_code, occurred_at DESC);

CREATE TABLE usage_period_aggregate (
    id               UUID PRIMARY KEY,
    tenant_id        UUID NOT NULL REFERENCES tenants (id),
    meter_code       VARCHAR(64) NOT NULL,
    period_start     TIMESTAMPTZ NOT NULL,
    period_end       TIMESTAMPTZ NOT NULL,
    used_quantity    BIGINT NOT NULL DEFAULT 0,
    limit_value      BIGINT,
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_usage_period UNIQUE (tenant_id, meter_code, period_start),
    CONSTRAINT chk_usage_used CHECK (used_quantity >= 0)
);

CREATE TABLE entitlement_override (
    id               UUID PRIMARY KEY,
    tenant_id        UUID NOT NULL REFERENCES tenants (id),
    meter_code       VARCHAR(64) NOT NULL,
    limit_value      BIGINT,
    reason           VARCHAR(255) NOT NULL,
    expires_at       TIMESTAMPTZ,
    created_by_staff UUID,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    revoked_at       TIMESTAMPTZ
);

CREATE INDEX idx_entitlement_override_active
    ON entitlement_override (tenant_id, meter_code)
    WHERE revoked_at IS NULL;
