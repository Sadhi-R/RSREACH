# Phase 2 Milestone M4.2.1 — Merge Concurrency and Atomicity Verification

**Date:** 2026-11-03  
**Scope:** Strengthen PostgreSQL Testcontainers concurrency/atomicity tests for contact merge; fix only defects those tests demonstrate  
**Not in scope:** M4.3 frontend; M5 CSV; API contract changes; Flyway V1–V16 edits; new infrastructure  
**Gate:** M4.2 approval remains **withheld** until explicit user approval. This report records verification only.

---

## Suite

`Phase2ContactM4MergeConcurrencyAtomicityTest` (Testcontainers PostgreSQL 16, Docker required, **0 skipped**).

Concurrent scenarios call `ContactMergeService.execute` from two worker threads after a shared `CountDownLatch` start barrier. Each call uses Spring `TransactionTemplate` / independent JDBC transactions against real PostgreSQL — not sequential double-invokes and not MockMvc worker threads.

---

## 1. Concurrent same Idempotency-Key

| Item | Detail |
|---|---|
| Test | `concurrentSameIdempotencyKeyProducesOneMerge` (`@RepeatedTest(3)`) |
| Setup | Same tenant, same `Idempotency-Key`, same canonical execute body |
| Concurrency | Two `ExecutorService` tasks; both await `ready` then release `start` together |
| Timeout | Both futures complete within 45s |

**Assertions**

- Both responses return the **same** merge `id`, survivor, and loser.
- `COUNT(*)` on `contact_merges` for that tenant + key = **1**.
- Exactly **one** `COMPLETED` merge for the loser.
- Merge items for that merge equal all items tied to the loser’s merges (no duplicates).
- Exactly **one** `audit_events` row with `action = 'contact.merged'` for that merge id.
- Consent `MERGE_RECONCILED` count is stable (no double write).
- Side effects: share revoked once; static membership on survivor only; dynamic group `PENDING` + outbox present once for the winner path.

**Outcome per repetition:** 2 successful responses (identical replay), **1** merge row.

---

## 2. Concurrent different keys, same loser

| Item | Detail |
|---|---|
| Test | `concurrentDifferentKeysSameLoserAllowOnlyOneMerge` (`@RepeatedTest(3)`) |
| Setup | Same loser/survivor/candidate; **different** idempotency keys |
| Concurrency | Same latch barrier + two independent transactions |

**Assertions**

- Success count = **1**; conflict/not-found count = **1** (`ErrorCode.CONFLICT` or `NOT_FOUND` as documented race outcomes).
- Exactly one `COMPLETED` merge for the loser; `contacts.merged_into_contact_id` = survivor.
- Exactly one `contact.merged` audit for the winner merge id.
- Same single-merge side-effect checks as §1.

**Outcome per repetition:** 1 winner + 1 conflicting request; no partial duplicate side effects.

---

## 3. Forced mid-merge transaction rollback

| Item | Detail |
|---|---|
| Test | `forcedFailureRollsBackAllMergeSideEffects` |
| Fixture | ACTIVE + non-ACTIVE channels; consent needing reconciliation; static memberships; dynamic group refresh; active loser CONTACT share; candidate OPEN |
| Failure injection | Test-only `MergeExecutionProbe` bean (`ProbeConfig`) throws **after** channel/consent/group/share mutations and **before** `contact_merges` insert. No production bean; no public failure endpoint |

**Rollback snapshot equality** (`Snapshot` before vs after failed execute)

| Domain / table | Asserted unchanged |
|---|---|
| `contact_channels` (ACTIVE counts for pair) | Yes |
| `contact_consents` projection | Yes |
| `contact_consent_events` (`MERGE_RECONCILED`) | Yes |
| `contact_group_members` (static group) | Yes |
| Dynamic `contact_groups.materialization_status` | Yes |
| `outbox_events` (`CONTACT_GROUP_REFRESH`) | Yes |
| `resource_shares` (still active) | Yes |
| `contact_merges` / merge items for loser | Zero |
| `audit_events` (`contact.merged` / `contact.share_revoked` referencing loser) | Zero |
| Candidate status + `merged_into_contact_id` | Unchanged (stale-candidate `REQUIRES_NEW` path not exercised here) |

Retry with probe disabled and a fresh key succeeds and leaves exactly one merge.

---

## 4. Idempotency race and recovery

| Item | Detail |
|---|---|
| Test | `sameKeyDifferentRequestReturnsConflictAndFailedTxLeavesNoCompletedRecord` (+ concurrent same-key race in §1) |

**Covered**

| Scenario | Result |
|---|---|
| Unique-key race (two same key+hash) | Handled via rollback + replay; both callers get the same merge id; **no unexplained 500** |
| Same key + same request | Replay original merge |
| Same key + different request body | **409** `CONFLICT` |
| Forced failure before persist | **Zero** rows for that idempotency key |
| Retry after rolled-back attempt | Succeeds with a new key (§3) |

---

## 5. Implementation review (locking / transactions)

Confirmed in `ContactMergeService`:

| Concern | Finding |
|---|---|
| Contact lock order | PostgreSQL-compatible UUID order via `PostgresUuidOrderingSupport` (`LEAST`/`GREATEST` semantics) before mutations |
| Idempotency | Pre-tx lookup; after-lock re-lookup; unique `(tenant_id, idempotency_key)` insert; race → `MergeIdempotencyRaceException` → facade rolls back then replays or returns **409** |
| Unique completed loser | App `isMergeLoser` after locks + DB unique completed-loser constraint; intentional conflict mapping |
| Same transaction | Share revoke, group reconcile, consent updates, outbox, merge row/items, audits all inside one `TransactionTemplate` execute |
| External side effects | None before commit (no provider calls in merge path) |
| Probe | Optional `ObjectProvider<MergeExecutionProbe>`; production has no bean |

### Production code changes required by these tests

| Change | Why |
|---|---|
| `TransactionTemplate` facade (not only `@Transactional` on `execute`) | Catch unique-key races **after** Spring rolls back the programmatic transaction, then safely replay — avoiding “return peer result while local mutations stay pending” |
| `MergeIdempotencyRaceException` | Signals unique violation so the facade can roll back + replay without an unexplained 500 |
| After-lock idempotency + loser checks | Peer may commit while the second tx waits on contact locks |
| `MergeExecutionProbe` | Deterministic test-only failure before merge persist; no production wiring |

**Flyway V1–V16:** unchanged. No schema defect found.

---

## 6. Verification

### Flake check (`Phase2ContactM4MergeConcurrencyAtomicityTest`)

| Pass | Result |
|---|---|
| Isolated run | 8 tests, 0 fail, 0 skip — BUILD SUCCESS |
| Repeat 1 | 8 / 0 / 0 — BUILD SUCCESS |
| Repeat 2 | 8 / 0 / 0 — BUILD SUCCESS |
| Repeat 3 | 8 / 0 / 0 — BUILD SUCCESS |

(`@RepeatedTest(3)` on the two concurrent methods ⇒ 3+3+2 = 8 method executions per suite run.)

### Full backend verify

```text
cd backend
mvn -B verify -Dapi.version=1.44
→ BUILD SUCCESS
→ Total time: 04:07 min
→ Module surefire totals: Failures 0, Errors 0, Skipped 0 (every module)
→ Phase2ContactM4MergeConcurrencyAtomicityTest: Tests run: 8, Failures: 0, Errors: 0, Skipped: 0
```

No existing tests were weakened or disabled.

---

## 7. Gate

- **M4.2.1 verification complete** (concurrency + atomicity evidence recorded).  
- **Do not treat M4.2 as approved** until explicit approval.  
- **Do not start M4.3 or M5.**
