# ADR 0010: Phase 4 billing Maven module

## Status

Accepted

## Context

Phase 4 requires subscriptions, Razorpay payments, invoices, and entitlements. Controllers must stay free of business rules. MODULE-MAP requires an ADR before a new domain library. No `rsreach-billing` module exists yet.

## Decision

- Add library `rsreach-billing` (not a new OS process).
- Owns: plan catalog/versions, tenant subscriptions, payments, invoices, refunds/credit notes, `EntitlementService`, usage events/aggregates, `PaymentGateway` port with Razorpay adapter.
- Depends on `rsreach-common`, `rsreach-persistence`, `rsreach-security`, `rsreach-audit`, `rsreach-tenancy`.
- Must not depend on `rsreach-api`, `rsreach-worker`, or Spring MVC.
- Flyway for billing schema stays in `rsreach-persistence` (V24+).
- `rsreach-api` and `rsreach-worker` depend on this library; scheduler may enqueue renewal ticks only.
- Subscription is tenant-scoped; workspaces are usage dimensions.
- Money is `BIGINT` paise, currency `INR` for MVP.
- Commercial plan prices are operator-configured data; do not treat transcript samples as approved.

## Consequences

- Thin billing controllers in `rsreach-api`.
- Feature modules call `EntitlementService` instead of hardcoding limits.
- Future gateways implement `PaymentGateway` without a microservice split.
