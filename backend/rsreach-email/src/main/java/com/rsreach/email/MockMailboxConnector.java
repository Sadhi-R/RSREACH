package com.rsreach.email;

import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Always available as fallback when live Gmail/Graph connectors are not selected. */
@Component
public class MockMailboxConnector implements MailboxConnector {

    @Override
    public String provider() {
        return "mock_mailbox";
    }

    @Override
    public List<SyncedMessage> fetchIncremental(
            UUID tenantId, UUID workspaceId, UUID accountId, String accessToken
    ) {
        String id = "mock-mail-" + accountId + "-" + System.currentTimeMillis();
        return List.of(new SyncedMessage(
                id,
                "thread-" + accountId,
                "Mock synced mail",
                "Inbound mailbox sync body",
                "sender@example.com"
        ));
    }
}
