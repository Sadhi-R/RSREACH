# ADR 0006 — Contact CSV import/export and sharing operational defaults

**Status:** Accepted  
**Date:** 2026-11-03  
**Depends on:** ADR 0003, ADR 0004, ADR 0005; Phase 2 contacts organization plan (D1–D7)

## Context

M5 (CSV) and M6 (sharing polish) are specified in the Phase 2 contacts plan but lack dedicated slice plans. Several operational parameters were unspecified. This ADR locks defaults for implementation without inventing product policy beyond the approved plan and normal SaaS practice.

## Decision

### Import / export (M5)

| Topic | Default |
|---|---|
| Conflict policy default | `SKIP` (plan D3) |
| Staging | PostgreSQL job/row tables only; no object storage (D5/A6) |
| Max upload size | 10 MiB |
| Max rows per import | 10,000 |
| Preview | First 100 rows validated synchronously; remainder on commit/worker |
| Worker batch | 200 rows per `CONTACT_IMPORT_PROCESS` claim |
| Staging retention | Keep job/row rows 30 days (no contact hard-delete) |
| UPDATE fields | `display_name`, mapped custom fields; add missing channels |
| Consent on import | Only when explicit CSV columns mapped **and** actor has `contacts.consent.manage`; never infer from address |
| CREATE_ANYWAY | Attempt create; ACTIVE channel uniqueness constraint wins → row FAILED |
| Export consent columns | Include status with `contacts.read` |
| Large export | Sync stream ≤5,000 contacts; above → async export job |
| Formula injection | Prefix risky cells with `'` on export |

### Sharing (M6)

| Topic | Default |
|---|---|
| Directory/detail expand | Home workspace **or** active `CONTACT` share **to** current workspace |
| WRITE share | Allows update/archive with `contacts.manage`; **not** consent.manage unless home |
| Consent | Never copied into target workspace (ADR 0004) |
| CONTACT_GROUP share | Create/revoke + membership list for READ; materialization remains home-only |
| Merge | Existing revoke-on-merge behavior unchanged (ADR 0005 F3) |

## Consequences

- Flyway V17+ for import tables; prefer V9 `resource_shares` for M6.  
- Worker handles `CONTACT_IMPORT_PROCESS` (and optional export).  
- Changing these defaults requires an ADR amendment before code changes.
