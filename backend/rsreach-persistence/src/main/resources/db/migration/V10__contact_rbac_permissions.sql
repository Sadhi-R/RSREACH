-- Phase 2 M0: contact-related RBAC permissions only (no contact tables yet).
-- Role mapping: owners/admins manage; workspace members read-only.

INSERT INTO permissions (code, description) VALUES
 ('contacts.read', 'View contacts, groups, and consent status'),
 ('contacts.manage', 'Create, update, archive, restore, merge, share contacts and groups'),
 ('contacts.consent.manage', 'Change contact consent and suppressions'),
 ('contacts.import', 'Preview and commit contact CSV imports'),
 ('contacts.export', 'Export contacts');

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
CROSS JOIN permissions p
WHERE r.code IN ('TENANT_OWNER', 'TENANT_ADMIN', 'WORKSPACE_ADMIN')
  AND p.code IN (
    'contacts.read',
    'contacts.manage',
    'contacts.consent.manage',
    'contacts.import',
    'contacts.export'
  );

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
JOIN permissions p ON p.code = 'contacts.read'
WHERE r.code = 'WORKSPACE_MEMBER';
