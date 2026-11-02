# ADR 0002: Phase 0 reliability primitives

## Status

Accepted

## Context

Communication delivery and billing webhooks require at-least-once processing with idempotency. A naive “write then enqueue in memory” approach loses work on crashes.

## Decision

Ship baseline Flyway objects for:

1. Transactional outbox
2. Durable provider webhook events with uniqueness and event-time fields
3. Integration secret key version metadata
4. Configurable worker concurrency and HikariCP pool sizes via environment variables

## Consequences

- Phase 1+ features build on these tables instead of inventing ad-hoc queues
- Operators can tune throughput without code changes
- Secret material remains outside the repository
