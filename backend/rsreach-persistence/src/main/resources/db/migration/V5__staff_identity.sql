CREATE TABLE staff_users (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email            VARCHAR(320) NOT NULL,
    email_normalized VARCHAR(320) NOT NULL,
    display_name     VARCHAR(255) NOT NULL,
    status           VARCHAR(32)  NOT NULL DEFAULT 'ACTIVE',
    mfa_required     BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_staff_email_normalized UNIQUE (email_normalized),
    CONSTRAINT chk_staff_status CHECK (status IN ('ACTIVE', 'DISABLED', 'PENDING'))
);

CREATE TABLE staff_credentials (
    user_id         UUID PRIMARY KEY REFERENCES staff_users (id) ON DELETE CASCADE,
    password_hash   VARCHAR(255) NOT NULL,
    password_algo   VARCHAR(32)  NOT NULL DEFAULT 'BCRYPT',
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE TABLE staff_refresh_tokens (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id         UUID NOT NULL REFERENCES staff_users (id) ON DELETE CASCADE,
    token_hash      VARCHAR(128) NOT NULL UNIQUE,
    family_id       UUID NOT NULL,
    expires_at      TIMESTAMPTZ  NOT NULL,
    revoked_at      TIMESTAMPTZ,
    replaced_by     UUID,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    user_agent      VARCHAR(512),
    ip_address      VARCHAR(64)
);

CREATE INDEX idx_staff_refresh_user ON staff_refresh_tokens (user_id, created_at DESC);
CREATE INDEX idx_staff_refresh_family ON staff_refresh_tokens (family_id);

CREATE TABLE staff_mfa_factors (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id         UUID NOT NULL REFERENCES staff_users (id) ON DELETE CASCADE,
    factor_type     VARCHAR(32)  NOT NULL DEFAULT 'TOTP',
    secret_ref      VARCHAR(128) NOT NULL,
    enabled         BOOLEAN      NOT NULL DEFAULT FALSE,
    verified_at     TIMESTAMPTZ,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_staff_mfa_type CHECK (factor_type IN ('TOTP')),
    CONSTRAINT uq_staff_mfa_user_type UNIQUE (user_id, factor_type)
);

CREATE TABLE staff_role_bindings (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    staff_user_id   UUID NOT NULL REFERENCES staff_users (id) ON DELETE CASCADE,
    role_id         UUID NOT NULL REFERENCES roles (id),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_staff_role_binding UNIQUE (staff_user_id, role_id)
);

CREATE TABLE staff_mfa_challenges (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    staff_user_id   UUID NOT NULL REFERENCES staff_users (id) ON DELETE CASCADE,
    challenge_hash  VARCHAR(128) NOT NULL UNIQUE,
    expires_at      TIMESTAMPTZ  NOT NULL,
    consumed_at     TIMESTAMPTZ,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_staff_role_bindings_user ON staff_role_bindings (staff_user_id);
