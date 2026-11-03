package com.rsreach.billing;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Shared constants and immutable view/command records for the billing domain.
 * All amounts are integer paise (BIGINT). Never use double or float for money.
 */
public final class BillingModels {

    private BillingModels() {
    }

    // -----------------------------------------------------------------------
    // Outbox event types
    // -----------------------------------------------------------------------

    /** Outbox event dispatched after a verified Razorpay webhook is ingested. */
    public static final String EVENT_BILLING_WEBHOOK_APPLY = "BILLING_WEBHOOK_APPLY";

    /** Outbox event for async usage aggregation reconciliation. */
    public static final String EVENT_USAGE_AGGREGATE = "USAGE_AGGREGATE";

    /** Outbox event emitted by the scheduler to trigger renewal checks. */
    public static final String EVENT_SUBSCRIPTION_RENEWAL_TICK = "SUBSCRIPTION_RENEWAL_TICK";

    // -----------------------------------------------------------------------
    // Meter codes – must match plan_entitlement.meter_code values in the DB
    // -----------------------------------------------------------------------

    public static final String METER_MEMBERS = "MEMBERS";
    public static final String METER_CONTACTS = "CONTACTS";
    public static final String METER_WHATSAPP_MESSAGES = "WHATSAPP_MESSAGES";
    public static final String METER_EMAIL_MESSAGES = "EMAIL_MESSAGES";
    public static final String METER_CAMPAIGNS = "CAMPAIGNS";
    public static final String METER_AUTOMATIONS = "AUTOMATIONS";
    public static final String METER_AUTOMATION_RUNS = "AUTOMATION_RUNS";
    public static final String METER_CONNECTED_ACCOUNTS = "CONNECTED_ACCOUNTS";
    public static final String METER_MEDIA_BYTES = "MEDIA_BYTES";

    // -----------------------------------------------------------------------
    // Subscription statuses – must match tenant_subscription.status CHECK
    // -----------------------------------------------------------------------

    public static final String STATUS_TRIALING = "TRIALING";
    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_PAST_DUE = "PAST_DUE";
    public static final String STATUS_SUSPENDED = "SUSPENDED";
    public static final String STATUS_CANCELLED = "CANCELLED";
    public static final String STATUS_EXPIRED = "EXPIRED";

    // -----------------------------------------------------------------------
    // View records (read models)
    // -----------------------------------------------------------------------

    public record PlanView(
            UUID id,
            String code,
            String name,
            String description,
            int sortOrder,
            boolean active,
            Instant createdAt
    ) {
    }

    public record PlanVersionView(
            UUID id,
            UUID planId,
            int versionNumber,
            String billingPeriod,
            Long pricePaise,
            String currency,
            int trialDays,
            int graceDays,
            Instant effectiveFrom
    ) {
    }

    public record EntitlementView(
            UUID id,
            UUID planVersionId,
            String meterCode,
            Long limitValue,
            int softLimitPct
    ) {
    }

    public record SubscriptionView(
            UUID id,
            UUID tenantId,
            UUID planVersionId,
            String status,
            String billingPeriod,
            Instant trialEndsAt,
            Instant currentPeriodStart,
            Instant currentPeriodEnd,
            Instant graceEndsAt,
            boolean cancelAtPeriodEnd,
            Instant cancelledAt,
            Instant suspendedAt,
            Instant createdAt
    ) {
    }

    public record UsageMeterView(
            String meterCode,
            long usedQuantity,
            Long limitValue,
            int softLimitPct,
            Instant periodStart,
            Instant periodEnd,
            boolean warningFlag
    ) {
    }

    public record InvoiceView(
            UUID id,
            UUID tenantId,
            UUID subscriptionId,
            String invoiceNumber,
            String status,
            String currency,
            long subtotalPaise,
            long taxPaise,
            long totalPaise,
            Instant periodStart,
            Instant periodEnd,
            Instant issuedAt,
            Instant dueAt,
            Instant createdAt
    ) {
    }

    public record PaymentView(
            UUID id,
            UUID tenantId,
            UUID invoiceId,
            UUID subscriptionId,
            String method,
            String status,
            long amountPaise,
            String currency,
            String provider,
            String providerOrderId,
            String providerPaymentId,
            String externalReference,
            Instant paidAt,
            Instant createdAt
    ) {
    }

    // -----------------------------------------------------------------------
    // Command / result records
    // -----------------------------------------------------------------------

    public record CheckoutResult(
            UUID subscriptionId,
            UUID invoiceId,
            UUID paymentId,
            String razorpayOrderId,
            String keyId,
            long amountPaise,
            String currency
    ) {
    }

    public record ManualPaymentCommand(
            long amountPaise,
            String currency,
            String method,
            String externalReference,
            String notes
    ) {
    }

    public record OverrideCommand(
            UUID tenantId,
            String meterCode,
            Long limitValue,
            String reason,
            Instant expiresAt
    ) {
    }

    /** Result of an entitlement check. {@code reason} is null when {@code allowed} is true. */
    public record CheckResult(boolean allowed, String reason) {
    }

    public record RefundView(
            UUID id,
            UUID tenantId,
            UUID paymentId,
            long amountPaise,
            String currency,
            String reason,
            String providerRefundId,
            String status,
            Instant createdAt
    ) {
    }

    public record CreditNoteView(
            UUID id,
            UUID tenantId,
            UUID invoiceId,
            String creditNoteNumber,
            long amountPaise,
            String currency,
            String reason,
            Instant createdAt
    ) {
    }

    public record RefundCommand(
            UUID paymentId,
            long amountPaise,
            String reason
    ) {
    }

    public record CreditNoteCommand(
            UUID invoiceId,
            long amountPaise,
            String reason
    ) {
    }

    // -----------------------------------------------------------------------
    // Internal list wrapper
    // -----------------------------------------------------------------------

    public record PlanPage(List<PlanView> items) {
    }
}
