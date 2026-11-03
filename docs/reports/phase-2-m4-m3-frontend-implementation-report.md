# Phase 2 Milestone M4.3 — Duplicate Detection UI and Merge Wizard

**Date:** 2026-11-03  
**Scope:** Frontend only — duplicate review, merge wizard, merge history  
**Not in scope:** M4.4 hardening gate, M5 CSV, M6 share APIs, campaigns, automatic/bulk/undo merge, backend changes  
**Backend:** Unchanged. Contracts taken from M4.1/M4.2 controllers and DTOs.

---

## Routes and screens

| Route | Screen |
|---|---|
| `/app/contact-duplicates` | Candidate list + status/contact filters + pagination + scan |
| `/app/contact-duplicates/:candidateId` | Candidate detail, evidence, dismiss/reopen |
| `/app/contact-duplicates/:candidateId/merge` | Guided merge wizard (steps A–F) |
| `/app/merges` | Merge history |
| `/app/merges/:mergeId` | Merge detail + items |

Navigation wired from customer dashboard, contacts directory, and contact detail (`?contactId=` filters).

---

## Components and API integrations

Feature module: `frontend/apps/web/src/features/duplicates/`

| Module | Role |
|---|---|
| `api.ts` | Typed wrappers over `apiFetch` for all M4 duplicate/merge endpoints |
| `types.ts` | Explicit TypeScript mirrors of backend JSON contracts |
| `helpers.ts` | Status badges, dispositions, idempotency fingerprint/reuse, consent filters |
| `components.tsx` | Loading/empty/error, candidate table, channel/consent tables, wizard progress |
| `DuplicatesDirectoryPage.tsx` | List / scan / filter |
| `CandidateDetailPage.tsx` | Compare contacts + evidence + dismiss/reopen |
| `MergeWizardPage.tsx` | Multi-step merge + execute |
| `MergeHistoryPage.tsx` / `MergeDetailPage.tsx` | History views |

Shared client remains `auth.ts` `apiFetch` (tenant/workspace headers). No ad hoc `fetch` in feature pages.

---

## Endpoint contracts used

| Method | Path | Headers / notes |
|---|---|---|
| `POST` | `/api/v1/app/contact-duplicates/scans` | `contacts.manage` → 202 `ScanAccepted` |
| `GET` | `/api/v1/app/contact-duplicates` | `status`, `contactId`, `page`, `size` |
| `GET` | `/api/v1/app/contact-duplicates/{id}` | Detail + resolved matches + `stale` |
| `POST` | `/api/v1/app/contact-duplicates/{id}/dismiss` | Body `{ reason }` |
| `POST` | `/api/v1/app/contact-duplicates/{id}/reopen` | |
| `POST` | `/api/v1/app/contacts/merges/preview` | Field/primary choices |
| `POST` | `/api/v1/app/contacts/merges` | **`Idempotency-Key`** + `previewHash` + `confirm: true` |
| `GET` | `/api/v1/app/contacts/merges` | Optional `contactId`, pagination |
| `GET` | `/api/v1/app/contacts/merges/{id}` | Detail + items |
| `GET` | `/api/v1/app/permissions` | UI gating only |

Permissions:

- List/detail/history: `contacts.read`
- Scan/dismiss/reopen: `contacts.manage`
- Preview/execute: `contacts.manage` **and** `contacts.consent.manage`

UI hides/disables actions without permission; API remains authoritative. 401/403/404 map to safe messages without leaking cross-workspace details.

---

## Wizard behavior and idempotency

1. **Survivor** — operator picks contact A or B; irreversible warning shown.  
2. **Fields** — preview defaults applied; conflicting non-null values selectable; blocking conflicts prevent continue.  
3. **Channels** — dispositions from preview (`RETAINED` / `MOVED` / `DEDUPED_ARCHIVED` / `LEFT_ARCHIVED` / `LEFT_INVALID`); primary selection among retained/moved only.  
4. **Consent** — before/after cells, introduced-address UNKNOWN warning; no client-side policy overrides.  
5. **Groups & shares** — static union / dynamic refresh / loser share revoke notice (no share management).  
6. **Confirm** — summary + explicit checkbox; submit disabled while in flight.

Idempotency:

- Fingerprint = candidate + survivor + loser + previewHash + sorted field/primary choices.
- Same fingerprint → reuse `Idempotency-Key` on retry.
- Any logical change → new key.
- 409 stale/conflict → clear preview, return to field review, require refresh; never auto-retry a different body with the same key.
- Success UI only after `201` merge response.

---

## Tests and verification

Vitest + React Testing Library added (`npm test`). No separate ESLint script exists in this app today.

| Command | Result |
|---|---|
| `npm run typecheck` | **PASS** |
| `npm test` | **PASS** — 6 files, **27** tests, 0 failed |
| `npm run build` | **PASS** (`tsc --noEmit && vite build`) |
| Frontend lint | **N/A** — no `lint` script in `frontend/apps/web/package.json` |
| Backend `mvn -B verify` | **Not run** — no backend code changed |

Coverage includes: list empty/error/retry, scan, permission gating, stale detail, dismiss/reopen, channel dispositions, consent downgrades, wizard flow, 409 preview refresh, idempotency reuse, in-flight submit lock, success confirmation, merge history, unauthorized/inaccessible handling.

---

## Screenshots

Not captured in this pass (no interactive browser session against a running stack). UI follows existing shell/panel/table conventions in `styles.css`.

---

## Deviations and known limitations

1. No TanStack Query yet — pages use local state + `apiFetch`, matching contacts/groups.  
2. No ESLint pipeline in the web app; typecheck + Vitest + production build are the gates.  
3. Merge history shows actor/survivor/loser UUIDs (API does not return display names on history list).  
4. Scan acceptance is fire-and-forget with a refresh hint (backend returns accepted scan id/status only).  
5. M4.4 full hardening / end-to-end against live API is explicitly out of this milestone.

---

## Gate

**M4.3 implementation complete.** Do **not** start M4.4, M5, or M6 until explicitly approved.
