package com.rsreach.staffaccess;

import com.rsreach.audit.AuditService;
import com.rsreach.common.RsReachConstants;
import com.rsreach.common.error.ApiException;
import com.rsreach.common.error.ErrorCode;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class StaffAccessService {

    private final JdbcTemplate jdbcTemplate;
    private final AuditService auditService;

    public StaffAccessService(JdbcTemplate jdbcTemplate, AuditService auditService) {
        this.jdbcTemplate = jdbcTemplate;
        this.auditService = auditService;
    }

    @Transactional
    public UUID requestGrant(UUID staffUserId, UUID tenantId, UUID workspaceId, String justification, Instant expiresAt) {
        if (justification == null || justification.isBlank()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Justification is required");
        }
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO staff_access_grants
                    (id, staff_user_id, tenant_id, workspace_id, justification, status, requested_at, expires_at, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, 'PENDING', NOW(), ?, NOW(), NOW())
                """,
                id, staffUserId, tenantId, workspaceId, justification.trim(), Timestamp.from(expiresAt)
        );
        auditService.record(RsReachConstants.REALM_STAFF, staffUserId, tenantId, workspaceId,
                "staff_grant.requested", "staff_access_grant", id.toString(), "{}");
        return id;
    }

    @Transactional
    public void approveGrant(UUID approverId, UUID grantId) {
        jdbcTemplate.update(
                """
                UPDATE staff_access_grants
                SET status = 'ACTIVE', approved_by = ?, approved_at = NOW(), updated_at = NOW()
                WHERE id = ? AND status = 'PENDING'
                """,
                approverId, grantId
        );
        auditService.record(RsReachConstants.REALM_STAFF, approverId, null, null,
                "staff_grant.approved", "staff_access_grant", grantId.toString(), "{}");
    }

    @Transactional
    public void revokeGrant(UUID actorId, UUID grantId) {
        jdbcTemplate.update(
                """
                UPDATE staff_access_grants
                SET status = 'REVOKED', revoked_by = ?, revoked_at = NOW(), updated_at = NOW()
                WHERE id = ? AND status IN ('PENDING', 'ACTIVE')
                """,
                actorId, grantId
        );
        auditService.record(RsReachConstants.REALM_STAFF, actorId, null, null,
                "staff_grant.revoked", "staff_access_grant", grantId.toString(), "{}");
    }

    public boolean hasActiveGrant(UUID staffUserId, UUID tenantId, UUID workspaceId) {
        expireOverdue();
        Integer count = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*) FROM staff_access_grants
                WHERE staff_user_id = ?
                  AND tenant_id = ?
                  AND status = 'ACTIVE'
                  AND expires_at > NOW()
                  AND revoked_at IS NULL
                  AND (workspace_id IS NULL OR workspace_id = ?)
                """,
                Integer.class, staffUserId, tenantId, workspaceId
        );
        return count != null && count > 0;
    }

    public void requireActiveGrant(UUID staffUserId, UUID tenantId, UUID workspaceId) {
        if (!hasActiveGrant(staffUserId, tenantId, workspaceId)) {
            throw new ApiException(ErrorCode.FORBIDDEN, "Active staff access grant required");
        }
        auditService.record(RsReachConstants.REALM_STAFF, staffUserId, tenantId, workspaceId,
                "staff_grant.used", "tenant", tenantId.toString(), "{}");
    }

    public List<Map<String, Object>> listGrants() {
        expireOverdue();
        return jdbcTemplate.query(
                """
                SELECT id, staff_user_id, tenant_id, workspace_id, justification, status, expires_at, requested_at
                FROM staff_access_grants ORDER BY requested_at DESC
                """,
                (rs, i) -> Map.of(
                        "id", rs.getObject("id"),
                        "staffUserId", rs.getObject("staff_user_id"),
                        "tenantId", rs.getObject("tenant_id"),
                        "workspaceId", rs.getObject("workspace_id") == null ? "" : rs.getObject("workspace_id"),
                        "justification", rs.getString("justification"),
                        "status", rs.getString("status"),
                        "expiresAt", rs.getTimestamp("expires_at").toInstant().toString(),
                        "requestedAt", rs.getTimestamp("requested_at").toInstant().toString()
                )
        );
    }

    private void expireOverdue() {
        jdbcTemplate.update(
                """
                UPDATE staff_access_grants
                SET status = 'EXPIRED', updated_at = NOW()
                WHERE status = 'ACTIVE' AND expires_at <= NOW()
                """
        );
    }
}
