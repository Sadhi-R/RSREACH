package com.rsreach.billing;

import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Separate bean so FREE-plan lazy activation can run in {@code REQUIRES_NEW}
 * when called from {@link EntitlementService} (avoids Spring self-invocation).
 */
@Service
public class SubscriptionBootstrapService {

    private static final Logger log = LoggerFactory.getLogger(SubscriptionBootstrapService.class);

    private final SubscriptionService subscriptionService;

    public SubscriptionBootstrapService(SubscriptionService subscriptionService) {
        this.subscriptionService = subscriptionService;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void ensureFreeIfMissing(UUID tenantId) {
        if (subscriptionService.getSubscription(tenantId).isEmpty()) {
            try {
                subscriptionService.activateFree(tenantId, null);
                log.info("lazyBootstrap: FREE plan activated for tenant={}", tenantId);
            } catch (Exception ex) {
                log.warn("lazyBootstrap: auto-activate failed tenant={}: {}", tenantId, ex.getMessage());
            }
        }
    }
}
