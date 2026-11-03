-- Phase 3 M0: communications RBAC permissions (no conversation tables yet).

INSERT INTO permissions (code, description) VALUES
 ('inbox.read', 'View conversations and messages in the workspace inbox'),
 ('inbox.reply', 'Compose and send replies in the workspace inbox'),
 ('inbox.assign', 'Assign and unassign conversations to workspace members'),
 ('integrations.manage', 'Connect and disconnect messaging and email providers'),
 ('templates.manage', 'Manage and sync message templates'),
 ('campaigns.read', 'View campaigns and schedules'),
 ('campaigns.manage', 'Create and edit campaigns'),
 ('campaigns.send', 'Start, pause, resume, and cancel campaign sends');

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
CROSS JOIN permissions p
WHERE r.code IN ('TENANT_OWNER', 'TENANT_ADMIN', 'WORKSPACE_ADMIN')
  AND p.code IN (
    'inbox.read',
    'inbox.reply',
    'inbox.assign',
    'integrations.manage',
    'templates.manage',
    'campaigns.read',
    'campaigns.manage',
    'campaigns.send'
  );

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
CROSS JOIN permissions p
WHERE r.code = 'WORKSPACE_MEMBER'
  AND p.code IN ('inbox.read', 'inbox.reply', 'campaigns.read');
