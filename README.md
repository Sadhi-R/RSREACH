# RSReach

Unified Communication & Automation Platform (modular monolith).

**Current phase:** Phase 5 — commercial launch harden + light UX (see `docs/architecture/phase-5-roadmap-plan.md`). Phases 0–4 are closed.

## Stack

- Java 17, Spring Boot 3.3.x, Maven
- React + TypeScript (Vite)
- PostgreSQL + Flyway
- Docker Compose + Nginx
- Separate processes: `rsreach-api`, `rsreach-worker`, `rsreach-scheduler`

## Repository layout

```text
backend/                 Maven multi-module backend
frontend/apps/web        React + TypeScript SPA
infra/compose            Docker Compose files
infra/docker             Dockerfiles and Nginx config
docs/architecture        Architecture notes and safeguards
docs/adr                 Architecture decision records
```

## Prerequisites

- Docker Desktop / Docker Engine with Compose
- Java 17 (for local Maven builds)
- Node.js 22+ (for local frontend development)
- Maven 3.9+ (optional locally; CI and Docker images provide Maven)

## Quick start (Docker Compose)

```powershell
Copy-Item .env.example .env
# Edit .env and set RSREACH_DB_PASSWORD (required)

docker compose -f infra/compose/docker-compose.dev.yml --env-file .env up --build
```

Compose uses Spring profile `docker` (unauthenticated API access disabled). Persistent Postgres data uses volume `rsreach_pg_data`.

Services:

| Service | URL |
|---|---|
| Web (Nginx + SPA) | http://localhost:8088 |
| API health | http://localhost:8080/actuator/health |
| API system health | http://localhost:8080/api/v1/system/health |
| Worker health | http://localhost:8081/actuator/health |
| Scheduler health | http://localhost:8082/actuator/health |

Expect HTTP 403 for `/api/v1/system/info`, `/swagger-ui.html`, and `/actuator/info` under the docker profile.

Stop:

```powershell
docker compose -f infra/compose/docker-compose.dev.yml --env-file .env down
```

## Local backend build and test

```powershell
cd backend
mvn -B verify
```

`mvn verify` runs unit tests and the API Testcontainers smoke test (Docker required).

## Local frontend

```powershell
cd frontend/apps/web
npm install
npm run dev
```

Vite proxies `/api` to `http://localhost:8080`.

## Local PostgreSQL (without Compose Postgres)

For day-to-day native development against an installed PostgreSQL instance, see [docs/development/local-postgres-setup.md](docs/development/local-postgres-setup.md). Use `.env.local` (from `.env.local.example`). Docker Compose and Testcontainers remain unchanged for integration verification.

## Configuration

All secrets and tunables come from environment variables. See [.env.example](.env.example) (Compose) and [.env.local.example](.env.local.example) (native local).

Important Phase 0 safeguards are documented in [docs/architecture/phase-0-safeguards.md](docs/architecture/phase-0-safeguards.md):

- Transactional outbox
- Idempotent webhook ingestion
- Tenant/workspace isolation constraints
- Secret key versioning
- Configurable worker concurrency and DB pools

## Phase boundaries

- **Phase 0:** foundation, health, Flyway baseline, Compose, CI
- **Phase 1:** identity, tenancy, RBAC, dual-realm auth
- **Phase 2:** contacts, groups, consent, duplicates/merge, CSV, shares
- **Phase 3:** unified inbox, WhatsApp/email mocks, templates, campaigns (V18–V23)
- **Phase 3.5:** live connector scaffolding, MinIO ADR, prod Compose harden
- **Phase 4:** billing, entitlements, Razorpay MVP, linear automation (V24–V29) — closed
- **Phase 5:** commercial launch harden + light UX (in progress); see `docs/architecture/phase-5-roadmap-plan.md`

Do not treat mock providers or local helpers as production integrations. Live Meta/Google/Microsoft/delivery/Razorpay calls need operator-supplied credentials.
