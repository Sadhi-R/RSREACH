CREATE TABLE conversation_assignments (
    id               UUID PRIMARY KEY,
    tenant_id        UUID NOT NULL REFERENCES tenants (id),
    workspace_id     UUID NOT NULL,
    conversation_id  UUID NOT NULL REFERENCES conversations (id),
    assignee_user_id UUID NOT NULL,
    assigned_by      UUID,
    status           VARCHAR(32) NOT NULL DEFAULT 'OPEN',
    assigned_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    unassigned_at    TIMESTAMPTZ,
    CONSTRAINT fk_conversation_assignments_workspace
        FOREIGN KEY (tenant_id, workspace_id) REFERENCES workspaces (tenant_id, id),
    CONSTRAINT chk_conversation_assignments_status CHECK (status IN ('OPEN', 'CLOSED'))
);

CREATE UNIQUE INDEX uq_conversation_assignments_open
    ON conversation_assignments (tenant_id, workspace_id, conversation_id)
    WHERE status = 'OPEN' AND unassigned_at IS NULL;

CREATE TABLE conversation_notes (
    id               UUID PRIMARY KEY,
    tenant_id        UUID NOT NULL REFERENCES tenants (id),
    workspace_id     UUID NOT NULL,
    conversation_id  UUID NOT NULL REFERENCES conversations (id),
    author_user_id   UUID NOT NULL,
    body_text        TEXT NOT NULL,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_conversation_notes_workspace
        FOREIGN KEY (tenant_id, workspace_id) REFERENCES workspaces (tenant_id, id)
);

CREATE INDEX idx_conversation_notes_conversation
    ON conversation_notes (tenant_id, workspace_id, conversation_id, created_at DESC);
