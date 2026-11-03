# Phase 6 — Web application completion

**Status:** M1 implemented (webhook account binding). M2 and later are not started.
**Inspection date:** 2026-11-03. M1 code landed after that inspection.
**Evidence:** Static inspection of the SPA, controllers, domain services, Flyway V1–V32, CI, and [the 2026-11-03 audit](../reports/rsreach-complete-project-status-audit.md). `mvn verify` and `npm test` were **not** re-run for this plan.
**M1** (webhook account binding) is implemented. Do not start M2 from this file alone.

Android, iOS, Windows, and macOS clients are out of Phase 6. After the web gate in M10, a separate API and client-readiness note can decide the first native client. This roadmap does not write that note.

## How to read status

| Status | Meaning |
|---|---|
| PARTIAL | Some layer or workflow is missing. |
| MISSING | Not implemented. |
| BLOCKED | Needs a product decision, credential, or explicit approval. |
| NOT VERIFIED | The code exists. This inspection did not prove it still passes. |

Nothing in this document is marked COMPLETE. Phase 0–5 reports claim green automated gates. Those claims are **not re-run** here. A page or button is not treated as a finished workflow.

[docs/architecture/MODULE-MAP.md](../architecture/MODULE-MAP.md) describes `rsreach-api` as a WebSocket application. A search of Java, TypeScript, and YAML found no WebSocket type or endpoint. Realtime inbox is not part of the current design.

The audit’s CI note is stale. [.github/workflows/ci.yml](../../.github/workflows/ci.yml) already runs frontend `npm test` (typecheck, test, and build).

## 1. Current inventory

One web application: [frontend/apps/web](../../frontend/apps/web) (React 18, TypeScript, Vite). Customer workspace, organization administration, and Global Admin are route trees in that SPA (`/app/*` and `/platform/*`), not separate deployables.

Backend shape is unchanged: Java 17, Spring Boot 3.3.x, PostgreSQL, Flyway, modular monolith. Three processes share one database: API (8080), worker (8081), scheduler (8082). Nginx serves the SPA. No Kubernetes, Redis, or RabbitMQ.

### Applications

| Application | Location | Status |
|---|---|---|
| RSReach Web (customer + staff) | `frontend/apps/web` | PARTIAL |
| Android, iOS, Windows, macOS | — | Not started. Out of Phase 6. |

### Maven modules (19)

`rsreach-common`, `rsreach-persistence`, `rsreach-security`, `rsreach-audit`, `rsreach-identity-customer`, `rsreach-identity-staff`, `rsreach-tenancy`, `rsreach-contacts`, `rsreach-communications`, `rsreach-whatsapp`, `rsreach-email`, `rsreach-campaigns`, `rsreach-billing`, `rsreach-automation`, `rsreach-staff-access`, `rsreach-admin`, `rsreach-api`, `rsreach-worker`, `rsreach-scheduler`.

Parent POM: [backend/pom.xml](../../backend/pom.xml). Latest migration: `V32__phase5_free_plan_quota_adjustments.sql`. V1–V32 stay immutable. The next migration, only if a later milestone proves a new column or table is required, is **V33**.

### Customer routes that call `/api/v1`

Auth, password reset, invitation accept, dashboard tenant and workspace switch, contacts, import and export, groups, duplicates and merges, inbox, integrations, campaigns, billing, automations, notifications.

### Platform routes that call `/api/v1`

Staff login and MFA, dashboard, billing lookup, grants, audit.

### Routes that do not exist

Organization settings, member directory, invitation management, role management, departments, teams, customer-scoped audit. `/` is still the Phase 1 placeholder in [frontend/apps/web/src/pages.tsx](../../frontend/apps/web/src/pages.tsx). There is no shared platform shell.

### Frontend tests

Nine Vitest files, all with mocked HTTP. Coverage is contacts, duplicates and merges, contact share, contact import, and one inbox permission case. No tests for auth, billing, campaigns, automations, notifications, platform pages, or the shell.

## 2. Feature completion matrix

| Feature | Frontend | Backend/API | Database | Authorization | Tests | Status |
|---|---|---|---|---|---|---|
| Customer register and login | `pages.tsx` | `POST /api/v1/app/auth/register`, `/login` | V4 | Public, then customer JWT | Phase reports claimed; not re-run | NOT VERIFIED |
| Password reset | Routes exist. Login does not link to them | `POST .../password-reset/request`, `/confirm` | V4 | Public | Not re-run | PARTIAL |
| Staff login and MFA | `StaffLoginPage` | `POST /api/v1/platform/auth/login`, `/mfa/verify` | V5 | Staff realm | Not re-run | NOT VERIFIED |
| Workspace create and list | Dashboard only | `POST/GET /api/v1/app/workspaces` | V6 | `workspace.manage` on create | Not re-run | PARTIAL |
| Organization profile and settings | None | No update endpoint found | Tenant row in V6 | — | — | MISSING |
| User profile | Session and `GET /api/v1/app/me` | No profile update | V4 | Customer JWT | — | PARTIAL |
| Members | None | Role assign only: `POST /api/v1/app/workspaces/members/role` | V6 memberships | `workspace.members.manage` | Not re-run | PARTIAL |
| Invitations | Accept page only | `POST /api/v1/app/tenants/{id}/invitations`, `POST /api/v1/app/invitations/accept` | `tenant_invitations` | `tenant.members.manage` | Not re-run | PARTIAL |
| Roles | None | Assign an existing role code. No role editor | V3 | Member-manage permissions | — | PARTIAL |
| Departments and teams | None | `POST /api/v1/app/departments`, `POST /api/v1/app/teams` only | V7 | `workspace.org.manage` | — | PARTIAL |
| Dashboard and navigation | `AppShell` omits integrations, groups, duplicates, merges | `GET /api/v1/app/me`, tenants, workspaces | — | Customer JWT | — | PARTIAL |
| Search and filters | Contacts and groups | List query params on those resources | — | Resource read permissions | Contact tests exist; not re-run | PARTIAL |
| Responsive layout | One `min-width: 800px` breakpoint | — | — | — | Not checked on a device | PARTIAL |
| Org overview, usage, subscription | Dashboard plus billing pages | Billing read APIs | V25–V28 | `tenant.billing.read` | Not re-run | PARTIAL |
| Connected accounts | Create and list. No phone-number id, disconnect, sync, or templates | Also delete, sync, OAuth start/refresh, templates | V19 | `integrations.manage`, `templates.manage` | Not re-run | PARTIAL |
| Customer audit history | None | Staff `GET /api/v1/platform/audit-events` only | V8 | `platform.audit.read` | Not re-run | MISSING for org admins |
| Inbox list, thread, reply, notes | `InboxPages.tsx` | Conversations, messages, notes | V20–V21 | `inbox.read`, `inbox.reply` | One mocked permission test | PARTIAL |
| Inbox assignment | None | `POST/DELETE .../assignment` | V21 | `inbox.assign` | — | PARTIAL |
| Inbox search, forward, unread badge | None | No forward endpoint found | — | — | — | MISSING |
| Attachments and media | None in inbox | Metadata on `message_attachments`. MinIO default off | ADR 0008, ADR 0009 | Inbox permissions | SigV4 unit test exists; live MinIO not claimed | PARTIAL |
| Delivery and read status | Not shown as a workflow | Provider status fields exist in communications | V20 | Inbox read | Not re-run | NOT VERIFIED |
| Realtime inbox | None | No WebSocket implementation | — | — | — | MISSING. Not required unless the owner asks |
| WhatsApp connection and send | Token paste. `externalAccountRef` omitted | `CloudApiWhatsAppSender` when `RSREACH_WHATSAPP_LIVE_ENABLED=true`; otherwise mock | V19 secrets | `integrations.manage` | Mock path claimed; live not run | PARTIAL. Live BLOCKED |
| WhatsApp templates | None | `GET/POST /api/v1/app/templates` | V22 | `templates.manage` | Not re-run | PARTIAL |
| WhatsApp webhooks | — | GET verify is unchanged. POST resolves tenant and workspace from the phone-number id ([ADR 0012](../adr/0012-webhook-account-tenant-resolution.md)) | `provider_webhook_events` | App secret, or mock signature when allowed | `mvn -B verify` passed after M1 | PARTIAL. Live Meta send is still blocked |
| Embedded Signup | None | Deferred | — | — | — | BLOCKED |
| Gmail and Microsoft Graph sync | No OAuth UI | Live connectors list ids and store a placeholder body when `RSREACH_MAILBOX_LIVE_ENABLED=true` | V19 | `integrations.manage` | Not a live mailbox test | PARTIAL. Live BLOCKED |
| SendGrid delivery | No provider-specific UI | Flag `RSREACH_EMAIL_DELIVERY_LIVE_ENABLED` | — | Integrations | Not re-run | PARTIAL. Live BLOCKED |
| SES and Mailgun | None | Named in SOURCES. No adapter class | — | — | — | MISSING |
| Contacts CRUD, import, export, shares | Pages and nav for contacts | Contact and import controllers | V11, V16, V17 | `contacts.read`, `contacts.manage`, import, export | Some Vitest; not re-run | NOT VERIFIED |
| Groups | Pages exist, not in the shell | `ContactGroupController` | V12 | Contacts permissions | Not re-run | PARTIAL |
| Duplicates and merge | Pages exist, not in the shell | Duplicate and merge controllers | V15 | Contacts manage | Several Vitest files; not re-run | NOT VERIFIED |
| Consent and suppression | On contact detail only | Consent and suppression controllers | V13, V14 | `contacts.consent.manage` | Not re-run | PARTIAL |
| Communication groups (Q7) | — | Not decided | — | — | — | BLOCKED |
| Campaign create, start, stats | `CampaignsPage.tsx` | `GET/POST /campaigns`, `GET .../stats`, `POST .../start` | V23 | `campaigns.read`, `campaigns.manage`, `campaigns.send` | Not re-run | PARTIAL |
| Campaign pause and cancel | Buttons absent | `POST .../pause`, `POST .../cancel` | V23 | `campaigns.manage` | Not re-run | PARTIAL |
| Campaign resume | None | No resume method on `CampaignService` | V23 | — | — | MISSING |
| Campaign audience, template, schedule, charts | Name, channel, purpose, body only | Create accepts a thinner UI than the domain can store | V22, V23 | Campaign permissions | Not re-run | PARTIAL |
| Plan display and subscription | Billing pages | `GET /subscription`, `GET /plans`, `POST /activate-free`, `POST /trial`, `POST /change-plan`, `POST /cancel` | V25, V26 | Billing read/manage | Not re-run | PARTIAL |
| Checkout and Razorpay | Checkout action | `POST /checkout`. Null or zero `price_paise` returns 409 | V27 | `tenant.billing.manage` | Not re-run | BLOCKED on D2 |
| Invoices and payments | List and detail | `GET /invoices`, `GET /payments`. No PDF endpoint | V27, V30 | `billing.invoices.read` | Not re-run | PARTIAL |
| Usage and entitlements | Usage on overview | `GET /usage` is read-only | V28, V32 | `tenant.billing.read` | Not re-run | NOT VERIFIED |
| Platform billing admin | Page exists | Manual payment, overrides, refunds, credit notes | V30 | `platform.billing.manage` | Not re-run | PARTIAL |
| Automation list, edit, pause, runs | JSON textareas | Automation controller | V29 | `automations.read`, `automations.manage` | Phase 4/5 claimed; not re-run | PARTIAL |
| Visual workflow / DAG | None | Linear step list only | V29 | — | — | MISSING. Do not build in Phase 6 |
| In-app notifications | List, mark read, read-all. No badge | `NotificationController` | V31 | Customer JWT, tenant scoped | Not re-run | PARTIAL |
| Email, push, SMS, WhatsApp notifications | None | In-app rows only | V31 | — | — | MISSING |
| Notification preferences | None | None | — | — | — | MISSING |
| Platform tenant admin | Thin dashboard | `GET /platform/tenants`, status, secure view | V6, V8 | `platform.tenants.read`, `platform.tenants.manage` | Not re-run | PARTIAL |
| Staff grants and audit | Pages, no shell | Grants and `GET /audit-events` | V8 | `platform.grants.*`, `platform.audit.read` | Not re-run | PARTIAL |
| Analytics dashboards | None | No aggregation API. Campaign stats and usage meters only | Existing tables | Read permissions of each domain | — | MISSING |
| Public homepage | Phase 1 placeholder | None | — | Public | — | PARTIAL. Marketing site is not approved |
| Empty, loading, and error states | Uneven | `ApiError` envelope | — | — | — | PARTIAL |
| Accessibility | Not inspected on a device | — | — | — | — | NOT VERIFIED |

## 3. Missing and incomplete work

Reuse the APIs below. Do not add a Maven module, a second SPA, or a new tenant hierarchy.

| Gap | Existing code to extend | What is missing |
|---|---|---|
| Webhook tenant binding | `ProviderWebhookController`, `WhatsAppWebhookService`, delivery webhook ingest, `connected_accounts.external_account_ref` | Done in M1. See [ADR 0012](../adr/0012-webhook-account-tenant-resolution.md). |
| Org administration UI | `CustomerTenancyController`, `TenancyService`, V3–V7 | List and revoke members, invitations, departments, and teams. Pages under `/app`. |
| Invitation delivery | Outbox event `TENANT_INVITATION_CREATED` in `TenancyService.invite` | No worker consumer. Raw token is returned only when the dev exposure gate is on. |
| Inbox depth | `InboxController` assignment, notes, messages | Search, assignment UI, attachment metadata, delivery state, pagination. Forward is not in the API. |
| Connection form | `CreateConnectedAccountCommand.externalAccountRef`; delete, sync, OAuth, templates | `IntegrationsPage` posts provider, display name, access token, and `ownershipMode` only. |
| Campaign control | `CampaignService.pause` and `cancel` | UI calls start only. Resume does not exist. Audience and template pickers are absent. |
| Unpriced plans | `plan_version.price_paise` NULL for paid rows in V25 | `BillingController` maps null to `0`. Checkout already rejects null and zero. |
| Automation UX | `AutomationEngine` linear actions and triggers | Forms instead of raw JSON. Run failure detail. Not a DAG. |
| Notifications | `NotificationService` | Unread badge. Preferences. No email, push, SMS, or WhatsApp channel. |
| Global Admin shell | Platform pages and `PlatformAdminController` | Shared navigation and read-only troubleshooting. |
| Analytics | Message, campaign, contact, usage, and automation tables | Aggregation queries, then charts. |
| Homepage | `HomePage` | Short entry to login and register, unless a marketing site is approved. |

`USAGE_AGGREGATE` and `AUTOMATION_ACTION` are constants. Usage aggregation is not dispatched. Automation steps run inside `AutomationEngine`, not as a second outbox type. Do not describe that engine as a visual workflow product.

## 4. API dependency matrix

| UI workflow | API already present | API to add in Phase 6 | New table |
|---|---|---|---|
| Login, reset, invite accept | Yes | None for the happy path | No |
| Member directory and revoke | Role assign only | List members; revoke invitation; remove member if the V6 model already allows it | No, unless revoke needs a column the current invitation row lacks |
| Departments and teams | Create | List, and archive or rename only if V7 already has the columns | No by default |
| Inbox assignment and reply | Yes | Search only if list filters are absent | No |
| Inbox forward | No | Do not add until the owner confirms forward is in scope | — |
| Connect WhatsApp number | Create account supports `externalAccountRef` | None if the UI sends that field | No |
| Webhook ingest | POST requires tenant and workspace query params | Resolve ids from payload after signature check | No, if `external_account_ref` already stores the phone-number id |
| Templates | GET and POST | None for a list/create UI | No |
| Campaign pause and cancel | Yes | Resume only if the current status machine can leave `PAUSED` without a new model | No |
| Billing display | Yes | Represent null price as unset. Do not seed prices | No |
| In-app notifications | List and mark read | Unread count can be derived | No |
| Email notification channel | No | Out of M6 | — |
| Staff audit and grants | Yes | None for the shell | No |
| Analytics | Campaign stats and usage only | Read-only aggregates | No, unless a query cannot be served from current tables |
| Customer audit | Staff audit only | Org-admin read of that tenant’s audit, reusing `audit_events`, if M2 or M7 includes it | No |

Controllers live under `backend/rsreach-api/src/main/java/com/rsreach/api/web/`. Permission codes are `com.rsreach.common.Permissions`.

## 5. Security and authorization

Customer and staff JWTs stay separate. Tenant-owned queries keep `tenant_id` and, where the table is workspace-owned, `workspace_id`. Phase 6 does not merge the realms.

| Item | Evidence | Phase 6 action |
|---|---|---|
| Webhook tenant comes from the connected account | After a valid signature, the phone-number id or delivery account id is matched to `connected_accounts.external_account_ref`. Query-string tenant ids are ignored. Zero or multiple active matches are rejected and not stored. | Implemented in M1. |
| Cross-tenant ingest | The signature is the app secret, then the body is stored on the query tenant. A valid signature plus known UUIDs can target the wrong tenant. `accountRef` defaults to `default` when omitted. | Same mapping. Duplicate `(provider, connected_account_ref, provider_event_id)` stays idempotent. |
| Verify handshake | `GET /api/v1/webhooks/whatsapp` checks `hub.mode`, `hub.verify_token`, and `hub.challenge` against `RSREACH_WHATSAPP_VERIFY_TOKEN`. | Keep one app verify token. No tenant id on GET. |
| Mock signatures | `rsreach.webhooks.allow-mock-signatures` defaults to true in `backend/rsreach-api/src/main/resources/application.yml`. | Production and Docker stay false. Tests must show a forged signature is 401 and that mock signatures fail when the flag is false. |
| Mock sends | WhatsApp, mailbox, email delivery, and Razorpay orders are flag-off by default. | Do not call those paths live integrations. |
| Invitation token | `TenancyService.invite` stores a hash and emits `TENANT_INVITATION_CREATED`. Nothing consumes that event. | M2 sends mail through the existing delivery port and labels mock delivery as mock. |
| Null price shown as zero | `BillingController` plan payload uses `0` when `pricePaise` is null. `PaymentService.checkout` already returns 409 for null or zero. | M5 shows “price not set” and keeps checkout rejected. |
| Staff break-glass | Grants API and platform page | M7 reuses it. No new staff power. |
| Customer versus staff | Platform controllers are staff-only | M7 test: a customer token cannot call `/api/v1/platform/*`. |

M1 changed the provider webhook contract in [ADR 0012](../adr/0012-webhook-account-tenant-resolution.md). ADR 0001 stays in force. This roadmap does not supersede it.

## 6. Provider blockers

| Provider | Code today | Blocker |
|---|---|---|
| Meta WhatsApp Cloud API | Mock sender default. Live sender behind `RSREACH_WHATSAPP_LIVE_ENABLED` | Operator secrets, a registered phone-number id, and an approved test recipient. No live send in M1. |
| Meta Embedded Signup | Not built | Tech Provider / Solution Partner confirmation in [SOURCES.md](../../.cursor/skills/SOURCES.md). Commercial stage. |
| Gmail and Microsoft Graph | Flag-gated list/ack scaffolding | OAuth credentials and a real sync design. The Gmail connector does not fetch message bodies. |
| SendGrid | Flag-gated delivery connector | API key and a sandbox send. |
| Amazon SES and Mailgun | No adapter | Do not add either unless the owner selects it. SOURCES names them; it does not require both in Phase 6. |
| Razorpay | HMAC webhook. Live Orders behind `RSREACH_RAZORPAY_LIVE_ORDERS` (falls back to `RSREACH_RAZORPAY_LIVE_ENABLED`, default false) | Test-mode keys and a webhook URL. No live charges, refunds, or settlement changes. |
| MinIO | SigV4 helper. Object store default off | Optional media-profile smoke. Inbox can show metadata without a live bucket. |

The WhatsApp number used for the owner’s business is an operator choice. This roadmap does not change Meta apps, phone numbers, or Razorpay mode.

## 7. Product-owner decisions

These stay open. Phase 6 must not invent answers.

| ID | Decision | Effect |
|---|---|---|
| D2 | Paid prices, quotas, trial length, GST | Checkout stays rejected for null prices. No plan seed. |
| Q7 | Whether one communication group is shared across channels | Contact groups stay as they are. No new group model. |
| Marketing site | Whether `/` becomes a full public marketing site | Default for M9 is a short entry to login and register. |
| Realtime | Whether inbox needs WebSocket | Default is manual refresh. No Redis. |
| Forward | Whether inbox forward is in the web scope | Not in the API today. Leave it out until confirmed. |
| Embedded Signup | Tech Provider program | Remains deferred. Manual connected-account token remains the implemented path. |
| SES / Mailgun | Which delivery provider follows SendGrid | Do not build both. |
| Native clients | Which client is first after the web gate | Separate note after M10. |

## 8. Milestones

Sequence: M1 through M10. Do not start a milestone until the owner approves it. Reuse current modules. No new Maven module. V1–V32 stay immutable.

### M1 — Webhook account binding

Implemented. See [ADR 0012](../adr/0012-webhook-account-tenant-resolution.md). `mvn -B verify` passed with the new isolation tests.

- **Objective:** A provider callback identifies the tenant and workspace from the signed payload and the connected account.
- **Features:** WhatsApp ingest and the delivery-provider ingest that shares `ProviderWebhookController`.
- **Reuse:** `connected_accounts.external_account_ref`, `WhatsAppWebhookService`, delivery webhook service, webhook idempotency.
- **Backend:** ADR 0012 first. After signature verification, resolve one account. Ignore query-string tenant ids for that provider path. Keep GET verify as it is.
- **Frontend:** None.
- **Database:** None expected.
- **API:** `POST /api/v1/webhooks/{provider}` succeeds without `tenantId` and `workspaceId` when the payload maps to one account. Unknown or ambiguous ids are rejected. Forged signatures stay 401.
- **Authorization:** No customer JWT on the webhook. Isolation is the account row’s tenant and workspace.
- **Tests:** Unknown phone-number id rejected. Two tenants with different phone-number ids do not receive each other’s events. Duplicate provider event id is idempotent. Forged signature is 401. Mock signatures fail when the flag is false.
- **Acceptance:** A Meta-shaped URL with no tenant query parameters is stored on the owning workspace only.
- **Out:** Live sends, Meta dashboard edits, Embedded Signup.

### M2 — Organization administration

- **Objective:** An org admin can manage members, invitations, departments, and teams with the current model.
- **Reuse:** `TenancyService`, V3–V7, permissions `tenant.members.manage`, `workspace.members.manage`, `workspace.org.manage`.
- **Backend:** List and revoke invitations. List members. List departments and teams. Remove or update only where the current tables already support it. Consume `TENANT_INVITATION_CREATED` through the existing email delivery port.
- **Frontend:** `/app` pages and shell links for members, invitations, departments, teams, and workspaces. Mock email is labeled mock.
- **Database:** None unless revoke cannot be represented on `tenant_invitations`.
- **API:** New list and revoke routes under `/api/v1/app`. Same error envelope.
- **Tests:** A caller without the permission receives 403. Tenant A cannot list tenant B’s members, invitations, departments, or teams.
- **Acceptance:** Invite, list, and revoke work without reading a dev token from the API response in the production configuration.
- **Out:** Custom role builder, a new hierarchy, Q7 groups.

### M3 — Inbox and connection UX

- **Objective:** The inbox and integrations screens use the APIs that already exist.
- **Reuse:** Inbox assignment, notes, messages; integration delete, sync, templates; `externalAccountRef`.
- **Backend:** Add a conversation search filter only if the list endpoint cannot already filter.
- **Frontend:** Assignment, delivery state, attachment metadata, pagination or explicit refresh, phone-number id on connect, disconnect, template list. Shell links for integrations.
- **Database:** None expected.
- **Tests:** `inbox.assign` required to assign. Workspace A cannot read workspace B’s conversation. A connected account stores the phone-number id.
- **Acceptance:** A user with reply permission can reply; a user without assign permission cannot assign. Refresh shows new rows without a WebSocket.
- **Out:** Forward, live Meta send, WebSocket, MinIO as a required dependency.

### M4 — Campaign operations

- **Objective:** Operators can run the campaign controls the API already has, with consent and suppression still enforced.
- **Reuse:** `CampaignService` start, pause, cancel, stats; contact groups; templates.
- **Backend:** Resume only if `PAUSED` can return to a sendable status without a new campaign model. Otherwise document resume as missing.
- **Frontend:** Audience preview, template selection, pause, cancel, stats. Shell already links campaigns.
- **Database:** None expected.
- **Tests:** Suppressed recipients and recipients without consent are excluded. Pause does not enqueue further recipients.
- **Acceptance:** The detail page can pause and cancel a campaign the API already controls.
- **Out:** New chart library as a substitute for the stats API. Invented audiences.

### M5 — Billing presentation

- **Objective:** Unpriced plans are visible as unpriced. Checkout stays closed until D2.
- **Reuse:** `BillingController`, `PaymentService`, plan catalog.
- **Backend and frontend:** Null `price_paise` is not serialized or rendered as zero. Copy says the price is not set. Checkout remains 409 for null and zero.
- **Database:** Do not update V25. Do not insert prices.
- **Tests:** A null price does not checkout and is not displayed as zero. Free plan activation still works.
- **Acceptance:** A paid plan row with NULL price cannot be mistaken for a free plan.
- **Out:** Razorpay live mode, GST rules, invoice PDF unless the owner asks and no price is invented. Test-mode Razorpay is an operator exercise with the owner’s test keys.

### M6 — Linear automation and in-app notifications

- **Objective:** The existing linear engine and in-app notifications are usable without a new product.
- **Reuse:** Triggers already mapped in `AutomationDomainEventListener` (contact created, contact updated, group added, consent changed, inbound message, campaign completed, schedule). Actions already in `AutomationEngine` (WhatsApp send, email send, add to group, remove from group, update contact field, create notification, enqueue workflow).
- **Frontend:** Forms for those triggers and actions. Run status and failure text. Unread count on the shell.
- **Database:** None expected. Unread count can use `notifications.read_at`.
- **Tests:** An unknown action is skipped. A consent failure skips the send. A notification for tenant A is invisible to tenant B.
- **Acceptance:** A workflow can be activated and paused, and a failed step shows a status, without pasting raw JSON.
- **Out:** DAG, AI steps, push, SMS, and WhatsApp as notification channels.

### M7 — Global Admin shell

- **Objective:** Staff move among the platform pages that already exist, without new privileges.
- **Reuse:** Tenant list and status, billing lookup, grants, audit.
- **Frontend:** One staff shell. Read-only links for troubleshooting data the APIs already return.
- **Database:** None.
- **Tests:** Staff lacking `platform.audit.read` cannot list audit events. A customer token cannot call `/api/v1/platform/*`.
- **Acceptance:** Grants, audit, and billing remain the same operations, reached from one navigation.
- **Out:** A support inbox, impersonation beyond the existing grant, cross-tenant message browsing.

### M8 — Analytics

- **Objective:** Dashboards read persisted rows. Charts follow the queries.
- **Reuse:** Messages, campaigns, contacts, usage meters, automation runs.
- **Backend:** Tenant-scoped read APIs for volume, delivery and failure counts, campaign outcomes, contact growth, usage, and automation run counts.
- **Frontend:** Charts only after those responses exist.
- **Database:** No summary table unless a query cannot meet a normal page load on current indexes. If a table is required, it is V33 and justified in the milestone.
- **Tests:** Fixture rows match the aggregate. Tenant B’s rows are absent from tenant A’s response.
- **Acceptance:** Every chart number can be traced to a table queried in the test.
- **Out:** A warehouse, third-party analytics SaaS, invented KPIs.

### M9 — Entry, empty states, and layout

- **Objective:** The public entry and the pages from M2–M8 have loading, empty, and error states, and remain usable on a narrow viewport.
- **Reuse:** Existing design tokens and `AppShell`.
- **Frontend:** Replace the Phase 1 homepage with a short entry to login and register, unless the owner has approved a marketing site. Link password reset from login. Add the missing shell links (integrations, groups, duplicates, merges).
- **Database:** None.
- **Tests:** Vitest for the new pages. A browser pass on desktop and a narrow width for the pages touched. Do not claim that pass until it is run.
- **Acceptance:** `/` no longer says it is a Phase 1 portal. A failed API call shows `ApiError` text. An empty list tells the user what to do next.
- **Out:** A multi-page marketing site, a new brand guide, native apps.

### M10 — Web completion gate

- **Objective:** Record what the web app does, and what is still mock or blocked.
- **Checks:** `mvn -B verify`, frontend typecheck, `npm test`, frontend build. Isolation tests from M1–M8 are part of verify.
- **Report:** A new file under `docs/reports/` that lists D2, Q7, Embedded Signup, live WhatsApp, live mailbox, live Razorpay, SES/Mailgun, push/SMS, and native clients as **not done**.
- **Acceptance:** The report does not call a mock provider live, and does not call the web app commercially complete while D2 and live providers are open.
- **Out:** Starting Android, iOS, Windows, or macOS.

## 9. Files and modules likely to change

Only when that milestone is approved.

| Milestone | Likely files |
|---|---|
| M1 | `docs/adr/0012-webhook-account-tenant-resolution.md`, `ProviderWebhookController.java`, `WhatsAppWebhookService.java`, delivery webhook ingest, connected-account lookup, API tests |
| M2 | `TenancyService.java`, `CustomerTenancyController.java`, worker handler for `TENANT_INVITATION_CREATED`, `frontend/apps/web/src/App.tsx`, `AppShell.tsx`, new org pages |
| M3 | `InboxPages.tsx`, `IntegrationsPage.tsx`, `InboxController` only if search is missing |
| M4 | `CampaignsPage.tsx`, `CampaignService.java` only if resume fits the current statuses |
| M5 | `BillingController.java`, `BillingPages.tsx` |
| M6 | `AutomationsPages.tsx`, `NotificationsPage.tsx`, `AppShell.tsx` |
| M7 | `PlatformAdminPages.tsx`, `pages.tsx`, a staff shell component |
| M8 | New read methods in existing domain modules, new controller methods, chart components |
| M9 | `pages.tsx`, `styles.css`, shells |
| M10 | `docs/reports/` gate report only |

Do not edit `V1__baseline_platform.sql` through `V32__phase5_free_plan_quota_adjustments.sql`. Do not edit this roadmap’s source plan file under `.cursor/plans/`.

## 10. Definition of web complete

The web application is complete for this roadmap when all of the following are true:

- M2–M9 workflows call real `/api/v1` APIs, persist in PostgreSQL, and enforce customer and staff permissions.
- Tenant and workspace isolation tests cover every new tenant-owned read.
- Loading, empty, and error states exist on the pages those milestones touch.
- A desktop and a narrow viewport have been checked for those pages.
- `mvn -B verify` and the frontend typecheck, test, and build succeed.
- CI continues to run `npm test`.
- M10 names every remaining mock and every open decision.

The following are outside that declaration until their own approvals: live Meta send and receive, Embedded Signup, live Gmail or Graph sync, live SendGrid, live Razorpay charges, D2 prices, Q7, a marketing site, a DAG builder, push/SMS/WhatsApp notification channels, SES/Mailgun, and native clients.

A mock provider is not a live integration.

## 11. Stop line

M1 is done. Implementation of a later milestone starts only when the owner approves it. The next milestone is **M2**.
