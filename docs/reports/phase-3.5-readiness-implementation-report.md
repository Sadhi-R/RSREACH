# Phase 3.5 — Integration & production readiness implementation report

**Date:** 2026-11-03  
**Scope:** P0–P5 from the Phase 3.5 readiness assessment  
**Phase 4:** Not started

## Summary

| Tranche | Status |
|---|---|
| P0 Hygiene | Done — runbook fix, docker secret fail-fast, webhook harden, backup runbook, correlation MDC, scheduler IT |
| P1 WhatsApp live scaffolding | Done — HMAC verifier, GET challenge, apply path, Cloud API sender behind flag, retry classifier, template sync helper; sandbox IT gated |
| P2 Mailbox live scaffolding | Done — Gmail/Graph connectors behind flag; OAuth start with live URL when client id supplied; refresh token store |
| P3 Delivery live scaffolding | Done — SendGrid `DeliveryConnector`, live email sender, signature harden, bounce→suppression |
| P4 Media | Done — ADR 0009 MinIO; `ObjectStoragePort` + MinIO/NoOp; provider media materialize helper |
| P5 Ops | Done — prod Compose (private network), rate-limit filter, monitoring + secret-rotation runbooks |

Mocks remain the **default** CI path (`live-enabled=false`). Live HTTP requires operator secrets — none invented.

## Acceptance checklist

| Criterion | Evidence |
|---|---|
| P0.1 Runbook paths | [phase-3-local.md](../runbooks/phase-3-local.md) uses root `.env` + `docker-compose.dev.yml` |
| P0.2 Fail-fast | `DockerSecretsValidator` + unit tests |
| P0.3 Webhook sigs | Invalid signature → 401; docker `allow-mock-signatures=false` |
| P0.4 Backup | [postgres-backup-restore.md](../runbooks/postgres-backup-restore.md) |
| P0.5 Correlation | `CorrelationIdFilter` + logging pattern `%X{correlationId}` |
| P0.6 Scheduler IT | `ScheduledMessageSchedulerTest` |
| P1–P3 live | Feature flags; sandbox IT `@EnabledIfEnvironmentVariable` |
| P4 ADR | [0009-minio-object-storage.md](../adr/0009-minio-object-storage.md) |
| P5 Prod compose | [docker-compose.prod.yml](../../infra/compose/docker-compose.prod.yml) |

## Verify

| Gate | Result |
|---|---|
| `mvn -B verify -Dapi.version=1.44` | **BUILD SUCCESS**, Skipped **0** |
| Flyway V1–V23 | Untouched |
| Phase 4 | Not started |

## Residual risks

- Compose smoke still needs a local `.env` with real operator secrets.
- MinIO SigV4 signing is MVP bearer/path-style; harden before public internet MinIO.
- Live Meta Embedded Signup still deferred pending Meta program confirmation (SOURCES).
- Discovery Question 7 still unanswered.
