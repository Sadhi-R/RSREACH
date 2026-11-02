# RSReach

Unified Communication & Automation Platform (modular monolith).

**Current phase:** Phase 1 — identity, authentication, multi-tenancy.

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

## Configuration

All secrets and tunables come from environment variables. See [.env.example](.env.example).

Important Phase 0 safeguards are documented in [docs/architecture/phase-0-safeguards.md](docs/architecture/phase-0-safeguards.md):

- Transactional outbox
- Idempotent webhook ingestion
- Tenant/workspace isolation constraints
- Secret key versioning
- Configurable worker concurrency and DB pools

## Phase boundaries

- **Phase 0 (this commit set):** foundation, health, Flyway baseline, Compose, CI
- **Phase 1+:** identity, tenancy, RBAC, portals, communications — not started

Do not treat any stub or local helper as a production integration.
