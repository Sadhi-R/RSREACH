package com.rsreach.persistence.outbox;

import java.time.Instant;
import java.util.UUID;

public record OutboxEvent(
        UUID id,
        String aggregateType,
        UUID aggregateId,
        String eventType,
        String payloadJson,
        UUID tenantId,
        UUID workspaceId,
        String correlationId,
        String idempotencyKey,
        String status,
        int attemptCount,
        int maxAttempts,
        Instant availableAt,
        Instant processedAt,
        String lastError
) {
}
