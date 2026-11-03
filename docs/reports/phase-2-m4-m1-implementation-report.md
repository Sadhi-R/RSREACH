# Phase 2 Milestone M4.1 — Implementation Report

**Date:** 2026-11-02  
**Scope:** Duplicate detection, workspace scanning (outbox/worker), candidate persistence, list/detail/dismiss/reopen APIs, audits  
**Not in scope:** Merge preview/execute, channel movement, consent reconciliation, share revocation, merge UI/wizard, M4.2+

---

## Schema verification (pre-implementation)

### 1. `contacts.merged_into_contact_id` scope

**Finding:** V15 defined `merged_into_contact_id UUID REFERENCES contacts (id)` only. That permitted a loser to point at a survivor in another tenant or home workspace.

**Correction (justified, separate migration):**  
`V16__contacts_merged_into_home_scope.sql`

- Drop `contacts_merged_into_contact_id_fkey`
- Add composite FK `fk_contacts_merged_into_home_scope`  
  `(tenant_id, home_workspace_id, merged_into_contact_id) → contacts (tenant_id, home_workspace_id, id)`

V1–V15 files were not edited.

### 2. Canonical UUID pair ordering

Application code uses `PostgresUuidOrdering.canonical` via PostgreSQL `LEAST`/`GREATEST`. Tests assert stored `contact_id_a` equals PG `LEAST` (Java `UUID.compareTo` must not be used).

### 3. Candidate evidence PII

Evidence JSON stores `matches[{matchType, channelType, channelIdA, channelIdB}]` plus `scoreBreakdown` / `score` only. No email/phone strings. Detail API resolves values through workspace-scoped channel reads under `contacts.read`.

---

## Migrations

| Version | File | Purpose |
|---|---|---|
| V15 | (M4.0) | Candidates, merges, merge items, merge marker |
| **V16** | `V16__contacts_merged_into_home_scope.sql` | Home-scope FK for `merged_into_contact_id` |

---

## Services

| Class | Role |
|---|---|
| `DuplicateDetectionService` | Exact EMAIL/PHONE match; ACTIVE/ARCHIVED only; exclude INVALID and merge losers; upsert pairs; supersede stale OPEN |
| `DuplicateCandidateRepository` | JDBC upsert (DISMISSED/MERGED sticky), list/detail rows, scan contact batches, evidence serialize/parse |
| `DuplicateCandidateQueryService` | List + detail with revalidation → SUPERSEDED when match gone; resolve channel values for authorized readers |
| `DuplicateCandidateService` | Enqueue scan (202), dismiss, reopen, worker `processScan` |
| `PostgresUuidOrdering` | PG-canonical pair ordering |
| `ContactService` | Calls `refreshForContact` after create/update/archive/restore |

---

## APIs

Base: `/api/v1/app`

| Method | Path | Authz | Behavior |
|---|---|---|---|
| `POST` | `/contact-duplicates/scans` | `contacts.manage` | **202** + `{scanId, status:PENDING}`; coalesce pending outbox per workspace |
| `GET` | `/contact-duplicates` | `contacts.read` | Page of candidates (default `status=OPEN`) |
| `GET` | `/contact-duplicates/{id}` | `contacts.read` | Detail + resolved matches; revalidate |
| `POST` | `/contact-duplicates/{id}/dismiss` | `contacts.manage` | Body `{reason}`; sticky across rescans |
| `POST` | `/contact-duplicates/{id}/reopen` | `contacts.manage` | Explicit only; refuses if evidence invalid |

Fail closed without tenant/workspace context. Cross-tenant/cross-workspace candidate ids → **404**. Resource shares do not expand visibility or authority. Members (`contacts.read` only) cannot scan/dismiss/reopen.

---

## Worker / scan behavior

- Outbox event type: `CONTACT_DUPLICATE_SCAN`
- Aggregate: `workspace` / workspace id
- `OutboxEventDispatcher` → `DuplicateCandidateService.processScan`
- Batched contact ids (`SCAN_BATCH=200`), workspace-scoped (`tenant_id` + `home_workspace_id`)
- Idempotent upsert on `(tenant_id, workspace_id, contact_id_a, contact_id_b)`
- Pending scans coalesced: second enqueue while PENDING returns the same `scanId`
- Outbox idempotency key: `DUP_SCAN:{uuid}` (fits `VARCHAR(128)`)

---

## Candidate rules (enforced)

- Same tenant + same home workspace only
- Exact normalized EMAIL/PHONE; INVALID excluded
- ACTIVE↔ARCHIVED (and ACTIVE↔ACTIVE only if uniqueness somehow bypassed — normally blocked by V11 unique index)
- No auto-merge
- DISMISSED not reopened by rescan; reopen is explicit + authorized
- Merge losers (`merged_into_contact_id IS NOT NULL`) excluded from OPEN generation
- Stale evidence → SUPERSEDED on detail/rescan
- Never log resolved addresses or evidence payloads at INFO

### Audits

- `contact.duplicate.scan_requested`
- `contact.duplicate.dismissed`
- `contact.duplicate.reopened`

---

## Tests

| Suite | Result |
|---|---|
| `Phase2ContactM4DetectionTest` | PASS — cases 1–22 covered in one Testcontainers flow |
| `ContactMergeMigrationTest` (V15+V16, upgrade, FK/canonical/idempotency, cross-WS merged_into rejected) | PASS |
| Phase 0–M3 / unit suites | PASS |
| Skipped | **0** |

---

## Verification

```text
mvn -B verify -Dapi.version=1.44   # backend/, Docker available
→ BUILD SUCCESS
```

(First full run hit a transient Testcontainers `ContainerLaunch` on `ContactConsentMigrationTest`; clean re-run succeeded with zero skips.)

Frontend unchanged for M4.1.

---

## Deviations

1. **V16** added (not in original M4.0 V15-only assumption) to close the merged_into cross-tenant/workspace FK gap before M4.1 detection work.
2. Outbox scan idempotency key shortened to `DUP_SCAN:{scanId}` to stay within `VARCHAR(128)`.

---

## Gate

**M4.1 complete.** Do **not** start M4.2 (merge preview/execute) until explicitly approved.
