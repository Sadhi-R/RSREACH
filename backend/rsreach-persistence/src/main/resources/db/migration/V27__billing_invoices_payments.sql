CREATE TABLE billing_invoice (
    id                    UUID PRIMARY KEY,
    tenant_id             UUID NOT NULL REFERENCES tenants (id),
    subscription_id       UUID REFERENCES tenant_subscription (id),
    invoice_number        VARCHAR(64) NOT NULL,
    status                VARCHAR(32) NOT NULL DEFAULT 'DRAFT',
    currency              CHAR(3) NOT NULL DEFAULT 'INR',
    subtotal_paise        BIGINT NOT NULL DEFAULT 0,
    tax_paise             BIGINT NOT NULL DEFAULT 0,
    total_paise           BIGINT NOT NULL DEFAULT 0,
    cgst_paise            BIGINT NOT NULL DEFAULT 0,
    sgst_paise            BIGINT NOT NULL DEFAULT 0,
    igst_paise            BIGINT NOT NULL DEFAULT 0,
    place_of_supply       VARCHAR(64),
    supplier_legal_name   VARCHAR(255),
    buyer_legal_name      VARCHAR(255),
    period_start          TIMESTAMPTZ,
    period_end            TIMESTAMPTZ,
    issued_at             TIMESTAMPTZ,
    due_at                TIMESTAMPTZ,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_billing_invoice_number UNIQUE (invoice_number),
    CONSTRAINT chk_invoice_status CHECK (status IN (
        'DRAFT', 'ISSUED', 'PAID', 'VOID', 'PAST_DUE'
    )),
    CONSTRAINT chk_invoice_money CHECK (
        subtotal_paise >= 0 AND tax_paise >= 0 AND total_paise >= 0
    )
);

CREATE INDEX idx_billing_invoice_tenant ON billing_invoice (tenant_id, created_at DESC);

CREATE TABLE billing_payment (
    id                     UUID PRIMARY KEY,
    tenant_id              UUID NOT NULL REFERENCES tenants (id),
    invoice_id             UUID REFERENCES billing_invoice (id),
    subscription_id        UUID REFERENCES tenant_subscription (id),
    method                 VARCHAR(32) NOT NULL,
    status                 VARCHAR(32) NOT NULL,
    amount_paise           BIGINT NOT NULL,
    currency               CHAR(3) NOT NULL DEFAULT 'INR',
    provider               VARCHAR(32),
    provider_order_id      VARCHAR(128),
    provider_payment_id    VARCHAR(128),
    external_reference     VARCHAR(255),
    recorded_by_staff_id   UUID,
    paid_at                TIMESTAMPTZ,
    created_at             TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at             TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_payment_method CHECK (method IN (
        'RAZORPAY', 'MANUAL', 'BANK_TRANSFER'
    )),
    CONSTRAINT chk_payment_status CHECK (status IN (
        'CREATED', 'AUTHORIZED', 'CAPTURED', 'FAILED', 'REFUNDED'
    )),
    CONSTRAINT chk_payment_amount CHECK (amount_paise >= 0)
);

CREATE UNIQUE INDEX uq_billing_payment_provider_payment
    ON billing_payment (provider, provider_payment_id)
    WHERE provider_payment_id IS NOT NULL;

CREATE TABLE billing_refund (
    id                UUID PRIMARY KEY,
    tenant_id         UUID NOT NULL REFERENCES tenants (id),
    payment_id        UUID NOT NULL REFERENCES billing_payment (id),
    amount_paise      BIGINT NOT NULL,
    currency          CHAR(3) NOT NULL DEFAULT 'INR',
    reason            VARCHAR(255),
    provider_refund_id VARCHAR(128),
    status            VARCHAR(32) NOT NULL DEFAULT 'RECORDED',
    created_at        TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_refund_amount CHECK (amount_paise > 0)
);

CREATE TABLE credit_note (
    id                UUID PRIMARY KEY,
    tenant_id         UUID NOT NULL REFERENCES tenants (id),
    invoice_id        UUID NOT NULL REFERENCES billing_invoice (id),
    credit_note_number VARCHAR(64) NOT NULL,
    amount_paise      BIGINT NOT NULL,
    currency          CHAR(3) NOT NULL DEFAULT 'INR',
    reason            VARCHAR(255),
    created_at        TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_credit_note_number UNIQUE (credit_note_number),
    CONSTRAINT chk_credit_note_amount CHECK (amount_paise > 0)
);

CREATE TABLE billing_invoice_seq (
    supplier_code VARCHAR(64) PRIMARY KEY,
    next_value    BIGINT NOT NULL DEFAULT 1
);

INSERT INTO billing_invoice_seq (supplier_code, next_value) VALUES ('RSREACH', 1);
