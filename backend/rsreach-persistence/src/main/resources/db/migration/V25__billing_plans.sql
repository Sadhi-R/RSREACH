CREATE TABLE plan_catalog (
    id           UUID PRIMARY KEY,
    code         VARCHAR(64) NOT NULL UNIQUE,
    name         VARCHAR(128) NOT NULL,
    description  TEXT,
    sort_order   INT NOT NULL DEFAULT 0,
    active       BOOLEAN NOT NULL DEFAULT TRUE,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE plan_version (
    id                   UUID PRIMARY KEY,
    plan_id              UUID NOT NULL REFERENCES plan_catalog (id),
    version_number       INT NOT NULL,
    billing_period       VARCHAR(16) NOT NULL,
    price_paise          BIGINT,
    currency             CHAR(3) NOT NULL DEFAULT 'INR',
    trial_days           INT NOT NULL DEFAULT 0,
    grace_days           INT NOT NULL DEFAULT 7,
    effective_from       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_at           TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_plan_version UNIQUE (plan_id, version_number),
    CONSTRAINT chk_plan_billing_period CHECK (billing_period IN ('MONTHLY', 'ANNUAL')),
    CONSTRAINT chk_plan_price_nonneg CHECK (price_paise IS NULL OR price_paise >= 0)
);

CREATE TABLE plan_entitlement (
    id               UUID PRIMARY KEY,
    plan_version_id  UUID NOT NULL REFERENCES plan_version (id) ON DELETE CASCADE,
    meter_code       VARCHAR(64) NOT NULL,
    limit_value      BIGINT,
    soft_limit_pct   INT NOT NULL DEFAULT 80,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_plan_entitlement UNIQUE (plan_version_id, meter_code),
    CONSTRAINT chk_plan_entitlement_soft CHECK (soft_limit_pct BETWEEN 1 AND 100)
);

-- Provisional Free quotas for CI/dev only. Paid plan prices intentionally NULL (D2).
INSERT INTO plan_catalog (id, code, name, description, sort_order) VALUES
 ( 'a1000000-0000-4000-8000-000000000001', 'FREE', 'Free', 'Provisional free tier', 1),
 ( 'a1000000-0000-4000-8000-000000000002', 'STARTER', 'Starter', 'Starter tier (price unset)', 2),
 ( 'a1000000-0000-4000-8000-000000000003', 'PROFESSIONAL', 'Professional', 'Professional tier (price unset)', 3),
 ( 'a1000000-0000-4000-8000-000000000004', 'ENTERPRISE', 'Enterprise', 'Enterprise tier (custom)', 4);

INSERT INTO plan_version (id, plan_id, version_number, billing_period, price_paise, trial_days, grace_days) VALUES
 ( 'a2000000-0000-4000-8000-000000000001', 'a1000000-0000-4000-8000-000000000001', 1, 'MONTHLY', 0, 0, 7),
 ( 'a2000000-0000-4000-8000-000000000002', 'a1000000-0000-4000-8000-000000000002', 1, 'MONTHLY', NULL, 14, 7),
 ( 'a2000000-0000-4000-8000-000000000003', 'a1000000-0000-4000-8000-000000000003', 1, 'MONTHLY', NULL, 14, 7),
 ( 'a2000000-0000-4000-8000-000000000004', 'a1000000-0000-4000-8000-000000000004', 1, 'MONTHLY', NULL, 30, 14);

INSERT INTO plan_entitlement (id, plan_version_id, meter_code, limit_value) VALUES
 (gen_random_uuid(), 'a2000000-0000-4000-8000-000000000001', 'MEMBERS', 2),
 (gen_random_uuid(), 'a2000000-0000-4000-8000-000000000001', 'CONTACTS', 100),
 (gen_random_uuid(), 'a2000000-0000-4000-8000-000000000001', 'WHATSAPP_MESSAGES', 50),
 (gen_random_uuid(), 'a2000000-0000-4000-8000-000000000001', 'EMAIL_MESSAGES', 100),
 (gen_random_uuid(), 'a2000000-0000-4000-8000-000000000001', 'CAMPAIGNS', 1),
 (gen_random_uuid(), 'a2000000-0000-4000-8000-000000000001', 'AUTOMATIONS', 0),
 (gen_random_uuid(), 'a2000000-0000-4000-8000-000000000001', 'AUTOMATION_RUNS', 0),
 (gen_random_uuid(), 'a2000000-0000-4000-8000-000000000001', 'CONNECTED_ACCOUNTS', 1),
 (gen_random_uuid(), 'a2000000-0000-4000-8000-000000000001', 'MEDIA_BYTES', 104857600);
