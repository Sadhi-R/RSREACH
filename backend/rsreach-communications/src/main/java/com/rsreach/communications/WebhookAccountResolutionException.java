package com.rsreach.communications;

/**
 * Signed webhook whose account cannot be mapped to exactly one active connected account.
 * Nothing is persisted for the caller to attach to a tenant.
 */
public class WebhookAccountResolutionException extends RuntimeException {

    public enum Reason {
        MISSING_IDENTIFIER,
        NOT_FOUND,
        AMBIGUOUS
    }

    private final Reason reason;

    public WebhookAccountResolutionException(Reason reason) {
        super(reason.name());
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
