package com.rsreach.scheduler.runtime;

import com.rsreach.billing.BillingRenewalService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Periodically drives subscription lifecycle transitions (trial expiry, grace, suspension).
 *
 * <p>Calls {@link BillingRenewalService#processRenewalTick()} directly rather than via the
 * outbox to avoid tenant_id/workspace_id null constraints in the outbox table.
 *
 * <p>Runs every 5 minutes by default (configurable via
 * {@code rsreach.scheduler.billing-renewal-ms}).
 */
@Component
public class BillingRenewalScheduler {

    private static final Logger log = LoggerFactory.getLogger(BillingRenewalScheduler.class);

    private final BillingRenewalService billingRenewalService;

    public BillingRenewalScheduler(BillingRenewalService billingRenewalService) {
        this.billingRenewalService = billingRenewalService;
    }

    @Scheduled(fixedDelayString = "${rsreach.scheduler.billing-renewal-ms:300000}")
    public void tick() {
        log.debug("billing_renewal_tick_start");
        try {
            int count = billingRenewalService.processRenewalTick();
            if (count > 0) {
                log.info("billing_renewal_tick_done transitioned={}", count);
            }
        } catch (Exception ex) {
            log.error("billing_renewal_tick_error: {}", ex.getMessage(), ex);
        }
    }
}
