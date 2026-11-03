# Phase 2 M6 — Sharing, docs, final verification

**Date:** 2026-11-03  
**Authority:** Execution plan §5 / §12; ADR 0004/0005/0006  
**Status:** DONE (gap closure complete)

## Summary

M6 workspace sharing reuses V9 `resource_shares`. This pass closed remaining acceptance gaps: home-only group share authority, group list share-expand, consent history fail-closed, contact `access` on GET, frontend privilege-escalation UX, deeper IT/Vitest coverage, and architecture matrix refresh.

## M6.0 baseline

Green path (contact share CRUD, contact share-expand, WRITE limits, home-only consent matrix/PUT, prior M6 IT) was re-verified. No rewrite of M0–M5. Gaps closed in M6.1–M6.4.

## Delivered

### Sharing

- `ContactShareService` / repository on V9 `resource_shares`
- Contact + CONTACT_GROUP share create / list / revoke APIs (`contacts.manage`, **home workspace only**)
- Directory and detail **share-expand** for contacts and groups (home OR active share-to-workspace)
- WRITE share: update/archive/restore; consent GET/PUT/**history** remain home-only (no copy)
- Contact GET exposes `access`: `HOME` | `READ` | `WRITE`
- Group mutations (archive, members, refresh) home-only; shared targets may read group + members
- Revoked natural-key shares reactivate on re-share
- UI: share panel on home contact/group detail; `(shared)` in directory; shared-in banner; consent/edit gated

### Docs

- `docs/architecture/phase-2-contacts.md` — share authority matrix
- This report + final verification report

### Schema

- No new Flyway version (V9 sufficient). V1–V17 untouched.

## Tests

| Suite | Result |
|---|---|
| `Phase2ContactM6Test` | 2/2 PASS (contact path + group expand/home-only) |
| `Phase2ContactM3SecurityVerificationTest` | PASS (consent still home-only) |
| Full `mvn -B verify` | BUILD SUCCESS, Skipped 0 |
| Frontend typecheck / Vitest / build | PASS (35 tests incl. 4 share UX tests) |

## Compose smoke

`infra/compose/.env` was **absent** in this environment, so the full Compose stack (api/worker/postgres) was not started. Residual risk: operator Compose path not exercised here. Functional DB isolation and share behavior were verified via Testcontainers in `mvn -B verify`. Operators should copy env from local secrets guidance and run `docker compose -f infra/compose/docker-compose.dev.yml up` per `docs/development/local-postgres-setup.md`.

## Stop line

M6 acceptance gates met for code and automated verify. Phase 3 not started.
