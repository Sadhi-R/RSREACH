# N1 — Operator verification report

**Date:** 2026-11-03  
**Scope:** Frontend CI gate, Compose fail-fast for the Razorpay webhook secret, MinIO SigV4 unit evidence, dev Compose smoke  
**Verdict:** PASS for the automated gates and a local dev Compose smoke. Live MinIO put/get was not run.

## CI workflow

[`.github/workflows/ci.yml`](../../.github/workflows/ci.yml) frontend job now:

1. `npm ci` (cache key `frontend/apps/web/package-lock.json`)
2. `npm run typecheck`
3. `npm test` (`vitest run`)
4. `npm run build`

GitHub Actions was not executed in this session. The same commands were run locally against a copy of `frontend/apps/web` (the workspace `node_modules` tree was locked by a running Vite process during the first `npm ci`).

| Step | Result |
|---|---|
| `npm ci` | PASS (180 packages) |
| `npm run typecheck` | PASS |
| `npm test` | **37/37 PASS** (9 files, Vitest 2.1.9) |
| `npm run build` | PASS |

After that run, the locked Vite and esbuild processes were stopped and `npm ci` was run again in `frontend/apps/web` so `node_modules` is complete. Restart `npm run dev` if a local web server is needed.

## Backend unit evidence

```text
mvn -B -pl rsreach-communications -am test -Dtest=MinioSigV4Test -Dsurefire.failIfNoSpecifiedTests=false
```

| Suite | Result |
|---|---|
| `MinioSigV4Test` | **2 tests, 0 failures** |

The test builds an `AWS4-HMAC-SHA256` Authorization value with fixture keys. It does not open a socket and does not assert the secret key. Default `rsreach.media.object-store-enabled=false` still selects `NoOpObjectStorage`.

## Compose wiring

- Dev and prod API services require `RSREACH_RAZORPAY_WEBHOOK_SECRET` (Compose interpolation fails if it is unset).
- Dev and prod API services pass MinIO endpoint, bucket, access key, and secret key. Empty access/secret keys leave SigV4 off.
- API, worker, and scheduler set `RSREACH_APP_ROLE` so the worker and scheduler do not take the API secret check. Worker and scheduler jars already default those roles in their own `application.yml`.
- `.env.example` leaves JWT, master key, Razorpay webhook secret, and MinIO keys blank. Docker profile rejects blank values and any value that starts with `replace-with-`.
- `RSREACH_WEBHOOKS_ALLOW_MOCK_SIGNATURES` in `.env.example` is `false`.

`docker compose -f infra/compose/docker-compose.dev.yml --env-file .env config` against the operator `.env` (which does not yet define `RSREACH_RAZORPAY_WEBHOOK_SECRET`):

```text
error while interpolating services.api.environment.RSREACH_RAZORPAY_WEBHOOK_SECRET:
required variable RSREACH_RAZORPAY_WEBHOOK_SECRET is missing a value
```

Exit code 1. No secret value was printed.

## Dev Compose smoke

The operator `.env` already had JWT and master-key material of sufficient length and did not contain the new webhook variable. A temporary env file under `.tmp` (gitignored) copied that file, set media storage off, set mock webhook signatures off, and added a generated webhook secret. That file was deleted after the run. It was not committed.

Host ports for this run were **18080 / 18081 / 18082 / 18088** because **8080 was already in use** by the local API process. The published Postgres port was restored to **55433**. The existing `rsreach` volume was kept (register succeeded against that database). API, worker, scheduler, and web were stopped after the checks. Postgres was left running on 55433.

| Check | Result |
|---|---|
| `GET :18080/actuator/health` | **200** `{"status":"UP"}` |
| `GET :18081/actuator/health` | **200** `{"status":"UP"}` |
| `GET :18082/actuator/health` | **200** `{"status":"UP"}` |
| `GET :18088/` | **200**, SPA root present |
| `GET /api/v1/system/info` without auth | **403** |
| `POST /api/v1/app/auth/register` | **200** (tokens not recorded) |
| `POST /api/v1/app/auth/login` | **200** (tokens not recorded) |
| `POST /api/v1/webhooks/razorpay` forged `X-Razorpay-Signature` | **401**; log `razorpay_webhook_invalid_signature` |
| API env `RSREACH_APP_ROLE` | `api` |
| Worker / scheduler role | `worker` / `scheduler` |
| `RSREACH_MEDIA_OBJECT_STORE_ENABLED` | `false` |
| `RSREACH_WEBHOOKS_ALLOW_MOCK_SIGNATURES` | `false` |
| MinIO access key | empty |
| `docker_secrets_validated role=api` | present in API log |

## MinIO

| Path | Result |
|---|---|
| Default object store | **Off** (`NoOpObjectStorage`). Primary smoke did not start MinIO. |
| SigV4 | **PASS** via `MinioSigV4Test` |
| Live put/get with `--profile media` | **Not run — no operator MinIO password** (`RSREACH_MINIO_ROOT_PASSWORD` and MinIO access/secret keys are absent from `.env`) |

## Residuals

- Add `RSREACH_RAZORPAY_WEBHOOK_SECRET` (at least 16 characters, not a `replace-with-` placeholder) to the operator `.env` before the next Compose up. Compose will refuse to start the API until that variable is set.
- Live Meta Embedded Signup, live Gmail, and live Razorpay Orders end-to-end are out of N1.
- D2 catalog prices and Q7 are unchanged.
- Phase 0–5 reports were not edited.
- Shell exports of `RSREACH_*` override `--env-file`. Clear them before Compose, or published ports and the database user can diverge from `.env`. See `docs/runbooks/compose-smoke-checklist.md`.
