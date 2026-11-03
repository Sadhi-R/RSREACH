# Compose smoke checklist (Phase 5 M0 / N1)

Operator-run checklist before treating a Compose stack as production-like. Do **not** invent secrets; use operator-generated values in `.env`. Never commit `.env`. Do not print secret values in logs or reports.

## Prerequisites

1. Copy `.env.example` → `.env` and set every required secret to an operator-generated value.
2. Confirm Docker Engine is running.
3. Docker profile fail-fast rejects blank values and any value that starts with `replace-with-`.
4. API (`RSREACH_APP_ROLE=api`) requires `RSREACH_SECRETS_MASTER_KEY`, `RSREACH_JWT_CUSTOMER_SECRET`, `RSREACH_JWT_STAFF_SECRET`, and `RSREACH_RAZORPAY_WEBHOOK_SECRET` (each at least 16 characters). Compose fails at startup if the webhook secret is unset.
5. Worker and scheduler roles require the master key only. Leave `RSREACH_MEDIA_OBJECT_STORE_ENABLED=false` for the primary smoke so storage stays `NoOpObjectStorage`.
6. Shell variables override `--env-file`. Clear any exported `RSREACH_*` values in the current shell before `docker compose`, or the published ports and database user can diverge from `.env`.

## Start

```powershell
docker compose -f infra/compose/docker-compose.dev.yml --env-file .env up --build -d
```

## Checks

| # | Check | Expect |
|---|---|---|
| 1 | `GET http://localhost:8080/actuator/health` | 200 UP |
| 2 | `GET http://localhost:8081/actuator/health` | 200 UP |
| 3 | `GET http://localhost:8082/actuator/health` | 200 UP |
| 4 | `GET http://localhost:8088/` | SPA loads |
| 5 | `GET /api/v1/system/info` without auth | 401/403 (not open) |
| 6 | Customer register + login | Tokens issued |
| 7 | Create tenant + `POST /app/billing/activate-free` | Subscription ACTIVE |
| 8 | `POST /api/v1/webhooks/razorpay` with forged signature | 401 |
| 9 | Media flag off by default | No public MinIO required; API uses `NoOpObjectStorage` |

## Optional MinIO (not part of the primary smoke)

Only when the operator has set `RSREACH_MINIO_ROOT_PASSWORD`, `RSREACH_MEDIA_MINIO_ACCESS_KEY`, and `RSREACH_MEDIA_MINIO_SECRET_KEY` in `.env`:

```powershell
docker compose -f infra/compose/docker-compose.prod.yml --env-file .env --profile media up --build -d
```

Set `RSREACH_MEDIA_OBJECT_STORE_ENABLED=true` for that run. Put and get one tiny object through the API, then record HTTP status codes only. CI covers SigV4 with `MinioSigV4Test` and does not start MinIO.

## Stop

```powershell
docker compose -f infra/compose/docker-compose.dev.yml --env-file .env down
```

## Residual

- Live Meta Embedded Signup still deferred.
- Live Razorpay Orders require `RSREACH_RAZORPAY_LIVE_ORDERS=true` plus real keys.
- Prod Compose TLS terminates at host edge (see `docker-compose.prod.yml`).
