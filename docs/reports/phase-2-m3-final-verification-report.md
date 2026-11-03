# Phase 2 Milestone M3 — Final Consent Security and Scope Verification

**Date:** 2026-11-02  
**Scope:** Consent scope hardening, exact-address eligibility, append-only history, suppression, concurrent UNKNOWN seed, isolation tests  
**Not in scope:** M4 merge, CSV import/export, campaigns, messaging/sending

---

## Consent scope decision

**Decision:** Consent is **home-workspace scoped** (not tenant-wide, not independently workspace-copied).

| Rule | Detail |
|---|---|
| Ownership | `contact_consents.workspace_id` must equal the contact’s `home_workspace_id` (composite FK already enforced in V13) |
| Uniqueness | `(tenant_id, workspace_id, contact_id, channel_type, purpose)` after V14 |
| Management | Only from the home workspace with `contacts.consent.manage` |
| Sharing | An active `resource_shares` row does **not** grant consent read/write; non-home workspaces receive `404` |
| Suppressions | Workspace-scoped by normalized address; do not follow shared contacts into other workspaces |
| Dynamic filters | Evaluate `cc.workspace_id = c.home_workspace_id` only |
| History | Events store and return `workspace_id` as the decision scope |

Rationale and consequences: `docs/adr/0004-consent-workspace-scope.md`.

---

## Database changes

| Migration | Change |
|---|---|
| `V13__contact_consent_suppression.sql` | Unchanged |
| `V14__consent_scope_hardening.sql` | **New.** Drop/recreate `uq_contact_consent` to include `workspace_id`; append-only triggers on `contact_consent_events` (block UPDATE/DELETE) |

V1–V13 were not modified.

---

## Exact-address eligibility

`ConsentEvaluationService.evaluate(tenant, workspace, contact, channel, purpose, address)` is the reusable fail-closed contract for future send paths.

- `address` is **required**; primary-email/phone fallback was removed.
- Address is normalized (EMAIL / E.164 phone) and must match an **active** channel on the contact (`EMAIL` or `PHONE` for WhatsApp).
- Suppression is checked against that exact normalized address.
- An address not on the contact cannot inherit eligibility from another address (`ADDRESS_NOT_ON_CONTACT`).
- Missing/blank → `ADDRESS_REQUIRED`; invalid → `ADDRESS_INVALID`; suppression/consent DB errors → `EVALUATION_ERROR` (never `ELIGIBLE`).

**Limitation (documented):** Consent status remains per contact × channel × purpose (not per address). Eligibility is therefore “contact opted in **and** this exact address is present and not suppressed,” not a separate opt-in row per address. Callers must not treat one address’s eligibility as all-address eligibility.

---

## History, seed, and suppression findings

| Area | Result |
|---|---|
| Grant/withdraw | Updates current row + inserts event in one transaction; contact row locked |
| Append-only | No app delete/update APIs for events; DB triggers reject UPDATE/DELETE |
| UNKNOWN seed | `INSERT … ON CONFLICT DO NOTHING RETURNING`; event only when inserted; never seeds `OPTED_IN`; read permission may seed UNKNOWN only |
| Concurrent seed | Idempotent (6 cells / 6 `SEEDED_UNKNOWN` events under parallel GET) |
| Suppression | Active suppression overrides `OPTED_IN`; DELETE performs logical release (`released_at`/`released_by`); history row retained; release audited |
| Fail-closed | Suppression/consent lookup failures return non-eligible `EVALUATION_ERROR` |

---

## Security and authorization findings

| Finding | Severity | Outcome |
|---|---|---|
| Uniqueness omitted `workspace_id` | High (schema drift vs FK scope) | Fixed in V14 |
| Primary-address eligibility could be mistaken for all-address | High (send misuse) | Fixed; explicit address required |
| Resource share WRITE could be assumed to manage consent | High if silent | Confirmed blocked; ADR documents home-only until share-aware APIs |
| Cross-workspace / cross-tenant consent | Isolation | `404` |
| Concurrent GET seed races | Medium | Idempotent upsert |
| Event mutation | Medium | DB append-only triggers |
| Evidence in logs | OK | Audit payloads exclude evidence notes; API returns evidence only on authorized consent/history reads |

---

## Files changed

| Area | Paths |
|---|---|
| ADR | `docs/adr/0004-consent-workspace-scope.md` |
| Migration | `backend/rsreach-persistence/.../V14__consent_scope_hardening.sql` |
| Domain | `ConsentModels`, `ConsentRepository`, `ConsentService`, `ConsentEvaluationService`, `ContactRepository` |
| API | `ConsentController` (required `address` on eligibility) |
| Tests | `ConsentEvaluationServiceTest`, `Phase2ContactM3Test`, `Phase2ContactM3SecurityVerificationTest`, `ContactConsentMigrationTest` |
| Reports | this file; ADR 0004 |

---

## Automated tests

| Suite | Result |
|---|---|
| `ContactConsentMigrationTest` (V13+V14) | PASS |
| `ConsentEvaluationServiceTest` (6) | PASS |
| `Phase2ContactM3Test` | PASS |
| `Phase2ContactM3SecurityVerificationTest` (items 1–11) | PASS |
| Phase 0 / Phase 1 / M1 / M2 regressions | PASS |
| Skipped | **0** |

`mvn -B verify` (Docker/Testcontainers enabled) → **BUILD SUCCESS**

Verification coverage includes: workspace isolation, share ≠ consent authority, cross-tenant rejection, home-scoped dynamic consent filters, suppression override + audited release, exact-address non-inheritance, history consistency + append-only DB enforcement, concurrent updates, concurrent UNKNOWN seed idempotency, fail-closed evaluation.

---

## Gate

**M3 final verification complete.** Do **not** start M4 until explicitly approved.
