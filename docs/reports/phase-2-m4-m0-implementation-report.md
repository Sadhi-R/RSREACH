# Phase 2 Milestone M4.0 — Implementation Report

**Date:** 2026-11-02  
**Scope:** ADR 0005, Flyway V15 schema, migration Testcontainers tests  
**Not in scope:** M4.1 detection services, merge APIs/business logic, UI, worker scan processing

---

## ADR created

`docs/adr/0005-contact-duplicate-detection-and-merge.md` — **Accepted**

Captures approved M4 decisions: same-home-workspace candidates/merges, exact EMAIL/PHONE matching, no auto-merge, irreversible merges, ACTIVE-only channel movement, shared planner, most-restrictive + new-active-address → UNKNOWN consent, append-only events, untouched suppressions, stale revalidation, DISMISSED sticky, share revoke-without-transfer, PII-minimized evidence/items, request-hash idempotency, authz/audit/concurrency. Aligned with ADR 0003, ADR 0004, and the approved M4 plan §0 / §0.1.

---

## Migration

**File:** `backend/rsreach-persistence/src/main/resources/db/migration/V15__contact_duplicate_candidates_and_merges.sql`  
**V1–V14:** not modified (new file only).

### Schema changes

| Object | Change |
|---|---|
| `contacts` | Columns `merged_into_contact_id` (FK → `contacts`), `merged_at`; `chk_contacts_merged_pair`; index `idx_contacts_merged_into` |
| `contact_duplicate_candidates` | New table — same-home-workspace pairs |
| `contact_merges` | New table — completed merges + idempotency |
| `contact_merge_items` | New table — explainability rows (ids/enums) |
| `contact_consent_events` | `chk_contact_consent_event_type` extended with `MERGE_RECONCILED` |

### Constraints, indexes, foreign keys (summary)

**Candidates**

- FK `(tenant_id, workspace_id)` → `workspaces`
- FK both contacts → `contacts(tenant_id, home_workspace_id, id)` (enforces same home workspace)
- `CHECK (contact_id_a < contact_id_b)` canonical pair
- `UNIQUE (tenant_id, workspace_id, contact_id_a, contact_id_b)`
- Status / confidence / score / dismiss checks
- Indexes on `(tenant_id, workspace_id, status, score DESC)` and per-contact lookup

**Merges**

- FK workspace; composite FKs for survivor and loser (same home workspace)
- `UNIQUE (tenant_id, idempotency_key)`
- `request_hash`, `preview_hash` required
- Partial unique `uq_contact_merges_loser_completed` on `loser_contact_id`
- Indexes by workspace completed_at and survivor

**Merge items**

- FK → `contact_merges` ON DELETE CASCADE
- Allow-listed `item_type` / `action` checks (no email/phone columns)

**Consent**

- V14 append-only UPDATE/DELETE triggers unchanged

Evidence and snapshots are JSONB for match/channel **ids** and enum metadata only — no dedicated address columns.

---

## Tests

| Suite | Result |
|---|---|
| `ContactMergeMigrationTest` (4) | PASS — fresh V15; V14→V15 upgrade; same-WS/canonical/uniqueness/idempotency/FK; MERGE_RECONCILED + append-only |
| Prior persistence / Phase 0–3 / M1–M3 suites | PASS |
| Skipped | **0** |

---

## Verification

```text
mvn -B verify   # from backend/, Docker/Testcontainers enabled
→ BUILD SUCCESS
```

Frontend not changed by M4.0; typecheck/build not required for this slice.

---

## Deviations / unresolved

- None vs approved M4.0 scope.
- Test uses PostgreSQL `LEAST`/`GREATEST` for canonical UUID ordering (Java `UUID.compareTo` differs from PG).

---

## Gate

**M4.0 complete.** Do **not** start M4.1 until explicitly approved.
