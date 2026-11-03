package com.rsreach.billing;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Pure-unit tests for {@link SubscriptionService#isValidTransition}.
 * No Spring context or database required.
 */
class SubscriptionStateMachineTest {

    // -----------------------------------------------------------------------
    // New subscription (null -> first status)
    // -----------------------------------------------------------------------

    @Test
    void newSubscriptionAllowsTrialing() {
        assertThat(SubscriptionService.isValidTransition(null, BillingModels.STATUS_TRIALING)).isTrue();
    }

    @Test
    void newSubscriptionAllowsActive() {
        assertThat(SubscriptionService.isValidTransition(null, BillingModels.STATUS_ACTIVE)).isTrue();
    }

    @Test
    void newSubscriptionDoesNotAllowPastDue() {
        assertThat(SubscriptionService.isValidTransition(null, BillingModels.STATUS_PAST_DUE)).isFalse();
    }

    @Test
    void newSubscriptionDoesNotAllowSuspended() {
        assertThat(SubscriptionService.isValidTransition(null, BillingModels.STATUS_SUSPENDED)).isFalse();
    }

    @Test
    void newSubscriptionDoesNotAllowNullTo() {
        assertThat(SubscriptionService.isValidTransition(null, null)).isFalse();
    }

    // -----------------------------------------------------------------------
    // TRIALING transitions
    // -----------------------------------------------------------------------

    @Test
    void trialingCanActivate() {
        assertThat(SubscriptionService.isValidTransition(
                BillingModels.STATUS_TRIALING, BillingModels.STATUS_ACTIVE)).isTrue();
    }

    @Test
    void trialingCanBecomePastDue() {
        assertThat(SubscriptionService.isValidTransition(
                BillingModels.STATUS_TRIALING, BillingModels.STATUS_PAST_DUE)).isTrue();
    }

    @Test
    void trialingCanBeCancelled() {
        assertThat(SubscriptionService.isValidTransition(
                BillingModels.STATUS_TRIALING, BillingModels.STATUS_CANCELLED)).isTrue();
    }

    @Test
    void trialingCanExpire() {
        assertThat(SubscriptionService.isValidTransition(
                BillingModels.STATUS_TRIALING, BillingModels.STATUS_EXPIRED)).isTrue();
    }

    @Test
    void trialingCannotGoToSuspended() {
        assertThat(SubscriptionService.isValidTransition(
                BillingModels.STATUS_TRIALING, BillingModels.STATUS_SUSPENDED)).isFalse();
    }

    // -----------------------------------------------------------------------
    // ACTIVE transitions
    // -----------------------------------------------------------------------

    @Test
    void activeCanBecomePastDue() {
        assertThat(SubscriptionService.isValidTransition(
                BillingModels.STATUS_ACTIVE, BillingModels.STATUS_PAST_DUE)).isTrue();
    }

    @Test
    void activeCanBeCancelled() {
        assertThat(SubscriptionService.isValidTransition(
                BillingModels.STATUS_ACTIVE, BillingModels.STATUS_CANCELLED)).isTrue();
    }

    @Test
    void activeCannotGoToSuspended() {
        assertThat(SubscriptionService.isValidTransition(
                BillingModels.STATUS_ACTIVE, BillingModels.STATUS_SUSPENDED)).isFalse();
    }

    @Test
    void activeCannotGoToExpired() {
        assertThat(SubscriptionService.isValidTransition(
                BillingModels.STATUS_ACTIVE, BillingModels.STATUS_EXPIRED)).isFalse();
    }

    // -----------------------------------------------------------------------
    // PAST_DUE transitions
    // -----------------------------------------------------------------------

    @Test
    void pastDueCanActivateOnPayment() {
        assertThat(SubscriptionService.isValidTransition(
                BillingModels.STATUS_PAST_DUE, BillingModels.STATUS_ACTIVE)).isTrue();
    }

    @Test
    void pastDueCanBeSuspended() {
        assertThat(SubscriptionService.isValidTransition(
                BillingModels.STATUS_PAST_DUE, BillingModels.STATUS_SUSPENDED)).isTrue();
    }

    @Test
    void pastDueCanBeCancelled() {
        assertThat(SubscriptionService.isValidTransition(
                BillingModels.STATUS_PAST_DUE, BillingModels.STATUS_CANCELLED)).isTrue();
    }

    @Test
    void pastDueCannotExpireDirectly() {
        assertThat(SubscriptionService.isValidTransition(
                BillingModels.STATUS_PAST_DUE, BillingModels.STATUS_EXPIRED)).isFalse();
    }

    // -----------------------------------------------------------------------
    // SUSPENDED transitions
    // -----------------------------------------------------------------------

    @Test
    void suspendedCanActivate() {
        assertThat(SubscriptionService.isValidTransition(
                BillingModels.STATUS_SUSPENDED, BillingModels.STATUS_ACTIVE)).isTrue();
    }

    @Test
    void suspendedCanExpire() {
        assertThat(SubscriptionService.isValidTransition(
                BillingModels.STATUS_SUSPENDED, BillingModels.STATUS_EXPIRED)).isTrue();
    }

    @Test
    void suspendedCanBeCancelled() {
        assertThat(SubscriptionService.isValidTransition(
                BillingModels.STATUS_SUSPENDED, BillingModels.STATUS_CANCELLED)).isTrue();
    }

    @Test
    void suspendedCannotGoToTrialing() {
        assertThat(SubscriptionService.isValidTransition(
                BillingModels.STATUS_SUSPENDED, BillingModels.STATUS_TRIALING)).isFalse();
    }

    // -----------------------------------------------------------------------
    // Terminal states re-activation
    // -----------------------------------------------------------------------

    @Test
    void cancelledCanReactivateViFreePlan() {
        assertThat(SubscriptionService.isValidTransition(
                BillingModels.STATUS_CANCELLED, BillingModels.STATUS_ACTIVE)).isTrue();
    }

    @Test
    void expiredCanReactivateViaFreePlan() {
        assertThat(SubscriptionService.isValidTransition(
                BillingModels.STATUS_EXPIRED, BillingModels.STATUS_ACTIVE)).isTrue();
    }

    @Test
    void cancelledCannotGoToTrialing() {
        assertThat(SubscriptionService.isValidTransition(
                BillingModels.STATUS_CANCELLED, BillingModels.STATUS_TRIALING)).isFalse();
    }

    @Test
    void cancelledCannotGoToPastDue() {
        assertThat(SubscriptionService.isValidTransition(
                BillingModels.STATUS_CANCELLED, BillingModels.STATUS_PAST_DUE)).isFalse();
    }

    // -----------------------------------------------------------------------
    // Unknown state
    // -----------------------------------------------------------------------

    @Test
    void unknownFromStatusIsDenied() {
        assertThat(SubscriptionService.isValidTransition("UNKNOWN", BillingModels.STATUS_ACTIVE))
                .isFalse();
    }
}
