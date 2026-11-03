# Phase 2 Milestone M3 — Implementation Report

**Date:** 2026-11-02  
**Scope:** Channel/purpose consent, append-only history, suppressions, fail-closed eligibility evaluation, contact-detail UI  
**Not in scope:** M4 merge, M5 CSV, sending, campaigns

---

## Implemented features

- Current consent matrix per contact (`EMAIL`/`WHATSAPP` × `MARKETING`/`TRANSACTIONAL`/`SERVICE`)
- Lazy seed of `UNKNOWN` rows on first view (never inferred from email/phone/group membership)
- Grant (`OPTED_IN`) and withdraw (`OPTED_OUT`) with MANUAL evidence note required for opt-in
- Append-only `contact_consent_events` + high-level `consent.changed` audit
- Workspace suppressions by normalized EMAIL/WHATSAPP address; release via DELETE
- `ConsentEvaluationService`: fail-closed; suppression and non-`OPTED_IN` block eligibility; groups ignored
- Dynamic group filter DSL now accepts allow-listed `consent.CHANNEL.PURPOSE` fields (read-only)

---

## Consent data model

| Entity | Role |
|---|---|
| `contact_consents` | Current state projection (unique tenant+workspace+contact+channel+purpose; see ADR 0004 / V14) |
| `contact_consent_events` | Append-only history (`GRANTED`, `WITHDRAWN`, `SEEDED_UNKNOWN`, …) |
| `contact_suppressions` | Address kill-switch; partial unique active index |

Statuses: `OPTED_IN` \| `OPTED_OUT` \| `UNKNOWN`  
Sources: `MANUAL` \| `IMPORT` \| `FORM` \| `API` \| `PROVIDER_WEBHOOK`

---

## Lifecycle and evaluation

1. Missing/UNKNOWN/OPTED_OUT → not eligible (`CONSENT_UNKNOWN` / `OPTED_OUT`).
2. Active suppression for the resolved address → not eligible (`SUPPRESSED`), even if opted in.
3. `OPTED_IN` + address present + not suppressed → `ELIGIBLE`.
4. Withdrawal updates current state and history in the same transaction (immediate for evaluation).

---

## Migration

`V13__contact_consent_suppression.sql` — **new**. V1–V12 unchanged.

---

## API endpoints

| Method | Path | Permission |
|---|---|---|
| GET | `/contacts/{id}/consents` | `contacts.read` (seeds UNKNOWN) |
| GET | `/contacts/{id}/consents/history` | `contacts.read` |
| PUT | `/contacts/{id}/consents` | `contacts.consent.manage` |
| POST | `/contacts/{id}/consent-eligibility` | `contacts.read` |
| GET/POST | `/suppressions` | read / `contacts.consent.manage` |
| DELETE | `/suppressions/{id}` | `contacts.consent.manage` (release) |

---

## Authorization / isolation

- Members: read consents/history/eligibility; cannot grant/withdraw/suppress.
- Admins with `contacts.consent.manage`: manage consent and suppressions.
- Cross-tenant/workspace contact access → `404`; missing context → `403`.

---

## Frontend

Contact detail (`/app/contacts/:id`): consent matrix grant/withdraw, history timeline, workspace suppressions create/release; permission-aware controls; copy that membership ≠ consent.

---

## Tests

| Suite | Result |
|---|---|
| `ContactConsentMigrationTest` | PASS |
| `ConsentEvaluationServiceTest` (3) | PASS |
| `GroupFilterValidatorTest` (consent field allowed) | PASS |
| `Phase2ContactM3Test` | PASS |
| Phase 0/1/M1/M2 regressions | PASS |
| Skipped | **0** |

`mvn -B verify` → **BUILD SUCCESS**  
Frontend typecheck + production build → **PASS**

### Compose smoke

API healthy after rebuild. Flow: UNKNOWN ineligible → grant with evidence → eligible → suppress → `SUPPRESSED`.

---

## Known limitations

- Consent status is per contact × channel × purpose (not per address); eligibility requires an explicit address on the contact (see final verification report / ADR 0004).
- No campaign send path yet (by design).
- Suppression UI on contact detail lists workspace-active suppressions (not contact-scoped filter).
- Import-driven consent columns deferred to M5.
- Share-aware consent APIs are deferred; resource shares do not grant consent authority today.

---

## Gate

**M3 complete.** Final security/scope verification: `docs/reports/phase-2-m3-final-verification-report.md`. Do **not** start M4 until explicitly approved.
