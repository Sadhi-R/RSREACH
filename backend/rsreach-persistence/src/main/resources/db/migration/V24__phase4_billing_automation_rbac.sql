-- Phase 4 M0: billing + automation RBAC (no domain tables yet).

INSERT INTO permissions (code, description) VALUES
 ('tenant.billing.manage', 'Manage tenant subscription, checkout, and plan changes'),
 ('billing.invoices.read', 'View invoices and payment history'),
 ('automations.read', 'View automation workflows and run history'),
 ('automations.manage', 'Create, edit, activate, and pause automations'),
 ('platform.billing.manage', 'Platform admin: tenant billing, manual payments, entitlement overrides')
ON CONFLICT (code) DO NOTHING;

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
CROSS JOIN permissions p
WHERE r.code = 'TENANT_OWNER'
  AND p.code IN (
    'tenant.billing.read',
    'tenant.billing.manage',
    'billing.invoices.read',
    'automations.read',
    'automations.manage'
  )
ON CONFLICT DO NOTHING;

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
CROSS JOIN permissions p
WHERE r.code = 'TENANT_ADMIN'
  AND p.code IN (
    'tenant.billing.read',
    'billing.invoices.read',
    'automations.read',
    'automations.manage'
  )
ON CONFLICT DO NOTHING;

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
CROSS JOIN permissions p
WHERE r.code = 'WORKSPACE_ADMIN'
  AND p.code IN ('automations.read', 'automations.manage')
ON CONFLICT DO NOTHING;

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
CROSS JOIN permissions p
WHERE r.code = 'WORKSPACE_MEMBER'
  AND p.code = 'automations.read'
ON CONFLICT DO NOTHING;

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
CROSS JOIN permissions p
WHERE r.code = 'FINANCE_ADMIN'
  AND p.code IN (
    'platform.billing.manage',
    'platform.tenants.read',
    'platform.audit.read'
  )
ON CONFLICT DO NOTHING;

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
CROSS JOIN permissions p
WHERE r.code = 'PLATFORM_OWNER'
  AND p.code = 'platform.billing.manage'
ON CONFLICT DO NOTHING;
