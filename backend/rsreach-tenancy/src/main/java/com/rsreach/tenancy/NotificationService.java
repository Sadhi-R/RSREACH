package com.rsreach.tenancy;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Manages in-app notifications scoped to a tenant member.
 * Notifications are read-only for the recipient; creation is triggered by the automation engine
 * or other internal services via {@link #create}.
 */
@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);
    private static final int PAGE_SIZE = 50;

    private final JdbcTemplate jdbcTemplate;

    public NotificationService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Creates a notification for a specific user inside a tenant.
     * {@code userId} may be {@code null} for broadcast-style notifications (listed by any tenant
     * member), but for M4 all notifications are user-scoped.
     */
    @Transactional
    public UUID create(UUID tenantId, UUID workspaceId, UUID userId,
                       String title, String body, String linkPath) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO notifications
                    (id, tenant_id, workspace_id, user_id, title, body, link_path, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, NOW())
                """,
                id, tenantId, workspaceId, userId, title, body, linkPath
        );
        log.debug("notification.created id={} tenantId={} userId={}", id, tenantId, userId);
        return id;
    }

    /**
     * Lists up to {@value PAGE_SIZE} most-recent notifications for the authenticated user
     * within their tenant. Returns both read and unread; the UI can filter locally.
     */
    public List<NotificationView> listForUser(UUID tenantId, UUID userId) {
        return jdbcTemplate.query(
                """
                SELECT id, tenant_id, workspace_id, user_id, title, body, link_path, read_at, created_at
                FROM notifications
                WHERE tenant_id = ? AND (user_id = ? OR user_id IS NULL)
                ORDER BY created_at DESC
                LIMIT ?
                """,
                (rs, i) -> mapRow(rs),
                tenantId, userId, PAGE_SIZE
        );
    }

    /**
     * Marks a single notification as read. The operation is idempotent.
     * Returns {@code true} if the row belonged to this user and was updated.
     */
    @Transactional
    public boolean markRead(UUID tenantId, UUID userId, UUID notificationId) {
        int rows = jdbcTemplate.update(
                """
                UPDATE notifications
                SET read_at = NOW()
                WHERE id = ? AND tenant_id = ? AND (user_id = ? OR user_id IS NULL) AND read_at IS NULL
                """,
                notificationId, tenantId, userId
        );
        return rows > 0;
    }

    /**
     * Marks all unread notifications as read for the given user.
     */
    @Transactional
    public int markAllRead(UUID tenantId, UUID userId) {
        return jdbcTemplate.update(
                """
                UPDATE notifications
                SET read_at = NOW()
                WHERE tenant_id = ? AND (user_id = ? OR user_id IS NULL) AND read_at IS NULL
                """,
                tenantId, userId
        );
    }

    private NotificationView mapRow(ResultSet rs) throws SQLException {
        var readAt = rs.getTimestamp("read_at");
        return new NotificationView(
                (UUID) rs.getObject("id"),
                (UUID) rs.getObject("tenant_id"),
                (UUID) rs.getObject("workspace_id"),
                (UUID) rs.getObject("user_id"),
                rs.getString("title"),
                rs.getString("body"),
                rs.getString("link_path"),
                readAt == null ? null : readAt.toInstant(),
                rs.getTimestamp("created_at").toInstant()
        );
    }

    public record NotificationView(
            UUID id,
            UUID tenantId,
            UUID workspaceId,
            UUID userId,
            String title,
            String body,
            String linkPath,
            Instant readAt,
            Instant createdAt
    ) {
    }
}
