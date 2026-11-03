package com.rsreach.billing;

import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Periodic subscription lifecycle transitions driven by the scheduler.
 *
 * <p>{@link #processRenewalTick()} is designed to be called on a regular schedule (e.g. every
 * hour) by the scheduler process. It is idempotent: running it twice for the same batch of
 * subscriptions has no extra effect because each transition method guards on the current status.
 *
 * <h3>Transitions handled</h3>
 * <ul>
 *   <li>TRIALING + trial_ends_at past + price = 0 → ACTIVE (free plan auto-activation)
 *   <li>TRIALING + trial_ends_at past + price > 0 → PAST_DUE (grace period starts)
 *   <li>ACTIVE  + current_period_end past + price > 0 → PAST_DUE (missed renewal)
 *   <li>PAST_DUE + grace_ends_at past → SUSPENDED
 * </ul>
 *
 * <p>CANCELLED and EXPIRED are terminal; the scheduler does not touch them.
 */
@Service
public class BillingRenewalService {

    private static final Logger log = LoggerFactory.getLogger(BillingRenewalService.class);

    private final JdbcTemplate jdbcTemplate;
    private final SubscriptionService subscriptionService;

    public BillingRenewalService(
            JdbcTemplate jdbcTemplate,
            SubscriptionService subscriptionService) {
        this.jdbcTemplate = jdbcTemplate;
        this.subscriptionService = subscriptionService;
    }

    /**
     * Runs all renewal lifecycle transitions and returns the total number of subscriptions
     * that were transitioned.
     */
    @Transactional
    public int processRenewalTick() {
        int count = 0;
        count += suspendPastDueAfterGrace();
        count += handleTrialExpiry();
        count += markActiveOverduePastDue();
        log.info("BillingRenewalTick: {} subscription(s) transitioned", count);
        return count;
    }

    // -----------------------------------------------------------------------
    // Private transition methods
    // -----------------------------------------------------------------------

    /**
     * Suspends PAST_DUE subscriptions whose grace period has elapsed.
     */
    private int suspendPastDueAfterGrace() {
        List<UUID> tenantIds = jdbcTemplate.query(
                """
                SELECT tenant_id
                FROM tenant_subscription
                WHERE status = 'PAST_DUE'
                  AND grace_ends_at IS NOT NULL
                  AND grace_ends_at < NOW()
                """,
                (rs, i) -> (UUID) rs.getObject("tenant_id")
        );
        for (UUID tenantId : tenantIds) {
            try {
                subscriptionService.suspendAfterGrace(tenantId);
            } catch (Exception ex) {
                log.error("suspendAfterGrace failed tenant={}: {}", tenantId, ex.getMessage());
            }
        }
        return tenantIds.size();
    }

    /**
     * Handles TRIALING subscriptions whose trial period has ended:
     * <ul>
     *   <li>Free (price_paise = 0 or null): auto-activates via
     *       {@link SubscriptionService#applyPaidActivation} (already TRIALING; "paid" just means
     *       no payment required for free plans).
     *   <li>Paid: transitions to PAST_DUE giving the grace period to pay.
     * </ul>
     */
    private int handleTrialExpiry() {
        List<TrialExpiredRow> candidates = jdbcTemplate.query(
                """
                SELECT ts.tenant_id,
                       ts.id             AS subscription_id,
                       pv.price_paise
                FROM tenant_subscription ts
                JOIN plan_version pv ON pv.id = ts.plan_version_id
                WHERE ts.status = 'TRIALING'
                  AND ts.trial_ends_at IS NOT NULL
                  AND ts.trial_ends_at < NOW()
                """,
                (rs, i) -> {
                    long rawPrice = rs.getLong("price_paise");
                    Long price = rs.wasNull() ? null : rawPrice;
                    return new TrialExpiredRow(
                            (UUID) rs.getObject("tenant_id"),
                            (UUID) rs.getObject("subscription_id"),
                            price
                    );
                }
        );

        for (TrialExpiredRow row : candidates) {
            try {
                boolean isFree = row.pricePaise() == null || row.pricePaise() == 0L;
                if (isFree) {
                    subscriptionService.applyPaidActivation(row.tenantId(), row.subscriptionId());
                    log.info("Trial ended; free plan auto-activated tenant={}", row.tenantId());
                } else {
                    subscriptionService.markPastDue(row.tenantId());
                    log.info("Trial ended; moved to PAST_DUE tenant={}", row.tenantId());
                }
            } catch (Exception ex) {
                log.error("handleTrialExpiry failed tenant={}: {}", row.tenantId(), ex.getMessage());
            }
        }
        return candidates.size();
    }

    /**
     * Marks ACTIVE subscriptions as PAST_DUE when their billing period has ended and the plan
     * is not free. This handles missed renewals for subscribers whose payment was not received.
     */
    private int markActiveOverduePastDue() {
        List<UUID> tenantIds = jdbcTemplate.query(
                """
                SELECT ts.tenant_id
                FROM tenant_subscription ts
                JOIN plan_version pv ON pv.id = ts.plan_version_id
                WHERE ts.status = 'ACTIVE'
                  AND ts.current_period_end < NOW()
                  AND (pv.price_paise IS NULL OR pv.price_paise > 0)
                """,
                (rs, i) -> (UUID) rs.getObject("tenant_id")
        );
        for (UUID tenantId : tenantIds) {
            try {
                subscriptionService.markPastDue(tenantId);
            } catch (Exception ex) {
                log.error("markPastDue failed tenant={}: {}", tenantId, ex.getMessage());
            }
        }
        return tenantIds.size();
    }

    // -----------------------------------------------------------------------
    // Internal record
    // -----------------------------------------------------------------------

    private record TrialExpiredRow(UUID tenantId, UUID subscriptionId, Long pricePaise) {
    }
}
