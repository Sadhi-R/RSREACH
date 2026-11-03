package com.rsreach.email;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsreach.communications.CommunicationModels;
import com.rsreach.communications.ConnectedAccountService;
import com.rsreach.communications.ConversationService;
import com.rsreach.persistence.outbox.OutboxAppendCommand;
import com.rsreach.persistence.outbox.OutboxRepository;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MailboxSyncService {

    private static final Logger log = LoggerFactory.getLogger(MailboxSyncService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final OutboxRepository outboxRepository;
    private final ConversationService conversationService;
    private final ConnectedAccountService connectedAccountService;
    private final List<MailboxConnector> mailboxConnectors;
    private final MockMailboxConnector mockMailboxConnector;
    private final JdbcTemplate jdbcTemplate;

    public MailboxSyncService(
            OutboxRepository outboxRepository,
            ConversationService conversationService,
            ConnectedAccountService connectedAccountService,
            List<MailboxConnector> mailboxConnectors,
            MockMailboxConnector mockMailboxConnector,
            JdbcTemplate jdbcTemplate
    ) {
        this.outboxRepository = outboxRepository;
        this.conversationService = conversationService;
        this.connectedAccountService = connectedAccountService;
        this.mailboxConnectors = mailboxConnectors;
        this.mockMailboxConnector = mockMailboxConnector;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional
    public void enqueueSync(UUID tenantId, UUID workspaceId, UUID accountId) {
        outboxRepository.append(OutboxAppendCommand.of(
                "connected_account",
                accountId,
                CommunicationModels.EVENT_MAILBOX_SYNC,
                "{\"accountId\":\"" + accountId + "\"}",
                tenantId,
                workspaceId,
                null,
                "MAILBOX_SYNC:" + accountId + ":" + UUID.randomUUID()
        ));
    }

    @Transactional
    public int processSync(UUID tenantId, UUID workspaceId, String payloadJson) {
        try {
            JsonNode node = MAPPER.readTree(payloadJson);
            UUID accountId = UUID.fromString(node.get("accountId").asText());
            CommunicationModels.ConnectedAccountView account =
                    connectedAccountService.get(tenantId, workspaceId, accountId);
            String token = connectedAccountService.decryptAccessToken(tenantId, workspaceId, accountId);
            MailboxConnector connector = resolveConnector(account.provider());
            List<MailboxConnector.SyncedMessage> synced =
                    connector.fetchIncremental(tenantId, workspaceId, accountId, token);
            int applied = 0;
            for (MailboxConnector.SyncedMessage item : synced) {
                UUID conversationId = resolveConversation(tenantId, workspaceId, accountId, item);
                conversationService.appendInbound(
                        tenantId,
                        workspaceId,
                        conversationId,
                        item.bodyText(),
                        item.providerMessageId(),
                        item.fromAddress()
                );
                applied++;
            }
            log.info("mailbox_sync_applied accountId={} count={}", accountId, applied);
            return applied;
        } catch (Exception ex) {
            throw new IllegalStateException("MAILBOX_SYNC failed: " + ex.getMessage(), ex);
        }
    }

    private MailboxConnector resolveConnector(String provider) {
        String p = provider == null ? "" : provider.trim().toLowerCase(Locale.ROOT);
        for (MailboxConnector connector : mailboxConnectors) {
            if (connector.provider().equalsIgnoreCase(p)) {
                return connector;
            }
        }
        return mockMailboxConnector;
    }

    private UUID resolveConversation(
            UUID tenantId,
            UUID workspaceId,
            UUID accountId,
            MailboxConnector.SyncedMessage item
    ) {
        List<UUID> existing = jdbcTemplate.query(
                """
                SELECT id FROM conversations
                WHERE tenant_id = ? AND workspace_id = ? AND connected_account_id = ?
                  AND channel = 'EMAIL'
                ORDER BY last_message_at DESC NULLS LAST
                LIMIT 1
                """,
                (rs, i) -> (UUID) rs.getObject("id"),
                tenantId, workspaceId, accountId
        );
        if (!existing.isEmpty()) {
            return existing.get(0);
        }
        UUID contactId = findAnyContact(tenantId, workspaceId);
        if (contactId == null) {
            throw new IllegalStateException("No contact available for mailbox sync conversation");
        }
        CommunicationModels.ConversationView created = conversationService.create(
                tenantId,
                workspaceId,
                null,
                new CommunicationModels.CreateConversationCommand(
                        CommunicationModels.CHANNEL_EMAIL,
                        contactId,
                        accountId,
                        item.subject(),
                        item.threadKey()
                )
        );
        return created.id();
    }

    private UUID findAnyContact(UUID tenantId, UUID workspaceId) {
        List<UUID> ids = jdbcTemplate.query(
                """
                SELECT id FROM contacts
                WHERE tenant_id = ? AND home_workspace_id = ? AND status = 'ACTIVE'
                ORDER BY created_at
                LIMIT 1
                """,
                (rs, i) -> (UUID) rs.getObject("id"),
                tenantId, workspaceId
        );
        return ids.isEmpty() ? null : ids.get(0);
    }
}
