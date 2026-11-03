# Monitoring and alerting (MVP)

## Health

| Process | URL (internal) |
|---|---|
| API | `http://api:8080/actuator/health` |
| Worker | `http://worker:8081/actuator/health` |
| Scheduler | `http://scheduler:8082/actuator/health` |

Public edge should expose only web `:80/:443` and optionally a protected health path. Do not publish OpenAPI or `/actuator/info`.

## Metrics (optional)

Set `management.endpoints.web.exposure.include=health,metrics` only on a private network scrape endpoint. Default docker profile stays `health` only.

Suggested alerts:

1. Any process health ≠ UP for 2 minutes.
2. Outbox `DEAD` row count increase (query `outbox_events` where status=`DEAD`).
3. Webhook `signature_valid=false` rate spike.
4. Disk usage on `rsreach_pg_data` / MinIO volume > 80%.

## Logging

- Stdout JSON/plain with MDC `correlationId` (`X-Correlation-Id`).
- Never log access tokens, refresh tokens, message bodies, or webhook raw PII dumps in app logs.
- Retain webhook payloads in DB with an operator retention policy (see Phase 3.5 risks).

## Rollback

1. Redeploy previous image tags via Compose.
2. Restore DB from last good dump if schema/data regression ([postgres-backup-restore.md](postgres-backup-restore.md)).
3. Confirm health then smoke login + inbox list.
