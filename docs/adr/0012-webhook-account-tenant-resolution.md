# ADR 0012: Webhook account-to-tenant resolution

## Status

Accepted

## Context

`POST /api/v1/webhooks/{provider}` required `tenantId` and `workspaceId` query parameters and stored the event on those ids after the signature check. Meta’s WhatsApp callback will not add those parameters. A valid app signature plus known UUIDs could ingest the body into the wrong tenant. When `accountRef` was omitted, delivery ingest stored the connected-account reference as `default`, which is not a tenant mapping.

`GET /api/v1/webhooks/whatsapp` already follows Meta’s handshake (`hub.mode`, `hub.verify_token`, `hub.challenge`) and does not take a tenant id. Razorpay stays on `POST /api/v1/webhooks/razorpay` and is not part of this decision.

`connected_accounts.external_account_ref` already stores the provider account id. WhatsApp send uses that column as the Cloud API phone-number id. There is no unique constraint on `(provider, external_account_ref)`. V1–V32 are unchanged.

## Decision

1. Verify the provider signature against the raw body before parsing. Invalid signatures return HTTP 401 and are not stored.
2. Read the account identifier from the signed body. Ignore query-string `tenantId`, `workspaceId`, and `accountRef`. Ignore any tenant or workspace id inside the JSON.
3. Load `connected_accounts` where `provider` matches, `external_account_ref` equals that identifier, `status = 'ACTIVE'`, and `disconnected_at` is null.
4. One row: ingest and apply using that row’s `tenant_id` and `workspace_id`. The stored `connected_account_ref` is the identifier from the body, never `default`.
5. No row, including only disconnected or `ERROR` rows: HTTP 404 `account_not_found`. Do not insert `provider_webhook_events`.
6. More than one active row, or more than one distinct identifier in the body: HTTP 409 `ambiguous_account`. Log provider and match count only. Do not insert.
7. No identifier in the body, or a body that is not JSON: HTTP 400 `missing_account_ref`. Do not insert.
8. Idempotency stays `(provider, connected_account_ref, provider_event_id)` with `provider_event_id` equal to the SHA-256 of the raw body. A duplicate returns the existing row and does not reset it or re-apply it.
9. The GET verify handshake is unchanged.

### Identifiers

| Provider | Field in the signed body | `connected_accounts.provider` |
|---|---|---|
| WhatsApp | `entry[].changes[].value.metadata.phone_number_id`. A body with more than one distinct id is ambiguous. The WABA id (`entry[].id`) is not a lookup key. | `whatsapp` |
| `mock` URL | Same phone-number id shape. The event row is still stored as provider `whatsapp`. | `mock` |
| SendGrid | Each event’s `account_ref`, `accountRef`, or `unique_args.account_ref`. An array must agree on one value. | `sendgrid` |
| SES | `account_ref`, `accountRef`, or `mail.tags.account_ref`. | `ses` |
| Mailgun | `event-data.user-variables.account_ref` or `accountRef`. | `mailgun` |
| Gmail | `emailAddress` (the mailbox). | `gmail` |
| Microsoft Graph | `value[].subscriptionId`. Every notification in the body must use that same id. | `microsoft_graph` |

Operators set `external_account_ref` to that same value when they connect the account. SendGrid, SES, and Mailgun do not send a tenant id; the identifier above has to be present in the signed event (unique argument, tag, or user variable).

## Consequences

- A Meta callback URL without tenant query parameters is valid once the phone-number id matches one active WhatsApp account.
- Saved callback URLs that still include tenant query parameters keep working only because those parameters are ignored. The phone-number id in the body decides the tenant.
- Delivery webhooks that omit the account field are rejected instead of being filed under `default`.
- Ambiguous historical rows are rejected until an operator disconnects the extras. No migration adds a unique index in this change.
- Mock signatures remain off in production. Forged signatures and disabled mock signatures do not ingest.
- Razorpay webhook resolution is unchanged.
