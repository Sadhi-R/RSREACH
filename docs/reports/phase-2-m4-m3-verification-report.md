# Phase 2 Milestone M4.3 — Verification Report

**Date:** 2026-11-03  
**Type:** Audit / verification of existing implementation (not a rewrite)  
**Code under review:** `frontend/apps/web/src/features/duplicates/**`, routes in `App.tsx`  
**Backend contracts:** `DuplicateCandidateController`, `MergeController`

---

## Checklist results

| # | Check | Result |
|---|---|---|
| 1 | Routes in `App.tsx` | **PASS** — `/app/contact-duplicates`, `/:id`, `/:id/merge`, `/app/merges`, `/app/merges/:id` |
| 2 | Scan | **PASS** — `POST /api/v1/app/contact-duplicates/scans`; busy/success/error; gated on `contacts.manage` |
| 3 | List/filter/pagination | **PASS** — only `status`, `contactId`, `page`, `size` |
| 4 | Detail + evidence | **PASS** — resolved matches; STALE / DISMISSED / SUPERSEDED badges |
| 5 | Dismiss/reopen | **PASS** — manage gated; 409 reopen messaging |
| 6 | Wizard A–F | **PASS** — survivor → fields → channels → consent → groups/shares → confirm → done |
| 7 | Channel dispositions | **PASS** — labels for RETAINED/MOVED/DEDUPED_ARCHIVED/LEFT_ARCHIVED/LEFT_INVALID |
| 8 | Consent downgrades | **PASS** — preview-driven; introduced-address + fresh-consent warnings |
| 9 | Idempotency | **PASS** — fingerprint reuse; new key on body/hash change; tests cover retry |
| 10 | 409 stale | **PASS** — clears preview, returns to fields, requires refresh |
| 11 | History/detail | **PASS** — `contacts.read`; safe 401/403/404 messages |
| 12 | Frontend gates | **PASS** — typecheck, 27/27 tests, production build |
| 13 | No PII console logs | **PASS** — no `console.*` in feature module |

---

## Contract alignment

Frontend `api.ts` paths and payloads match controllers:

- Scans / list / get / dismiss (`{ reason }`) / reopen  
- Preview + execute with `fieldChoices`, `primaryChoices`, `previewHash`, `confirm`  
- Execute sends `Idempotency-Key` header  
- History/detail with optional `contactId` pagination  

Permissions: read vs manage vs manage+consent.manage match backend.

---

## Verification commands

```text
cd frontend/apps/web
npm run typecheck   → PASS
npm test            → 6 files, 27 tests, 0 failed
npm run build       → PASS
```

Lint: **N/A** (no `lint` script in package.json).

---

## Defects found

**None requiring code changes.** Known limitations (accepted, deferred):

- Merge history list shows truncated UUIDs (API does not return display names).  
- Scan is accepted/queued only; UI refreshes list rather than polling scan completion.  
- No interactive screenshot capture in this verification pass.

---

## Gate

**M4.3 verified.** Proceeding to M4.4 hardening per approved execution plan.
