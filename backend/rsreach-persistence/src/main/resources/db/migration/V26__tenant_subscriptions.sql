CREATE TABLE tenant_subscription (
    id                    UUID PRIMARY KEY,
    tenant_id             UUID NOT NULL REFERENCES tenants (id),
    plan_version_id       UUID NOT NULL REFERENCES plan_version (id),
    status                VARCHAR(32) NOT NULL,
    billing_period        VARCHAR(16) NOT NULL,
    trial_ends_at         TIMESTAMPTZ,
    current_period_start  TIMESTAMPTZ NOT NULL,
    current_period_end    TIMESTAMPTZ NOT NULL,
    grace_ends_at         TIMESTAMPTZ,
    cancel_at_period_end  BOOLEAN NOT NULL DEFAULT FALSE,
    cancelled_at          TIMESTAMPTZ,
    suspended_at          TIMESTAMPTZ,
    razorpay_customer_id  VARCHAR(128),
    created_at            TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_tenant_subscription UNIQUE (tenant_id),
    CONSTRAINT chk_subscription_status CHECK (status IN (
        'TRIALING', 'ACTIVE', 'PAST_DUE', 'SUSPENDED', 'CANCELLED', 'EXPIRED'
    )),
    CONSTRAINT chk_subscription_period CHECK (billing_period IN ('MONTHLY', 'ANNUAL'))
);

CREATE INDEX idx_tenant_subscription_status ON tenant_subscription (status, current_period_end);

CREATE TABLE subscription_event (
    id               UUID PRIMARY KEY,
    tenant_id        UUID NOT NULL REFERENCES tenants (id),
    subscription_id  UUID NOT NULL REFERENCES tenant_subscription (id),
    event_type       VARCHAR(64) NOT NULL,
    from_status      VARCHAR(32),
    to_status        VARCHAR(32),
    details_json     JSONB NOT NULL DEFAULT '{}'::jsonb,
    actor_realm      VARCHAR(32),
    actor_id         UUID,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_subscription_event_tenant ON subscription_event (tenant_id, created_at DESC);
