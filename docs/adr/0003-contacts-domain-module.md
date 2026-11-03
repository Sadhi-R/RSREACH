# ADR 0003: Contacts domain library module

## Status

Accepted

## Context

Phase 2 introduces Contacts & Organization Management. Phase 1 already has capability libraries (`rsreach-tenancy`, `rsreach-audit`, identity modules). Controllers must stay free of business rules. API and worker both need shared contact policies (import jobs, dynamic group refresh, merge). `MODULE-MAP.md` requires an ADR before adding a domain/capability library depended on by runnable processes.

## Decision

- Add Maven module `rsreach-contacts` as a **capability library** (not a fourth OS process).
- Depend on `rsreach-common`, `rsreach-persistence`, `rsreach-security`, `rsreach-audit`, and `rsreach-tenancy` (for scoped authorization helpers only).
- `rsreach-api` and `rsreach-worker` may depend on `rsreach-contacts`.
- `rsreach-contacts` must not depend on `rsreach-api`, `rsreach-worker`, or Spring MVC.
- Flyway migrations for contact schema and RBAC remain in `rsreach-persistence`.
- Contact ownership is workspace-scoped; cross-workspace access uses existing `resource_shares`.
- Contact permissions are seeded in Flyway V10+ and enforced via Phase 1 `CustomerAuthorizationService`.

## Consequences

- Contact CRUD, consent, groups, and import/export application services live in `rsreach-contacts`.
- Controllers in `rsreach-api` stay thin.
- Worker can execute contact import and dynamic group refresh without duplicating rules.
- Future WhatsApp/email/campaign modules consume contacts/consent without owning contact lifecycle.
