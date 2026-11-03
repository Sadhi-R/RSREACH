# ADR 0007: Phase 3 communications Maven modules

## Status

Accepted

## Context

Phase 3 adds WhatsApp, email, unified inbox, and campaigns. Controllers must stay free of business rules. API, worker, and scheduler need shared policies. MODULE-MAP requires an ADR before new domain libraries.

## Decision

- Add capability libraries (not OS processes):
  - `rsreach-communications` — channel-neutral conversations, messages, assignments, notes, inbox queries
  - `rsreach-whatsapp` — Meta Cloud API adapter, WABA connections, WA templates/media mapping
  - `rsreach-email` — MailboxConnector vs DeliveryConnector; Gmail/Graph sync; delivery providers
  - `rsreach-campaigns` — campaign definition, recipient snapshot, schedule, enqueue
- Depend on `rsreach-common`, `rsreach-persistence`, `rsreach-security`, `rsreach-audit`, `rsreach-tenancy`, and `rsreach-contacts` (consent/eligibility only where needed).
- `rsreach-whatsapp`, `rsreach-email`, and `rsreach-campaigns` may depend on `rsreach-communications`.
- Domain libraries must not depend on `rsreach-api`, `rsreach-worker`, or Spring MVC.
- Flyway for communications schema and RBAC remains in `rsreach-persistence` (V18+).
- `rsreach-api` and `rsreach-worker` depend on these libraries; scheduler may depend for enqueue-only ticks.

## Consequences

- Thin controllers in `rsreach-api`.
- Worker executes send/sync/webhook-apply without duplicating rules.
- Channel adapters stay out of the inbox core.
- Question 7 remains unanswered; campaigns use Phase 2 contact groups as audiences.
