# RSReach Module Map

> Authoritative source for which Maven module owns which package and what each depends on.
> Updated after Phase 4 wiring (billing and automation controllers, schedulers, event listeners).

## Backend Maven modules

| Module | Package | Role | Key dependencies |
|---|---|---|---|
| `rsreach-common` | `com.rsreach.common` | Shared constants, error types, permission codes, `WorkspaceDomainEvent` | none |
| `rsreach-persistence` | `com.rsreach.persistence` | Outbox, webhook, JDBC helpers | common |
| `rsreach-security` | `com.rsreach.security` | JWT, `RsReachPrincipal`, filters, `TenantContext` | common, persistence |
| `rsreach-audit` | `com.rsreach.audit` | `AuditService`, `audit_events` table | common, persistence |
| `rsreach-tenancy` | `com.rsreach.tenancy` | `TenancyService`, `CustomerAuthorizationService`, workspace membership | common, persistence, security, audit |
| `rsreach-identity-customer` | `com.rsreach.identity.customer` | Customer auth, register, login, refresh | common, persistence, security, audit, tenancy |
| `rsreach-identity-staff` | `com.rsreach.identity.staff` | Staff auth, MFA | common, persistence, security, audit |
| `rsreach-admin` | `com.rsreach.admin` | Platform tenant admin | common, persistence, tenancy |
| `rsreach-staff-access` | `com.rsreach.staffaccess` | Staff break-glass grants | common, persistence, audit |
| `rsreach-billing` | `com.rsreach.billing` | Subscriptions, Razorpay, invoices, entitlements, overrides | common, persistence, security, audit, tenancy |
| `rsreach-contacts` | `com.rsreach.contacts` | Contacts, groups, consent, import/export | common, persistence, security, audit, tenancy, **billing** |
| `rsreach-communications` | `com.rsreach.communications` | Conversations, messages, templates, connected accounts | common, persistence, security, audit, tenancy, contacts, **billing** |
| `rsreach-whatsapp` | `com.rsreach.whatsapp` | WhatsApp Cloud API adapter | common, persistence, security, audit, tenancy, contacts, communications |
| `rsreach-email` | `com.rsreach.email` | Gmail/Graph mailbox sync, delivery | common, persistence, security, audit, tenancy, contacts, communications |
| `rsreach-campaigns` | `com.rsreach.campaigns` | Bulk messaging campaigns | common, persistence, contacts, communications, **billing** |
| `rsreach-automation` | `com.rsreach.automation` | Trigger-based workflow automation | common, persistence, contacts, communications, **billing** |
| `rsreach-api` | `com.rsreach.api` | Spring Boot REST + WebSocket application | all domain libraries |
| `rsreach-worker` | `com.rsreach.worker` | Background job worker | common, persistence, contacts, communications, whatsapp, email, campaigns, **billing**, automation |
| `rsreach-scheduler` | `com.rsreach.scheduler` | Periodic job promotion and scheduling | common, persistence, contacts, communications, campaigns, **billing** |

## Controller map (rsreach-api)

| Controller | Path prefix | Permission gate |
|---|---|---|
| `CustomerAuthController` | `/api/v1/app/auth` | Public |
| `StaffAuthController` | `/api/v1/platform/auth` | Public |
| `CustomerTenancyController` | `/api/v1/app/tenants`, `/api/v1/app/workspaces` | Customer JWT |
| `ContactController` | `/api/v1/app/contacts` | `CONTACTS_READ` / `CONTACTS_MANAGE` |
| `InboxController` | `/api/v1/app/inbox`, `/api/v1/app/conversations` | `INBOX_READ` / `INBOX_REPLY` |
| `CampaignController` | `/api/v1/app/campaigns` | `CAMPAIGNS_READ` / `CAMPAIGNS_MANAGE` |
| `IntegrationController` | `/api/v1/app/integrations` | `INTEGRATIONS_MANAGE` |
| `ProviderWebhookController` | `/api/v1/webhooks/whatsapp` | Signature verified |
| **`BillingController`** | `/api/v1/app/billing` | `TENANT_BILLING_READ` / `TENANT_BILLING_MANAGE` |
| **`RazorpayWebhookController`** | `/api/v1/webhooks/razorpay` | Razorpay HMAC signature |
| **`PlatformBillingController`** | `/api/v1/platform/billing` | `PLATFORM_BILLING_MANAGE` + MFA |
| **`AutomationController`** | `/api/v1/app/automations` | `AUTOMATIONS_READ` / `AUTOMATIONS_MANAGE` |
| `PlatformAdminController` | `/api/v1/platform` | Staff JWT + MFA |
| `SystemController` | `/api/v1/system/health` | Public |

## Outbox event types

| Event type | Handler | Notes |
|---|---|---|
| `CONTACT_GROUP_REFRESH` | `ContactGroupMaterializer` | Workspace-scoped |
| `CONTACT_DUPLICATE_SCAN` | `DuplicateCandidateService` | Workspace-scoped |
| `CONTACT_IMPORT_PROCESS` | `ContactImportService` | Workspace-scoped |
| `CONTACT_EXPORT_PROCESS` | `ContactExportService` | Workspace-scoped |
| `MESSAGE_SEND` | `MessageSendWorker` | Workspace-scoped |
| `CAMPAIGN_ENQUEUE` | `CampaignService.processEnqueue` | Workspace-scoped |
| `MAILBOX_SYNC` | `MailboxSyncService` | Workspace-scoped |
| `SCHEDULED_MESSAGE_FIRE` | `ScheduledMessageService` | Workspace-scoped |
| `WEBHOOK_APPLY` (WhatsApp) | `WhatsAppWebhookService` | Workspace-scoped |
| **`BILLING_WEBHOOK_APPLY`** | **`BillingWebhookService.applyEvent`** | **Tenant-level; handled BEFORE null workspace check** |
| **`SUBSCRIPTION_RENEWAL_TICK`** | **`BillingRenewalService.processRenewalTick`** | **Tenant-level; handled BEFORE null workspace check** |
| `AUTOMATION_RUN` | `AutomationEngine.processRun` | Workspace-scoped |

## Domain event bridge (Spring ApplicationEvents)

| Publisher | Event type | Listener | Automation trigger |
|---|---|---|---|
| `ContactService.create` | `WorkspaceDomainEvent("CONTACT_CREATED")` | `AutomationDomainEventListener` | `CONTACT_CREATED` |
| `ContactService.update` | `WorkspaceDomainEvent("CONTACT_UPDATED")` | `AutomationDomainEventListener` | `CONTACT_UPDATED` |
| `CampaignService.processEnqueue` | `WorkspaceDomainEvent("CAMPAIGN_COMPLETED")` | `AutomationDomainEventListener` | `CAMPAIGN_COMPLETED` |

## Scheduler components

| Scheduler | Trigger | Action |
|---|---|---|
| `ContactGroupRefreshScheduler` | Fixed delay | Promotes stale group refresh jobs |
| `ScheduledMessageScheduler` | Fixed delay | Fires due scheduled messages |
| **`BillingRenewalScheduler`** | **Every 5 min (configurable)** | **Calls `BillingRenewalService.processRenewalTick()`** |
