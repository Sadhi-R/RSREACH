# ADR 0008: Attachment and media storage (Phase 3 MVP)

## Status

Accepted

## Context

Inbox and WhatsApp/email need attachments. Object storage (S3/MinIO) is not selected in SOURCES. Skills forbid inventing a product object store without an ADR.

## Decision

- MVP stores **attachment metadata only** on `message_attachments`: mime type, size bytes, filename, `provider_media_id`, optional `storage_ref` (nullable).
- Binary content is fetched on demand by the **worker** from the provider using encrypted credentials; API does not hotlink Meta temporary URLs to browsers long-term.
- Durable customer-owned object storage (S3/MinIO/etc.) is **deferred** until a future ADR names the product and ops model.
- Do not commit large blobs into PostgreSQL for MVP.

## Consequences

- Templates and media features work with provider media ids.
- Operators must not expect RSReach-hosted CDN until object storage ADR.
- Migration path: later fill `storage_ref` when a store is chosen without rewriting message rows.
