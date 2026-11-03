# Phase 2 — Milestone M4: Duplicate Detection and Contact Merge

**Status:** APPROVED plan — **M4.0–M4.4 closed** (M4.3 verified; M4.4 hardening green). See `docs/reports/phase-2-m4-m3-verification-report.md` and `docs/reports/phase-2-m4-m4-implementation-report.md`. M5 unblocked.  
**Date:** 2026-11-02 (final planning revision; M4.4 verified 2026-11-03)  
**Depends on:** Phase 0, Phase 1, M0–M3 (closed); Flyway **V15** (M4.0) + **V16** home-scope merge FK (M4.1 schema safety)  
**Out of scope:** M5 CSV import/export, M6 share APIs, campaigns, messaging/sending, automatic merges, cross-workspace duplicate discovery

---

## 0. Review resolution (post-review corrections)

Three issues from the first plan review remain closed as R1–R3. Implementation must follow §0 (including §0.1) over any superseded wording.

### R1 — Prevent consent broadening when addresses are introduced (critical)

**Problem:** M3 consent is `contact × channel × purpose` (`contact_consents`), while eligibility (`ConsentEvaluationService.evaluate`) requires an explicit **ACTIVE** address on the contact and then applies that contact-level consent cell. Keeping `OPTED_IN` while transferring the loser’s distinct emails/phones would let a newly introduced address inherit eligibility from consent recorded for another contact/address set.

**Policy (normative):**

1. Compute address sets using **only channels that will be ACTIVE on the survivor after the merge** (§5.3 / §0.1 F1). Do not count ARCHIVED or INVALID channels toward “introduced” or post-merge eligibility sets.
2. **Newly introduced addresses** = loser’s addresses that become ACTIVE on the survivor and are **not** already among the survivor’s post-merge ACTIVE set for that storage channel (`value_normalized`).
3. Apply pairwise most-restrictive ranking (`OPTED_OUT > UNKNOWN > OPTED_IN`) as a first pass.
4. **If any newly introduced address exists for that consent channel**, the survivor’s result for **every purpose** on that channel **must not remain or become `OPTED_IN`**. Force **`UNKNOWN`** unless the first pass already produced **`OPTED_OUT`**.
5. `OPTED_IN` + `OPTED_IN` with distinct additional active addresses → **`UNKNOWN`**. Fresh explicit grant (M3 consent API + evidence) is required before eligibility.
6. If there are **no** newly introduced active addresses on that channel, `OPTED_IN` + `OPTED_IN` may remain `OPTED_IN`.
7. Preserve all original consent events on their original `contact_id`. Never UPDATE/DELETE loser events. Append `MERGE_RECONCILED` on the survivor when the current projection changes.
8. Suppression precedence and exact-address eligibility remain unchanged (ADR 0004 / M3).
9. Preview and `MergeConsentPolicy` **must use the same deterministic post-merge ACTIVE address set** (§5.3.1).

This is an enforcement rule in `MergeConsentPolicy`, not a UI warning.

### R2 — Same-home-workspace candidates only

- Detect and persist **only** same-home-workspace candidates.
- Do **not** persist or expose cross-workspace candidates.
- Cross-workspace merge attempts → `404 NOT_FOUND`.
- Cross-workspace duplicate discovery is a **future enhancement**.

### R3 — Minimize PII in candidate evidence

- Persist match type(s), matching `contact_channels.id` values, detection timestamps, and score/confidence metadata only.
- Resolve display values at read time via home-workspace-scoped channel queries under `contacts.read`.
- Never log evidence payloads or resolved addresses at INFO.

### 0.1 Final review resolution (implementation-detail lock)

Four further details are locked before M4.0 approval:

#### F1 — Channel status during merge and consent address calculation

| Loser / survivor channel `status` | Merge behavior |
|---|---|
| Survivor `ACTIVE` | **Retained** on survivor (unchanged unless primary flag adjusted per preview). |
| Survivor `ARCHIVED` | **Retained as ARCHIVED.** Never silently reactivated by merge. Not in post-merge ACTIVE address set. |
| Survivor `INVALID` | **Retained as INVALID.** Never silently reactivated. Excluded from ACTIVE address set and from eligibility. |
| Loser `ACTIVE`, value **not** already ACTIVE on survivor | **Moved** to survivor as `ACTIVE` (reassign `contact_id` / workspace scope to survivor). Counts as **newly introduced** if normalized value absent from survivor’s post-merge ACTIVE set. |
| Loser `ACTIVE`, value **already ACTIVE** on survivor | **Deduped:** keep survivor’s ACTIVE row; loser row **archived or deleted** as duplicate (prefer set loser channel `ARCHIVED` then drop with contact archive, or delete loser channel row in-tx). Does **not** introduce a new address. |
| Loser `ARCHIVED` | **Not moved. Not reactivated.** Remains on loser (archived contact). Excluded from post-merge ACTIVE set and from “introduced.” |
| Loser `INVALID` | **Not moved. Not reactivated.** Remains on loser. Excluded from ACTIVE set and from “introduced.” |

**Deterministic post-merge ACTIVE set** (shared by preview and `MergeConsentPolicy`):

```text
postMergeActive(storageType) =
  { survivor channels | type=storageType AND status=ACTIVE after merge ops }
  ∪ { loser ACTIVE channels moved onto survivor for storageType }
  // minus deduped values already represented by survivor ACTIVE rows
```

`introduced(storageType) = postMergeActive(storageType) \ preMergeSurvivorActive(storageType)`  
where `preMergeSurvivorActive` is survivor’s ACTIVE set **before** merge ops.

Primaries: operator may choose among **post-merge ACTIVE** channels only; defaults keep survivor primaries.

Detection (§4) may still **match** on ARCHIVED channels for candidate discovery (ACTIVE↔ARCHIVED duplicates). That does not reactivate ARCHIVED channels at merge time.

#### F2 — Stale candidate reconciliation

1. **Exact-match still valid** iff at least one evidence match still holds: both referenced channel rows exist, belong to the candidate’s two contacts, share the same `channel_type` + `value_normalized`, and each channel status is in `{ACTIVE, ARCHIVED}` (INVALID does not sustain a match).
2. **Preview** revalidates all evidence matches. If none remain valid → return `409 CONFLICT` with reason `CANDIDATE_STALE` (or equivalent ApiError message); do not return a mergeable plan. Optionally mark candidate `SUPERSEDED` in the same request transaction.
3. **Execute** revalidates again under contact row locks **before** mutations. Stale → `409`; no partial writes.
4. **`previewHash`** incorporates the canonical post-merge plan **and** a fingerprint of currently valid match channel ids / normalized equality proofs (ids + types only, not address strings). Stale identifiers invalidate the hash.
5. **SUPERSEDED** when: exact-match condition no longer exists; either contact becomes a merge loser; or both sides no longer share any ACTIVE/ARCHIVED identifier pair. SUPERSEDED is terminal for merge from that candidate (operator may run a new scan if a new pair appears).
6. **Rescans / upserts:** if candidate `status = DISMISSED`, update `last_detected_at` only when match still exists; **never** set status back to OPEN. Only explicit `reopen` (manage) may return DISMISSED → OPEN, and only if match still valid; else `409`.
7. OPEN candidates that fail revalidation → SUPERSEDED (not DISMISSED).

#### F3 — Resource-share handling

- **Never** copy or transfer loser `resource_shares` onto the survivor.
- On successful merge, within the **same atomic transaction**: for every active share (`revoked_at IS NULL`) where `resource_type = 'CONTACT'` and `resource_id = loser_contact_id`, set `revoked_at = now()` (and optional `revoked_by` if column exists—today V9 has no `revoked_by`; use audit for actor).
- **Audit action:** `contact.share_revoked` (already named in the Phase 2 parent plan) **once per revoked share row**, metadata JSON **without PII**:  
  `{ "shareId", "mergeId", "loserContactId", "survivorContactId", "sourceWorkspaceId", "targetWorkspaceId", "permission", "reason": "CONTACT_MERGED" }`.
- Also record a `contact_merge_items` row per revocation: `item_type=SHARE`, `action=REVOKED`, keys/ids only.
- If merge rolls back, share revocations roll back with it.
- Active shares **never** grant cross-workspace merge or consent authority (ADR 0004; Phase 1 scoped authz).

#### F4 — PII minimization for merge items + idempotency binding

**`contact_merge_items` / merge snapshots must not store:**

- Raw or normalized email/phone strings  
- Consent evidence notes  
- Full contact JSON dumps  
- Unnecessary personal-data snapshots  

**Allowed:** field keys (`display_name`), channel UUIDs, consent `CHANNEL:PURPOSE`, share UUIDs, enum actions, before/after **status enums** and UUID references, counts.

History UIs resolve display values through authorized reads of contacts/channels.

**Idempotency:**

- Require `Idempotency-Key` on `POST /contacts/merges`.
- Compute `request_hash` = SHA-256 (or equivalent) over a **canonical JSON** of the execute body (survivor/loser ids, fieldChoices, primary channel ids, `previewHash`, `confirm`) with sorted keys.
- Persist `(tenant_id, idempotency_key, request_hash, merge_id)`.
- Same key + **same** `request_hash` → return original `201`/`200` MergeView (idempotent replay).
- Same key + **different** `request_hash` → `409 CONFLICT`.
- `previewHash` mismatch vs recomputed plan → `409` (stale plan), distinct from idempotency payload conflict.

---

## 1. Repository findings (source of truth)

### 1.1 What already exists

| Area | Location / behavior |
|---|---|
| Contacts module | `rsreach-contacts` (ADR 0003); thin controllers in `rsreach-api` |
| Schema | V11 contacts/channels/custom fields; V12 groups/members; V13–V14 consent/suppression |
| Ownership | `contacts.home_workspace_id`; channels/groups keyed by `(tenant_id, workspace_id)` |
| Identifier uniqueness | Partial unique `uq_contact_channel_active_normalized` where channel `status = 'ACTIVE'` |
| Channel statuses | `ACTIVE` \| `ARCHIVED` \| `INVALID` (V11) |
| Create/update/restore | `ContactService` → `409` on active identifier collisions; restore checks active uniqueness |
| Archive | Soft-archive; channels → `ARCHIVED` (releases unique slot) |
| Normalization | `ContactIdentifierNormalizer` |
| Consent | ADR 0004; append-only events (V14 triggers); EMAIL/WHATSAPP × purposes |
| Eligibility | `ConsentEvaluationService` — explicit address; must be **ACTIVE** on contact |
| Groups | Static + DYNAMIC; home-workspace composite FKs |
| Shares | `resource_shares` (V9); `revoked_at`; no share-aware contact APIs (M6) |
| Permissions / authz | V10 codes; Phase 1 `CustomerAuthorizationService` |
| Audit / outbox | `AuditService`; outbox for group refresh (extend for duplicate scan) |
| Migrations | V1–V14 immutable; M4 → **V15** only when implementation approved |

### 1.2 Detection implication

ACTIVE–ACTIVE exact duplicates cannot exist in-workspace. M4 targets ACTIVE↔ARCHIVED (and ARCHIVED↔ARCHIVED) exact matches. Cross-workspace discovery is out of M4 (§0 R2).

### 1.3 Prior plan defaults

Operator-selected survivor/loser; no auto-merge; consent §0 R1 + §6; dynamic group refresh via outbox; manage + consent.manage for merge; Idempotency-Key (§0.1 F4).

---

## 2. Functional requirements

### 2.1 Must

1. Same-home-workspace exact EMAIL/PHONE candidate detection and persistence.
2. Review workflow with read-time address resolution.
3. Atomic merge with §5.3 channel rules and §6 consent policy.
4. ADR 0004 safety; preview/policy share one ACTIVE address-set function.
5. Tenant/workspace fail-closed isolation.
6. Audit without INFO address dumps; share revocations audited (§0.1 F3).
7. Idempotency-Key bound to canonical request hash (§0.1 F4).
8. Stale candidate revalidation on preview and execute (§0.1 F2).
9. Never auto-merge; never persist cross-workspace candidates.

### 2.2 Must not

- Silently reactivate ARCHIVED or INVALID channels.
- Count non-ACTIVE channels as newly introduced for consent.
- Transfer loser shares to survivor.
- Store emails/phones in candidate evidence or merge_items.
- Auto-reopen DISMISSED on rescan.
- Merge when exact-match evidence is stale.
- Cross-workspace merge; reverse completed merges; start M5.

### 2.3 Should

- Preview lists introduced **active** addresses and consent downgrades.
- Enqueue dynamic group refresh.
- Contact-detail banner for OPEN candidates.

---

## 3. Architecture and workflow

```text
Detection (same WS) → candidates (channel-id evidence)
        → preview: revalidate match + ChannelMergePlan + MergeConsentPolicy
        → execute: lock → revalidate → mutate → revoke shares → audit → outbox
```

Services in `rsreach-contacts`: `DuplicateDetectionService`, `DuplicateCandidateQueryService`, `MergePreviewService`, `ContactMergeService`, `MergeConsentPolicy`, `ChannelMergePlanner` (deterministic ACTIVE set + move/dedupe plan). Reuse repositories, `AuditService`, outbox.

---

## 4. Duplicate detection rules and scoring

### 4.1 Fields

ACTIVE and ARCHIVED channels participate in **matching**. INVALID never matches. Fuzzy deferred.

### 4.2 Match types

`EXACT_EMAIL`, `EXACT_PHONE`, `EXACT_EMAIL_AND_PHONE` — same home workspace only. Scores 80 / 80 / 100.

### 4.3 Generation

Canonical pair ids; upsert OPEN if new; DISMISSED never auto-reopened (§0.1 F2); ignore cross-workspace hits.

### 4.4 Lifecycle

| Event | Candidate status |
|---|---|
| Match valid, new pair | OPEN |
| Rescan, was DISMISSED, still matches | stay DISMISSED; bump `last_detected_at` |
| Match no longer valid (OPEN) | SUPERSEDED |
| Successful merge | MERGED |
| Either contact is merge loser | SUPERSEDED |

### 4.5 False-positive prevention

No auto-merge; DISMISSED sticky; INVALID excluded from match sustainment; authorized resolve on read.

---

## 5. Merge reconciliation rules

### 5.1 Preconditions

Same tenant + home workspace; permissions; not already merged; survivor ≠ loser; valid `previewHash`; empty `blockingConflicts`; **evidence revalidation passed**; else 404/409 as specified.

### 5.2 Survivor selection

Operator chooses; UI may suggest ACTIVE over ARCHIVED.

### 5.3 Per-entity rules

| Entity | Rule |
|---|---|
| Fields / custom / linked user | Unchanged from prior revision (linked user conflict blocks) |
| **Channels** | Exact table in §0.1 F1 — no silent reactivation |
| Consent | §6 using `ChannelMergePlanner.postMergeActive` |
| Consent events | Immutable on original contact_id |
| Suppressions | Untouched |
| Static / dynamic groups | Union static; refresh dynamic via outbox |
| **Shares** | Revoke loser active CONTACT shares only (§0.1 F3); never transfer |
| Audit | `contact.merged` + per-share `contact.share_revoked` + consent.changed per changed cell |

### 5.3.1 Shared planner contract

```text
ChannelMergePlanner.plan(survivor, loser, primaryChoices) →
  moves[], dedupeLoserChannelIds[], retainedArchivedOrInvalid[],
  postMergeActiveByType, introducedByType, previewFingerprint
```

`MergePreviewService` and `ContactMergeService` / `MergeConsentPolicy` consume this plan only—no divergent address logic.

### 5.4 Loser terminal state

ARCHIVED + `merged_into_contact_id` + `merged_at`. Restore blocked with 409.

### 5.5 Merge records

`contact_merges`: status COMPLETED, `idempotency_key`, `request_hash`, `preview_hash`, actor, timestamps.  
`contact_merge_items`: ids/enums only (§0.1 F4).

---

## 6. Consent and suppression safety matrix

### 6.1 Invariants

As R1 + never broaden via non-ACTIVE channels; eligibility still requires ACTIVE address post-merge (M3 service).

### 6.2 Algorithm

```text
for each consent channel C in {EMAIL, WHATSAPP}:
  storageType = WHATSAPP → PHONE else EMAIL
  introduced = plan.introducedByType(storageType)   // ACTIVE-only
  for each purpose P:
    base = mostRestrictive(survivor[C,P], loser[C,P])  // missing → UNKNOWN
    result = (introduced non-empty AND base == OPTED_IN) ? UNKNOWN : base
    if result != survivor[C,P]: update + MERGE_RECONCILED + consent.changed audit
```

### 6.3–6.4 Examples

Unchanged intent: OPTED_IN+OPTED_IN with new **active** address → UNKNOWN; overlapping only → may stay OPTED_IN; OPTED_OUT stands; loser ARCHIVED-only addresses do **not** force UNKNOWN.

### 6.5 Events / suppression / preview

`MERGE_RECONCILED` evidence uses channel ids + mergeId. Suppression unchanged. Preview must show `introducedAddresses` (resolved ACTIVE only), consent diffs, `requiresFreshConsent`, and channel disposition summary (moved / deduped / left archived / left invalid).

### 6.6 Cross-workspace / shares

No candidates; merge 404; shares do not authorize (§0.1 F3).

---

## 7. Tenant / workspace authorization

| Action | Permission |
|---|---|
| List/get candidates, history | `contacts.read` |
| Dismiss / reopen | `contacts.manage` |
| Preview / execute | `contacts.manage` + `contacts.consent.manage` |

Phase 1 scoped authz; missing context 403; cross-tenant/home 404; shares never expand merge authority.

---

## 8. Database schema proposal (V15 — do not create yet)

### 8.1 `contacts` merge columns

`merged_into_contact_id`, `merged_at` + check + index (as before).

### 8.2 `contact_duplicate_candidates`

As before; evidence = channel ids + match meta only; statuses OPEN|DISMISSED|MERGED|SUPERSEDED; composite FKs enforce same home workspace.

### 8.3 `contact_merges`

Add **`request_hash VARCHAR(64) NOT NULL`** alongside `idempotency_key`.  
UNIQUE `(tenant_id, idempotency_key)`. Store `request_hash` for conflict detection on replay.

### 8.4 `contact_merge_items`

PII-prohibited payloads (§0.1 F4). item_type includes SHARE with action REVOKED.

### 8.5 Consent event type

Allow `MERGE_RECONCILED` (V15 only).

### 8.6 Transactions

Detect upsert short tx; merge one tx (locks → revalidate → channels → consent → groups → share revokes → merge rows → audits → outbox); worker refresh separate.

---

## 9. API endpoint specifications

| Endpoint | Notes |
|---|---|
| `POST /contact-duplicates/scans` | Workspace-only scan |
| `GET /contact-duplicates` | No cross-WS; ids/scores; optional resolved values via join |
| `GET /contact-duplicates/{id}` | Resolve channel ids; if all matches invalid, surface `stale: true` |
| Dismiss / reopen | Reopen only if match still valid; else 409 |
| `POST /contacts/merges/preview` | Revalidate evidence; build ChannelMergePlan; 409 if stale |
| `POST /contacts/merges` | Idempotency-Key + request_hash rules; revalidate under lock; 409 stale/idempotency mismatch |
| Merge history GETs | Ids only in items; resolve on detail read |

---

## 10. Frontend design

Duplicates list/compare, merge wizard with: channel disposition (moved vs left archived/invalid), introduced **active** addresses, consent downgrades, share-revoke notice (“existing shares on the merged-away contact will be revoked”), confirm re-opt-in. No cross-workspace UI. Handle 409 stale with “identifiers changed—re-scan.”

---

## 11. Transactions, concurrency, recovery

- Lock contacts by UUID order.  
- Revalidate candidate under lock.  
- Atomic merge including share revokes.  
- DISMISSED sticky.  
- No reverse.  
- Idempotent replay per §0.1 F4.  
- Dynamic refresh eventual via outbox.

---

## 12. Test matrix

Testcontainers; Docker required; critical suites **0 skipped**.

| # | Case | Expected |
|---|---|---|
| 1–8 | Detection / dismiss / reopen basics | As prior (same-WS exact match; DISMISSED sticky; INVALID ignored for match) |
| 9–14 | Consent pairwise + introduced ACTIVE addresses | R1 / §6 |
| 15 | Loser ACTIVE distinct email moved | ACTIVE on survivor; introduced → UNKNOWN if was OPTED_IN |
| 16 | Loser ARCHIVED email only | Not moved; not introduced; no forced UNKNOWN from that address |
| 17 | Loser INVALID channel | Not moved; not introduced |
| 18 | Survivor ARCHIVED channel | Stays ARCHIVED; not silently ACTIVE |
| 19 | Dedupe identical ACTIVE on both | One ACTIVE; not introduced |
| 20 | Custom fields / groups / linked user | Prior rules |
| 21 | Cross-WS no candidate / merge 404 | R2 |
| 22–24 | Authz member / missing consent.manage / cross-tenant | 403/404 |
| 25–28 | Suppression, eligibility ACTIVE-only, events append-only | M3 compatible |
| 29 | Concurrent merge same loser | one ok, one CONFLICT |
| 30 | **Idempotency same key + same request_hash** | Same merge id/result |
| 31 | **Idempotency same key + different body** | 409 CONFLICT |
| 32 | Stale previewHash | 409 |
| 33 | **Identifier removed after candidate created** | Preview 409; candidate SUPERSEDED |
| 34 | **Identifier changed / channel INVALID after detect** | Preview/execute 409; SUPERSEDED |
| 35 | **Execute with stale preview after revalidate fail** | 409; no mutations |
| 36 | Rescan does not reopen DISMISSED | status DISMISSED |
| 37 | Restore merge loser | 409 |
| 38 | Share does not authorize merge from other WS | 404 |
| 39 | **Successful merge revokes loser active shares** | revoked_at set; `contact.share_revoked` audits; merge_items SHARE/REVOKED |
| 40 | **Failed/rolled-back merge leaves shares active** | revoked_at still null |
| 41 | **Never creates survivor shares from loser** | no new share rows for survivor |
| 42 | Candidate evidence / merge_items have no email/phone strings | Assert |
| 43 | Detail resolves addresses; logs do not dump them | |
| 44 | Audit `contact.merged` | Present |
| 45 | Phase 0/1/M1/M2/M3 regression | PASS |

Unit: `ChannelMergePlanner`, `MergeConsentPolicy` (ACTIVE-only introduced), evidence/merge_item serializers, request_hash canonicalization.

---

## 13. Implementation milestones

| Slice | Deliverable | Exit |
|---|---|---|
| **M4.0** | ADR 0005 (§0 R1–R3 + §0.1 F1–F4); V15 schema (`request_hash`); migration test | **DONE** — Flyway green |
| **M4.1** | Detection + scan + candidates + SUPERSEDED/DISMISSED rules; V16 merged_into home-scope FK | **DONE** — same-WS candidates; stale/dismiss tests |
| **M4.2** | ChannelMergePlanner + preview/execute + consent + shares + idempotency | **DONE** — approved with M4.2.1 |
| **M4.2.1** | Concurrent idempotency / same-loser / rollback / race recovery Testcontainers verification | **DONE** — `docs/reports/phase-2-m4-m2-1-concurrency-atomicity-report.md` |
| **M4.3** | Frontend duplicates UI + merge wizard + history | **DONE** — implemented + verified (`phase-2-m4-m3-verification-report.md`) |
| **M4.4** | Hardening + `mvn verify` + report | **DONE** — §12 gaps closed; `mvn verify` 0 skipped (`phase-2-m4-m4-implementation-report.md`) |

M4 complete. Proceed to M5 per Phase 2 contacts plan / execution plan.

---

## 14. Risks, assumptions, unresolved decisions

### Assumptions

| ID | Content |
|---|---|
| A1–A8 | Prior (same-WS only; dual permissions; R1; fuzzy deferred; irreversible; DISMISSED sticky; no cross-WS discovery; import in M5) |
| A9 | ARCHIVED/INVALID never auto-reactivated on merge |
| A10 | Idempotency binds key + canonical request_hash |
| A11 | Share revoke uses audit action `contact.share_revoked` with reason CONTACT_MERGED |

### Risks

| Risk | Mitigation |
|---|---|
| OPTED_IN→UNKNOWN surprise | Preview + confirm |
| Stale candidates after edits | Revalidate preview+execute; SUPERSEDED |
| Operators expect ARCHIVED loser channels to “come along” | Preview disposition list; F1 table |
| V9 shares lack revoked_by column | Actor in audit metadata only |
| Contact-level consent vs per-address | Conservative UNKNOWN; future ADR if needed |

### Unresolved

None blocking. Default: `consent.changed` audit **per cell that changes**, plus one `contact.merged`, plus per-share `contact.share_revoked`.

---

## 15. Acceptance criteria

1. V15 only when implementation authorized; V1–V14 untouched.  
2. Same-home-workspace candidates only; evidence = channel ids + meta.  
3. Channel merge follows §0.1 F1; no silent reactivation; consent introduced set = post-merge ACTIVE only.  
4. Preview and execute revalidate matches; stale → 409; DISMISSED not auto-reopened; SUPERSEDED when match gone.  
5. Loser shares revoked in-tx, audited, never transferred; rollback retains shares; shares ≠ merge authz.  
6. merge_items/snapshots PII-minimized; idempotency same hash replay / different hash 409.  
7. Consent §6 / R1; append-only events; suppression + exact ACTIVE address eligibility preserved.  
8. Test matrix §12 including F1–F4 cases (15–18, 30–31, 33–36, 39–42); no skipped critical tests.  
9. Frontend reflects dispositions, downgrades, stale errors.  
10. M4.0 not started until explicit approval; M5 not started until M4.4.

---

## 16. Skills / docs at implementation time

Contacts workflow skills; ADR **0005** at M4.0 must encode §0 R1–R3 and §0.1 F1–F4, consistent with ADR 0003, ADR 0004, V11–V14, groups, audit, outbox, and Phase 1 authorization.

---

**End of final planning revision. Stop for explicit approval before any implementation.**
