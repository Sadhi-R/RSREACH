# Phase 2 Milestone M0 — Implementation Report

**Date:** 2026-11-02  
**Scope:** ADR 0003, `rsreach-contacts` module, Flyway V10 contact RBAC only  
**Not in scope:** M1+ contact tables, APIs, UI

---

## D1–D7 mapping to approvals

| Plan decision | Approval | Conflict? |
|---|---|---|
| D1 Workspace-owned contacts | Approved #1 | None |
| D2 Members read-only | Approved #5 | None |
| D3 CSV duplicates default SKIP | Approved #6 | None |
| D4 Merge most restrictive wins | Approved via “proposed defaults” | None |
| D5 CSV staging in PostgreSQL | Approved #7 | None |
| D6 Minimal share UI in Phase 2 | Approved via defaults + `resource_shares` #2 | None |
| D7 Purposes MARKETING / TRANSACTIONAL / SERVICE | Approved via proposed defaults | None |

Additional mandatory rules (ownership vs sharing, membership ≠ consent, dynamic isolation, consent history, merge audit, no cross-tenant merge, CSV controls/cleanup) do not conflict with D1–D7. They bind later milestones; M0 only seeds RBAC.

---

## Files changed

| Path | Change |
|---|---|
| `docs/adr/0003-contacts-domain-module.md` | **Added** — accepted ADR |
| `backend/rsreach-contacts/` | **Added** — Maven module (package-info, `ContactPermissionCodes`, unit tests) |
| `backend/pom.xml` | Module + dependencyManagement entry |
| `backend/rsreach-api/pom.xml` | Depends on `rsreach-contacts` |
| `backend/rsreach-worker/pom.xml` | Depends on `rsreach-contacts` |
| `backend/rsreach-common/.../Permissions.java` | Contact permission constants |
| `backend/rsreach-persistence/.../V10__contact_rbac_permissions.sql` | **Added** — permission + role_permission seeds |
| `backend/rsreach-persistence/.../ContactRbacMigrationTest.java` | **Added** — Testcontainers assertion of V10 |
| `.cursor/skills/MODULE-MAP.md` | Documents `rsreach-contacts` |
| `docs/plans/phase-2-contacts-organization-plan.md` | Status → APPROVED |
| `docs/reports/phase-2-m0-implementation-report.md` | This report |

V1–V9 migrations: **unchanged**.

---

## Migration details (V10)

File: `V10__contact_rbac_permissions.sql`

Inserts permissions:

- `contacts.read`
- `contacts.manage`
- `contacts.consent.manage`
- `contacts.import`
- `contacts.export`

Role grants:

- `TENANT_OWNER`, `TENANT_ADMIN`, `WORKSPACE_ADMIN` → all five
- `WORKSPACE_MEMBER` → `contacts.read` only

No contact/group/consent tables in M0.

---

## Tests

| Test | Type | Expectation |
|---|---|---|
| `ContactPermissionCodesTest` (3) | Unit | Codes and admin/member mapping |
| `ContactRbacMigrationTest` | Testcontainers | V10 applied; DB role_permissions match |

Frontend: not changed for M0 (N/A).

---

## Verification results

### Command

```powershell
cd D:\Projects\RSReach\backend
$env:DOCKER_HOST = "tcp://127.0.0.1:2375"
$env:DOCKER_API_VERSION = "1.44"
mvn -B verify "-Dapi.version=1.44"
```

| Check | Status |
|---|---|
| `mvn -B verify` | **PASS — BUILD SUCCESS** |
| `ContactPermissionCodesTest` | **PASS** — 3 tests, 0 skipped |
| `ContactRbacMigrationTest` | **PASS** — 1 test, executed (not skipped) |
| Prior Phase 0/1 Testcontainers suites | **PASS** — 0 skipped in this run |
| Frontend | **N/A** for M0 (no UI changes) |

### Gate

**M0 complete.** Do **not** start M1 until explicitly approved.
