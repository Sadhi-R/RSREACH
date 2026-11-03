# Phase 2 — Contacts domain architecture notes

**Status:** Implemented through M6  
**ADRs:** 0003 (module), 0004 (consent), 0005 (duplicates/merge), 0006 (import/export + sharing defaults)

## Processes and data

- Contacts live in the modular monolith (`rsreach-contacts`) used by API, worker, and scheduler against one PostgreSQL database.
- Schema is Flyway-owned (`V10`–`V17` for contacts; `V1`–`V16` immutable once shipped). Hibernate `ddl-auto=validate`.
- Async work uses the transactional outbox: `CONTACT_GROUP_REFRESH`, `CONTACT_DUPLICATE_SCAN`, `CONTACT_IMPORT_PROCESS`, `CONTACT_EXPORT_PROCESS`.

## Isolation

- Every contact query is tenant-scoped. Directory/detail include **home workspace** contacts plus active `resource_shares` (`resource_type=CONTACT`, `revoked_at IS NULL`) targeting the current workspace.
- Channels are stored under the contact **home** workspace id; shared readers load channels via `home_workspace_id`.
- Consent and suppressions are home-workspace only. WRITE shares do **not** grant `contacts.consent.manage`. Consent is never copied on share or import.

## Import / export (M5)

| Limit | Value (ADR 0006) |
|---|---|
| Upload | 10 MiB |
| Rows | 10,000 |
| Preview validation | First 100 rows sync |
| Worker batch | 200 |
| Conflict policies | `SKIP` (default), `UPDATE`, `CREATE_ANYWAY` |
| Export | Sync CSV ≤5,000; larger retained as export job row |

Staging tables: `contact_import_jobs`, `contact_import_rows`, `contact_export_jobs`. No object storage.

## Sharing (M6)

- Store: V9 `resource_shares` (`CONTACT`, `CONTACT_GROUP`; permission `READ`|`WRITE`).
- APIs: `POST/GET/DELETE /api/v1/app/contacts/{id}/shares` and parallel `contact-groups/{id}/shares`.
- Permissions: `contacts.manage` to create/list/revoke (home workspace only); directory expand needs `contacts.read`.
- Contact and group directories expand: home **or** active share **to** the current workspace.
- Contact detail GET includes `access`: `HOME` | `READ` | `WRITE`.
- WRITE share: update/archive/restore with `contacts.manage`; consent GET/PUT/history remain home-only (404 off-home).
- Group share create/list/revoke and group mutations (archive, members, refresh) require the **home** workspace; shared targets may read group + members only.
- Revoked shares can be reactivated (V9 unique key); revoke sets `revoked_at`.
- Merge still revokes loser contact shares; shares never authorize merge (ADR 0005).
- Audits: `contact.shared`, `contact.share_revoked`, `contact.group.shared`, `contact.group.share_revoked`.

### Share authority matrix

| Action | Home | Shared READ | Shared WRITE |
|---|---|---|---|
| Read contact/group | Yes | Yes | Yes |
| Update/archive/restore contact | Yes | No | Yes |
| Consent / history | Yes | No | No |
| Create/list/revoke shares | Yes | No | No |
| Merge | Same-home only | Never | Never |
| Group materialize / member mutate | Yes | No | No |

## Permissions

`contacts.read`, `contacts.manage`, `contacts.consent.manage`, `contacts.import`, `contacts.export` (seeded in V10).

## Local development

Prefer local PostgreSQL `rsreach_dev` per `docs/development/local-postgres-setup.md`. Testcontainers and Compose remain the verification path; do not point Testcontainers at `rsreach_dev`.
