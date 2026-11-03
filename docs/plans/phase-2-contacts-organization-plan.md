# Phase 2 Implementation Plan — Contacts & Organization Management

**Status:** COMPLETE for contacts M0–M6 (see `docs/reports/phase-2-final-verification-report.md`); M4.3–M6 via `phase-2-remaining-milestones-execution-plan.md`  
**Date:** 2026-11-02  
**Prerequisite:** Phase 1 closed and verified  
**Approvals:** D1–D7 accepted as proposed defaults; additional mandatory rules recorded in the approval message.

---

## 1. Current architecture inspection and reusable components

### What already exists (reuse, do not recreate)

| Capability | Location | Reuse for Phase 2 |
|---|---|---|
| Dual-realm JWT auth | `rsreach-security`, API filters | All customer contact APIs under `/api/v1/app/**` |
| Tenant/workspace context | `TenantWorkspaceContextFilter`, `TenantContext` | Required headers; fail closed |
| Scoped permissions | `CustomerAuthorizationService` | New contact permissions checked per active context |
| Tenancy / memberships | `rsreach-tenancy` | Membership validation; no new tenancy module |
| Audit | `rsreach-audit` / `audit_events` | Contact, consent, import, merge events |
| Outbox / jobs foundation | `OutboxRepository`, worker/scheduler processes | Async CSV import, dynamic group refresh, merge |
| Resource shares | `resource_shares` (V9) | Cross-workspace contact/group visibility |
| Error envelope | `ApiError`, `ErrorCode` | All new endpoints |
| Frontend auth + headers | `frontend/apps/web` `auth.ts` | Attach Bearer + `X-Tenant-Id` / `X-Workspace-Id` |
| Flyway V1–V9 | `rsreach-persistence` migrations | **Immutable** — next version starts at **V10** |

### What does not exist yet

- Contact, channel, consent, group, import tables
- Contact permissions in RBAC seed
- Domain library for contact policies (controllers must not own rules)
- Worker handlers for import / dynamic group materialization

### Skills that govern this phase

Per `.cursor/skills/WORKFLOWS.md` → **Contacts and consent**:

`rsreach-business-logic`, `rsreach-multitenancy`, `rsreach-database`, `rsreach-api-design`, `rsreach-security`, `rsreach-frontend`, `rsreach-campaigns` (consent/group constraints only), `rsreach-testing`, `rsreach-documentation`, plus `rsreach-backend`, `rsreach-background-jobs`, `rsreach-ui-ux`, `rsreach-frontend-api`.

---

## 2. Proposed modules and package structure

### ADR required (before coding)

**ADR 0003 — Contacts domain library module**

Introduce Maven module `rsreach-contacts` (capability library, same pattern as `rsreach-tenancy`):

- Depends on: `rsreach-common`, `rsreach-persistence`, `rsreach-security`, `rsreach-audit`, `rsreach-tenancy` (authorization helpers only)
- Depended on by: `rsreach-api`, `rsreach-worker` (and optionally scheduler for refresh scheduling)
- Must **not** depend on `rsreach-api` or Spring MVC
- Must **not** add a fourth OS process

### Package layout (proposed)

```text
rsreach-contacts/
  com.rsreach.contacts
    ContactApplicationService          # CRUD, archive/restore, search
    ContactMergeService
    ContactGroupService                # static + dynamic
    ConsentService
    SuppressionService
    ContactImportService               # validate/preview + enqueue
    ContactExportService
    DuplicateDetectionService
    model/                             # records / domain types
    persistence/                       # JDBC repositories (or shared via persistence module)
```

Schema SQL remains in `rsreach-persistence` Flyway. Repositories may live in `rsreach-contacts` using `JdbcTemplate` (preferred for Phase 0/1 style) unless an approved ADR moves them into persistence.

API controllers (thin):

```text
rsreach-api/.../web/contacts/
  ContactController
  ContactGroupController
  ConsentController
  ContactImportController
  ContactExportController
```

Frontend routes (customer app only):

```text
/app/contacts
/app/contacts/:id
/app/contacts/groups
/app/contacts/groups/:id
/app/contacts/import
/app/contacts/:id/consent
```

Staff platform UI for contacts is **out of scope** unless break-glass later; staff grants may eventually allow read, but Phase 2 ships customer portal only.

---

## 3. Database schema, relationships, indexes, and constraints

**New migrations only:** `V10__contacts_core.sql`, `V11__contact_groups_consent.sql`, `V12__contact_import_jobs.sql` (split for reviewability; exact filenames may be combined if preferred at implementation).

### 3.1 Ownership model

- Contacts are **workspace-owned**: every contact has `tenant_id` + `home_workspace_id`.
- Default visibility: home workspace only.
- Cross-workspace access: `resource_shares` with `resource_type` in (`CONTACT`, `CONTACT_GROUP`) and share permission `READ` or `WRITE`.
- Contacts are **not** `customer_users`. Optional nullable `linked_customer_user_id` reserved for a later “invite contact to login” flow (no invite UX in Phase 2 beyond the nullable column).

### 3.2 Core tables (conceptual)

#### `contacts`

| Column | Notes |
|---|---|
| `id` UUID PK | |
| `tenant_id`, `home_workspace_id` | Composite FK → `workspaces(tenant_id, id)` |
| `display_name` | Required trimmed |
| `status` | `ACTIVE` \| `ARCHIVED` |
| `primary_email_normalized` | Nullable; stored lowercased for search/dedup |
| `primary_phone_e164` | Nullable; E.164 |
| `custom_fields` JSONB | Key/value map; keys constrained by workspace field defs |
| `linked_customer_user_id` | Nullable FK → `customer_users` |
| `created_by`, `updated_by` | Customer user ids |
| `created_at`, `updated_at`, `archived_at` | TIMESTAMPTZ |
| `search_document` | Optional `tsvector` generated/maintained for directory search |

Indexes:

- `(tenant_id, home_workspace_id, status, updated_at DESC)`
- `(tenant_id, home_workspace_id, primary_email_normalized)` where not null
- `(tenant_id, home_workspace_id, primary_phone_e164)` where not null
- Unique soft-dedup recommendation index is **not** a hard unique on email alone (same email may appear across workspaces; within a workspace, unique active email/phone is enforced — see §5).

#### `contact_channels`

| Column | Notes |
|---|---|
| `id`, `tenant_id`, `workspace_id`, `contact_id` | Workspace composite FK + contact FK |
| `channel_type` | `EMAIL` \| `PHONE` (WhatsApp uses phone MSISDN later; store as `PHONE` with purpose tagging) |
| `value_raw`, `value_normalized` | Normalized for dedup |
| `is_primary` | At most one primary per `(contact_id, channel_type)` |
| `label` | Optional (work/personal) |
| `status` | `ACTIVE` \| `INVALID` \| `ARCHIVED` |

Unique: `(tenant_id, workspace_id, channel_type, value_normalized)` among **ACTIVE** contacts’ channels (partial unique index) to support duplicate detection.

#### `contact_custom_field_defs`

Workspace-scoped definitions: `key`, `label`, `data_type` (`STRING|NUMBER|DATE|BOOLEAN`), `required`, `active`. Caps the custom field surface; prevents unbounded free-form keys in imports.

#### `contact_groups`

| Column | Notes |
|---|---|
| `id`, `tenant_id`, `workspace_id` | Workspace-owned |
| `name`, `slug` | Unique `(tenant_id, workspace_id, slug)` |
| `group_type` | `STATIC` \| `DYNAMIC` |
| `filter_definition` JSONB | Required for DYNAMIC; null for STATIC |
| `status` | `ACTIVE` \| `ARCHIVED` |
| `materialized_at` | Last dynamic refresh |

#### `contact_group_members`

Static membership (and **materialized** dynamic membership snapshot):

- `(tenant_id, workspace_id, group_id, contact_id)` unique
- `source` = `MANUAL` \| `DYNAMIC` \| `IMPORT`
- Dynamic refresh replaces `DYNAMIC` rows only; does not wipe `MANUAL`

#### Consent & suppression

#### `contact_consents` (current state)

| Column | Notes |
|---|---|
| `contact_id`, `tenant_id`, `workspace_id` | |
| `channel_type` | `EMAIL` \| `WHATSAPP` (WhatsApp consent attaches to phone channel) |
| `purpose` | Enum: `MARKETING` \| `TRANSACTIONAL` \| `SERVICE` (extensible via check + future seed) |
| `status` | `OPTED_IN` \| `OPTED_OUT` \| `UNKNOWN` |
| `source` | `MANUAL` \| `IMPORT` \| `FORM` \| `API` \| `PROVIDER_WEBHOOK` (webhook unused in Phase 2) |
| `evidence` JSONB | Non-secret metadata (filename, operator note, form id) — **no** raw PII dumps beyond needed |
| `captured_at`, `updated_at` | |
| `updated_by` | Nullable |

Unique: `(tenant_id, contact_id, channel_type, purpose)`.

**Default:** new contacts start with `UNKNOWN` for all channel/purpose pairs that are created lazily on first view/edit — **never** inferred from presence of email/phone.

#### `contact_consent_events` (append-only history)

Every consent change inserts an event; current table is updated in the same transaction. Audit also records a high-level `consent.changed` via `AuditService`.

#### `contact_suppressions`

Workspace (or tenant-wide flag) suppression by normalized address:

- `(tenant_id, workspace_id, channel_type, value_normalized)` unique
- `reason`, `created_by`, `created_at`, `released_at`
- Suppressions block outbound eligibility in future campaigns; Phase 2 exposes CRUD + checks in export/import validation only.

#### Import jobs

#### `contact_import_jobs`

- `id`, tenant/workspace, `filename`, `status` (`PENDING|VALIDATING|PREVIEW_READY|RUNNING|COMPLETED|FAILED|CANCELLED`)
- `row_count`, `success_count`, `failure_count`
- `created_by`, timestamps
- Optional storage of staging rows in `contact_import_rows` (`row_number`, `payload` JSONB, `result`, `error_code`)

Large files: worker processes rows in batches via outbox event `CONTACT_IMPORT_PROCESS`.

---

## 4. Tenant/workspace isolation and contact sharing

### Rules

1. Every query includes `tenant_id` (and workspace predicate or share expand).
2. List/read in workspace W returns:
   - contacts with `home_workspace_id = W`, **or**
   - contacts shared **to** W via active `resource_shares` (`revoked_at IS NULL`) with `resource_type = 'CONTACT'`.
3. Write/update/archive/consent: allowed on home workspace with manage permission, or on shared contacts only if share `permission = 'WRITE'`.
4. Cross-tenant share is impossible (`resource_shares.tenant_id` scopes both workspaces).
5. Staff: no contact APIs in Phase 2 customer routes; future break-glass uses existing grants + audit (explicitly deferred).

### Sharing API (minimal Phase 2)

- `POST /api/v1/app/contacts/{id}/shares` — create share to target workspace
- `DELETE /api/v1/app/contacts/{id}/shares/{shareId}` — revoke  
  Reuses `resource_shares` table; no parallel share store.

Groups may be shared as `CONTACT_GROUP` (read membership list); dynamic group filter evaluation always runs in the **home** workspace of the group.

---

## 5. Contact lifecycle and duplicate handling

### Lifecycle

```text
CREATE (ACTIVE) → UPDATE → ARCHIVE → RESTORE (ACTIVE)
                 ↘ MERGE into survivor (loser ARCHIVED + merge record)
```

- Archive: soft; excluded from default directory (filter `status=ACTIVE`).
- Restore: clears `archived_at`.
- Delete hard: **not** in Phase 2 (privacy erase can be a later DPDP workflow).

### Duplicate detection

Triggers:

- Same workspace + same normalized email or phone among ACTIVE contacts
- Import preview flags duplicates against existing + within-file duplicates
- Manual “find duplicates” endpoint

Statuses: `EXACT_CHANNEL_MATCH`, `LIKELY_NAME_EMAIL`, `WITHIN_FILE`

### Merge strategy (MVP)

1. Operator selects **survivor** and **loser**.
2. Service validates same tenant + writable workspace.
3. In one transaction:
   - Move channels (skip conflicts; keep survivor primary)
   - Reassign static group memberships (union)
   - Consent: for each `(channel, purpose)`, survivor wins if `OPTED_OUT` or newer `OPTED_IN`; never downgrade opt-out to unknown without explicit operator choice (default: most restrictive wins)
   - Custom fields: survivor wins; fill blanks from loser
   - Mark loser `ARCHIVED`, write `contact_merge_events`
   - Audit `contact.merged`
4. Async: rebuild dynamic group memberships that referenced either id

---

## 6. Static and dynamic group design

### Static

- Explicit add/remove members
- Many-to-many; a contact may be in many groups
- Membership ≠ consent (enforced in API docs and campaign skill; Phase 2 UI copy states this)

### Dynamic

`filter_definition` JSON schema (versioned):

```json
{
  "version": 1,
  "match": "ALL",
  "conditions": [
    { "field": "status", "op": "eq", "value": "ACTIVE" },
    { "field": "primary_email_normalized", "op": "is_not_null" },
    { "field": "custom.city", "op": "eq", "value": "Pune" },
    { "field": "consent.EMAIL.MARKETING", "op": "eq", "value": "OPTED_IN" }
  ]
}
```

Allow-listed fields/ops only; reject unknown fields with `VALIDATION_FAILED`.

### Materialization

- On create/update of filter → enqueue `CONTACT_GROUP_REFRESH`
- Worker evaluates within tenant/workspace isolation, replaces `source=DYNAMIC` members
- Manual members preserved
- Directory “members” API returns union; UI badges show Manual vs Dynamic

### Discovery Question 7 (groups × channels)

**Assumption (documented, not a silent product lock-in):**  
Phase 2 contact groups are **channel-agnostic organization units**. Channel eligibility for sending remains a **future campaign** concern driven by consent + suppression + channel presence. This does **not** decide whether one communication group is shared across WhatsApp/email; it keeps groups organizational until that UX is approved.

---

## 7. Consent and suppression model

| Principle | Behavior |
|---|---|
| No inferred consent | Email/phone present ≠ opt-in |
| Channel-specific | EMAIL vs WHATSAPP tracked separately |
| Purpose-specific | MARKETING / TRANSACTIONAL / SERVICE |
| Evidence | Required for MANUAL opt-in (short note or source reference) |
| History | Append-only `contact_consent_events` |
| Suppression | Independent kill-switch by address; survives contact archive |
| Group membership | Never writes consent |

UI: consent panel on contact detail; bulk opt-out via import column mapping (explicit columns only).

---

## 8. API endpoints and authorization

Base: `/api/v1/app` + required `X-Tenant-Id` / `X-Workspace-Id`.

### New permissions (V10 seed)

| Code | Meaning |
|---|---|
| `contacts.read` | Directory, detail, group list, export |
| `contacts.manage` | Create/update/archive/restore/merge/share/groups |
| `contacts.consent.manage` | Consent + suppression changes |
| `contacts.import` | Import preview + commit |
| `contacts.export` | Export |

Role mapping (proposed):

- `TENANT_OWNER`, `TENANT_ADMIN`, `WORKSPACE_ADMIN`: all five
- `WORKSPACE_MEMBER`: `contacts.read` only  

(Adjustable before implementation if product wants members to manage.)

### Endpoints (summary)

| Method | Path | Permission |
|---|---|---|
| GET | `/contacts?query&status&page&size` | read |
| POST | `/contacts` | manage |
| GET | `/contacts/{id}` | read |
| PATCH | `/contacts/{id}` | manage |
| POST | `/contacts/{id}/archive` | manage |
| POST | `/contacts/{id}/restore` | manage |
| POST | `/contacts/{id}/merge` | manage |
| GET | `/contacts/duplicates` | read |
| GET/POST | `/contacts/{id}/channels` | read/manage |
| GET/PUT | `/contacts/{id}/consents` | read / consent.manage |
| GET/POST/DELETE | `/suppressions` | consent.manage |
| CRUD | `/contact-groups` | manage (read for GET) |
| POST/DELETE | `/contact-groups/{id}/members` | manage |
| POST | `/contact-groups/{id}/refresh` | manage |
| POST | `/contacts/import/preview` | import |
| POST | `/contacts/import/{jobId}/commit` | import |
| GET | `/contacts/import/{jobId}` | import |
| GET | `/contacts/export` | export |
| POST/DELETE | `/contacts/{id}/shares` | manage |

Pagination: `page`, `size` (max 100), response `{ items, page, size, hasNext }` per API skill.

Idempotency: `Idempotency-Key` on import commit and merge.

Errors: existing `ApiError` codes; add only if needed e.g. `CONFLICT` for merge/duplicate hard failure.

---

## 9. Frontend screens and user flows

Preserve existing customer shell (tenant/workspace selectors). No new design system library (no Tailwind/shadcn unless separately approved).

| Screen | Flow |
|---|---|
| Contact directory | Search, status filter, pagination, row → detail; CTA Create / Import |
| Contact detail | Profile, channels, custom fields, groups, consent panel, archive/restore, share |
| Create/Edit contact | Validated form; duplicate warning banner with link to existing |
| Groups list | Static/dynamic badge; create |
| Group detail | Members table; add/remove (static); filter editor + Refresh (dynamic) |
| Consent | Per channel/purpose toggles with evidence note; history timeline |
| Import | Upload CSV → preview table (ok/warn/error) → commit → job progress |
| Export | Filter mirrors directory → download CSV |

Empty/loading/error states follow `rsreach-frontend` patterns. Authorization: hide manage CTAs without permission; backend remains authoritative.

---

## 10. Import/export architecture

### CSV import

1. Upload (multipart) → store staging metadata (file bytes: **decision** — see §15; MVP may store parsed rows only in DB to avoid object storage ADR).
2. Sync validate first N rows for preview; full file validated async if large.
3. Column mapping UI: `display_name`, `email`, `phone`, `consent_email_marketing`, custom fields.
4. Commit enqueues worker; row-level results in `contact_import_rows`.
5. Duplicates: `SKIP` \| `UPDATE` \| `CREATE_ANYWAY` policy selected at commit (default `SKIP`).

### Export

- Stream CSV from API (or worker-generated download for large sets).
- Columns allow-list; consent columns included only with `contacts.consent.manage` or always as read-only status with `contacts.read` (propose: include consent status with `contacts.read`).
- Audit `contacts.exported` with filter summary (not full PII list at INFO).

---

## 11. Audit events and security considerations

### Audit actions (minimum)

`contact.created`, `contact.updated`, `contact.archived`, `contact.restored`, `contact.merged`, `contact.shared`, `contact.share_revoked`, `contact.group.created`, `contact.group.updated`, `contact.group.members_changed`, `consent.changed`, `suppression.created`, `suppression.released`, `contacts.import.committed`, `contacts.exported`

### Security

- Scoped permission checks via `CustomerAuthorizationService` (never principal flat union)
- No cross-tenant identifiers in responses for foreign ids (404/403 fail closed)
- Do not log raw phone/email lists at INFO
- Import evidence stores filename + counts, not full CSV in audit payload
- Staff realm unchanged; no platform contact browser in Phase 2
- Preserve Docker defaults: no dev token leakage, auth required

---

## 12. Testing plan

### Backend unit

- Normalization (email/phone)
- Duplicate detection ranking
- Consent merge “most restrictive wins”
- Dynamic filter allow-list validation
- Invitation/group ≠ consent invariants

### Backend integration (Testcontainers)

- Cross-tenant isolation on contacts/groups/consents
- Cross-workspace isolation + share READ/WRITE
- Missing context → 403
- Import preview/commit row results
- Permission matrix (member read-only vs admin manage)
- Archive excludes from default list; restore returns
- Concurrent import idempotency

### Frontend

- Directory renders empty/error/loading
- Create form validation
- Import preview shows error rows
- Consent toggles require evidence field

### Commands (verification)

```powershell
cd backend
mvn -B verify "-Dapi.version=1.44"

cd frontend/apps/web
npm run typecheck
npm run build
```

---

## 13. Implementation sequence and milestones

| Milestone | Deliverable | Exit criteria |
|---|---|---|
| **M0 — ADR & permissions** | ADR 0003, Maven `rsreach-contacts`, V10 permissions seed | Module compiles; roles have contact permissions |
| **M1 — Contact CRUD** | V10 contacts + channels + custom field defs; APIs; directory + detail UI | Isolation tests green; create/search/archive/restore |
| **M2 — Groups** | Static + dynamic groups; materialization job | Members API; refresh worker; UI |
| **M3 — Consent & suppression** | Consents, events, suppressions APIs + UI | No inferred consent; history; audit |
| **M4 — Duplicates & merge** | Detect + merge transaction | Merge tests; audit |
| **M5 — Import/export** | CSV preview/commit/export | Row-level report; authz |
| **M6 — Sharing polish & docs** | Share endpoints; runbook; Phase 2 verification report | Compose smoke; `mvn verify` 0 skipped when Docker up |

Estimated sequencing: M0→M1→M3 can partially parallel with M2 after contacts exist; M4/M5 depend on M1; M6 last.

---

## 14. Acceptance criteria

Phase 2 is complete when:

1. Contacts exist independently of login accounts and support CRUD, search, archive, restore.
2. Multiple emails/phones per contact; custom fields via definitions.
3. Static and dynamic groups work; membership does not grant consent.
4. Consent is channel- and purpose-specific with history; suppression works.
5. CSV import preview + commit with per-row results; export authorized.
6. All contact APIs enforce tenant/workspace isolation and RBAC.
7. Sensitive operations are audited.
8. Frontend covers directory, detail, groups, consent, import/export.
9. Flyway V1–V9 untouched; new versions only.
10. `mvn -B verify` and frontend build pass; Testcontainers suites execute when Docker is available.
11. No WhatsApp send, email send, or campaign execution implemented.

---

## 15. Risks, assumptions, and decisions requiring approval

### Assumptions (will proceed unless you object)

| ID | Assumption |
|---|---|
| A1 | Contacts are **workspace-owned** with optional cross-workspace `resource_shares`. |
| A2 | Contact groups are **channel-agnostic**; Question 7 remains open for campaigns. |
| A3 | Default consent state is **UNKNOWN**; never inferred from address presence. |
| A4 | `WORKSPACE_MEMBER` gets **read-only** contact access. |
| A5 | Hard delete / DPDP erase is **out of Phase 2** (archive only). |
| A6 | MVP import stores staged rows in PostgreSQL; **no** S3/MinIO until a storage ADR. |
| A7 | New module name is `rsreach-contacts` (capability library), introduced via ADR 0003. |
| A8 | WhatsApp consent uses channel type `WHATSAPP` bound to a phone channel value. |
| A9 | Dynamic group consent filters are allowed in filter DSL but do not change consent records. |

### Decisions needing your approval

| ID | Question | Recommendation |
|---|---|---|
| D1 | Confirm workspace-owned vs tenant-owned contacts | **Workspace-owned** (A1) |
| D2 | Member write access to contacts? | **No** — read only (A4) |
| D3 | Import conflict default | **SKIP** duplicates |
| D4 | Merge consent policy | **Most restrictive wins** |
| D5 | File binary storage for CSV | **DB staging rows only** (A6) |
| D6 | Expose contact share UI in Phase 2? | **Yes**, minimal share/revoke |
| D7 | Purpose enum final list | `MARKETING`, `TRANSACTIONAL`, `SERVICE` |

### Risks

| Risk | Mitigation |
|---|---|
| Dynamic filters become a full query language | Strict allow-list; versioned JSON; no raw SQL |
| Large imports block API | Preview sync + worker commit; batch size limits |
| PII in logs/audit | Audit summaries only; testing for log hygiene |
| Question 7 pressure to overfit groups to channels | Keep groups organizational; document explicitly |
| Permission sprawl | Four contact permissions only; map cleanly to existing roles |

### Explicitly out of scope for Phase 2

- WhatsApp / email sending, mailbox sync, campaigns, unified inbox
- Visual automation, AI enrichment
- Object storage service
- Native WhatsApp group management
- Enterprise SSO/SCIM
- Hard delete / legal hold workflows
- Staff Global Admin contact browser

---

## Approval gate

**No implementation starts until this plan is approved** (including D1–D7 or amended decisions).

Upon approval, implementation begins at **M0 (ADR 0003 + module + V10 permissions)** and proceeds through M1–M6 without expanding into Phase 3 communications.
