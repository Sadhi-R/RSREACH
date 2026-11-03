# Razorpay Billing Runbook

## Overview

RSReach uses Razorpay as its primary payment gateway (INR, Phase 4). This runbook covers
webhook verification, payment activation flow, and common operational tasks.

## Required environment variables

| Variable | Description |
|---|---|
| `RSREACH_RAZORPAY_KEY_ID` | Razorpay API key ID (public) |
| `RSREACH_RAZORPAY_KEY_SECRET` | Razorpay API key secret (for order creation) |
| `RSREACH_RAZORPAY_WEBHOOK_SECRET` | Razorpay webhook signature secret |
| `RSREACH_RAZORPAY_LIVE_ENABLED` | `true` to make live API calls (default: `false`) |

Set these in `.env` (never committed). Do not hardcode.

## Webhook flow

1. Razorpay posts `POST /api/v1/webhooks/razorpay` with `X-Razorpay-Signature` header.
2. `RazorpayWebhookController` reads the raw body bytes and signature.
3. Signature is verified via `BillingWebhookService.verifySignature` (HMAC-SHA256).
4. If invalid → 401 returned immediately; payload is NOT stored.
5. If valid → `BillingWebhookService.ingest` stores the payload in `provider_webhook_events`
   and appends `BILLING_WEBHOOK_APPLY` outbox event.
6. Worker picks up the outbox event and calls `BillingWebhookService.applyEvent`.
7. For `payment.captured` → `PaymentService.applyRazorpayPayment` → `SubscriptionService.applyPaidActivation`.

## Subscription lifecycle transitions

Run by `BillingRenewalScheduler` (every 5 minutes via `BillingRenewalService.processRenewalTick`):

| Condition | Transition |
|---|---|
| TRIALING + trial_ends_at past + price = 0 | → ACTIVE (free auto-activate) |
| TRIALING + trial_ends_at past + price > 0 | → PAST_DUE |
| ACTIVE + period_end past + price > 0 | → PAST_DUE |
| PAST_DUE + grace_ends_at past | → SUSPENDED |

## Manual payment recording (staff)

Use `POST /api/v1/platform/billing/tenants/{tenantId}/manual-payment` with staff JWT (MFA required):

```json
{
  "amountPaise": 99900,
  "currency": "INR",
  "method": "BANK_TRANSFER",
  "externalReference": "TXN123"
}
```

## Entitlement overrides (staff)

Use `POST /api/v1/platform/billing/tenants/{tenantId}/entitlement-overrides`:

```json
{
  "meterCode": "AUTOMATIONS",
  "limitValue": 10,
  "reason": "Enterprise trial extension",
  "expiresAt": "2027-01-01T00:00:00Z"
}
```

`limitValue: null` means unlimited override.

## Debugging

- Check `provider_webhook_events` table: `processing_status`, `last_error`, `signature_valid`.
- Check `outbox_events` table for stuck `BILLING_WEBHOOK_APPLY` events.
- Check `subscription_event` table for lifecycle history.
- Set `rsreach.webhooks.allow-mock-signatures=true` in local profile for testing with mock payloads.
