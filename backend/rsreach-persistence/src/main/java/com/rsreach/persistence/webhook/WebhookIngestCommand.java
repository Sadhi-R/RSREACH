package com.rsreach.persistence.webhook;

import java.time.Instant;
import java.util.UUID;

public record WebhookIngestCommand(
        String provider,
        String connectedAccountRef,
        String providerEventId,
        String eventType,
        Instant eventTime,
        UUID tenantId,
        UUID workspaceId,
        boolean signatureValid,
        String payloadJson,
        String payloadHash
) {
    public WebhookIngestCommand {
        if (provider == null || provider.isBlank()) {
            throw new IllegalArgumentException("provider is required");
        }
        if (connectedAccountRef == null || connectedAccountRef.isBlank()) {
            throw new IllegalArgumentException("connectedAccountRef is required");
        }
        if (providerEventId == null || providerEventId.isBlank()) {
            throw new IllegalArgumentException("providerEventId is required");
        }
        if (eventType == null || eventType.isBlank()) {
            throw new IllegalArgumentException("eventType is required");
        }
        if (payloadJson == null || payloadJson.isBlank()) {
            throw new IllegalArgumentException("payloadJson is required");
        }
        if (payloadHash == null || payloadHash.isBlank()) {
            throw new IllegalArgumentException("payloadHash is required");
        }
        if (workspaceId != null && tenantId == null) {
            throw new IllegalArgumentException("tenantId is required when workspaceId is present");
        }
    }
}
