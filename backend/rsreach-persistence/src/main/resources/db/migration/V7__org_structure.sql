CREATE TABLE departments (
    id              UUID NOT NULL DEFAULT gen_random_uuid(),
    tenant_id       UUID NOT NULL,
    workspace_id    UUID NOT NULL,
    name            VARCHAR(255) NOT NULL,
    parent_id       UUID,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (tenant_id, workspace_id, id),
    CONSTRAINT fk_department_workspace
        FOREIGN KEY (tenant_id, workspace_id) REFERENCES workspaces (tenant_id, id) ON DELETE CASCADE,
    CONSTRAINT uq_department_name UNIQUE (tenant_id, workspace_id, name)
);

CREATE TABLE teams (
    id              UUID NOT NULL DEFAULT gen_random_uuid(),
    tenant_id       UUID NOT NULL,
    workspace_id    UUID NOT NULL,
    department_id   UUID,
    name            VARCHAR(255) NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (tenant_id, workspace_id, id),
    CONSTRAINT fk_team_workspace
        FOREIGN KEY (tenant_id, workspace_id) REFERENCES workspaces (tenant_id, id) ON DELETE CASCADE,
    CONSTRAINT uq_team_name UNIQUE (tenant_id, workspace_id, name)
);

CREATE TABLE department_memberships (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID NOT NULL,
    workspace_id    UUID NOT NULL,
    department_id   UUID NOT NULL,
    user_id         UUID NOT NULL REFERENCES customer_users (id) ON DELETE CASCADE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_department_membership UNIQUE (tenant_id, workspace_id, department_id, user_id),
    CONSTRAINT fk_dept_membership_dept
        FOREIGN KEY (tenant_id, workspace_id, department_id)
        REFERENCES departments (tenant_id, workspace_id, id) ON DELETE CASCADE
);

CREATE TABLE team_memberships (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID NOT NULL,
    workspace_id    UUID NOT NULL,
    team_id         UUID NOT NULL,
    user_id         UUID NOT NULL REFERENCES customer_users (id) ON DELETE CASCADE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_team_membership UNIQUE (tenant_id, workspace_id, team_id, user_id),
    CONSTRAINT fk_team_membership_team
        FOREIGN KEY (tenant_id, workspace_id, team_id)
        REFERENCES teams (tenant_id, workspace_id, id) ON DELETE CASCADE
);

CREATE INDEX idx_departments_ws ON departments (tenant_id, workspace_id);
CREATE INDEX idx_teams_ws ON teams (tenant_id, workspace_id);
