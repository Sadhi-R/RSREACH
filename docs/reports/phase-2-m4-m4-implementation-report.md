# Phase 2 Milestone M4.4 — Hardening and Verification Report

**Date:** 2026-11-03  
**Re-verified:** 2026-11-03 (scope inspection + focused suites + full `mvn verify` + frontend)  
**Scope:** Hardening + full regression verify + §12 matrix gap fill (no new product features)  
**Prior:** M4.3 verification report green  
**Status:** DONE — M5 unblocked (not started in this turn per stop-before-M5 instruction)

---

## Scope (approved plan)

From `phase-2-remaining-milestones-execution-plan.md` §3 / M4 plan §13:

1. Map §12 critical cases (esp. 15–18, 30–31, 33–36, 39–42); add only missing coverage  
2. `mvn -B verify` with Testcontainers, skipped = 0  
3. Frontend typecheck + test + build  
4. Optional smoke against `rsreach_dev` (non-destructive)  
5. This report  
6. Plan status: M4.4 done → M5 unblocked  

**Out of scope:** New APIs, schema, wizard redesign, CSV, share management UI.

---

## Implementation note

M4.4 hardening was already applied earlier on this branch. Re-inspection found **no remaining §12 gaps** and **no approved work left to implement**. No Flyway edits; no API contract changes; previously approved milestones left untouched.

Hardening already present from prior M4.4 pass:

| Gap | Coverage |
|---|---|
| §12.18 | `ChannelMergePlannerTest#retainsSurvivorArchivedWithoutReactivation`; merge IT keeps survivor ARCHIVED |
| §12.34 | Detection IT: INVALID match → SUPERSEDED + preview 409 |
| §12.35 | Merge IT: stale `previewHash` after identifier change → 409, zero merge rows |
| §12.39 | Merge IT: `contact_merge_items` SHARE/REVOKED + `revoked_at` + `contact.share_revoked` audit |

---

## §12 critical matrix mapping

| # | Case | Covered by |
|---|---|---|
| 15 | Loser ACTIVE distinct email moved | `Phase2ContactM4MergeTest` (MOVED disposition) |
| 16 | Loser ARCHIVED not moved | Merge IT `LEFT_ARCHIVED` |
| 17 | Loser INVALID not moved | Merge IT `LEFT_INVALID` |
| 18 | Survivor ARCHIVED stays ARCHIVED | `ChannelMergePlannerTest` + merge IT RETAINED |
| 30 | Idempotency same key + same hash | Merge IT + `Phase2ContactM4MergeConcurrencyAtomicityTest` |
| 31 | Idempotency same key + different body | Merge IT 409 |
| 33 | Identifier removed → preview 409 / SUPERSEDED | Detection + Merge ITs |
| 34 | Channel INVALID after detect | Detection IT SUPERSEDED + preview 409 |
| 35 | Execute stale preview | Merge IT 409, no mutations |
| 36 | Rescan does not reopen DISMISSED | Detection IT |
| 39 | Merge revokes loser shares | Merge IT SHARE/REVOKED + audits |
| 40 | Rolled-back merge leaves shares active | ConcurrencyAtomicityTest rollback assertions |
| 41 | Never creates survivor shares from loser | Merge IT share-count assertions |
| 42 | No PII strings in evidence/items | Merge/detection ITs + serializer unit coverage |

---

## Verification (this turn)

```text
# Focused M4 suites
ChannelMergePlannerTest                         6/6 PASS Skipped 0
Phase2ContactM4DetectionTest                    1/1 PASS Skipped 0
Phase2ContactM4MergeTest                        1/1 PASS Skipped 0
Phase2ContactM4MergeConcurrencyAtomicityTest    8/8 PASS Skipped 0

# Full backend
cd backend
mvn -B verify -Dapi.version=1.44
→ BUILD SUCCESS (Failures 0, Errors 0, Skipped 0)

# Frontend
cd frontend/apps/web
npm run typecheck → PASS
npm test -- --run → 29/29 PASS
npm run build → PASS
```

Environment: JDK 17; Maven `C:\Users\Nityainc\AppData\Local\Temp\apache-maven-3.9.9\bin\mvn.cmd`; Docker/Testcontainers via `DOCKER_HOST=tcp://127.0.0.1:2375`; Compose postgres healthy (not mutated for this gate).

Optional native smoke against `rsreach_dev`: not required for M4.4 exit; deferred.

---

## Blockers

**None.**

---

## Gate

**M4.4 complete.** Acceptance criteria met. M5 is unblocked by the plan but **not started** in this turn (awaiting explicit proceed after this report).
