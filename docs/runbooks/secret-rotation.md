# Integration secret and master-key rotation

## Envelope master key

1. Generate a new key material (≥32 random bytes, base64 or hex). Do **not** commit it.
2. Set `RSREACH_SECRETS_MASTER_KEY_V2` (or next version) in `.env` and keep V1 until re-encrypt completes.
3. Activate key version 2 in DB (`integration_secret_keys`) — Phase 0 reliability path already supports versioned decrypt.
4. Re-encrypt active `integration_secrets` rows with the new version (ops job / SQL+app helper). Confirm decrypt of a sample connected-account token.
5. Retire V1 key alias version after dual-read window.
6. Restart API/worker/scheduler so env picks up the active version (`RSREACH_SECRETS_KEY_VERSION`).

## JWT signing secrets

1. Issue new `RSREACH_JWT_CUSTOMER_SECRET` / `RSREACH_JWT_STAFF_SECRET`.
2. Rolling restart API. Expect refresh tokens signed with the old secret to fail after restart — users re-login.
3. Document the cutover window for operators.

## Provider tokens (WhatsApp / Gmail / Graph / SendGrid)

1. Rotate at the provider console.
2. Call `POST /api/v1/app/connected-accounts/{id}/oauth/refresh` with the new access token (encrypted at rest).
3. For OAuth refresh tokens, store via `storeRefreshToken` path when mailbox live mode is enabled.
4. Never paste tokens into tickets, logs, or the frontend.

## Verification

- `DockerSecretsValidator` refuses blank docker secrets at boot.
- `Phase0ReliabilityTest` covers encrypt/decrypt across key versions.
