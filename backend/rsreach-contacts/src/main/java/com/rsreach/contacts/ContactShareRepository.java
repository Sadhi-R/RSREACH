package com.rsreach.contacts;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ContactShareRepository {

    private final JdbcTemplate jdbcTemplate;

    public ContactShareRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public UUID insertShare(
            UUID tenantId,
            UUID sourceWorkspaceId,
            UUID targetWorkspaceId,
            String resourceType,
            UUID resourceId,
            String permission,
            UUID actorId
    ) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO resource_shares (
                    id, tenant_id, source_workspace_id, target_workspace_id,
                    resource_type, resource_id, permission, created_by, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, NOW())
                """,
                id, tenantId, sourceWorkspaceId, targetWorkspaceId,
                resourceType, resourceId, permission, actorId
        );
        return id;
    }

    public Optional<ContactShareModels.ShareView> findByNaturalKey(
            UUID tenantId,
            UUID sourceWorkspaceId,
            UUID targetWorkspaceId,
            String resourceType,
            UUID resourceId
    ) {
        List<ContactShareModels.ShareView> rows = jdbcTemplate.query(
                """
                SELECT id, source_workspace_id, target_workspace_id, resource_type, resource_id,
                       permission, created_at, revoked_at
                FROM resource_shares
                WHERE tenant_id = ?
                  AND source_workspace_id = ?
                  AND target_workspace_id = ?
                  AND resource_type = ?
                  AND resource_id = ?
                """,
                (rs, i) -> mapShare(rs),
                tenantId, sourceWorkspaceId, targetWorkspaceId, resourceType, resourceId
        );
        return rows.stream().findFirst();
    }

    public void reactivate(UUID shareId, String permission) {
        jdbcTemplate.update(
                """
                UPDATE resource_shares
                SET permission = ?, revoked_at = NULL
                WHERE id = ?
                """,
                permission, shareId
        );
    }

    public Optional<ContactShareModels.ShareView> findById(
            UUID tenantId, UUID resourceId, String resourceType, UUID shareId
    ) {
        List<ContactShareModels.ShareView> rows = jdbcTemplate.query(
                """
                SELECT id, source_workspace_id, target_workspace_id, resource_type, resource_id,
                       permission, created_at, revoked_at
                FROM resource_shares
                WHERE id = ? AND tenant_id = ? AND resource_id = ? AND resource_type = ?
                """,
                (rs, i) -> mapShare(rs),
                shareId, tenantId, resourceId, resourceType
        );
        return rows.stream().findFirst();
    }

    public List<ContactShareModels.ShareView> listForResource(
            UUID tenantId, UUID sourceWorkspaceId, String resourceType, UUID resourceId
    ) {
        return jdbcTemplate.query(
                """
                SELECT id, source_workspace_id, target_workspace_id, resource_type, resource_id,
                       permission, created_at, revoked_at
                FROM resource_shares
                WHERE tenant_id = ?
                  AND source_workspace_id = ?
                  AND resource_type = ?
                  AND resource_id = ?
                ORDER BY created_at DESC
                """,
                (rs, i) -> mapShare(rs),
                tenantId, sourceWorkspaceId, resourceType, resourceId
        );
    }

    public int revoke(UUID tenantId, UUID shareId) {
        return jdbcTemplate.update(
                """
                UPDATE resource_shares
                SET revoked_at = NOW()
                WHERE id = ? AND tenant_id = ? AND revoked_at IS NULL
                """,
                shareId, tenantId
        );
    }

    public Optional<String> findActiveSharePermission(
            UUID tenantId, UUID targetWorkspaceId, String resourceType, UUID resourceId
    ) {
        List<String> rows = jdbcTemplate.query(
                """
                SELECT permission
                FROM resource_shares
                WHERE tenant_id = ?
                  AND target_workspace_id = ?
                  AND resource_type = ?
                  AND resource_id = ?
                  AND revoked_at IS NULL
                ORDER BY CASE permission WHEN 'WRITE' THEN 0 ELSE 1 END
                LIMIT 1
                """,
                (rs, i) -> rs.getString("permission"),
                tenantId, targetWorkspaceId, resourceType, resourceId
        );
        return rows.stream().findFirst();
    }

    public boolean workspaceExists(UUID tenantId, UUID workspaceId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM workspaces WHERE tenant_id = ? AND id = ?",
                Integer.class, tenantId, workspaceId
        );
        return count != null && count > 0;
    }

    private static ContactShareModels.ShareView mapShare(java.sql.ResultSet rs) throws java.sql.SQLException {
        Timestamp revoked = rs.getTimestamp("revoked_at");
        return new ContactShareModels.ShareView(
                (UUID) rs.getObject("id"),
                (UUID) rs.getObject("source_workspace_id"),
                (UUID) rs.getObject("target_workspace_id"),
                rs.getString("resource_type"),
                (UUID) rs.getObject("resource_id"),
                rs.getString("permission"),
                rs.getTimestamp("created_at").toInstant(),
                revoked == null ? null : revoked.toInstant()
        );
    }
}
