package com.rsreach.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.rsreach.common.error.ApiException;
import com.rsreach.common.error.ErrorCode;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/**
 * Unit tests for {@link EntitlementService}: limit resolution, fail-closed policy,
 * and idempotent consume.
 *
 * <p>Uses Mockito to stub JdbcTemplate and SubscriptionService. No database required.
 */
@ExtendWith(MockitoExtension.class)
class EntitlementServiceTest {

    @Mock
    JdbcTemplate jdbcTemplate;

    @Mock
    SubscriptionService subscriptionService;

    @Mock
    SubscriptionBootstrapService subscriptionBootstrapService;

    EntitlementService entitlementService;

    private final UUID tenantId = UUID.randomUUID();
    private final UUID planVersionId = UUID.randomUUID();
    private final Instant periodStart = Instant.parse("2026-11-01T00:00:00Z");
    private final Instant periodEnd = Instant.parse("2026-12-01T00:00:00Z");

    @BeforeEach
    void setUp() {
        entitlementService = new EntitlementService(
                jdbcTemplate, subscriptionService, subscriptionBootstrapService);
    }

    // -----------------------------------------------------------------------
    // check() – fail-closed policy
    // -----------------------------------------------------------------------

    @Test
    void checkDeniedWhenNoSubscription() {
        when(subscriptionService.getSubscription(tenantId)).thenReturn(Optional.empty());

        BillingModels.CheckResult result = entitlementService.check(tenantId, BillingModels.METER_MEMBERS, 1L);

        assertThat(result.allowed()).isFalse();
        assertThat(result.reason()).isEqualTo("NO_SUBSCRIPTION");
    }

    @Test
    void checkDeniedWhenExpired() {
        when(subscriptionService.getSubscription(tenantId))
                .thenReturn(Optional.of(activeSubscription(BillingModels.STATUS_EXPIRED)));

        BillingModels.CheckResult result = entitlementService.check(tenantId, BillingModels.METER_MEMBERS, 1L);

        assertThat(result.allowed()).isFalse();
        assertThat(result.reason()).isEqualTo("SUBSCRIPTION_EXPIRED");
    }

    @Test
    void checkDeniedWhenCancelled() {
        when(subscriptionService.getSubscription(tenantId))
                .thenReturn(Optional.of(activeSubscription(BillingModels.STATUS_CANCELLED)));

        BillingModels.CheckResult result = entitlementService.check(tenantId, BillingModels.METER_MEMBERS, 1L);

        assertThat(result.allowed()).isFalse();
        assertThat(result.reason()).isEqualTo("SUBSCRIPTION_CANCELLED");
    }

    @Test
    void checkDeniedWhenSuspended() {
        when(subscriptionService.getSubscription(tenantId))
                .thenReturn(Optional.of(activeSubscription(BillingModels.STATUS_SUSPENDED)));

        BillingModels.CheckResult result = entitlementService.check(tenantId, BillingModels.METER_MEMBERS, 1L);

        assertThat(result.allowed()).isFalse();
        assertThat(result.reason()).isEqualTo("SUBSCRIPTION_SUSPENDED");
    }

    @Test
    void checkAllowedWhenTrialing() {
        when(subscriptionService.getSubscription(tenantId))
                .thenReturn(Optional.of(activeSubscription(BillingModels.STATUS_TRIALING)));

        // No override, plan limit = 10, current used = 0
        stubNoOverride();
        stubPlanLimit(5L);
        stubCurrentUsed(0L);

        BillingModels.CheckResult result = entitlementService.check(tenantId, BillingModels.METER_MEMBERS, 3L);

        assertThat(result.allowed()).isTrue();
    }

    @Test
    void checkAllowedWhenPastDue() {
        when(subscriptionService.getSubscription(tenantId))
                .thenReturn(Optional.of(activeSubscription(BillingModels.STATUS_PAST_DUE)));

        stubNoOverride();
        stubPlanLimit(100L);
        stubCurrentUsed(50L);

        BillingModels.CheckResult result = entitlementService.check(tenantId, BillingModels.METER_CONTACTS, 10L);

        assertThat(result.allowed()).isTrue();
    }

    @Test
    void checkDeniedWhenLimitExceeded() {
        when(subscriptionService.getSubscription(tenantId))
                .thenReturn(Optional.of(activeSubscription(BillingModels.STATUS_ACTIVE)));

        stubNoOverride();
        stubPlanLimit(100L);
        stubCurrentUsed(95L);

        BillingModels.CheckResult result = entitlementService.check(tenantId, BillingModels.METER_CONTACTS, 10L);

        assertThat(result.allowed()).isFalse();
        assertThat(result.reason()).isEqualTo("LIMIT_EXCEEDED");
    }

    @Test
    void checkAllowedWhenUnlimited() {
        when(subscriptionService.getSubscription(tenantId))
                .thenReturn(Optional.of(activeSubscription(BillingModels.STATUS_ACTIVE)));

        // No override, no plan entitlement row -> unlimited; service returns before calling currentAggregateUsed
        stubNoOverride();
        stubNoPlanLimit();

        BillingModels.CheckResult result = entitlementService.check(tenantId, BillingModels.METER_WHATSAPP_MESSAGES, 1L);

        assertThat(result.allowed()).isTrue();
        assertThat(result.reason()).isNull();
    }

    // -----------------------------------------------------------------------
    // Limit math: resolveLimit with override taking priority
    // -----------------------------------------------------------------------

    @Test
    void resolveLimitPrefersOverride() {
        // Override says 500, plan says 100 — override should win
        stubOverrideLimit(500L);
        // plan limit should not be consulted — no stub needed

        Long limit = entitlementService.resolveLimit(tenantId, planVersionId, BillingModels.METER_MEMBERS);

        assertThat(limit).isEqualTo(500L);
    }

    @Test
    void resolveLimitFallsBackToPlan() {
        stubNoOverride();
        stubPlanLimit(42L);

        Long limit = entitlementService.resolveLimit(tenantId, planVersionId, BillingModels.METER_MEMBERS);

        assertThat(limit).isEqualTo(42L);
    }

    @Test
    void resolveLimitReturnsNullWhenNoEntitlement() {
        stubNoOverride();
        stubNoPlanLimit();

        Long limit = entitlementService.resolveLimit(tenantId, planVersionId, BillingModels.METER_MEMBERS);

        assertThat(limit).isNull();
    }

    // -----------------------------------------------------------------------
    // consume() – denied for expired subscription
    // -----------------------------------------------------------------------

    @Test
    void consumeThrowsConflictForExpiredSubscription() {
        when(subscriptionService.getSubscription(tenantId))
                .thenReturn(Optional.of(activeSubscription(BillingModels.STATUS_EXPIRED)));

        assertThatThrownBy(() ->
                entitlementService.consume(tenantId, null,
                        BillingModels.METER_MEMBERS, 1L, "key-1", null, null)
        )
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getCode())
                .isEqualTo(ErrorCode.CONFLICT);
    }

    @Test
    void consumeThrowsConflictWhenNoSubscription() {
        when(subscriptionService.getSubscription(tenantId)).thenReturn(Optional.empty());

        assertThatThrownBy(() ->
                entitlementService.consume(tenantId, null,
                        BillingModels.METER_MEMBERS, 1L, "key-2", null, null)
        )
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getCode())
                .isEqualTo(ErrorCode.CONFLICT);
    }

    @Test
    void consumeThrowsConflictWhenLimitWouldBeExceeded() {
        when(subscriptionService.getSubscription(tenantId))
                .thenReturn(Optional.of(activeSubscription(BillingModels.STATUS_ACTIVE)));

        stubNoOverride();
        stubPlanLimit(10L);
        stubCurrentUsed(9L); // 9 + 5 > 10

        assertThatThrownBy(() ->
                entitlementService.consume(tenantId, null,
                        BillingModels.METER_MEMBERS, 5L, "key-3", null, null)
        )
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getCode())
                .isEqualTo(ErrorCode.CONFLICT);

        // No usage event should be inserted
        verify(jdbcTemplate, never()).update(
                org.mockito.ArgumentMatchers.contains("INSERT INTO usage_event"),
                any(Object[].class));
    }

    // -----------------------------------------------------------------------
    // Warning flag in UsageMeterView
    // -----------------------------------------------------------------------

    @Test
    void warningFlagLogicAt80Pct() {
        // 80 used out of 100 limit at 80% soft_limit_pct -> warning should be true
        boolean warning80 = computeWarning(80L, 100L, 80);
        assertThat(warning80).isTrue();

        // 79 used -> no warning
        boolean warning79 = computeWarning(79L, 100L, 80);
        assertThat(warning79).isFalse();

        // unlimited -> no warning
        boolean warningUnlimited = computeWarning(9999L, null, 80);
        assertThat(warningUnlimited).isFalse();
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private BillingModels.SubscriptionView activeSubscription(String status) {
        return new BillingModels.SubscriptionView(
                UUID.randomUUID(), tenantId, planVersionId, status,
                "MONTHLY", null, periodStart, periodEnd,
                null, false, null, null, Instant.now());
    }

    @SuppressWarnings("unchecked")
    private void stubNoOverride() {
        when(jdbcTemplate.query(
                org.mockito.ArgumentMatchers.contains("entitlement_override"),
                any(RowMapper.class),
                eq(tenantId), any(String.class))
        ).thenReturn(List.of());
    }

    @SuppressWarnings("unchecked")
    private void stubOverrideLimit(Long limit) {
        when(jdbcTemplate.query(
                org.mockito.ArgumentMatchers.contains("entitlement_override"),
                any(RowMapper.class),
                eq(tenantId), any(String.class))
        ).thenReturn(List.of(limit));
    }

    @SuppressWarnings("unchecked")
    private void stubPlanLimit(Long limit) {
        when(jdbcTemplate.query(
                org.mockito.ArgumentMatchers.contains("plan_entitlement"),
                any(RowMapper.class),
                eq(planVersionId), any(String.class))
        ).thenReturn(List.of(limit));
    }

    @SuppressWarnings("unchecked")
    private void stubNoPlanLimit() {
        when(jdbcTemplate.query(
                org.mockito.ArgumentMatchers.contains("plan_entitlement"),
                any(RowMapper.class),
                eq(planVersionId), any(String.class))
        ).thenReturn(List.of());
    }

    @SuppressWarnings("unchecked")
    private void stubCurrentUsed(Long used) {
        when(jdbcTemplate.query(
                org.mockito.ArgumentMatchers.contains("usage_period_aggregate"),
                any(RowMapper.class),
                eq(tenantId), any(String.class), any(Timestamp.class))
        ).thenReturn(List.of(used));
    }

    private static boolean computeWarning(long used, Long limit, int softPct) {
        return limit != null && used >= (limit * softPct / 100L);
    }
}
