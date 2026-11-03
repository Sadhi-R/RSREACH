# Phase 2 M5 — CSV import / export implementation report

**Date:** 2026-11-03  
**Authority:** `docs/plans/phase-2-remaining-milestones-execution-plan.md` §4 / §12; ADR 0006; ADR 0004  
**Status:** DONE (gap closure complete; stop before M6 work in this pass)

## Summary

M5 workspace-scoped contact CSV import (preview → commit → worker) and export (sync ≤ sync-max, async above) is complete against ADR 0006. This pass closed remaining acceptance gaps: suppression validation messaging, explicit consent-column import fail-closed on `contacts.consent.manage`, async export above sync max via outbox/worker + download UX, and deeper frontend Vitest coverage.

## M5.0 baseline

Existing green path (V17, `ContactImportService` / `ContactExportService`, controller, worker `CONTACT_IMPORT_PROCESS`, import UI) was re-verified against ADR 0006. No rewrite of the happy path. Gaps closed in M5.1–M5.4 below.

## Delivered

### Schema (Flyway V17)

- `contact_import_jobs`, `contact_import_rows` with tenant/workspace FKs, status/policy checks, unique `(job_id, row_number)`
- `contact_export_jobs` used for async export (no V18 required)

### Backend

| Surface | Permission | Notes |
|---|---|---|
| `POST /api/v1/app/contacts/import/preview` | `contacts.import` | Multipart CSV + mapping JSON; max 10 MiB / 10k rows |
| `POST /api/v1/app/contacts/import/{jobId}/commit` | `contacts.import` | Requires `Idempotency-Key`; policies SKIP / UPDATE / CREATE_ANYWAY |
| `GET /api/v1/app/contacts/import/{jobId}` | `contacts.import` | Status + first 100 sample rows |
| `GET /api/v1/app/contacts/export` | `contacts.export` | Sync CSV when count ≤ `rsreach.contacts.export.sync-max-rows` (default 5000); **202 + jobId** when above |
| `GET /api/v1/app/contacts/export/jobs/{jobId}` | `contacts.export` | Poll status; `?download=true` returns CSV when COMPLETED |
| Worker `CONTACT_IMPORT_PROCESS` | — | Batch claim 200; per-row transactions |
| Worker `CONTACT_EXPORT_PROCESS` | — | Fills `csv_content`, marks COMPLETED |

### Gap closures (this pass)

- **Suppression messaging:** preview/worker validate sets `WARNING` / `SUPPRESSED` when normalized email/phone matches an active suppression (messaging only; no auto-create).
- **Consent columns:** mapping may include `consentEmailMarketing` / `consentWhatsappMarketing`; applied only with `contacts.consent.manage`; mapped columns without permission → 403 fail-closed; never inferred from address presence.
- **Async export:** count > sync max → PENDING export job + outbox; UI polls then downloads.
- **Frontend tests:** preview ERROR/WARNING rows; commit in-flight disables second submit; optional consent + custom-field mapping inputs.

### Frontend

- `/app/contacts/import` — upload, column mapping (incl. optional consent / custom fields), preview table, conflict policy, commit + poll
- Directory CTAs: Import / Export (permission-gated); Export handles sync blob and async job poll/download
- `apiFetch` skips forcing `Content-Type` for `FormData`

### Safeguards

- Tenant/workspace from auth context only
- No consent writes without explicit mapped columns + `contacts.consent.manage`
- Audits: filename / counts / policy / filters only (no PII bodies)
- Export formula injection prefix `'`

## Verification

| Gate | Result |
|---|---|
| `ContactCsvSupportTest` | 3/3 PASS |
| `Phase2ContactM5Test` | 3/3 PASS (core path; suppression+consent fail-closed; async export download) |
| `mvn -B verify` | BUILD SUCCESS, Skipped 0 |
| Frontend typecheck / Vitest / build | PASS (31 tests incl. 4 import tests) |

## Deferred (not M5 exit blockers)

- Object storage / S3 for export artifacts (D5/A6)
- 30-day staging sweeper (ops follow-up)
- M6 shares / share-expand UI (not started in this M5 pass)

## Stop line

M5 acceptance gates met. **Do not treat this report as authorization to start M6** in the same workstream unless a separate M6 plan is approved.
