# PostgreSQL backup and restore

## Scope

Single-VPS Compose deployment. Volume: `rsreach_pg_data`. Database name defaults to `rsreach`.

Never commit dump files that may contain PII. Encrypt backups at rest and restrict filesystem ACLs.

## Backup (pg_dump)

From the repository root with Compose running:

```powershell
$stamp = Get-Date -Format "yyyyMMdd-HHmmss"
New-Item -ItemType Directory -Force -Path ".\backups" | Out-Null
docker compose -f infra/compose/docker-compose.dev.yml --env-file .env exec -T postgres `
  pg_dump -U rsreach -d rsreach -Fc > ".\backups\rsreach-$stamp.dump"
```

Expected: non-empty `.dump` file. Failure signal: empty file or Docker exec error.

Schedule (operator): nightly cron/Task Scheduler that runs the dump and copies encrypted artifacts off-box.

## Restore drill

1. Stop API/worker/scheduler (leave postgres up or use a scratch Compose project).
2. Recreate empty database or restore into a scratch DB:

```powershell
docker compose -f infra/compose/docker-compose.dev.yml --env-file .env exec -T postgres `
  pg_restore -U rsreach -d rsreach --clean --if-exists < ".\backups\rsreach-YYYYMMDD-HHmmss.dump"
```

3. Start API; confirm Flyway at expected version; hit `/actuator/health` UP on API/worker/scheduler.
4. Spot-check a tenant login and `GET /api/v1/app/conversations` (or contacts) against known fixture data.

## Production Compose

Use [`infra/compose/docker-compose.prod.yml`](../../infra/compose/docker-compose.prod.yml): Postgres is not published on the host. Run `pg_dump` via `docker compose exec` on the private network the same way.

## Rollback

1. Restore the last known-good dump.
2. Redeploy the same application image tags that match that schema era.
3. Confirm health and a read path before accepting traffic.
