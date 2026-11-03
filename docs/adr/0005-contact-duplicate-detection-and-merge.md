# ADR 0005: Contact duplicate detection and merge

## Status

Accepted

## Context

Phase 2 M4 introduces duplicate candidates and operator-driven contact merge in `rsreach-contacts` (ADR 0003). M3 locked consent as home-workspace scoped with append-only history and exact-address eligibility (ADR 0004). The approved plan is `docs/plans/phase-2-m4-duplicate-detection-merge-plan.md` (§0 and §0.1). Active channel uniqueness (V11) means ACTIVE–ACTIVE exact identifier duplicates cannot exist in one workspace; typical exact matches are ACTIVE↔ARCHIVED after archive released the unique slot.

## Decision

1. **Same-home-workspace only.** Detect, persist, and merge only pairs whose `home_workspace_id` equals the candidate/merge `workspace_id`. No cross-workspace candidates or merges in M4. Cross-workspace discovery is future work.
2. **Exact normalized EMAIL/PHONE matching** via `ContactIdentifierNormalizer`. No automatic merges. Fuzzy matching is out of scope for mergeable confidence.
3. **Completed merges are irreversible.** Loser is ARCHIVED with `merged_into_contact_id` / `merged_at`. Restore of a merge loser is rejected.
4. **Channel movement (F1):** Only loser `ACTIVE` channels may move onto the survivor. Survivor/loser `ARCHIVED` and `INVALID` channels are never silently reactivated; ARCHIVED/INVALID loser channels remain on the loser. Identical ACTIVE values are deduped onto the survivor.
5. **Shared planner:** Preview and execution use one deterministic `ChannelMergePlanner` post-merge ACTIVE address set. Newly introduced ACTIVE addresses feed consent policy.
6. **Consent:** Most-restrictive ranking `OPTED_OUT > UNKNOWN > OPTED_IN`. If any newly introduced ACTIVE address exists for a consent channel, that channel’s purposes must not remain/become `OPTED_IN` (force `UNKNOWN` unless already `OPTED_OUT`). Never rewrite or delete consent events; append `MERGE_RECONCILED` on the survivor when the projection changes. Suppressions are untouched and continue to override OPTED_IN. Eligibility remains exact ACTIVE address via `ConsentEvaluationService`.
7. **Stale candidates (F2):** Revalidate evidence on preview and before execute. Invalid/removed matches → conflict and `SUPERSEDED`. Rescans never reopen `DISMISSED`.
8. **Shares (F3):** Revoke active loser `CONTACT` shares in the same merge transaction; never transfer shares to the survivor. Audit `contact.share_revoked` with non-PII metadata. Shares never authorize merge.
9. **PII (F4 / R3):** Candidate `evidence` and `contact_merge_items` store channel/share/consent keys and UUIDs only—no raw or normalized email/phone snapshots. Resolve values on authorized read.
10. **Idempotency:** `Idempotency-Key` bound to canonical `request_hash`. Same key + same hash replays; same key + different hash → conflict.
11. **Authorization:** List/read with `contacts.read`; dismiss/reopen with `contacts.manage`; preview/execute with `contacts.manage` and `contacts.consent.manage`. Fail closed without tenant/workspace context; cross-tenant/other-home → not found.
12. **Concurrency:** Lock both contacts in UUID order; one atomic merge transaction; dynamic group refresh via existing outbox/worker after commit.

## Consequences

- Flyway V15 adds merge columns on `contacts`, `contact_duplicate_candidates`, `contact_merges` (with `request_hash`), `contact_merge_items`, and allows consent event type `MERGE_RECONCILED`. V1–V14 remain immutable. Append-only consent-event triggers from V14 stay in force.
- Application services (M4.1+) must implement detection, planner, consent policy, APIs, and UI per the plan; M4.0 is schema and ADR only.
- Future address-level consent or cross-workspace duplicate discovery requires a new ADR.
