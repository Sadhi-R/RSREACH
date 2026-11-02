# Phase 1 Authorization Design

## Dual realm

- **Customer realm:** JWT audience `rsreach-app`. Principals carry identity only; permissions are **not** loaded as a global set.
- **Staff realm:** JWT audience `rsreach-platform`. Platform permissions are loaded onto the principal from staff role bindings.

## Active context

`TenantWorkspaceContextFilter` validates `X-Tenant-Id` / `X-Workspace-Id` against **active** memberships before setting `TenantContext`. Invalid or foreign headers fail closed (403). Missing context for scoped operations fails closed via `TenantContext.requireTenantId()` / `requireWorkspaceId()`.

## Scoped customer permissions

`CustomerAuthorizationService` resolves permissions at check time:

| Check | Scope |
|---|---|
| `requireTenantPermission` | Active membership in the target tenant + that tenant role’s permissions only |
| `requireWorkspacePermission` | Active membership in tenant **and** workspace; tenant-role permissions for that tenant ∪ workspace-role permissions for that workspace |

Permissions from other tenants or other workspaces are never combined. Controllers must call these methods with the authoritative tenant/workspace id (path variable or validated `TenantContext`), never `principal.hasPermission(...)` for customer routes.

## Invitation role policy

Customer invitation APIs may assign only:

- Tenant invites: `TENANT_OWNER`, `TENANT_ADMIN`
- Workspace role assignment: `WORKSPACE_ADMIN`, `WORKSPACE_MEMBER`

Platform roles (`PLATFORM_OWNER`, `GLOBAL_ADMIN`, support/finance/techops/security/product roles) are rejected. Role scope is re-validated on invitation acceptance from the stored `role_id`; recipients cannot supply a role.

## Development token exposure

Raw password-reset and invitation tokens are never returned in normal API responses. Hashes only are stored. Delivery uses the transactional outbox.

Optional local-only exposure (`rsreach.security.expose-dev-tokens=true`) is honored only when the `local` profile is active and the `docker` profile is not. Docker and production keep the property false.
