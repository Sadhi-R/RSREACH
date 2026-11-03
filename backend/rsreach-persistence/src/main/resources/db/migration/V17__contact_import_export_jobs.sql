-- Phase 2 M5: CSV import job staging (DB-only; ADR 0006). No object storage.

CREATE TABLE contact_import_jobs (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id         UUID NOT NULL,
    workspace_id      UUID NOT NULL,
    filename          VARCHAR(255) NOT NULL,
    status            VARCHAR(32)  NOT NULL DEFAULT 'PENDING',
    conflict_policy   VARCHAR(32)  NOT NULL DEFAULT 'SKIP',
    row_count         INT NOT NULL DEFAULT 0,
    success_count     INT NOT NULL DEFAULT 0,
    failure_count     INT NOT NULL DEFAULT 0,
    skipped_count     INT NOT NULL DEFAULT 0,
    preview_ready_at  TIMESTAMPTZ,
    started_at        TIMESTAMPTZ,
    completed_at      TIMESTAMPTZ,
    error_summary     VARCHAR(1000),
    column_mapping    JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_by        UUID NOT NULL REFERENCES customer_users (id),
    idempotency_key   VARCHAR(128),
    created_at        TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_contact_import_job_workspace
        FOREIGN KEY (tenant_id, workspace_id) REFERENCES workspaces (tenant_id, id) ON DELETE CASCADE,
    CONSTRAINT chk_contact_import_job_status CHECK (status IN (
        'PENDING', 'VALIDATING', 'PREVIEW_READY', 'RUNNING', 'COMPLETED', 'FAILED', 'CANCELLED'
    )),
    CONSTRAINT chk_contact_import_conflict_policy CHECK (conflict_policy IN ('SKIP', 'UPDATE', 'CREATE_ANYWAY')),
    CONSTRAINT uq_contact_import_job_idempotency UNIQUE (tenant_id, workspace_id, idempotency_key)
);

CREATE INDEX idx_contact_import_jobs_ws_status
    ON contact_import_jobs (tenant_id, workspace_id, status, created_at DESC);

CREATE TABLE contact_import_rows (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    job_id       UUID NOT NULL REFERENCES contact_import_jobs (id) ON DELETE CASCADE,
    tenant_id    UUID NOT NULL,
    workspace_id UUID NOT NULL,
    row_number   INT NOT NULL,
    payload      JSONB NOT NULL DEFAULT '{}'::jsonb,
    result       VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    error_code   VARCHAR(64),
    error_message VARCHAR(1000),
    contact_id   UUID REFERENCES contacts (id),
    created_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_contact_import_row_workspace
        FOREIGN KEY (tenant_id, workspace_id) REFERENCES workspaces (tenant_id, id) ON DELETE CASCADE,
    CONSTRAINT uq_contact_import_row_number UNIQUE (job_id, row_number),
    CONSTRAINT chk_contact_import_row_result CHECK (result IN (
        'PENDING', 'OK', 'WARNING', 'ERROR', 'PROCESSING',
        'SKIPPED', 'CREATED', 'UPDATED', 'FAILED'
    ))
);

CREATE INDEX idx_contact_import_rows_job_result
    ON contact_import_rows (job_id, result);

CREATE TABLE contact_export_jobs (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id      UUID NOT NULL,
    workspace_id   UUID NOT NULL,
    status         VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    filter_query   VARCHAR(255),
    filter_status  VARCHAR(32),
    row_count      INT NOT NULL DEFAULT 0,
    csv_content    TEXT,
    created_by     UUID NOT NULL REFERENCES customer_users (id),
    created_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    completed_at   TIMESTAMPTZ,
    CONSTRAINT fk_contact_export_job_workspace
        FOREIGN KEY (tenant_id, workspace_id) REFERENCES workspaces (tenant_id, id) ON DELETE CASCADE,
    CONSTRAINT chk_contact_export_job_status CHECK (status IN ('PENDING', 'RUNNING', 'COMPLETED', 'FAILED'))
);

CREATE INDEX idx_contact_export_jobs_ws
    ON contact_export_jobs (tenant_id, workspace_id, created_at DESC);
