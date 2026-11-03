# Phase 3 — Local communications runbook

## Prerequisites

- Java 17, Maven 3.9+, Docker (Testcontainers) or local PostgreSQL
- Frontend: Node 20+ in `frontend/apps/web`
- Operator secrets for live Meta/Google/Microsoft/delivery are **optional**; mocks run without them

## Backend verify (CI-equivalent)

```powershell
cd backend
$env:DOCKER_HOST = "tcp://127.0.0.1:2375"   # if Docker Desktop TCP is enabled
$env:DOCKER_API_VERSION = "1.44"
mvn -B verify "-Dapi.version=1.44"
```

Expected: `BUILD SUCCESS`, Surefire **Skipped: 0** for executed suites.  
Failure signal: Testcontainers cannot reach Docker, or Flyway validate fails on V18–V23.

Focused Phase 3 smoke:

```powershell
mvn -B -pl rsreach-api,rsreach-persistence,rsreach-communications,rsreach-scheduler -am test `
  "-Dtest=Phase3CommunicationsTest,Phase3CommunicationsRbacMigrationTest,ConnectedAccountTokenRefreshTest,ScheduledMessageSchedulerTest" `
  "-Dsurefire.failIfNoSpecifiedTests=false" "-Dapi.version=1.44"
```

## Frontend gates

```powershell
cd frontend/apps/web
npm run typecheck
npm test -- --run
npm run build
```

Expected: typecheck clean; Vitest includes inbox permission tests; build succeeds.

## Compose (operator path)

Env templates live at the **repository root**, not under `infra/compose/`.

1. From the repository root:

```powershell
Copy-Item .env.example .env
# Set RSREACH_DB_PASSWORD, RSREACH_JWT_CUSTOMER_SECRET, RSREACH_JWT_STAFF_SECRET,
# and RSREACH_SECRETS_MASTER_KEY (never commit .env).
docker compose -f infra/compose/docker-compose.dev.yml --env-file .env up --build
```

2. Health checks:
   - http://localhost:8088 (web)
   - http://localhost:8080/actuator/health (API)
   - http://localhost:8081/actuator/health (worker)
   - http://localhost:8082/actuator/health (scheduler)

3. Docker profile **refuses to start** if JWT secrets or the secrets master key are blank.

If `.env` is missing, skip Compose and rely on Testcontainers verify (explicit residual risk). See also [postgres-backup-restore.md](postgres-backup-restore.md).

## Webhook signatures (local vs docker)

- Profile `local` / CI tests: header `X-Hub-Signature-256: mock-valid` is accepted when `rsreach.webhooks.allow-mock-signatures=true` (default outside docker).
- Profile `docker` / production: mock signatures are rejected; configure Meta app secret / delivery verification keys.

## Manual inbox smoke (mocked providers)

1. Register/login customer; create tenant.
2. Create contact with EMAIL + PHONE.
3. `POST /api/v1/app/conversations` with `INTERNAL_TEST` or `WHATSAPP`.
4. Opt in consent before WhatsApp/Email marketing send.
5. Outside 24h window, WhatsApp compose needs `templateName` of an APPROVED template.
6. Connect `gmail` / `sendgrid` accounts with a placeholder `accessToken` (encrypted at rest).
7. `POST /api/v1/app/connected-accounts/{id}/sync` then process worker / call mailbox sync.
8. Delivery webhook: `POST /api/v1/webhooks/sendgrid?tenantId=&workspaceId=` with header `X-Hub-Signature-256: mock-valid` (local only).

## Live providers (optional)

Do not invent credentials. When operators supply sandbox secrets, set feature flags documented in `.env.example` (`RSREACH_WHATSAPP_LIVE_ENABLED`, `RSREACH_EMAIL_DELIVERY_LIVE_ENABLED`, mailbox OAuth client ids). Sandbox ITs use `@EnabledIfEnvironmentVariable` and stay skipped in default CI.

## Stop line

Do not start Phase 4 (billing automation expansions) from this runbook. Do not invent Discovery Question 7.
