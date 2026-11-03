# Phase 2 Milestone M1 — Implementation Report

**Date:** 2026-11-02  
**Scope:** Contact CRUD, multi email/phone identifiers, custom field definitions, scoped RBAC, audit, V11 schema, customer directory UI  
**Not in scope:** Groups, consent APIs/UI, duplicate merging, CSV import/export, sharing UI, M2+

---

## Summary

M1 delivers workspace-owned contacts with normalized email/phone channels, tenant/workspace custom field definitions, Phase 1 scoped authorization (`contacts.read` / `contacts.manage`), audit events without raw identifier payloads, Flyway **V11**, REST under `/api/v1/app`, and customer portal directory/form/detail/archive-restore screens.

---

## Files changed

| Path | Change |
|---|---|
| `backend/rsreach-persistence/.../V11__contacts_core.sql` | **Added** — contacts, channels, custom field defs |
| `backend/rsreach-persistence/.../ContactsCoreMigrationTest.java` | **Added** — V11 schema Testcontainers check |
| `backend/rsreach-contacts/.../ContactModels.java` | **Added** — commands/views |
| `backend/rsreach-contacts/.../ContactIdentifierNormalizer.java` | **Added** — email/phone normalization |
| `backend/rsreach-contacts/.../CustomFieldValidator.java` | **Added** — typed custom field validation |
| `backend/rsreach-contacts/.../ContactRepository.java` | **Added** — JDBC persistence |
| `backend/rsreach-contacts/.../ContactService.java` | **Added** — domain/application service |
| `backend/rsreach-contacts/pom.xml` | Jackson databind for JSONB custom fields |
| `backend/rsreach-contacts/.../*Test.java` | Unit tests (normalizer, validator, service) |
| `backend/rsreach-api/.../contacts/ContactController.java` | **Added** — thin REST controller |
| `backend/rsreach-api/.../CustomerTenancyController.java` | `GET /permissions` for UI CTA gating |
| `backend/rsreach-api/.../Phase2ContactM1Test.java` | **Added** — CRUD/authz/isolation/audit IT |
| `frontend/apps/web/src/contacts.tsx` | **Added** — directory, form, detail |
| `frontend/apps/web/src/App.tsx` | Contact routes |
| `frontend/apps/web/src/pages.tsx` | Dashboard link to directory |
| `frontend/apps/web/src/styles.css` | Directory table / layout helpers |
| `docs/reports/phase-2-m1-implementation-report.md` | This report |
| `docs/plans/phase-2-contacts-organization-plan.md` | Status → M1 complete |

V1–V10 migrations: **unchanged**.

---

## Migration details (V11)

File: `V11__contacts_core.sql`

Tables:

- `contact_custom_field_defs` — workspace-scoped defs (`STRING|NUMBER|DATE|BOOLEAN`), unique `(tenant_id, workspace_id, field_key)`
- `contacts` — `tenant_id` + `home_workspace_id` ownership, `ACTIVE|ARCHIVED`, primary email/phone denormalized for search, `custom_fields` JSONB
- `contact_channels` — EMAIL/PHONE with `value_raw` / `value_normalized`

Indexes / constraints:

- Partial unique `uq_contact_channel_active_normalized` on active normalized identifiers per workspace
- Partial unique primary channel per `(contact_id, channel_type)` among active rows
- Workspace composite FKs; contact archive sets channels to `ARCHIVED` so unique indexes free

---

## API endpoints

Base: `/api/v1/app` with required `X-Tenant-Id` / `X-Workspace-Id`.

| Method | Path | Permission |
|---|---|---|
| GET | `/contacts?query&status&page&size` | `contacts.read` |
| POST | `/contacts` | `contacts.manage` |
| GET | `/contacts/{id}` | `contacts.read` |
| PATCH | `/contacts/{id}` | `contacts.manage` |
| POST | `/contacts/{id}/archive` | `contacts.manage` |
| POST | `/contacts/{id}/restore` | `contacts.manage` |
| GET | `/contact-field-defs` | `contacts.read` |
| POST | `/contact-field-defs` | `contacts.manage` |
| GET | `/permissions` | authenticated member (active context) |

Authorization: members read-only; tenant/workspace admins manage (V10 seed). Cross-tenant and cross-workspace lookups return `404`. Missing context returns `403`.

Audit actions: `contact.created`, `contact.updated`, `contact.archived`, `contact.restored`, `contact.field_def.created` — details omit raw email/phone values.

---

## Frontend

Routes:

- `/app/contacts` — search, status filter, pagination
- `/app/contacts/new` — create
- `/app/contacts/:id` — detail, archive/restore
- `/app/contacts/:id/edit` — edit

Manage CTAs gated by `GET /permissions` → `contacts.manage`. Loading, empty, validation/error states included.

---

## Tests executed

| Test | Type | Result |
|---|---|---|
| `ContactIdentifierNormalizerTest` (4) | Unit | PASS |
| `CustomFieldValidatorTest` (2) | Unit | PASS |
| `ContactServiceTest` (3) | Unit (Mockito) | PASS |
| `ContactPermissionCodesTest` (3) | Unit | PASS |
| `ContactsCoreMigrationTest` | Testcontainers | PASS |
| `ContactRbacMigrationTest` | Testcontainers | PASS |
| `Phase2ContactM1Test` | Testcontainers API | PASS |
| Prior Phase 0/1 suites | Testcontainers | PASS |

`Phase2ContactM1Test` covers: CRUD, email/phone normalization, workspace duplicate conflict, custom field validation, member read-only, admin manage, cross-tenant/cross-workspace isolation, missing context, audit creation without sensitive values, archive frees identifier / restore conflict.

Skipped tests: **0** in this verify run (Docker available).

---

## Verification results

### Backend

```powershell
$env:DOCKER_HOST = "tcp://127.0.0.1:2375"
$env:DOCKER_API_VERSION = "1.44"
& "$env:TEMP\apache-maven-3.9.9\bin\mvn.cmd" -B verify "-Dapi.version=1.44"
```

| Check | Status |
|---|---|
| `mvn -B verify` | **PASS — BUILD SUCCESS** |
| Testcontainers (Docker) | **Executed** — 0 skipped |
| `Phase2ContactM1Test` | **PASS** |

### Frontend

```powershell
cd frontend/apps/web
npm run typecheck
npm run build
```

| Check | Status |
|---|---|
| Typecheck | **PASS** |
| Production build (`tsc --noEmit && vite build`) | **PASS** |

---

## Gate

**M1 complete.** Do **not** start M2 (groups / consent / import / merge) until explicitly approved.
