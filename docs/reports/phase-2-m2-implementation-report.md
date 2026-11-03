# Phase 2 Milestone M2 — Implementation Report

**Date:** 2026-11-02  
**Scope:** Static and dynamic contact groups, membership APIs, filter DSL, outbox-driven materialization (worker + scheduler), group UI  
**Not in scope:** Consent, merge, CSV import/export, campaign audiences, channel eligibility (M3+)

---

## Delivered functionality

- Static groups: create, get, update (name), archive, add/remove/bulk members, paginated member list, duplicate membership prevention, workspace-scoped contact validation, member counts.
- Dynamic groups: versioned allow-listed filter DSL (`version: 1`, `match: ALL|ANY`), preview with match count, outbox enqueue on create/update/manual refresh, batched idempotent materialization replacing `source=DYNAMIC` only (preserves `MANUAL`).
- Authorization: `contacts.read` / `contacts.manage` via Phase 1 scoped authz; fail-closed missing context; cross-tenant/workspace `404`.
- Audit: `contact.group.created|updated|archived|members_changed|refresh_requested` without PII dumps.
- Worker outbox poll loop (first product handler) + scheduler stale DYNAMIC refresh enqueue (≥15 minutes).

---

## Dynamic evaluation strategy

| Trigger | Behavior |
|---|---|
| Create/update DYNAMIC filter | Enqueue `CONTACT_GROUP_REFRESH` in same transaction |
| `POST .../refresh` | Mark `PENDING`, enqueue outbox event |
| Scheduler tick | Enqueues stale ACTIVE DYNAMIC groups (`IDLE`/`FAILED`, never materialized or older than 15 minutes) |
| Worker | Claims outbox with `FOR UPDATE SKIP LOCKED`; materializer locks group row, deletes DYNAMIC members, inserts matching batches (200), sets `materialized_at` |

Consent filter fields are **rejected** until M3 (tables do not exist). No raw SQL / expression injection; column names come only from an allow-list switch.

---

## Files / modules changed

| Area | Paths |
|---|---|
| Migration | `backend/rsreach-persistence/.../V12__contact_groups.sql` |
| Outbox | `OutboxEvent.java`, `OutboxRepository.findById` |
| Domain | `ContactGroup*` / `GroupFilter*` in `rsreach-contacts` |
| API | `ContactGroupController` |
| Worker | `OutboxPoller`, `OutboxEventDispatcher` |
| Scheduler | `ContactGroupRefreshScheduler` (+ `rsreach-contacts` dependency) |
| Tests | `GroupFilterValidatorTest`, `ContactGroupsMigrationTest`, `Phase2ContactM2Test`, `OutboxPollerBackoffTest` |
| Frontend | `contact-groups.tsx`, routes in `App.tsx`, dashboard link |
| Docs | this report; plan status updated |

V1–V11 migrations: **unchanged**.

---

## Flyway V12

Tables: `contact_groups`, `contact_group_members`  
Also: unique indexes on `contacts (tenant_id, id)` and `(tenant_id, home_workspace_id, id)` for composite member FKs.  
Uniqueness: `(tenant_id, workspace_id, group_id, contact_id)` for membership; `(tenant_id, workspace_id, slug)` for groups.

---

## API endpoints

Base `/api/v1/app` + `X-Tenant-Id` / `X-Workspace-Id`

| Method | Path | Permission |
|---|---|---|
| GET/POST | `/contact-groups` | read / manage |
| GET/PATCH | `/contact-groups/{id}` | read / manage |
| POST | `/contact-groups/{id}/archive` | manage |
| GET | `/contact-groups/{id}/members` | read |
| POST/DELETE | `/contact-groups/{id}/members` | manage |
| POST | `/contact-groups/{id}/members/bulk` | manage |
| POST | `/contact-groups/{id}/refresh` | manage |
| POST | `/contact-groups/preview` | read |

---

## Frontend routes

- `/app/contact-groups` — directory (search, type/status filters, pagination)
- `/app/contact-groups/new` — create static/dynamic + filter builder + preview
- `/app/contact-groups/:groupId` — detail, members, add/remove, refresh, archive

---

## Authorization and isolation

- Reuses `CustomerAuthorizationService` + V10 contact permissions.
- Members: read-only; admins: manage.
- All queries scoped by tenant + workspace; foreign contact IDs rejected; cross-scope GET → `404`.

---

## Tests executed

| Suite | Result |
|---|---|
| Unit (normalizer, custom fields, group filter, service, backoff) | PASS |
| `ContactGroupsMigrationTest` / prior V10–V11 | PASS |
| `Phase2ContactM1Test` | PASS |
| `Phase2ContactM2Test` (CRUD, bulk, duplicates, filter validation, materialization idempotency, MANUAL preserve, authz, isolation, audit, outbox) | PASS |
| Phase 0/1 Testcontainers suites | PASS |
| Skipped | **0** (Docker available) |

### Verification commands

```powershell
mvn -B verify "-Dapi.version=1.44"   # BUILD SUCCESS
npm run typecheck && npm run build   # PASS
docker compose -f infra/compose/docker-compose.dev.yml --env-file .env up --build -d postgres api worker scheduler
```

Compose: images rebuilt; `api` healthy; `worker`/`scheduler` started with outbox poller / group refresh scheduler.

### Compose API/worker smoke

Against local Compose (`8080`):

| Check | Result |
|---|---|
| Static group create + add member | `added=1` |
| Dynamic group create | Outbox enqueued |
| Worker materialization | `contact_group_refresh_ok`; status `IDLE`; `materializedAt` set; members `1` |

---

## Known limitations / follow-up risks

- Contact mutations do not automatically enqueue group refresh (stale until manual refresh, filter update, or 15-minute scheduler).
- Consent filters deferred to M3.
- Group sharing (`CONTACT_GROUP` via `resource_shares`) not in M2 UI/API (plan M6).
- Static membership UI adds contacts by UUID (directory picker deferred).
- Concurrent materialization is serialized per group via `FOR UPDATE`; high churn may queue refresh jobs.

---

## Gate

**M2 complete.** Do **not** start M3 until this report is explicitly approved.
