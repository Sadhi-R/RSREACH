CREATE TABLE permissions (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    code         VARCHAR(128) NOT NULL UNIQUE,
    description  VARCHAR(255) NOT NULL,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE TABLE roles (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    code         VARCHAR(128) NOT NULL UNIQUE,
    name         VARCHAR(128) NOT NULL,
    scope        VARCHAR(32)  NOT NULL,
    description  VARCHAR(255),
    system_role  BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_role_scope CHECK (scope IN ('PLATFORM', 'TENANT', 'WORKSPACE'))
);

CREATE TABLE role_permissions (
    role_id       UUID NOT NULL REFERENCES roles (id) ON DELETE CASCADE,
    permission_id UUID NOT NULL REFERENCES permissions (id) ON DELETE CASCADE,
    PRIMARY KEY (role_id, permission_id)
);

INSERT INTO permissions (code, description) VALUES
 ('platform.tenants.read', 'List and view tenants'),
 ('platform.tenants.manage', 'Approve suspend reactivate tenants'),
 ('platform.users.manage', 'Manage platform staff bindings'),
 ('platform.audit.read', 'Read audit events'),
 ('platform.grants.manage', 'Approve and revoke staff access grants'),
 ('platform.grants.request', 'Request staff access grants'),
 ('platform.config.read', 'Read platform configuration'),
 ('tenant.read', 'Read tenant profile'),
 ('tenant.manage', 'Manage tenant settings'),
 ('tenant.members.manage', 'Invite and manage tenant members'),
 ('tenant.billing.read', 'Read tenant billing (foundation)'),
 ('workspace.read', 'Read workspace'),
 ('workspace.manage', 'Manage workspace settings'),
 ('workspace.members.manage', 'Manage workspace members'),
 ('workspace.org.manage', 'Manage departments and teams');

INSERT INTO roles (code, name, scope, description) VALUES
 ('PLATFORM_OWNER', 'Platform Owner', 'PLATFORM', 'Full platform control'),
 ('GLOBAL_ADMIN', 'Global Admin', 'PLATFORM', 'Tenant lifecycle and configuration'),
 ('SUPPORT_MANAGER', 'Support Manager', 'PLATFORM', 'Support operations management'),
 ('SUPPORT_AGENT', 'Support Agent', 'PLATFORM', 'Support operations'),
 ('FINANCE_ADMIN', 'Finance Admin', 'PLATFORM', 'Finance and invoices'),
 ('TECHOPS_ADMIN', 'Technical Operations Admin', 'PLATFORM', 'Jobs and integrations ops'),
 ('SECURITY_OFFICER', 'Security / Compliance Officer', 'PLATFORM', 'Audit and grants oversight'),
 ('PRODUCT_RELEASE_MANAGER', 'Product / Release Manager', 'PLATFORM', 'Feature flags and releases'),
 ('TENANT_OWNER', 'Tenant Owner', 'TENANT', 'Owns the organization'),
 ('TENANT_ADMIN', 'Tenant Admin', 'TENANT', 'Administers the organization'),
 ('WORKSPACE_ADMIN', 'Workspace Admin', 'WORKSPACE', 'Administers a workspace'),
 ('WORKSPACE_MEMBER', 'Workspace Member', 'WORKSPACE', 'Standard workspace member');

-- Platform Owner: all platform permissions
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r CROSS JOIN permissions p
WHERE r.code = 'PLATFORM_OWNER' AND p.code LIKE 'platform.%';

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r JOIN permissions p ON p.code IN (
  'platform.tenants.read','platform.tenants.manage','platform.audit.read','platform.grants.manage','platform.grants.request','platform.config.read'
) WHERE r.code = 'GLOBAL_ADMIN';

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r JOIN permissions p ON p.code IN (
  'platform.tenants.read','platform.grants.request','platform.grants.manage','platform.audit.read'
) WHERE r.code = 'SUPPORT_MANAGER';

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r JOIN permissions p ON p.code IN (
  'platform.tenants.read','platform.grants.request'
) WHERE r.code = 'SUPPORT_AGENT';

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r JOIN permissions p ON p.code IN (
  'platform.tenants.read','platform.audit.read'
) WHERE r.code = 'FINANCE_ADMIN';

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r JOIN permissions p ON p.code IN (
  'platform.tenants.read','platform.config.read','platform.grants.request'
) WHERE r.code = 'TECHOPS_ADMIN';

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r JOIN permissions p ON p.code IN (
  'platform.tenants.read','platform.audit.read','platform.grants.manage','platform.grants.request'
) WHERE r.code = 'SECURITY_OFFICER';

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r JOIN permissions p ON p.code IN (
  'platform.config.read','platform.tenants.read'
) WHERE r.code = 'PRODUCT_RELEASE_MANAGER';

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r JOIN permissions p ON p.code IN (
  'tenant.read','tenant.manage','tenant.members.manage','tenant.billing.read',
  'workspace.read','workspace.manage','workspace.members.manage','workspace.org.manage'
) WHERE r.code = 'TENANT_OWNER';

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r JOIN permissions p ON p.code IN (
  'tenant.read','tenant.manage','tenant.members.manage',
  'workspace.read','workspace.manage','workspace.members.manage','workspace.org.manage'
) WHERE r.code = 'TENANT_ADMIN';

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r JOIN permissions p ON p.code IN (
  'workspace.read','workspace.manage','workspace.members.manage','workspace.org.manage'
) WHERE r.code = 'WORKSPACE_ADMIN';

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r JOIN permissions p ON p.code IN ('workspace.read')
WHERE r.code = 'WORKSPACE_MEMBER';
