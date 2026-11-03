package com.rsreach.communications;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Maps a verified provider account id to the tenant and workspace on the connected-account row.
 */
@Service
public class WebhookAccountResolver {

    private static final Logger log = LoggerFactory.getLogger(WebhookAccountResolver.class);

    private final ConnectedAccountRepository connectedAccountRepository;

    public WebhookAccountResolver(ConnectedAccountRepository connectedAccountRepository) {
        this.connectedAccountRepository = connectedAccountRepository;
    }

    public CommunicationModels.ConnectedAccountView requireActive(String provider, String externalAccountRef) {
        if (externalAccountRef == null || externalAccountRef.isBlank()) {
            throw new WebhookAccountResolutionException(
                    WebhookAccountResolutionException.Reason.MISSING_IDENTIFIER);
        }
        String ref = externalAccountRef.trim();
        List<CommunicationModels.ConnectedAccountView> matches =
                connectedAccountRepository.findActiveByProviderAndExternalRef(provider, ref);
        if (matches.isEmpty()) {
            long inactive = connectedAccountRepository.countInactiveByProviderAndExternalRef(provider, ref);
            log.info("webhook_account_unresolved provider={} inactiveCount={}", provider, inactive);
            throw new WebhookAccountResolutionException(WebhookAccountResolutionException.Reason.NOT_FOUND);
        }
        if (matches.size() > 1) {
            log.warn("webhook_account_ambiguous provider={} matchCount={}", provider, matches.size());
            throw new WebhookAccountResolutionException(WebhookAccountResolutionException.Reason.AMBIGUOUS);
        }
        return matches.get(0);
    }
}
