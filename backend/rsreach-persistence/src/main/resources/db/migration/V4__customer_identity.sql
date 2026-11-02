CREATE TABLE customer_users (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email           VARCHAR(320) NOT NULL,
    email_normalized VARCHAR(320) NOT NULL,
    display_name    VARCHAR(255) NOT NULL,
    status          VARCHAR(32)  NOT NULL DEFAULT 'ACTIVE',
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_customer_email_normalized UNIQUE (email_normalized),
    CONSTRAINT chk_customer_status CHECK (status IN ('ACTIVE', 'DISABLED', 'PENDING'))
);

CREATE TABLE customer_credentials (
    user_id         UUID PRIMARY KEY REFERENCES customer_users (id) ON DELETE CASCADE,
    password_hash   VARCHAR(255) NOT NULL,
    password_algo   VARCHAR(32)  NOT NULL DEFAULT 'BCRYPT',
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE TABLE customer_refresh_tokens (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id         UUID NOT NULL REFERENCES customer_users (id) ON DELETE CASCADE,
    token_hash      VARCHAR(128) NOT NULL UNIQUE,
    family_id       UUID NOT NULL,
    expires_at      TIMESTAMPTZ  NOT NULL,
    revoked_at      TIMESTAMPTZ,
    replaced_by     UUID,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    user_agent      VARCHAR(512),
    ip_address      VARCHAR(64)
);

CREATE INDEX idx_customer_refresh_user ON customer_refresh_tokens (user_id, created_at DESC);
CREATE INDEX idx_customer_refresh_family ON customer_refresh_tokens (family_id);

CREATE TABLE password_reset_tokens (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id         UUID NOT NULL REFERENCES customer_users (id) ON DELETE CASCADE,
    token_hash      VARCHAR(128) NOT NULL UNIQUE,
    expires_at      TIMESTAMPTZ  NOT NULL,
    used_at         TIMESTAMPTZ,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_password_reset_user ON password_reset_tokens (user_id, created_at DESC);
