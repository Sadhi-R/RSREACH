# Phase 0 local runbook

## Start with Docker Compose

1. Start Docker Desktop and wait until the engine is healthy.
2. From the repository root:

```powershell
Copy-Item .env.example .env
# set RSREACH_DB_PASSWORD in .env
docker compose -f infra/compose/docker-compose.dev.yml --env-file .env up --build
```

3. Verify health (public):
   - http://localhost:8088
   - http://localhost:8080/api/v1/system/health
   - http://localhost:8080/actuator/health
   - http://localhost:8081/actuator/health
   - http://localhost:8082/actuator/health

4. Verify non-local security (expect HTTP 403):
   - http://localhost:8080/api/v1/system/info
   - http://localhost:8080/swagger-ui.html
   - http://localhost:8080/actuator/info

## Backend-only verify

```powershell
cd backend
mvn -B verify
```

Requires Java 17. The API Testcontainers test runs only when Docker is available; otherwise it is skipped.

## Frontend-only verify

```powershell
cd frontend/apps/web
npm install
npm run typecheck
npm run build
```
