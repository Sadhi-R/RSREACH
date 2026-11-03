# Local PostgreSQL Development Setup — Verification Report

**Date:** 2026-11-03  
**Scope:** Native local PostgreSQL + env/scripts/docs for IDE development  
**Out of scope:** M4.3 changes, M4.4+, product/API/business-logic changes, Flyway SQL edits, Docker/Testcontainers removal

M4.3 remains awaiting explicit approval. M4.4 remains blocked.

---

## Files changed / added

| Path | Action |
|---|---|
| `.env.local` | **Created (gitignored)** — local secrets for `rsreach_dev` |
| `.env.local.example` | Added — tracked template |
| `.env.example` | Updated — notes separating Compose `.env` vs native `.env.local` |
| `.gitignore` | Allow-list `!.env.local.example` |
| `scripts/load-env.ps1` | Added — load dotenv into PowerShell |
| `scripts/start-local-api.ps1` | Added |
| `scripts/start-local-worker.ps1` | Added |
| `scripts/start-local-scheduler.ps1` | Added |
| `scripts/start-local-web.ps1` | Added |
| `docs/development/local-postgres-setup.md` | Added — operator guide |
| `docs/reports/local-postgres-development-setup-report.md` | Added — this report |
| `README.md` | Short pointer to local Postgres guide |

**Not modified:** Flyway V1–V16 SQL, Dockerfiles, Compose, Nginx, Testcontainers tests, M4.3 frontend/backend product code, M4 plan approval status.

---

## PostgreSQL connection

| Check | Result |
|---|---|
| Service | `postgresql-x64-17` **Running** |
| `pg_isready -h localhost -p 5432 -U postgres` | Accepting connections |
| Auth (`postgres` / `root`) | **Success** |
| Server version | PostgreSQL **17.6** |

Password `root` matched the local install. No password change was performed.

---

## Database creation

| Check | Result |
|---|---|
| `rsreach_dev` before | Did not exist |
| Action | `CREATE DATABASE rsreach_dev OWNER postgres` |
| Drop / recreate | **Not** performed |
| Encoding | UTF8 |

---

## Flyway migration results

Applied by starting `rsreach-api` with `.env.local` (`jdbc:postgresql://localhost:5432/rsreach_dev`).

| Version | Description | Success |
|---|---|---|
| 1 | baseline platform | t |
| 2 | phase0 reliability corrections | t |
| 3 | rbac permissions roles | t |
| 4 | customer identity | t |
| 5 | staff identity | t |
| 6 | tenancy workspaces | t |
| 7 | org structure | t |
| 8 | audit staff access | t |
| 9 | resource shares foundation | t |
| 10 | contact rbac permissions | t |
| 11 | contacts core | t |
| 12 | contact groups | t |
| 13 | contact consent suppression | t |
| 14 | consent scope hardening | t |
| 15 | contact duplicate candidates and merges | t |
| 16 | contacts merged into home scope | t |

Additional checks:

- `flyway_schema_history` present; **16** successful rows; now at **v16**
- Public base tables: **42**
- Spot-check tables present: `contacts`, `contact_merges`, `contact_duplicate_candidates`, `outbox_events`, `tenants`, `workspaces`
- Flyway logged an informational warning that PostgreSQL 17 is newer than the Boot 3.3 Flyway “tested” matrix; migrations still applied cleanly
- **V1–V16 migration files were not edited**

---

## Backend startup

| Check | Result |
|---|---|
| Profile | `local` |
| DB URL | `jdbc:postgresql://localhost:5432/rsreach_dev` |
| Startup | `Started RsReachApiApplication` |
| `GET /actuator/health` | `{"status":"UP",...}` |
| `GET /api/v1/system/health` | `{"service":"rsreach-api","status":"UP","phase":0}` |

**Port conflict note:** Compose containers `rsreach-dev-api/worker/scheduler/web` were occupying 8080–8082. They were **stopped** (not removed) so native processes could bind. Compose **postgres** left running on host port **55433** (does not conflict with local PG on 5432). Docker images/Compose files unchanged.

Worker/scheduler scripts are provided; API was the process verified for Flyway + health in this run.

---

## Frontend connectivity

| Check | Result |
|---|---|
| Vite proxy config | Unchanged — `/api` → `http://localhost:8080` |
| `GET http://localhost:5173/api/v1/system/health` | `UP` (proxied to native API) |
| `npm run typecheck` | PASS |
| `npm test` | PASS — 6 files, 27 tests |
| `npm run build` | PASS |
| Frontend lint | **N/A** — no `lint` script in package.json |

---

## Backend local unit tests

Focused non-Testcontainers tests:

```text
mvn -B -pl rsreach-common,rsreach-tenancy,rsreach-contacts -am test
  (selected unit classes)
→ BUILD SUCCESS — Failures 0, Errors 0, Skipped 0
```

Testcontainers suites were **not** redirected to `rsreach_dev` and were **not** disabled.

Full `mvn -B verify` (Docker/Testcontainers) remains the final integration gate and was not required for this infrastructure-only task beyond confirming Docker config still exists.

---

## Docker configuration preservation

| Asset | Status |
|---|---|
| `infra/compose/docker-compose.dev.yml` | Present, unmodified |
| `infra/docker/Dockerfile.*` | Present |
| Nginx / healthchecks in Compose | Unchanged |
| Testcontainers usage in API/persistence tests | Unchanged |
| Ability to `docker compose ... up` | Preserved |

Normal local coding path: **local PostgreSQL + native JVM processes + Vite**. Compose/Testcontainers remain for verification/deployment.

---

## Issues / limitations

1. **PostgreSQL 17 vs Flyway “tested up to 16” warning** — informational only; V1–V16 applied successfully.  
2. **Compose app containers must be stopped** before binding native 8080–8082 if both workflows share default ports.  
3. **`.env.local` is gitignored** — each developer copies `.env.local.example`; do not commit secrets.  
4. **Separate data** — `rsreach_dev` (native) and Compose volume `rsreach_pg_data` do not share data.  
5. **No frontend lint script** — documented as N/A.  
6. **M4.3 / M4.4** — untouched; approval status unchanged.

---

## Gate

Local PostgreSQL development setup is complete. Stopped for review. Do not start M4.4.
