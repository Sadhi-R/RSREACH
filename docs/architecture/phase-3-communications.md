# Phase 3 — Communications architecture

**Status:** Complete (M0–M10); Phase 3.5 readiness scaffolding landed  
**ADRs:** [0007](../adr/0007-phase3-communications-modules.md) (modules), [0008](../adr/0008-attachment-media-storage.md) (media MVP), [0009](../adr/0009-minio-object-storage.md) (MinIO), [0004](../adr/0004-consent-workspace-scope.md) (consent), [0001](../adr/0001-modular-monolith-java17.md) (monolith)

## Processes

API / worker / scheduler share one PostgreSQL. Outbound send, mailbox sync, campaign enqueue, and scheduled-message fire use the transactional outbox. Scheduler enqueues only (due `scheduled_messages` → `SCHEDULED_MESSAGE_FIRE`).

## Modules

| Module | Owns |
|---|---|
| `rsreach-communications` | Conversations, messages, status events, attachment metadata, assignments, notes, templates, `MessageSender` registry |
| `rsreach-whatsapp` | WhatsApp Cloud API adapter (mock), inbound webhook ingest helpers |
| `rsreach-email` | `MailboxConnector` vs `DeliveryConnector`; mock mailbox sync; delivery webhook apply |
| `rsreach-campaigns` | Campaigns, recipient snapshot, consent re-check, pause/cancel |

Controllers live in `rsreach-api` under `/api/v1/app/...`. Public webhooks: `/api/v1/webhooks/{provider}`.

## Schema (Flyway V18–V23)

| Version | Content |
|---|---|
| V18 | Permissions: `inbox.*`, `integrations.manage`, `templates.manage`, `campaigns.*` |
| V19 | `connected_accounts` |
| V20 | `conversations`, `messages`, `message_status_events`, `message_attachments` |
| V21 | `conversation_assignments`, `conversation_notes` |
| V22 | `channel_templates` |
| V23 | `campaigns`, `campaign_recipients`, `scheduled_messages` |

Phase 0 `outbox_events`, `provider_webhook_events`, and `integration_secrets` are reused — no parallel stores. V1–V17 are immutable.

## Send / receive

1. Compose: `ConsentEvaluationService.evaluate` with explicit address; suppression wins.
2. WhatsApp interactive send outside the 24h inbound service window requires an APPROVED template name.
3. Attachments store **metadata + provider media id** only (ADR 0008).
4. Same TX: message `QUEUED` + `MESSAGE_SEND` outbox.
5. Worker: `MessageSenderRegistry` → mock WhatsApp/Email adapters mark `SENT`.
6. Webhooks: signature check, then resolve tenant and workspace from `connected_accounts.external_account_ref` ([ADR 0012](../adr/0012-webhook-account-tenant-resolution.md)). Query-string tenant ids are not trusted. Ingest stays idempotent by payload hash.

## Email

- **Mailbox:** connect account → encrypt token → `POST .../sync` enqueues `MAILBOX_SYNC` → mock connector inserts inbound `EMAIL` messages.
- **Delivery:** `MockEmailSender` / `DeliveryConnector` port; SendGrid/SES/Mailgun webhooks update `DELIVERED` / `FAILED` by `provider_message_id`.
- Live Gmail/Graph/Meta/delivery HTTP requires operator credentials — not invented here.

## Campaigns

- Audience = Phase 2 contact groups and/or ACTIVE contacts (no Discovery Question 7 model).
- Start snapshots recipients; worker re-checks eligibility; opted-out/suppressed → `SKIPPED`.
- One channel per campaign; no silent cross-channel fallback.
- Pause / resume / cancel honored via campaign status before enqueue.

## Isolation and security

- Every query is tenant + workspace scoped; auth context only.
- Credentials in `integration_secrets` (AES-GCM); never returned to UI/logs.
- Resource shares never grant `consent.manage` or campaign override of eligibility.
- Staff realm has no automatic customer inbox access.

## Frontend

Customer routes: `/app/inbox`, `/app/integrations`, `/app/campaigns` (plus conversation/campaign detail). Dashboard links under Communications. Vitest covers inbox permission gating.

## Explicit non-goals (stop line)

- Discovery Question 7 communication-group model
- Embedded Signup at scale / live provider E2E without operator secrets
- Phase 4 billing / automation expansions

Object storage product is MinIO (ADR 0009), feature-flagged off by default.
