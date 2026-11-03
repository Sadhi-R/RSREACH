# Local PostgreSQL development setup

Use a locally installed PostgreSQL for day-to-day development so you do not need to start the Compose Postgres container. Docker Compose and Testcontainers remain available for integration verification and deployment.

Local PG is the preferred day-to-day datasource. Contacts import (V17) and share-expand APIs apply on next API/worker start with Flyway migrate; do not drop `rsreach_dev` for routine pulls.

---

## Prerequisites

| Tool | Notes |
|---|---|
| PostgreSQL 16+ | Local service listening on `localhost:5432` (verified with 17.x) |
| Java 17 | Backend runtime |
| Maven 3.9+ | Or set `RSREACH_MVN` to your `mvn.cmd` |
| Node.js 22+ | Frontend Vite app |
| Docker | Optional for local coding; **required** for Testcontainers / Compose verify |

Default local credentials used in this guide (only if they match your install):

| Setting | Value |
|---|---|
| Host | `localhost` |
| Port | `5432` |
| Database | `rsreach_dev` |
| Username | `postgres` |
| Password | your local password (example `.env.local` uses `root` only when that is correct) |

If authentication fails, stop and fix credentials. Do not change the PostgreSQL user password from this project.

---

## 1. Create the database (once)

```powershell
$env:PGPASSWORD = '<your-postgres-password>'
& "C:\Program Files\PostgreSQL\17\bin\psql.exe" -h localhost -p 5432 -U postgres -d postgres `
  -c "SELECT 1 FROM pg_database WHERE datname = 'rsreach_dev'"
```

If empty, create (never drop an existing database):

```powershell
& "C:\Program Files\PostgreSQL\17\bin\psql.exe" -h localhost -p 5432 -U postgres -d postgres `
  -c "CREATE DATABASE rsreach_dev OWNER postgres;"
```

---

## 2. Environment variables

```powershell
# From repository root
Copy-Item .env.local.example .env.local
# Edit .env.local: set RSREACH_DB_PASSWORD and JWT / master-key placeholders
```

Load into the current shell:

```powershell
. .\scripts\load-env.ps1
# or: . .\scripts\load-env.ps1 -EnvFile .env.local
```

Important variables:

| Variable | Local native value |
|---|---|
| `RSREACH_DB_URL` | `jdbc:postgresql://localhost:5432/rsreach_dev` |
| `RSREACH_DB_USER` | `postgres` |
| `RSREACH_DB_PASSWORD` | local password |
| `RSREACH_JWT_CUSTOMER_SECRET` | ≥32 chars |
| `RSREACH_JWT_STAFF_SECRET` | ≥32 chars |
| `RSREACH_SECRETS_MASTER_KEY` | local-only key material |

`.env.local` is gitignored. Docker Compose continues to use `.env` from `.env.example`.

---

## 3. Flyway initialization

Flyway runs automatically when **rsreach-api** starts (`spring.flyway.enabled=true`). Worker and scheduler leave Flyway disabled and expect the API to own schema.

```powershell
. .\scripts\load-env.ps1
.\scripts\start-local-api.ps1
```

Equivalent manual Maven commands (API module only — do not run `spring-boot:run` on the parent POM):

```powershell
. .\scripts\load-env.ps1
cd backend
mvn -pl rsreach-api -am -DskipTests install
mvn -pl rsreach-api spring-boot:run "-Dspring-boot.run.profiles=local"
```

On first boot, migrations **V1–V16** apply in order. Verify:

```powershell
$env:PGPASSWORD = $env:RSREACH_DB_PASSWORD
psql -h localhost -p 5432 -U postgres -d rsreach_dev -c `
  "SELECT version, description, success FROM flyway_schema_history ORDER BY installed_rank;"
```

Expect versions `1` through `16` with `success = t`.

Do **not** edit committed migration files or rewrite Flyway history. If history conflicts or the DB is half-applied, stop and investigate; do not drop the database from scripts.

---

## 4. Start processes

Open separate terminals from the repo root.

### API

```powershell
. .\scripts\load-env.ps1
.\scripts\start-local-api.ps1
```

Health: http://localhost:8080/actuator/health  
System: http://localhost:8080/api/v1/system/health

### Worker

```powershell
. .\scripts\load-env.ps1
.\scripts\start-local-worker.ps1
```

Health: http://localhost:8081/actuator/health

### Scheduler

```powershell
. .\scripts\load-env.ps1
.\scripts\start-local-scheduler.ps1
```

Health: http://localhost:8082/actuator/health

### Frontend

```powershell
.\scripts\start-local-web.ps1
# or: cd frontend/apps/web && npm run dev
```

Vite listens on http://localhost:5173 and proxies `/api` and `/actuator` to `http://localhost:8080` (`vite.config.ts`). No frontend env change is required for the local API.

---

## 5. Shutdown

- Stop each Spring Boot / Vite process with `Ctrl+C` in its terminal.
- PostgreSQL service can stay running.
- Do not run `DROP DATABASE` as part of normal shutdown.

---

## 6. Local testing commands

### Frontend (no Docker required)

```powershell
cd frontend/apps/web
npm run typecheck
npm test
npm run build
```

There is currently no `lint` script in `frontend/apps/web/package.json`.

### Backend unit / focused tests

```powershell
cd backend
# unit modules that do not need Testcontainers
mvn -B -pl rsreach-common,rsreach-tenancy,rsreach-contacts -am test `
  "-Dtest=!Phase0ReliabilityTest,!Contact*MigrationTest"
```

Prefer the full suite below when validating against Docker.

### Final integration (Docker / Testcontainers required)

```powershell
cd backend
mvn -B verify
```

Testcontainers still starts its own PostgreSQL. Tests are **not** redirected to `rsreach_dev`. Do not skip or disable them to avoid Docker.

Compose stack (unchanged):

```powershell
Copy-Item .env.example .env   # if needed
docker compose -f infra/compose/docker-compose.dev.yml --env-file .env up --build
docker compose -f infra/compose/docker-compose.dev.yml --env-file .env down
```

---

## 7. Switching back to Docker Compose

1. Stop native API / worker / scheduler / Vite (`Ctrl+C`).
2. Use `.env` (Compose), not `.env.local`.
3. Start Compose as in the README / Phase 0 runbook.
4. Web via Nginx: http://localhost:8088

Native `rsreach_dev` data and Compose volume `rsreach_pg_data` are separate. Do not assume they share data.

---

## 8. Troubleshooting

| Symptom | Check |
|---|---|
| `password authentication failed` | Correct `RSREACH_DB_PASSWORD` in `.env.local`; do not auto-reset the Postgres role |
| `database "rsreach_dev" does not exist` | Create it once (section 1) |
| Port `5432` in use by Compose Postgres | Stop Compose postgres or change host port mapping; prefer one listener |
| Port `8080`/`8081`/`8082` already in use | Stop Compose app containers: `docker compose -f infra/compose/docker-compose.dev.yml --env-file .env stop api worker scheduler web` |
| Flyway warns PostgreSQL 17 newer than tested | Informational with Flyway bundled in Boot 3.3; V1–V16 still applied successfully on PG 17.6 in this setup |
| Flyway checksum / history conflict | Stop; report; do not delete `flyway_schema_history` or drop DB from tooling |
| API starts but JWT errors | Ensure JWT secrets are ≥32 characters in `.env.local` |
| Missing master key | Set `RSREACH_SECRETS_MASTER_KEY` |
| Frontend 502 / failed API | Confirm API health on 8080; Vite proxy targets localhost:8080 |
| Testcontainers skipped / failed | Start Docker Desktop; do not point those tests at local PG |

---

## Related

- `.env.local.example` — native local template  
- `.env.example` — Compose template  
- `docs/runbooks/phase-0-local.md` — Compose-oriented runbook  
- `docs/reports/local-postgres-development-setup-report.md` — verification of this setup
