package com.rsreach.common.event;

import java.util.UUID;

/**
 * Generic Spring application event published by domain services after committing a write.
 *
 * <p>Used to bridge domain events across module boundaries without introducing
 * direct circular dependencies. Listeners use {@code @TransactionalEventListener(phase=AFTER_COMMIT)}.
 *
 * <p>Not persisted; purely in-process.
 *
 * @param type        a constant from the domain, e.g. "CONTACT_CREATED", "CAMPAIGN_COMPLETED"
 * @param tenantId    isolation key (never null)
 * @param workspaceId isolation key (never null)
 * @param resourceId  primary resource UUID (contactId, campaignId, etc.)
 * @param payloadJson free-form JSON context for the listener; must be valid JSON, use "{}" if empty
 */
public record WorkspaceDomainEvent(
        String type,
        UUID tenantId,
        UUID workspaceId,
        UUID resourceId,
        String payloadJson
) {
}
