package com.rsreach.billing;

import com.rsreach.audit.AuditService;
import com.rsreach.common.RsReachConstants;
import com.rsreach.common.error.ApiException;
import com.rsreach.common.error.ErrorCode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Subscription lifecycle: create, trial, free activation, plan changes, status transitions,
 * and paid activation. Every status change is recorded in subscription_event.
 *
 * <p>The unique constraint on tenant_subscription (tenant_id) means each tenant has at most one
 * subscription row. Trial start requires no prior row. Re-activation of EXPIRED/CANCELLED via
 * {@link #activateFree} updates the existing row.
 */
@Service
public class SubscriptionService {

    private static final Logger log = LoggerFactory.getLogger(SubscriptionService.class);

    private final JdbcTemplate jdbcTemplate;
    private final PlanCatalogService planCatalogService;
    private final AuditService auditService;

    public SubscriptionService(
            JdbcTemplate jdbcTemplate,
            PlanCatalogService planCatalogService,
            AuditService auditService) {
        this.jdbcTemplate = jdbcTemplate;
        this.planCatalogService = planCatalogService;
        this.auditService = auditService;
    }

    // -----------------------------------------------------------------------
    // Queries
    // -----------------------------------------------------------------------

    @Transactional(readOnly = true)
    public Optional<BillingModels.SubscriptionView> getSubscription(UUID tenantId) {
        List<BillingModels.SubscriptionView> rows = jdbcTemplate.query(
                """
                SELECT id, tenant_id, plan_version_id, status, billing_period, trial_ends_at,
                       current_period_start, current_period_end, grace_ends_at, cancel_at_period_end,
                       cancelled_at, suspended_at, created_at
                FROM tenant_subscription
                WHERE tenant_id = ?
                """,
                (rs, i) -> mapSubscription(rs),
                tenantId
        );
        return rows.stream().findFirst();
    }

    // -----------------------------------------------------------------------
    // Mutations
    // -----------------------------------------------------------------------

    /**
     * Starts a TRIALING subscription for a tenant that has no existing subscription.
     * Throws {@link ErrorCode#CONFLICT} if a subscription already exists in any state.
     */
    @Transactional
    public BillingModels.SubscriptionView startTrial(UUID tenantId, UUID planVersionId, UUID actorId) {
        if (getSubscription(tenantId).isPresent()) {
            throw new ApiException(ErrorCode.CONFLICT,
                    "Tenant already has a subscription; cannot start trial");
        }
        BillingModels.PlanVersionView pv = planCatalogService.getPlanVersion(planVersionId);
        Instant now = Instant.now();
        Instant trialEndsAt = pv.trialDays() > 0
                ? now.plus(pv.trialDays(), ChronoUnit.DAYS) : null;
        Instant periodEnd = periodEndFrom(now, pv.billingPeriod());

        UUID subscriptionId = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO tenant_subscription (
                    id, tenant_id, plan_version_id, status, billing_period,
                    trial_ends_at, current_period_start, current_period_end,
                    grace_ends_at, cancel_at_period_end, created_at, updated_at
                ) VALUES (?, ?, ?, 'TRIALING', ?, ?, NOW(), ?, NULL, FALSE, NOW(), NOW())
                """,
                subscriptionId, tenantId, planVersionId, pv.billingPeriod(),
                trialEndsAt == null ? null : Timestamp.from(trialEndsAt),
                Timestamp.from(periodEnd)
        );

        writeSubscriptionEvent(subscriptionId, tenantId, null, BillingModels.STATUS_TRIALING,
                "trial_started", actorId, RsReachConstants.REALM_CUSTOMER, "{}");
        auditService.record(RsReachConstants.REALM_CUSTOMER, actorId, tenantId, null,
                "subscription.trial_started", "subscription", subscriptionId.toString(),
                "{\"planVersionId\":\"" + planVersionId + "\"}");

        log.info("Trial started tenant={} planVersion={} sub={}", tenantId, planVersionId, subscriptionId);
        return getSubscription(tenantId).orElseThrow();
    }

    /**
     * Subscribes a tenant to the FREE plan in ACTIVE state. Idempotent when already on FREE/ACTIVE.
     * Allowed from any state when no subscription exists, or from CANCELLED/EXPIRED.
     */
    @Transactional
    public BillingModels.SubscriptionView activateFree(UUID tenantId, UUID actorId) {
        UUID freePlanVersionId = jdbcTemplate.query(
                """
                SELECT pv.id FROM plan_version pv
                JOIN plan_catalog pc ON pc.id = pv.plan_id
                WHERE pc.code = 'FREE' AND pc.active = TRUE
                ORDER BY pv.version_number DESC
                LIMIT 1
                """,
                (rs, i) -> (UUID) rs.getObject("id")
        ).stream().findFirst().orElseThrow(() ->
                new ApiException(ErrorCode.INTERNAL_ERROR, "FREE plan version not found in catalog"));

        Optional<BillingModels.SubscriptionView> existing = getSubscription(tenantId);
        Instant now = Instant.now();
        Instant periodEnd = now.atZone(ZoneOffset.UTC).plusMonths(1).toInstant();

        if (existing.isPresent()) {
            BillingModels.SubscriptionView sub = existing.get();
            String status = sub.status();

            if (BillingModels.STATUS_ACTIVE.equals(status)
                    && freePlanVersionId.equals(sub.planVersionId())) {
                return sub; // already on free plan and active — idempotent
            }
            if (!BillingModels.STATUS_CANCELLED.equals(status)
                    && !BillingModels.STATUS_EXPIRED.equals(status)) {
                throw new ApiException(ErrorCode.CONFLICT,
                        "Cannot activate free plan; subscription is " + status);
            }

            jdbcTemplate.update(
                    """
                    UPDATE tenant_subscription
                    SET plan_version_id = ?, status = 'ACTIVE', billing_period = 'MONTHLY',
                        trial_ends_at = NULL, current_period_start = NOW(), current_period_end = ?,
                        grace_ends_at = NULL, cancel_at_period_end = FALSE,
                        cancelled_at = NULL, suspended_at = NULL, updated_at = NOW()
                    WHERE tenant_id = ?
                    """,
                    freePlanVersionId, Timestamp.from(periodEnd), tenantId
            );
            writeSubscriptionEvent(sub.id(), tenantId, status, BillingModels.STATUS_ACTIVE,
                    "free_plan_activated", actorId, RsReachConstants.REALM_CUSTOMER, "{}");
        } else {
            UUID subscriptionId = UUID.randomUUID();
            jdbcTemplate.update(
                    """
                    INSERT INTO tenant_subscription (
                        id, tenant_id, plan_version_id, status, billing_period,
                        trial_ends_at, current_period_start, current_period_end,
                        grace_ends_at, cancel_at_period_end, created_at, updated_at
                    ) VALUES (?, ?, ?, 'ACTIVE', 'MONTHLY', NULL, NOW(), ?, NULL, FALSE, NOW(), NOW())
                    """,
                    subscriptionId, tenantId, freePlanVersionId, Timestamp.from(periodEnd)
            );
            writeSubscriptionEvent(subscriptionId, tenantId, null, BillingModels.STATUS_ACTIVE,
                    "free_plan_activated", actorId, RsReachConstants.REALM_CUSTOMER, "{}");
        }

        auditService.record(RsReachConstants.REALM_CUSTOMER, actorId, tenantId, null,
                "subscription.free_activated", "subscription", tenantId.toString(), "{}");
        log.info("Free plan activated tenant={}", tenantId);
        return getSubscription(tenantId).orElseThrow();
    }

    /**
     * Cancels the subscription immediately or at period end.
     */
    @Transactional
    public BillingModels.SubscriptionView cancel(UUID tenantId, UUID actorId, boolean atPeriodEnd) {
        BillingModels.SubscriptionView sub = requireSubscription(tenantId);
        String status = sub.status();

        if (BillingModels.STATUS_CANCELLED.equals(status)
                || BillingModels.STATUS_EXPIRED.equals(status)) {
            throw new ApiException(ErrorCode.CONFLICT,
                    "Subscription already " + status + "; cannot cancel");
        }

        if (atPeriodEnd) {
            jdbcTemplate.update(
                    "UPDATE tenant_subscription SET cancel_at_period_end = TRUE, updated_at = NOW()" +
                    " WHERE tenant_id = ?",
                    tenantId
            );
            writeSubscriptionEvent(sub.id(), tenantId, status, status,
                    "cancel_scheduled", actorId, RsReachConstants.REALM_CUSTOMER,
                    "{\"atPeriodEnd\":true}");
        } else {
            jdbcTemplate.update(
                    """
                    UPDATE tenant_subscription
                    SET status = 'CANCELLED', cancel_at_period_end = FALSE,
                        cancelled_at = NOW(), updated_at = NOW()
                    WHERE tenant_id = ?
                    """,
                    tenantId
            );
            writeSubscriptionEvent(sub.id(), tenantId, status, BillingModels.STATUS_CANCELLED,
                    "cancelled", actorId, RsReachConstants.REALM_CUSTOMER, "{}");
            auditService.record(RsReachConstants.REALM_CUSTOMER, actorId, tenantId, null,
                    "subscription.cancelled", "subscription", sub.id().toString(), "{}");
        }

        return getSubscription(tenantId).orElseThrow();
    }

    /**
     * Changes the plan version on an ACTIVE or TRIALING subscription.
     * Writes a subscription_event with the plan change details.
     */
    @Transactional
    public BillingModels.SubscriptionView changePlan(UUID tenantId, UUID newPlanVersionId, UUID actorId) {
        BillingModels.SubscriptionView sub = requireSubscription(tenantId);
        String status = sub.status();

        if (!BillingModels.STATUS_ACTIVE.equals(status)
                && !BillingModels.STATUS_TRIALING.equals(status)) {
            throw new ApiException(ErrorCode.CONFLICT,
                    "Plan change only allowed in ACTIVE or TRIALING; current status: " + status);
        }

        // Validate the new plan version exists (throws NOT_FOUND if not)
        planCatalogService.getPlanVersion(newPlanVersionId);

        UUID oldPlanVersionId = sub.planVersionId();
        jdbcTemplate.update(
                "UPDATE tenant_subscription SET plan_version_id = ?, updated_at = NOW() WHERE tenant_id = ?",
                newPlanVersionId, tenantId
        );
        writeSubscriptionEvent(sub.id(), tenantId, status, status, "plan_changed",
                actorId, RsReachConstants.REALM_CUSTOMER,
                "{\"from\":\"" + oldPlanVersionId + "\",\"to\":\"" + newPlanVersionId + "\"}");
        auditService.record(RsReachConstants.REALM_CUSTOMER, actorId, tenantId, null,
                "subscription.plan_changed", "subscription", sub.id().toString(),
                "{\"newPlanVersionId\":\"" + newPlanVersionId + "\"}");

        return getSubscription(tenantId).orElseThrow();
    }

    /**
     * Transitions an ACTIVE or TRIALING subscription to PAST_DUE, setting the grace period.
     * Called by the renewal scheduler when payment is not received by period end.
     */
    @Transactional
    public void markPastDue(UUID tenantId) {
        BillingModels.SubscriptionView sub = requireSubscription(tenantId);
        String status = sub.status();
        if (!BillingModels.STATUS_ACTIVE.equals(status)
                && !BillingModels.STATUS_TRIALING.equals(status)) {
            log.debug("markPastDue: tenant={} already in {}; skipping", tenantId, status);
            return;
        }

        BillingModels.PlanVersionView pv = planCatalogService.getPlanVersion(sub.planVersionId());
        Instant graceEndsAt = Instant.now().plus(pv.graceDays(), ChronoUnit.DAYS);

        jdbcTemplate.update(
                """
                UPDATE tenant_subscription SET status = 'PAST_DUE', grace_ends_at = ?,
                    updated_at = NOW()
                WHERE tenant_id = ?
                """,
                Timestamp.from(graceEndsAt), tenantId
        );
        writeSubscriptionEvent(sub.id(), tenantId, status, BillingModels.STATUS_PAST_DUE,
                "past_due", null, RsReachConstants.REALM_STAFF, "{}");
        log.info("Subscription marked PAST_DUE tenant={} graceEndsAt={}", tenantId, graceEndsAt);
    }

    /**
     * Transitions a PAST_DUE subscription to SUSPENDED when the grace period has elapsed.
     * Called by the renewal scheduler.
     */
    @Transactional
    public void suspendAfterGrace(UUID tenantId) {
        BillingModels.SubscriptionView sub = requireSubscription(tenantId);
        if (!BillingModels.STATUS_PAST_DUE.equals(sub.status())) {
            log.debug("suspendAfterGrace: tenant={} not PAST_DUE; skipping", tenantId);
            return;
        }
        jdbcTemplate.update(
                """
                UPDATE tenant_subscription SET status = 'SUSPENDED', suspended_at = NOW(),
                    updated_at = NOW()
                WHERE tenant_id = ?
                """,
                tenantId
        );
        writeSubscriptionEvent(sub.id(), tenantId, BillingModels.STATUS_PAST_DUE,
                BillingModels.STATUS_SUSPENDED, "suspended", null, RsReachConstants.REALM_STAFF, "{}");
        log.info("Subscription SUSPENDED tenant={}", tenantId);
    }

    /**
     * Expires a subscription from any terminal-pending state. Called by the renewal scheduler.
     */
    @Transactional
    public void expire(UUID tenantId) {
        BillingModels.SubscriptionView sub = requireSubscription(tenantId);
        String from = sub.status();
        jdbcTemplate.update(
                "UPDATE tenant_subscription SET status = 'EXPIRED', updated_at = NOW() WHERE tenant_id = ?",
                tenantId
        );
        writeSubscriptionEvent(sub.id(), tenantId, from, BillingModels.STATUS_EXPIRED,
                "expired", null, RsReachConstants.REALM_STAFF, "{}");
        log.info("Subscription EXPIRED tenant={}", tenantId);
    }

    /**
     * Sets subscription status to ACTIVE and resets the billing period after a successful payment.
     * Called from {@link PaymentService} on verified Razorpay webhook or manual payment.
     */
    @Transactional
    public void applyPaidActivation(UUID tenantId, UUID subscriptionId) {
        BillingModels.SubscriptionView sub = requireSubscription(tenantId);
        if (!sub.id().equals(subscriptionId)) {
            throw new ApiException(ErrorCode.CONFLICT,
                    "Subscription ID mismatch for tenant " + tenantId);
        }

        String fromStatus = sub.status();
        BillingModels.PlanVersionView pv = planCatalogService.getPlanVersion(sub.planVersionId());
        Instant periodEnd = periodEndFrom(Instant.now(), pv.billingPeriod());

        jdbcTemplate.update(
                """
                UPDATE tenant_subscription
                SET status = 'ACTIVE', grace_ends_at = NULL,
                    current_period_start = NOW(), current_period_end = ?,
                    updated_at = NOW()
                WHERE tenant_id = ?
                """,
                Timestamp.from(periodEnd), tenantId
        );
        writeSubscriptionEvent(sub.id(), tenantId, fromStatus, BillingModels.STATUS_ACTIVE,
                "payment_activated", null, RsReachConstants.REALM_STAFF, "{}");
        log.info("Subscription activated via payment tenant={} from={}", tenantId, fromStatus);
    }

    // -----------------------------------------------------------------------
    // State machine helper (package-private for tests)
    // -----------------------------------------------------------------------

    /**
     * Returns {@code true} when the transition from {@code from} (null for new) to {@code to}
     * is a valid subscription state change. Used internally and in unit tests.
     */
    static boolean isValidTransition(String from, String to) {
        if (to == null) {
            return false;
        }
        if (from == null) {
            return BillingModels.STATUS_TRIALING.equals(to)
                    || BillingModels.STATUS_ACTIVE.equals(to);
        }
        return switch (from) {
            case BillingModels.STATUS_TRIALING -> List.of(
                    BillingModels.STATUS_ACTIVE,
                    BillingModels.STATUS_PAST_DUE,
                    BillingModels.STATUS_CANCELLED,
                    BillingModels.STATUS_EXPIRED
            ).contains(to);
            case BillingModels.STATUS_ACTIVE -> List.of(
                    BillingModels.STATUS_PAST_DUE,
                    BillingModels.STATUS_CANCELLED
            ).contains(to);
            case BillingModels.STATUS_PAST_DUE -> List.of(
                    BillingModels.STATUS_ACTIVE,
                    BillingModels.STATUS_SUSPENDED,
                    BillingModels.STATUS_CANCELLED
            ).contains(to);
            case BillingModels.STATUS_SUSPENDED -> List.of(
                    BillingModels.STATUS_ACTIVE,
                    BillingModels.STATUS_EXPIRED,
                    BillingModels.STATUS_CANCELLED
            ).contains(to);
            case BillingModels.STATUS_CANCELLED, BillingModels.STATUS_EXPIRED ->
                    BillingModels.STATUS_ACTIVE.equals(to); // re-activation via free plan only
            default -> false;
        };
    }

    // -----------------------------------------------------------------------
    // Private helpers
    // -----------------------------------------------------------------------

    private BillingModels.SubscriptionView requireSubscription(UUID tenantId) {
        return getSubscription(tenantId).orElseThrow(() ->
                new ApiException(ErrorCode.NOT_FOUND, "No subscription found for tenant: " + tenantId));
    }

    private void writeSubscriptionEvent(
            UUID subscriptionId, UUID tenantId,
            String fromStatus, String toStatus,
            String eventType, UUID actorId, String actorRealm, String detailsJson) {
        jdbcTemplate.update(
                """
                INSERT INTO subscription_event (
                    id, tenant_id, subscription_id, event_type,
                    from_status, to_status, details_json, actor_realm, actor_id, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, CAST(? AS jsonb), ?, ?, NOW())
                """,
                UUID.randomUUID(), tenantId, subscriptionId, eventType,
                fromStatus, toStatus,
                detailsJson == null ? "{}" : detailsJson,
                actorRealm, actorId
        );
    }

    private static Instant periodEndFrom(Instant from, String billingPeriod) {
        java.time.ZonedDateTime zdt = from.atZone(ZoneOffset.UTC);
        return "ANNUAL".equals(billingPeriod)
                ? zdt.plusYears(1).toInstant()
                : zdt.plusMonths(1).toInstant();
    }

    private static BillingModels.SubscriptionView mapSubscription(ResultSet rs) throws SQLException {
        Timestamp trialEndsAt = rs.getTimestamp("trial_ends_at");
        Timestamp graceEndsAt = rs.getTimestamp("grace_ends_at");
        Timestamp cancelledAt = rs.getTimestamp("cancelled_at");
        Timestamp suspendedAt = rs.getTimestamp("suspended_at");
        return new BillingModels.SubscriptionView(
                (UUID) rs.getObject("id"),
                (UUID) rs.getObject("tenant_id"),
                (UUID) rs.getObject("plan_version_id"),
                rs.getString("status"),
                rs.getString("billing_period"),
                trialEndsAt == null ? null : trialEndsAt.toInstant(),
                rs.getTimestamp("current_period_start").toInstant(),
                rs.getTimestamp("current_period_end").toInstant(),
                graceEndsAt == null ? null : graceEndsAt.toInstant(),
                rs.getBoolean("cancel_at_period_end"),
                cancelledAt == null ? null : cancelledAt.toInstant(),
                suspendedAt == null ? null : suspendedAt.toInstant(),
                rs.getTimestamp("created_at").toInstant()
        );
    }
}
