# ADR 0001: Modular monolith on Java 17

## Status

Accepted

## Context

RSReach needs a production-grade multi-tenant SaaS foundation that can start on a single VPS and evolve without premature microservices.

## Decision

- Modular monolith with Maven modules
- Separate runnable processes: API, worker, scheduler
- Java 17 only; Spring Boot 3.3.x
- PostgreSQL + Flyway
- Docker Compose + Nginx
- No Redis, Kubernetes, or microservices in early phases

## Consequences

- Shared database and shared domain modules keep operational cost low
- Clear process boundaries prepare future extraction if needed
- Team must maintain module boundaries and avoid controller business logic
