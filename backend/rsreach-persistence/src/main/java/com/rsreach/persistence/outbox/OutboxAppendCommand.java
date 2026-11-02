package com.rsreach.persistence.outbox;

import java.util.UUID;

/**
 * Command to append an outbox event inside an existing business transaction.
 */
public record OutboxAppendCommand(
        String aggregateType,
        UUID aggregateId,
        String eventType,
        String payloadJson,
        UUID tenantId,
        UUID workspaceId,
        String correlationId,
        String idempotencyKey,
        int maxAttempts
) {
    public OutboxAppendCommand {
        if (aggregateType == null || aggregateType.isBlank()) {
            throw new IllegalArgumentException("aggregateType is required");
        }
        if (aggregateId == null) {
            throw new IllegalArgumentException("aggregateId is required");
        }
        if (eventType == null || eventType.isBlank()) {
            throw new IllegalArgumentException("eventType is required");
        }
        if (payloadJson == null || payloadJson.isBlank()) {
            throw new IllegalArgumentException("payloadJson is required");
        }
        if (workspaceId != null && tenantId == null) {
            throw new IllegalArgumentException("tenantId is required when workspaceId is present");
        }
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be >= 1");
        }
    }

    public static OutboxAppendCommand of(
            String aggregateType,
            UUID aggregateId,
            String eventType,
            String payloadJson,
            UUID tenantId,
            UUID workspaceId,
            String correlationId,
            String idempotencyKey
    ) {
        return new OutboxAppendCommand(
                aggregateType,
                aggregateId,
                eventType,
                payloadJson,
                tenantId,
                workspaceId,
                correlationId,
                idempotencyKey,
                10
        );
    }
}
