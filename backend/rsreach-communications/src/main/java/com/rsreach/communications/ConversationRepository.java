package com.rsreach.communications;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ConversationRepository {

    private final JdbcTemplate jdbcTemplate;

    public ConversationRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void insert(
            UUID id,
            UUID tenantId,
            UUID workspaceId,
            String channel,
            UUID contactId,
            UUID connectedAccountId,
            String subject,
            String externalThreadKey
    ) {
        jdbcTemplate.update(
                """
                INSERT INTO conversations (
                    id, tenant_id, workspace_id, channel, contact_id, connected_account_id,
                    subject, external_thread_key, status, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'OPEN', NOW(), NOW())
                """,
                id, tenantId, workspaceId, channel, contactId, connectedAccountId, subject, externalThreadKey
        );
    }

    public Optional<CommunicationModels.ConversationView> findById(
            UUID tenantId, UUID workspaceId, UUID id
    ) {
        List<CommunicationModels.ConversationView> rows = jdbcTemplate.query(
                """
                SELECT * FROM conversations
                WHERE id = ? AND tenant_id = ? AND workspace_id = ?
                """,
                (rs, i) -> map(rs),
                id, tenantId, workspaceId
        );
        return rows.stream().findFirst();
    }

    public Optional<CommunicationModels.ConversationView> findByThread(
            UUID tenantId, UUID workspaceId, String channel, String externalThreadKey
    ) {
        List<CommunicationModels.ConversationView> rows = jdbcTemplate.query(
                """
                SELECT * FROM conversations
                WHERE tenant_id = ? AND workspace_id = ? AND channel = ? AND external_thread_key = ?
                """,
                (rs, i) -> map(rs),
                tenantId, workspaceId, channel, externalThreadKey
        );
        return rows.stream().findFirst();
    }

    public CommunicationModels.ConversationPage search(
            UUID tenantId, UUID workspaceId, String channel, int page, int size
    ) {
        int limit = Math.min(Math.max(size, 1), 100);
        int offset = Math.max(page, 0) * limit;
        boolean filterChannel = channel != null && !channel.isBlank();
        List<CommunicationModels.ConversationView> items = jdbcTemplate.query(
                """
                SELECT * FROM conversations
                WHERE tenant_id = ? AND workspace_id = ?
                  AND (? = FALSE OR channel = ?)
                ORDER BY last_message_at DESC NULLS LAST, created_at DESC
                LIMIT ? OFFSET ?
                """,
                (rs, i) -> map(rs),
                tenantId, workspaceId, filterChannel, filterChannel ? channel.trim().toUpperCase() : "",
                limit + 1, offset
        );
        boolean hasNext = items.size() > limit;
        if (hasNext) {
            items = new ArrayList<>(items.subList(0, limit));
        }
        return new CommunicationModels.ConversationPage(items, page, limit, hasNext);
    }

    public void touchLastMessage(UUID tenantId, UUID workspaceId, UUID conversationId) {
        jdbcTemplate.update(
                """
                UPDATE conversations
                SET last_message_at = NOW(), updated_at = NOW()
                WHERE id = ? AND tenant_id = ? AND workspace_id = ?
                """,
                conversationId, tenantId, workspaceId
        );
    }

    private static CommunicationModels.ConversationView map(java.sql.ResultSet rs)
            throws java.sql.SQLException {
        Timestamp last = rs.getTimestamp("last_message_at");
        return new CommunicationModels.ConversationView(
                (UUID) rs.getObject("id"),
                (UUID) rs.getObject("tenant_id"),
                (UUID) rs.getObject("workspace_id"),
                rs.getString("channel"),
                (UUID) rs.getObject("contact_id"),
                (UUID) rs.getObject("connected_account_id"),
                rs.getString("subject"),
                rs.getString("external_thread_key"),
                rs.getString("status"),
                last == null ? null : last.toInstant(),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant()
        );
    }
}
