# Phase 0 Architecture Safeguards

These decisions are locked into the foundation and must be respected by Phase 1+.

## 1. Transactional outbox

- Tables: `outbox_events` (Flyway V1 + V2)
- API: `OutboxRepository.append(...)` must be called inside the **same Spring transaction** as the business write
- Failed transactions roll back business rows and outbox rows together (verified by `Phase0ReliabilityTest` when Docker is available)
- Claim path uses `FOR UPDATE SKIP LOCKED`, lease timeouts, `attempt_count` / `max_attempts`, and `DEAD` status for exhausted retries
- Optional `idempotency_key` unique per tenant (or globally when tenant is null)

## 2. Durable, idempotent provider webhook ingestion

- Table: `provider_webhook_events`
- Uniqueness: `(provider, connected_account_ref, provider_event_id)`
- Ingest API: `ProviderWebhookRepository.ingest(...)` inserts once; duplicates return `inserted=false` without resetting processing state
- Status model: `RECEIVED | QUEUED | PROCESSED | IGNORED | FAILED | DEAD`
- Retry columns: `attempt_count`, `available_at`, `locked_until`, `locked_by`
- Out-of-order support via `event_time` + `received_at`

## 3. Duplicate and out-of-order webhook protection

- Duplicates blocked by unique constraint scoped to connected account
- Same provider event id on two accounts is allowed and treated as distinct
- Processors must reconcile using event time / version semantics, not arrival order alone

## 4. Tenant and workspace isolation constraints

- Marker interfaces: `TenantScoped`, `WorkspaceScoped`
- Baseline CHECK: `workspace_id` requires `tenant_id`
- Phase 1 requirements documented in `phase-1-isolation-requirements.md`

## 5. Integration secret encryption, key management, rotation

- `integration_secret_keys` supports multiple versions per alias; only one `ACTIVE` version
- `integration_secrets` stores ciphertext + nonce + `key_version`
- Master key material from env only:
  - `RSREACH_SECRETS_MASTER_KEY` (v1 fallback)
  - `RSREACH_SECRETS_MASTER_KEY_V{n}`
- `SecretEncryptionService` decrypts using the version stored with the ciphertext, so retired keys still decrypt old secrets
- Never commit `.env`, pem/key files, or master key values

## 6. Configurable worker concurrency and DB pools

| Setting | Env var | Default |
|---|---|---|
| Worker concurrency | `RSREACH_WORKER_CONCURRENCY` | 4 |
| Worker claim batch | `RSREACH_WORKER_CLAIM_BATCH_SIZE` | 20 |
| Worker poll interval | `RSREACH_WORKER_POLL_INTERVAL_MS` | 1000 |
| API pool size | `RSREACH_API_DB_POOL_SIZE` | 10 |
| Worker pool size | `RSREACH_WORKER_DB_POOL_SIZE` | 8 |
| Scheduler pool size | `RSREACH_SCHEDULER_DB_POOL_SIZE` | 5 |

## 7. Security exposure (Phase 0)

- Local profile: `rsreach.security.permit-unauthenticated-api=true`
- Docker profile: permit flag false; only `/actuator/health` and `/api/v1/system/health` are public
- Actuator exposes `health` only by default; `management.info.env.enabled=false`
- Health details are not shown (`show-details: never`)
