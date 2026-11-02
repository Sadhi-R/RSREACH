# Phase 1 Database Isolation Requirements

Phase 0 provides baseline CHECK constraints and marker interfaces only. Phase 1 must implement the following before tenant APIs go live.

## Mandatory on every tenant-owned table

- `tenant_id UUID NOT NULL`
- Index leading with `tenant_id` (and `workspace_id` when workspace-scoped)
- Application queries always filter by tenant (and workspace when scoped)
- Automated cross-tenant isolation tests

## Mandatory on every workspace-owned table

- `tenant_id UUID NOT NULL`
- `workspace_id UUID NOT NULL`
- Composite foreign key to workspaces:

```sql
FOREIGN KEY (tenant_id, workspace_id)
  REFERENCES workspaces (tenant_id, id)
```

- Unique business keys must be tenant/workspace scoped, for example:

```sql
UNIQUE (tenant_id, workspace_id, email)
UNIQUE (tenant_id, slug)
```

## Membership and RBAC

- Membership uniqueness: `UNIQUE (tenant_id, user_id)` and workspace role bindings scoped by `(tenant_id, workspace_id, user_id, role_id)`
- Roles carry explicit scope enum: `PLATFORM | TENANT | WORKSPACE`
- Staff identity tables remain outside tenant ownership; customer-data access uses time-limited grants
- Customer authorization must resolve permissions for the **active** tenant/workspace only (never a flat union). See `docs/architecture/phase-1-authorization.md`.
- Customer invitation APIs may assign only allowlisted tenant/workspace roles; platform roles are forbidden.

## Resource sharing

- Default private
- Cross-workspace access only through `resource_shares` with `(tenant_id, source_workspace_id, target_workspace_id, resource_type, resource_id)`

## Jobs, outbox, webhooks, secrets

- Persist `tenant_id` / `workspace_id` on job payloads and outbox rows
- Integration credentials stored in `integration_secrets` with tenant/workspace scope and `key_version`
- Webhook uniqueness remains `(provider, connected_account_ref, provider_event_id)`

## Remaining Phase 0 CHECK coverage

Already present:

- `workspace_id` requires `tenant_id` on outbox, webhook, and integration secret tables

Still required in Phase 1:

- Composite FKs for workspace children
- Tenant FKs for all tenant-owned rows
- Scoped UNIQUE constraints for natural keys
- Optional PostgreSQL RLS as defense-in-depth (after app-layer enforcement)
