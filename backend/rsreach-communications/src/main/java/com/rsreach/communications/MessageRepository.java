package com.rsreach.communications;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class MessageRepository {

    private final JdbcTemplate jdbcTemplate;

    public MessageRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void insert(
            UUID id,
            UUID tenantId,
            UUID workspaceId,
            UUID conversationId,
            String direction,
            String channel,
            String bodyText,
            String bodyPreview,
            String purpose,
            String destinationAddress,
            String providerMessageId,
            String idempotencyKey,
            String status,
            UUID createdBy
    ) {
        jdbcTemplate.update(
                """
                INSERT INTO messages (
                    id, tenant_id, workspace_id, conversation_id, direction, channel,
                    body_text, body_preview, purpose, destination_address, provider_message_id,
                    idempotency_key, status, accepted_at, queued_at, created_by, created_at, updated_at
                ) VALUES (
                    ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NOW(),
                    CASE WHEN ? = 'QUEUED' THEN NOW() ELSE NULL END,
                    ?, NOW(), NOW()
                )
                """,
                id, tenantId, workspaceId, conversationId, direction, channel,
                bodyText, bodyPreview, purpose, destinationAddress, providerMessageId,
                idempotencyKey, status, status, createdBy
        );
    }

    public void insertStatusEvent(
            UUID id, UUID tenantId, UUID workspaceId, UUID messageId, String status, String providerStatus
    ) {
        jdbcTemplate.update(
                """
                INSERT INTO message_status_events (
                    id, tenant_id, workspace_id, message_id, status, provider_status, event_time, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, NOW(), NOW())
                """,
                id, tenantId, workspaceId, messageId, status, providerStatus
        );
    }

    public Optional<CommunicationModels.MessageView> findById(UUID tenantId, UUID workspaceId, UUID id) {
        List<CommunicationModels.MessageView> rows = jdbcTemplate.query(
                """
                SELECT * FROM messages WHERE id = ? AND tenant_id = ? AND workspace_id = ?
                """,
                (rs, i) -> map(rs),
                id, tenantId, workspaceId
        );
        return rows.stream().findFirst();
    }

    public Optional<CommunicationModels.MessageView> findByIdempotency(
            UUID tenantId, UUID workspaceId, String idempotencyKey
    ) {
        List<CommunicationModels.MessageView> rows = jdbcTemplate.query(
                """
                SELECT * FROM messages
                WHERE tenant_id = ? AND workspace_id = ? AND idempotency_key = ?
                """,
                (rs, i) -> map(rs),
                tenantId, workspaceId, idempotencyKey
        );
        return rows.stream().findFirst();
    }

    public Optional<CommunicationModels.MessageView> findByProviderMessageId(
            UUID tenantId, UUID workspaceId, String channel, String providerMessageId
    ) {
        List<CommunicationModels.MessageView> rows = jdbcTemplate.query(
                """
                SELECT * FROM messages
                WHERE tenant_id = ? AND workspace_id = ? AND channel = ? AND provider_message_id = ?
                """,
                (rs, i) -> map(rs),
                tenantId, workspaceId, channel, providerMessageId
        );
        return rows.stream().findFirst();
    }

    public Optional<CommunicationModels.MessageView> findByProviderMessageIdAnyChannel(
            UUID tenantId, UUID workspaceId, String providerMessageId
    ) {
        List<CommunicationModels.MessageView> rows = jdbcTemplate.query(
                """
                SELECT * FROM messages
                WHERE tenant_id = ? AND workspace_id = ? AND provider_message_id = ?
                ORDER BY created_at DESC
                LIMIT 1
                """,
                (rs, i) -> map(rs),
                tenantId, workspaceId, providerMessageId
        );
        return rows.stream().findFirst();
    }

    public CommunicationModels.MessagePage listByConversation(
            UUID tenantId, UUID workspaceId, UUID conversationId, int page, int size
    ) {
        int limit = Math.min(Math.max(size, 1), 100);
        int offset = Math.max(page, 0) * limit;
        List<CommunicationModels.MessageView> items = jdbcTemplate.query(
                """
                SELECT * FROM messages
                WHERE tenant_id = ? AND workspace_id = ? AND conversation_id = ?
                ORDER BY created_at ASC
                LIMIT ? OFFSET ?
                """,
                (rs, i) -> map(rs),
                tenantId, workspaceId, conversationId, limit + 1, offset
        );
        boolean hasNext = items.size() > limit;
        if (hasNext) {
            items = new ArrayList<>(items.subList(0, limit));
        }
        return new CommunicationModels.MessagePage(items, page, limit, hasNext);
    }

    public int updateStatus(
            UUID tenantId,
            UUID workspaceId,
            UUID messageId,
            String status,
            String providerMessageId,
            String providerStatus,
            String errorCode,
            String errorMessage
    ) {
        return jdbcTemplate.update(
                """
                UPDATE messages SET
                    status = ?,
                    provider_message_id = COALESCE(?, provider_message_id),
                    provider_status = COALESCE(?, provider_status),
                    error_code = COALESCE(?, error_code),
                    error_message = COALESCE(?, error_message),
                    queued_at = CASE WHEN ? = 'QUEUED' THEN COALESCE(queued_at, NOW()) ELSE queued_at END,
                    sent_at = CASE WHEN ? = 'SENT' THEN COALESCE(sent_at, NOW()) ELSE sent_at END,
                    delivered_at = CASE WHEN ? = 'DELIVERED' THEN COALESCE(delivered_at, NOW()) ELSE delivered_at END,
                    read_at = CASE WHEN ? = 'READ' THEN COALESCE(read_at, NOW()) ELSE read_at END,
                    failed_at = CASE WHEN ? IN ('FAILED', 'REJECTED') THEN COALESCE(failed_at, NOW()) ELSE failed_at END,
                    updated_at = NOW()
                WHERE id = ? AND tenant_id = ? AND workspace_id = ?
                """,
                status, providerMessageId, providerStatus, errorCode, errorMessage,
                status, status, status, status, status,
                messageId, tenantId, workspaceId
        );
    }

    public void insertAttachment(
            UUID id,
            UUID tenantId,
            UUID workspaceId,
            UUID messageId,
            String filename,
            String mimeType,
            Long sizeBytes,
            String providerMediaId,
            String storageRef
    ) {
        jdbcTemplate.update(
                """
                INSERT INTO message_attachments (
                    id, tenant_id, workspace_id, message_id, filename, mime_type,
                    size_bytes, provider_media_id, storage_ref, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, NOW())
                """,
                id, tenantId, workspaceId, messageId, filename, mimeType, sizeBytes, providerMediaId, storageRef
        );
    }

    private static CommunicationModels.MessageView map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new CommunicationModels.MessageView(
                (UUID) rs.getObject("id"),
                (UUID) rs.getObject("conversation_id"),
                rs.getString("direction"),
                rs.getString("channel"),
                rs.getString("body_text"),
                rs.getString("body_preview"),
                rs.getString("purpose"),
                rs.getString("destination_address"),
                rs.getString("provider_message_id"),
                rs.getString("status"),
                instant(rs, "accepted_at"),
                instant(rs, "sent_at"),
                instant(rs, "delivered_at"),
                instant(rs, "read_at"),
                instant(rs, "failed_at"),
                instant(rs, "created_at")
        );
    }

    private static java.time.Instant instant(java.sql.ResultSet rs, String col) throws java.sql.SQLException {
        Timestamp ts = rs.getTimestamp(col);
        return ts == null ? null : ts.toInstant();
    }
}
