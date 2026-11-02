# Phase 1 local runbook — Identity, Auth, Multi-Tenancy

## Backend

```powershell
cd backend
# Docker Desktop Engine 29 / API 1.44+: Testcontainers needs an explicit API version.
# Example (Windows, TCP daemon exposure enabled in Docker Desktop):
#   $env:DOCKER_HOST = "tcp://127.0.0.1:2375"
#   $env:DOCKER_API_VERSION = "1.44"
# Also set api.version=1.44 in ~/.docker-java.properties and ~/.testcontainers.properties
mvn -B verify "-Dapi.version=1.44"
```

Local profile defaults:
- JWT secrets for customer/staff
- Bootstrap Platform Owner `owner@rsreach.local` / `ChangeMeOwner1!`
- MFA secret encryption master key
- `rsreach.security.expose-dev-tokens=false` (default). Set `RSREACH_SECURITY_EXPOSE_DEV_TOKENS=true` only for local manual testing of reset/invite delivery without mail. Never enable under Docker/production.

Authorization notes:
- Customer permissions are resolved per active tenant/workspace on the backend (`CustomerAuthorizationService`).
- Invitation role codes are allowlisted; platform roles are rejected. See `docs/architecture/phase-1-authorization.md`.

## Docker Compose

```powershell
Copy-Item .env.example .env
# Set RSREACH_DB_PASSWORD, RSREACH_JWT_CUSTOMER_SECRET, RSREACH_JWT_STAFF_SECRET,
# RSREACH_SECRETS_MASTER_KEY, and optionally bootstrap staff credentials.

docker compose -f infra/compose/docker-compose.dev.yml --env-file .env up --build
```

## Frontend

```powershell
cd frontend/apps/web
npm install
npm run dev
```

Routes:
- `/app/register`, `/app/login`, `/app` (customer)
- `/platform/login`, `/platform` (staff)

Customer and staff tokens are stored under separate localStorage keys and are never shared.
