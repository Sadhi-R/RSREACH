# Phase 4: Billing & Automation Runbook

## Overview

Phase 4 adds billing (subscriptions, entitlements, Razorpay payments) and linear workflow
automation to RSReach. This runbook covers startup checks, operational tasks, and debugging.

## New modules

- **`rsreach-billing`**: Subscriptions, plan catalog, entitlements, invoices, payments, Razorpay adapter.
- **`rsreach-automation`**: Workflow CRUD, trigger evaluation, run execution engine.

## New controllers (rsreach-api)

| Endpoint | Description |
|---|---|
| `GET /api/v1/app/billing/subscription` | Tenant subscription view |
| `POST /api/v1/app/billing/activate-free` | Activate FREE plan |
| `GET /api/v1/app/billing/plans` | Plan catalog with versions and entitlements |
| `POST /api/v1/app/billing/checkout` | Razorpay checkout initiation |
| `GET /api/v1/app/billing/usage` | Current period usage meters |
| `GET /api/v1/app/billing/invoices` | Invoice list |
| `POST /api/v1/webhooks/razorpay` | Razorpay webhook receiver |
| `GET /api/v1/platform/billing/tenants/{id}` | Platform: view tenant billing |
| `POST /api/v1/platform/billing/tenants/{id}/manual-payment` | Platform: record manual payment |
| `POST /api/v1/platform/billing/tenants/{id}/entitlement-overrides` | Platform: override meter limit |
| `GET /api/v1/app/automations` | List automation workflows |
| `POST /api/v1/app/automations` | Create draft workflow |
| `POST /api/v1/app/automations/{id}/activate` | Activate workflow (consumes AUTOMATIONS entitlement) |
| `GET /api/v1/app/automations/{id}/runs` | List workflow runs |

## New scheduler

`BillingRenewalScheduler` runs every 5 minutes (configurable: `rsreach.scheduler.billing-renewal-ms`).
Drives subscription lifecycle: trial expiry, grace period, suspension.

## Entitlement enforcement

Entitlements are checked and consumed at these points:

| Meter | Where consumed |
|---|---|
| `CONTACTS` | `ContactService.create` |
| `CAMPAIGNS` | `CampaignService.start` |
| `AUTOMATIONS` | `AutomationWorkflowService.activate` (DRAFT→ACTIVE only) |
| `WHATSAPP_MESSAGES` | `ConversationService.composeOutbound` (best-effort) |
| `EMAIL_MESSAGES` | `ConversationService.composeOutbound` (best-effort) |

**Lazy bootstrap**: If a tenant has no subscription when entitlement check/consume is called,
the FREE plan is auto-activated (`EntitlementService.lazyBootstrapIfMissing`).

## Domain event bridge

`WorkspaceDomainEvent` (in `rsreach-common`) is published by domain services after commit and
consumed by `AutomationDomainEventListener` (in `rsreach-automation`) using
`@TransactionalEventListener(AFTER_COMMIT)`.

Mapping:
- `CONTACT_CREATED` → `AutomationModels.TRIGGER_CONTACT_CREATED`
- `CONTACT_UPDATED` → `AutomationModels.TRIGGER_CONTACT_UPDATED`
- `CAMPAIGN_COMPLETED` → `AutomationModels.TRIGGER_CAMPAIGN_COMPLETED`

## Environment variables

See `billing-razorpay.md` for Razorpay-specific variables.

Scheduler: `RSREACH_SCHEDULER_BILLING_RENEWAL_MS` (default: 300000 ms = 5 min).

## Startup checks

1. `plan_catalog` and `plan_version` tables must have at least one FREE plan with `code='FREE'`.
2. `billing_invoice_seq` must have a row for supplier_code `RSREACH`.
3. Razorpay env vars can be empty for local dev (mock mode: `rsreach.webhooks.allow-mock-signatures=true`).

## Debugging

- **No FREE plan found**: Check `plan_catalog` and `plan_version` tables; ensure a row with `code='FREE'` and `active=true` exists.
- **Entitlement always denied**: Check `entitlement_override` and `plan_entitlement` tables. Use platform override endpoint to unblock.
- **Webhook signature failures**: Verify `RSREACH_RAZORPAY_WEBHOOK_SECRET` matches Razorpay dashboard webhook secret.
- **Automation not triggering**: Check `automation_workflow.status='ACTIVE'` and `automation_version.trigger_type` matches the event type. Check `outbox_events` and `automation_run` tables.
