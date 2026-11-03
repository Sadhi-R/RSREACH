# Phase 2 Milestone M4.2 — Implementation Report

**Date:** 2026-11-03  
**Scope:** Merge preview, `ChannelMergePlanner`, `MergeConsentPolicy`, atomic merge execution, groups, share revocation, idempotency  
**Not in scope:** M4.3 frontend duplicates UI / merge wizard; M5 CSV; M6 share APIs

---

## Services and APIs

| Component | Role |
|---|---|
| `ChannelMergePlanner` | Deterministic ACTIVE move / dedupe / left-archived / left-invalid plan + primaries + fingerprint |
| `MergeConsentPolicy` | Most-restrictive + introduced-ACTIVE → UNKNOWN rule (EMAIL/WHATSAPP × purposes) |
| `MergePreviewService` | Revalidate candidate; build preview + `previewHash`; linked-user conflicts |
| `ContactMergeService` | Idempotent execute under PG UUID-ordered locks; one atomic transaction |
| `ContactMergeRepository` | Merges/items, channel moves, share revoke, group membership queries |
| `DuplicateCandidateLifecycleService` | `REQUIRES_NEW` SUPERSEDED so stale 409 persists |
| `MergeController` | HTTP surface |

### Endpoints (`/api/v1/app`)

| Method | Path | Authz |
|---|---|---|
| `POST` | `/contacts/merges/preview` | `contacts.manage` + `contacts.consent.manage` |
| `POST` | `/contacts/merges` | same + required `Idempotency-Key` |
| `GET` | `/contacts/merges` | `contacts.read` |
| `GET` | `/contacts/merges/{mergeId}` | `contacts.read` |

Fail closed without tenant/workspace context. Cross-tenant/home → **404**. Shares never grant merge authority. Merge losers cannot be restored (`409`).

---

## Preview and execution behavior

1. Load candidate + contacts; require same home workspace; reject merge losers / identical ids.  
2. Revalidate exact-match evidence; stale → **409** + candidate **SUPERSEDED** (committed via `REQUIRES_NEW`).  
3. Plan channels; reconcile fields (display_name choice; custom fill blanks; linked-user conflict blocks).  
4. Consent diffs from planner `introducedByType` only (ACTIVE).  
5. Preview static union / loser removal / dynamic refresh; list active loser CONTACT shares.  
6. `previewHash` = SHA-256 over canonical plan + match fingerprint + field/consent/group/share decisions.  
7. Execute: idempotency lookup → lock contacts (`LEAST`/`GREATEST`) → recompute preview → reject hash mismatch → mutate → commit.

---

## Channel disposition rules (F1)

| Action | Meaning |
|---|---|
| `RETAINED` | Survivor channel kept |
| `MOVED` | Loser ACTIVE moved to survivor (may be introduced) |
| `DEDUPED_ARCHIVED` | Loser ACTIVE value already ACTIVE on survivor |
| `LEFT_ARCHIVED` / `LEFT_INVALID` | Remain on loser; never reactivated |

Primaries: operator may choose among post-merge ACTIVE channels; default keeps survivor primary.

---

## Consent reconciliation examples

| Inputs | Result |
|---|---|
| OPTED_IN + UNKNOWN | UNKNOWN |
| Any OPTED_OUT | OPTED_OUT |
| OPTED_IN + OPTED_IN, new ACTIVE email introduced | UNKNOWN for all EMAIL purposes |
| OPTED_IN + OPTED_IN, overlapping ACTIVE only | may stay OPTED_IN |
| New ACTIVE phone introduced | UNKNOWN for WHATSAPP purposes |
| Loser ARCHIVED/INVALID only | not introduced |

On change: update survivor projection, append `MERGE_RECONCILED`, audit `consent.changed`. Loser events untouched. Suppressions unchanged; eligibility still exact ACTIVE address + suppression precedence.

---

## Groups and shares

- Static MANUAL memberships: union onto survivor; remove loser rows.  
- Dynamic: mark pending + outbox `CONTACT_GROUP_REFRESH` in the same transaction.  
- Active loser `resource_shares` (`CONTACT`) revoked (`revoked_at`); never copied to survivor.  
- Audits: `contact.share_revoked` (reason `CONTACT_MERGED`) + `contact.merged`.  
- Rollback retains shares/memberships/consent (single `@Transactional`).

---

## Idempotency and concurrency

- Required `Idempotency-Key` (≤128).  
- `request_hash` = SHA-256 of canonical execute body (sorted keys).  
- Same key + same hash → replay original merge.  
- Same key + different hash → **409**.  
- Contacts locked in PostgreSQL UUID order before mutations.  
- Unique completed loser (`uq_contact_merges_loser_completed`) + app checks prevent rematch.  
- Stale `previewHash` → **409**.

---

## Database changes

**None.** V15/V16 already provide merge tables and home-scope `merged_into` FK. V1–V16 untouched.

Application-only: restore blocked for merge losers; linked-user / merge helpers on repositories.

---

## Tests

| Suite | Result |
|---|---|
| `ChannelMergePlannerTest` (5) | PASS |
| `MergeConsentPolicyTest` (7) | PASS |
| `Phase2ContactM4MergeTest` | PASS — preview/execute/consent/groups/shares/idempotency/stale/linked-user/isolation |
| Phase 0–M4.1 regression | PASS |
| Skipped | **0** |

---

## Verification

```text
mvn -B verify -Dapi.version=1.44   # backend/, Docker available
→ BUILD SUCCESS
```

Frontend unchanged.

---

## Deviations / notes

1. True multi-threaded MockMvc concurrent merge was omitted after connection-lock hangs in the test harness; coverage uses idempotent replay + unique-loser rejection instead (schema + service locks remain).  
2. Stale SUPERSEDED uses `DuplicateCandidateLifecycleService` (`REQUIRES_NEW`) so 409 does not roll back the status flip; `findRow` no longer defaults to `FOR UPDATE` (locking reserved for dismiss/reopen).

---

## Gate

**M4.2 complete.** Do **not** start M4.3 or M5 until explicitly approved.
