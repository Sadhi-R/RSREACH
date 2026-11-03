CREATE TABLE conversations (
    id                   UUID PRIMARY KEY,
    tenant_id            UUID NOT NULL REFERENCES tenants (id),
    workspace_id         UUID NOT NULL,
    channel              VARCHAR(32) NOT NULL,
    contact_id           UUID NOT NULL,
    connected_account_id UUID REFERENCES connected_accounts (id),
    subject              VARCHAR(512),
    external_thread_key  VARCHAR(255),
    status               VARCHAR(32) NOT NULL DEFAULT 'OPEN',
    last_message_at      TIMESTAMPTZ,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_conversations_workspace
        FOREIGN KEY (tenant_id, workspace_id) REFERENCES workspaces (tenant_id, id),
    CONSTRAINT fk_conversations_contact
        FOREIGN KEY (contact_id) REFERENCES contacts (id),
    CONSTRAINT chk_conversations_channel CHECK (channel IN ('WHATSAPP', 'EMAIL', 'INTERNAL_TEST')),
    CONSTRAINT chk_conversations_status CHECK (status IN ('OPEN', 'CLOSED', 'ARCHIVED'))
);

CREATE INDEX idx_conversations_workspace_last
    ON conversations (tenant_id, workspace_id, last_message_at DESC NULLS LAST);

CREATE UNIQUE INDEX uq_conversations_thread
    ON conversations (tenant_id, workspace_id, channel, external_thread_key)
    WHERE external_thread_key IS NOT NULL;

CREATE TABLE messages (
    id                   UUID PRIMARY KEY,
    tenant_id            UUID NOT NULL REFERENCES tenants (id),
    workspace_id         UUID NOT NULL,
    conversation_id      UUID NOT NULL REFERENCES conversations (id),
    direction            VARCHAR(16) NOT NULL,
    channel              VARCHAR(32) NOT NULL,
    body_text            TEXT,
    body_preview         VARCHAR(512),
    purpose              VARCHAR(32),
    destination_address  VARCHAR(320),
    provider_message_id  VARCHAR(255),
    idempotency_key      VARCHAR(128),
    status               VARCHAR(32) NOT NULL DEFAULT 'ACCEPTED',
    provider_status      VARCHAR(128),
    error_code           VARCHAR(64),
    error_message        VARCHAR(512),
    accepted_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    queued_at            TIMESTAMPTZ,
    sent_at              TIMESTAMPTZ,
    delivered_at         TIMESTAMPTZ,
    read_at              TIMESTAMPTZ,
    failed_at            TIMESTAMPTZ,
    created_by           UUID,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_messages_workspace
        FOREIGN KEY (tenant_id, workspace_id) REFERENCES workspaces (tenant_id, id),
    CONSTRAINT chk_messages_direction CHECK (direction IN ('INBOUND', 'OUTBOUND')),
    CONSTRAINT chk_messages_channel CHECK (channel IN ('WHATSAPP', 'EMAIL', 'INTERNAL_TEST')),
    CONSTRAINT chk_messages_status CHECK (status IN (
        'ACCEPTED', 'QUEUED', 'SENT', 'DELIVERED', 'READ', 'FAILED', 'REJECTED'
    )),
    CONSTRAINT chk_messages_purpose CHECK (
        purpose IS NULL OR purpose IN ('MARKETING', 'TRANSACTIONAL', 'SERVICE')
    )
);

CREATE INDEX idx_messages_conversation
    ON messages (tenant_id, workspace_id, conversation_id, created_at);

CREATE UNIQUE INDEX uq_messages_idempotency
    ON messages (tenant_id, workspace_id, idempotency_key)
    WHERE idempotency_key IS NOT NULL;

CREATE UNIQUE INDEX uq_messages_provider_id
    ON messages (tenant_id, workspace_id, channel, provider_message_id)
    WHERE provider_message_id IS NOT NULL;

CREATE TABLE message_status_events (
    id              UUID PRIMARY KEY,
    tenant_id       UUID NOT NULL REFERENCES tenants (id),
    workspace_id    UUID NOT NULL,
    message_id      UUID NOT NULL REFERENCES messages (id),
    status          VARCHAR(32) NOT NULL,
    provider_status VARCHAR(128),
    event_time      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    details_json    JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_message_status_workspace
        FOREIGN KEY (tenant_id, workspace_id) REFERENCES workspaces (tenant_id, id),
    CONSTRAINT chk_message_status_events_status CHECK (status IN (
        'ACCEPTED', 'QUEUED', 'SENT', 'DELIVERED', 'READ', 'FAILED', 'REJECTED'
    ))
);

CREATE INDEX idx_message_status_events_message
    ON message_status_events (tenant_id, workspace_id, message_id, event_time);

CREATE TABLE message_attachments (
    id                UUID PRIMARY KEY,
    tenant_id         UUID NOT NULL REFERENCES tenants (id),
    workspace_id      UUID NOT NULL,
    message_id        UUID NOT NULL REFERENCES messages (id),
    filename          VARCHAR(512),
    mime_type         VARCHAR(255),
    size_bytes        BIGINT,
    provider_media_id VARCHAR(255),
    storage_ref       VARCHAR(512),
    created_at        TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_message_attachments_workspace
        FOREIGN KEY (tenant_id, workspace_id) REFERENCES workspaces (tenant_id, id)
);

CREATE INDEX idx_message_attachments_message
    ON message_attachments (tenant_id, workspace_id, message_id);
