# ADR 0004: Consent and suppression scope

## Status

Accepted

## Context

M3 introduced `contact_consents` with columns `tenant_id` and `workspace_id`, but uniqueness was `(tenant_id, contact_id, channel_type, purpose)` (workspace omitted). Contacts are home-workspace owned (`home_workspace_id`). Resource shares exist for future cross-workspace visibility. The verification gate requires an explicit scope decision so sharing cannot silently inherit or mutate consent.

## Decision

1. **Consent is home-workspace scoped.** A consent row’s `workspace_id` must equal the contact’s `home_workspace_id`. FK already enforces this via `(tenant_id, workspace_id, contact_id) → contacts(tenant_id, home_workspace_id, id)`.
2. **Uniqueness is** `(tenant_id, workspace_id, contact_id, channel_type, purpose)` so the database matches the logical key.
3. **Consent management is authorized only in the contact’s home workspace** with `contacts.consent.manage`. An active `resource_shares` row does **not** grant consent read/write unless a later milestone explicitly implements share-aware consent APIs. Until then, non-home workspaces receive `404` for the contact (same as M1 isolation).
4. **Suppressions are workspace-scoped** by normalized address: `(tenant_id, workspace_id, channel_type, value_normalized)` among active rows. They do not travel with shared contacts into another workspace.
5. **Dynamic group consent filters** evaluate consent only for `cc.workspace_id = c.home_workspace_id` (home scope).
6. **Eligibility for sending** requires an **explicit normalized address** that belongs to the contact; primary-address fallback is not used for eligibility decisions.

## Consequences

- Flyway V14 corrects uniqueness and adds append-only enforcement for consent events.
- Future M6 share APIs may expose read-only consent to target workspaces with `READ`, or allow home-only writes; they must not copy consent rows into the target workspace.
- Campaign/send modules must call `ConsentEvaluationService.evaluate(..., address)` with the exact recipient address.
