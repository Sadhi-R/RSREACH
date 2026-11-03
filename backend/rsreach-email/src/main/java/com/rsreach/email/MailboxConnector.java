package com.rsreach.email;

import java.util.List;
import java.util.UUID;

/**
 * Mailbox sync port (Gmail / Microsoft Graph). Live OAuth adapters require operator credentials.
 */
public interface MailboxConnector {

    String provider();

    List<SyncedMessage> fetchIncremental(UUID tenantId, UUID workspaceId, UUID accountId, String accessToken);

    record SyncedMessage(
            String providerMessageId,
            String threadKey,
            String subject,
            String bodyText,
            String fromAddress
    ) {
    }
}
