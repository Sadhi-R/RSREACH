CREATE TABLE automation_workflow (
    id            UUID PRIMARY KEY,
    tenant_id     UUID NOT NULL REFERENCES tenants (id),
    workspace_id  UUID NOT NULL,
    name          VARCHAR(255) NOT NULL,
    status        VARCHAR(32) NOT NULL DEFAULT 'DRAFT',
    created_by    UUID,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_automation_workflow_workspace
        FOREIGN KEY (tenant_id, workspace_id) REFERENCES workspaces (tenant_id, id),
    CONSTRAINT chk_automation_status CHECK (status IN (
        'DRAFT', 'ACTIVE', 'PAUSED', 'ARCHIVED'
    ))
);

CREATE INDEX idx_automation_workflow_ws ON automation_workflow (tenant_id, workspace_id, updated_at DESC);

CREATE TABLE automation_version (
    id             UUID PRIMARY KEY,
    workflow_id    UUID NOT NULL REFERENCES automation_workflow (id) ON DELETE CASCADE,
    tenant_id      UUID NOT NULL,
    workspace_id   UUID NOT NULL,
    version_number INT NOT NULL,
    trigger_type   VARCHAR(64) NOT NULL,
    trigger_config JSONB NOT NULL DEFAULT '{}'::jsonb,
    conditions_json JSONB NOT NULL DEFAULT '[]'::jsonb,
    actions_json   JSONB NOT NULL DEFAULT '[]'::jsonb,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_automation_version UNIQUE (workflow_id, version_number)
);

CREATE TABLE automation_run (
    id               UUID PRIMARY KEY,
    tenant_id        UUID NOT NULL,
    workspace_id     UUID NOT NULL,
    workflow_id      UUID NOT NULL REFERENCES automation_workflow (id),
    version_id       UUID NOT NULL REFERENCES automation_version (id),
    status           VARCHAR(32) NOT NULL DEFAULT 'QUEUED',
    trigger_type     VARCHAR(64) NOT NULL,
    trigger_payload  JSONB NOT NULL DEFAULT '{}'::jsonb,
    depth            INT NOT NULL DEFAULT 0,
    contact_id       UUID,
    error_code       VARCHAR(64),
    started_at       TIMESTAMPTZ,
    finished_at      TIMESTAMPTZ,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_automation_run_status CHECK (status IN (
        'QUEUED', 'RUNNING', 'COMPLETED', 'FAILED', 'SKIPPED'
    )),
    CONSTRAINT chk_automation_run_depth CHECK (depth >= 0 AND depth <= 5)
);

CREATE INDEX idx_automation_run_ws ON automation_run (tenant_id, workspace_id, created_at DESC);

CREATE TABLE automation_action_log (
    id            UUID PRIMARY KEY,
    run_id        UUID NOT NULL REFERENCES automation_run (id) ON DELETE CASCADE,
    tenant_id     UUID NOT NULL,
    workspace_id  UUID NOT NULL,
    step_index    INT NOT NULL,
    action_type   VARCHAR(64) NOT NULL,
    status        VARCHAR(32) NOT NULL,
    detail_json   JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_automation_action_status CHECK (status IN (
        'PENDING', 'SUCCEEDED', 'FAILED', 'SKIPPED'
    ))
);

CREATE TABLE automation_cooldown (
    id            UUID PRIMARY KEY,
    tenant_id     UUID NOT NULL,
    workspace_id  UUID NOT NULL,
    workflow_id   UUID NOT NULL,
    contact_id    UUID NOT NULL,
    last_run_at   TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_automation_cooldown UNIQUE (tenant_id, workspace_id, workflow_id, contact_id)
);
