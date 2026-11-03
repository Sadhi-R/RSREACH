# ADR 0011: Phase 4 automation Maven module

## Status

Accepted

## Context

Phase 4 authorizes a constrained automation MVP (linear workflows). SOURCES previously listed advanced visual builders as future scope; Phase 4 explicitly includes a linear builder only. MODULE-MAP requires an ADR before a new domain library.

## Decision

- Add library `rsreach-automation` (not a new OS process).
- Owns: workflow definitions/versions, triggers, condition evaluation, action dispatch, execution runs, loop/depth guards.
- Depends on `rsreach-common`, `rsreach-persistence`, `rsreach-security`, `rsreach-audit`, `rsreach-tenancy`, `rsreach-contacts`, `rsreach-communications`, and `rsreach-billing` (entitlements for run quotas).
- Must not depend on `rsreach-api` or `rsreach-worker`.
- Outbound actions must call `ConsentEvaluationService` with an explicit address.
- MVP shape is linear: one trigger → condition group → ordered actions. No freeform DAG, AI steps, or silent cross-channel fallback.
- Flyway for automation schema stays in `rsreach-persistence` (V29+).
- Execution uses the transactional outbox (`AUTOMATION_RUN`, `AUTOMATION_ACTION`).

## Consequences

- Campaigns remain bulk-send; automations are event-driven linear rules.
- Entitlement meters gate automation count and run volume.
- Question 7 remains unanswered; audiences/actions use Phase 2 contact groups.
