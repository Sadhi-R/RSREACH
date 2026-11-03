# ADR 0009: Object storage product (MinIO)

## Status

Accepted

## Context

ADR 0008 deferred durable binary storage. Phase 3.5 requires a named product before implementing `storage_ref` uploads. RSReach is a modular monolith on Docker Compose / Linux VPS without Kubernetes. Local filesystem is unacceptable beyond single-dev. External S3 is viable but adds a third-party dependency and cost. MinIO is S3-compatible and fits self-hosted Compose.

## Decision

- **Product:** MinIO (S3 API) for self-hosted object storage.
- Object keys MUST be namespaced as `{tenantId}/{workspaceId}/{messageId}/{attachmentId}`.
- API never returns long-lived public URLs; use short-lived presigned GET/PUT issued after tenant/workspace authz.
- Server-side encryption at rest is enabled on the MinIO volume (disk encryption + MinIO SSE when configured).
- Default max object size: 10 MiB (aligned with Spring multipart limits) unless raised by ops config.
- Malware scanning is **out of scope** for this ADR; operators may add a gateway scanner later.
- Retention: soft-delete via object lifecycle or app job; hard-delete on conversation purge policies (future).
- Provider media workflow: worker downloads from Meta/Gmail using encrypted credentials, then PUT to MinIO and sets `message_attachments.storage_ref`.
- PostgreSQL does not store blobs.
- Feature flag `rsreach.media.object-store-enabled` keeps CI/mock paths working without MinIO.

## Consequences

- Compose gains an optional `minio` service for production-like stacks; not required for default Testcontainers CI.
- ADR 0008 metadata columns remain; `storage_ref` becomes `s3://{bucket}/{key}` or `minio://{bucket}/{key}`.
- A future ADR may supersede MinIO with a managed S3-compatible provider without rewriting message rows.
