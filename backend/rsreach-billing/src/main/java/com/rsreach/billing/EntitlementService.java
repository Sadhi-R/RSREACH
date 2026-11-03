package com.rsreach.billing;

import com.rsreach.common.error.ApiException;
import com.rsreach.common.error.ErrorCode;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Entitlement enforcement and usage metering.
 *
 * <h3>Fail-closed policy</h3>
 * <ul>
 *   <li>EXPIRED, CANCELLED, SUSPENDED: always denied.
 *   <li>TRIALING, ACTIVE, PAST_DUE (within grace): allowed subject to limit.
 *   <li>No subscription: denied.
 * </ul>
 *
 * <h3>Limit resolution</h3>
 * <ol>
 *   <li>Active (not revoked, not expired) {@code entitlement_override} for the tenant wins.
 *   <li>Otherwise {@code plan_entitlement} for the current plan_version.
 *   <li>If no entitlement row exists the limit is treated as unlimited (null).
 * </ol>
 *
 * <h3>Usage accounting</h3>
 * {@link #consume} inserts a {@code usage_event} row with ON CONFLICT DO NOTHING for idempotency,
 * then upserts the {@code usage_period_aggregate}. The limit check is performed before the insert;
 * this is a best-effort guard acceptable for MVP throughput.
 */
@Service
public class EntitlementService {

    private static final Logger log = LoggerFactory.getLogger(EntitlementService.class);

    private final JdbcTemplate jdbcTemplate;
    private final SubscriptionService subscriptionService;
    private final SubscriptionBootstrapService subscriptionBootstrapService;

    public EntitlementService(
            JdbcTemplate jdbcTemplate,
            SubscriptionService subscriptionService,
            SubscriptionBootstrapService subscriptionBootstrapService
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.subscriptionService = subscriptionService;
        this.subscriptionBootstrapService = subscriptionBootstrapService;
    }

    // -----------------------------------------------------------------------
    // Check (read-only)
    // -----------------------------------------------------------------------

    /**
     * Returns whether the tenant is allowed to consume {@code delta} units of {@code meterCode}.
     * Does NOT record any usage; use {@link #consume} to actually record and enforce.
     *
     * <p>If the tenant has no subscription, a FREE plan subscription is auto-activated
     * (lazy bootstrap) before the check proceeds.
     */
    @Transactional
    public BillingModels.CheckResult check(UUID tenantId, String meterCode, long delta) {
        subscriptionBootstrapService.ensureFreeIfMissing(tenantId);
        BillingModels.SubscriptionView sub = subscriptionService.getSubscription(tenantId)
                .orElse(null);
        if (sub == null) {
            return new BillingModels.CheckResult(false, "NO_SUBSCRIPTION");
        }
        String status = sub.status();
        if (isDeniedStatus(status)) {
            return new BillingModels.CheckResult(false, "SUBSCRIPTION_" + status);
        }

        Long limit = resolveLimit(tenantId, sub.planVersionId(), meterCode);
        if (limit == null) {
            return new BillingModels.CheckResult(true, null); // unlimited
        }
        long current = currentAggregateUsed(tenantId, meterCode, sub.currentPeriodStart());
        if (current + delta > limit) {
            return new BillingModels.CheckResult(false, "LIMIT_EXCEEDED");
        }
        return new BillingModels.CheckResult(true, null);
    }

    // -----------------------------------------------------------------------
    // Consume (write, idempotent on idempotencyKey)
    // -----------------------------------------------------------------------

    /**
     * Records {@code delta} usage units and atomically increments the period aggregate.
     *
     * <p>Idempotent: if {@code idempotencyKey} was already recorded for this tenant, the call
     * returns without double-counting.
     *
     * @param idempotencyKey caller-supplied unique key; must be globally unique per event
     * @throws ApiException {@link ErrorCode#CONFLICT} when the limit would be exceeded or the
     *                      subscription is in a denied state
     */
    @Transactional
    public void consume(
            UUID tenantId,
            UUID workspaceId,
            String meterCode,
            long delta,
            String idempotencyKey,
            String resourceType,
            String resourceId) {

        subscriptionBootstrapService.ensureFreeIfMissing(tenantId);
        BillingModels.SubscriptionView sub = subscriptionService.getSubscription(tenantId)
                .orElseThrow(() -> new ApiException(ErrorCode.CONFLICT,
                        "No subscription; usage denied"));
        String status = sub.status();
        if (isDeniedStatus(status)) {
            throw new ApiException(ErrorCode.CONFLICT,
                    "Subscription is " + status + "; usage denied for meter " + meterCode);
        }

        Long limit = resolveLimit(tenantId, sub.planVersionId(), meterCode);
        long current = currentAggregateUsed(tenantId, meterCode, sub.currentPeriodStart());
        if (limit != null && current + delta > limit) {
            throw new ApiException(ErrorCode.CONFLICT,
                    "Entitlement limit exceeded for " + meterCode
                            + ": used=" + current + " delta=" + delta + " limit=" + limit);
        }

        // Idempotent insert
        int inserted = jdbcTemplate.update(
                """
                INSERT INTO usage_event (
                    id, tenant_id, workspace_id, meter_code, quantity,
                    idempotency_key, resource_type, resource_id, occurred_at, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, NOW(), NOW())
                ON CONFLICT (tenant_id, idempotency_key) DO NOTHING
                """,
                UUID.randomUUID(), tenantId, workspaceId, meterCode, delta,
                idempotencyKey, resourceType, resourceId
        );

        if (inserted == 0) {
            log.debug("consume: idempotent skip tenant={} key={}", tenantId, idempotencyKey);
            return;
        }

        // Upsert aggregate
        jdbcTemplate.update(
                """
                INSERT INTO usage_period_aggregate (
                    id, tenant_id, meter_code, period_start, period_end,
                    used_quantity, limit_value, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, NOW())
                ON CONFLICT (tenant_id, meter_code, period_start) DO UPDATE
                SET used_quantity = usage_period_aggregate.used_quantity + EXCLUDED.used_quantity,
                    limit_value   = COALESCE(usage_period_aggregate.limit_value, EXCLUDED.limit_value),
                    updated_at    = NOW()
                """,
                UUID.randomUUID(), tenantId, meterCode,
                Timestamp.from(sub.currentPeriodStart()),
                Timestamp.from(sub.currentPeriodEnd()),
                delta, limit
        );
    }

    // -----------------------------------------------------------------------
    // Current usage view
    // -----------------------------------------------------------------------

    /**
     * Returns the usage meter views for the tenant's current billing period.
     * Only meters that have a {@code usage_period_aggregate} row are returned.
     */
    @Transactional(readOnly = true)
    public List<BillingModels.UsageMeterView> currentUsage(UUID tenantId) {
        BillingModels.SubscriptionView sub = subscriptionService.getSubscription(tenantId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND,
                        "No subscription found for tenant: " + tenantId));

        return jdbcTemplate.query(
                """
                SELECT upa.meter_code,
                       upa.used_quantity,
                       upa.limit_value,
                       upa.period_start,
                       upa.period_end,
                       COALESCE(pe.soft_limit_pct, 80) AS soft_limit_pct
                FROM usage_period_aggregate upa
                LEFT JOIN plan_entitlement pe
                       ON pe.plan_version_id = ? AND pe.meter_code = upa.meter_code
                WHERE upa.tenant_id = ?
                  AND upa.period_start = ?
                ORDER BY upa.meter_code
                """,
                (rs, i) -> {
                    long usedQty = rs.getLong("used_quantity");
                    long rawLimit = rs.getLong("limit_value");
                    Long limitValue = rs.wasNull() ? null : rawLimit;
                    int softPct = rs.getInt("soft_limit_pct");
                    boolean warning = limitValue != null
                            && usedQty >= (limitValue * softPct / 100L);
                    Instant pStart = rs.getTimestamp("period_start").toInstant();
                    Instant pEnd = rs.getTimestamp("period_end").toInstant();
                    return new BillingModels.UsageMeterView(
                            rs.getString("meter_code"), usedQty, limitValue,
                            softPct, pStart, pEnd, warning);
                },
                sub.planVersionId(), tenantId,
                Timestamp.from(sub.currentPeriodStart())
        );
    }

    // -----------------------------------------------------------------------
    // Override management (staff / platform admin)
    // -----------------------------------------------------------------------

    /**
     * Creates a tenant-level entitlement override that supersedes the plan limit for {@code meterCode}.
     * Staff-only operation; validated by the caller before invoking.
     *
     * @param command override details; {@code limitValue} null means unlimited
     */
    @Transactional
    public void createOverride(BillingModels.OverrideCommand command) {
        if (command.tenantId() == null) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "tenantId is required");
        }
        if (command.meterCode() == null || command.meterCode().isBlank()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "meterCode is required");
        }
        UUID overrideId = UUID.randomUUID();
        Timestamp expiresAt = command.expiresAt() == null ? null : Timestamp.from(command.expiresAt());
        jdbcTemplate.update(
                """
                INSERT INTO entitlement_override (
                    id, tenant_id, meter_code, limit_value, reason, expires_at, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, NOW())
                """,
                overrideId, command.tenantId(), command.meterCode().trim(),
                command.limitValue(), command.reason(), expiresAt
        );
        log.info("createOverride tenant={} meter={} limit={} id={}",
                command.tenantId(), command.meterCode(), command.limitValue(), overrideId);
    }

    // -----------------------------------------------------------------------
    // Private helpers
    // -----------------------------------------------------------------------

    /**
     * Activates the FREE plan for the tenant if they have no existing subscription.
     * Runs in a REQUIRES_NEW transaction so it can write even when called from a readOnly context.
     */
    /** Delegates to {@link SubscriptionBootstrapService} (proxy-safe REQUIRES_NEW). */
    public void lazyBootstrapIfMissing(UUID tenantId) {
        subscriptionBootstrapService.ensureFreeIfMissing(tenantId);
    }

    private static boolean isDeniedStatus(String status) {
        return BillingModels.STATUS_EXPIRED.equals(status)
                || BillingModels.STATUS_CANCELLED.equals(status)
                || BillingModels.STATUS_SUSPENDED.equals(status);
    }

    /**
     * Resolves the effective limit for a meter code:
     * override (not revoked, not expired) takes precedence over plan entitlement.
     * Returns {@code null} when unlimited.
     */
    public Long resolveLimit(UUID tenantId, UUID planVersionId, String meterCode) {
        // 1. Active override
        List<Long> overrideRows = jdbcTemplate.query(
                """
                SELECT limit_value FROM entitlement_override
                WHERE tenant_id = ?
                  AND meter_code = ?
                  AND revoked_at IS NULL
                  AND (expires_at IS NULL OR expires_at > NOW())
                ORDER BY created_at DESC
                LIMIT 1
                """,
                (rs, i) -> {
                    long v = rs.getLong("limit_value");
                    return rs.wasNull() ? null : v;
                },
                tenantId, meterCode
        );
        if (!overrideRows.isEmpty()) {
            return overrideRows.get(0); // may be null meaning unlimited via override
        }

        // 2. Plan entitlement
        List<Long> planRows = jdbcTemplate.query(
                """
                SELECT limit_value FROM plan_entitlement
                WHERE plan_version_id = ? AND meter_code = ?
                """,
                (rs, i) -> {
                    long v = rs.getLong("limit_value");
                    return rs.wasNull() ? null : v;
                },
                planVersionId, meterCode
        );
        if (!planRows.isEmpty()) {
            return planRows.get(0); // may be null meaning unlimited
        }

        return null; // no entitlement defined → unlimited
    }

    private long currentAggregateUsed(UUID tenantId, String meterCode, Instant periodStart) {
        List<Long> rows = jdbcTemplate.query(
                """
                SELECT used_quantity FROM usage_period_aggregate
                WHERE tenant_id = ? AND meter_code = ? AND period_start = ?
                """,
                (rs, i) -> rs.getLong("used_quantity"),
                tenantId, meterCode, Timestamp.from(periodStart)
        );
        return rows.isEmpty() ? 0L : rows.get(0);
    }
}
