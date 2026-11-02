package com.rsreach.tenancy;

import com.rsreach.audit.AuditService;
import com.rsreach.common.RsReachConstants;
import com.rsreach.common.error.ApiException;
import com.rsreach.common.error.ErrorCode;
import com.rsreach.persistence.outbox.OutboxAppendCommand;
import com.rsreach.persistence.outbox.OutboxRepository;
import com.rsreach.security.auth.TokenHasher;
import com.rsreach.security.config.DevTokenExposureGate;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TenancyService {

    private final JdbcTemplate jdbcTemplate;
    private final AuditService auditService;
    private final OutboxRepository outboxRepository;
    private final DevTokenExposureGate devTokenExposureGate;

    public TenancyService(
            JdbcTemplate jdbcTemplate,
            AuditService auditService,
            OutboxRepository outboxRepository,
            DevTokenExposureGate devTokenExposureGate
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.auditService = auditService;
        this.outboxRepository = outboxRepository;
        this.devTokenExposureGate = devTokenExposureGate;
    }

    @Transactional
    public TenantView createTenant(UUID ownerUserId, String name, String slug) {
        String normalizedSlug = slug.trim().toLowerCase(Locale.ROOT);
        UUID tenantId = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO tenants (id, name, slug, status, owner_user_id, created_at, updated_at)
                VALUES (?, ?, ?, 'ACTIVE', ?, NOW(), NOW())
                """,
                tenantId, name.trim(), normalizedSlug, ownerUserId
        );
        UUID ownerRoleId = roleId("TENANT_OWNER");
        jdbcTemplate.update(
                """
                INSERT INTO tenant_memberships (id, tenant_id, user_id, role_id, status, created_at, updated_at)
                VALUES (?, ?, ?, ?, 'ACTIVE', NOW(), NOW())
                """,
                UUID.randomUUID(), tenantId, ownerUserId, ownerRoleId
        );
        UUID workspaceId = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO workspaces (id, tenant_id, name, slug, status, created_at, updated_at)
                VALUES (?, ?, 'Default', 'default', 'ACTIVE', NOW(), NOW())
                """,
                workspaceId, tenantId
        );
        UUID wsAdminRole = roleId("WORKSPACE_ADMIN");
        jdbcTemplate.update(
                """
                INSERT INTO workspace_memberships
                    (id, tenant_id, workspace_id, user_id, role_id, status, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, 'ACTIVE', NOW(), NOW())
                """,
                UUID.randomUUID(), tenantId, workspaceId, ownerUserId, wsAdminRole
        );
        auditService.record(RsReachConstants.REALM_CUSTOMER, ownerUserId, tenantId, workspaceId,
                "tenant.created", "tenant", tenantId.toString(), "{}");
        return new TenantView(tenantId, name.trim(), normalizedSlug, "ACTIVE", workspaceId);
    }

    public List<TenantView> listTenantsForUser(UUID userId) {
        return jdbcTemplate.query(
                """
                SELECT t.id, t.name, t.slug, t.status,
                       (SELECT w.id FROM workspaces w WHERE w.tenant_id = t.id ORDER BY w.created_at LIMIT 1) AS default_workspace_id
                FROM tenants t
                JOIN tenant_memberships tm ON tm.tenant_id = t.id
                WHERE tm.user_id = ? AND tm.status = 'ACTIVE'
                ORDER BY t.created_at
                """,
                (rs, i) -> new TenantView(
                        (UUID) rs.getObject("id"),
                        rs.getString("name"),
                        rs.getString("slug"),
                        rs.getString("status"),
                        (UUID) rs.getObject("default_workspace_id")
                ),
                userId
        );
    }

    public void assertTenantMembership(UUID userId, UUID tenantId) {
        Integer count = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*) FROM tenant_memberships
                WHERE user_id = ? AND tenant_id = ? AND status = 'ACTIVE'
                """,
                Integer.class, userId, tenantId
        );
        if (count == null || count == 0) {
            throw new ApiException(ErrorCode.FORBIDDEN, "Not a member of tenant");
        }
    }

    public void assertWorkspaceMembership(UUID userId, UUID tenantId, UUID workspaceId) {
        Integer count = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*) FROM workspace_memberships
                WHERE user_id = ? AND tenant_id = ? AND workspace_id = ? AND status = 'ACTIVE'
                """,
                Integer.class, userId, tenantId, workspaceId
        );
        if (count == null || count == 0) {
            throw new ApiException(ErrorCode.FORBIDDEN, "Not a member of workspace");
        }
    }

    @Transactional
    public WorkspaceView createWorkspace(UUID actorId, UUID tenantId, String name, String slug) {
        assertTenantMembership(actorId, tenantId);
        UUID workspaceId = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO workspaces (id, tenant_id, name, slug, status, created_at, updated_at)
                VALUES (?, ?, ?, ?, 'ACTIVE', NOW(), NOW())
                """,
                workspaceId, tenantId, name.trim(), slug.trim().toLowerCase(Locale.ROOT)
        );
        UUID roleId = roleId("WORKSPACE_ADMIN");
        jdbcTemplate.update(
                """
                INSERT INTO workspace_memberships
                    (id, tenant_id, workspace_id, user_id, role_id, status, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, 'ACTIVE', NOW(), NOW())
                """,
                UUID.randomUUID(), tenantId, workspaceId, actorId, roleId
        );
        return new WorkspaceView(workspaceId, tenantId, name.trim(), slug.trim().toLowerCase(Locale.ROOT), "ACTIVE");
    }

    public List<WorkspaceView> listWorkspaces(UUID userId, UUID tenantId) {
        assertTenantMembership(userId, tenantId);
        return jdbcTemplate.query(
                """
                SELECT w.id, w.tenant_id, w.name, w.slug, w.status
                FROM workspaces w
                JOIN workspace_memberships wm ON wm.tenant_id = w.tenant_id AND wm.workspace_id = w.id
                WHERE w.tenant_id = ? AND wm.user_id = ? AND wm.status = 'ACTIVE'
                ORDER BY w.created_at
                """,
                (rs, i) -> new WorkspaceView(
                        (UUID) rs.getObject("id"),
                        (UUID) rs.getObject("tenant_id"),
                        rs.getString("name"),
                        rs.getString("slug"),
                        rs.getString("status")
                ),
                tenantId, userId
        );
    }

    /**
     * Creates a tenant-scoped invitation. Role codes are restricted to the tenant allowlist.
     * Platform and workspace roles are rejected.
     */
    @Transactional
    public Map<String, String> invite(UUID actorId, UUID tenantId, String email, String roleCode) {
        assertTenantMembership(actorId, tenantId);
        RoleRow role = resolveInviteRole(roleCode, "TENANT");
        String normalized = email.trim().toLowerCase(Locale.ROOT);
        String raw = TokenHasher.newOpaqueToken();
        UUID invitationId = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO tenant_invitations
                    (id, tenant_id, email_normalized, role_id, token_hash, invited_by, expires_at, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, NOW())
                ON CONFLICT (tenant_id, email_normalized) DO UPDATE
                SET token_hash = EXCLUDED.token_hash,
                    role_id = EXCLUDED.role_id,
                    invited_by = EXCLUDED.invited_by,
                    expires_at = EXCLUDED.expires_at,
                    accepted_at = NULL,
                    revoked_at = NULL
                """,
                invitationId, tenantId, normalized, role.id(), TokenHasher.sha256(raw), actorId,
                java.sql.Timestamp.from(Instant.now().plusSeconds(86400 * 7))
        );
        outboxRepository.append(OutboxAppendCommand.of(
                "tenant", tenantId, "TENANT_INVITATION_CREATED",
                "{\"email\":\"" + normalized + "\",\"roleCode\":\"" + role.code() + "\"}",
                tenantId, null, null, "invite-" + tenantId + "-" + normalized
        ));
        auditService.record(RsReachConstants.REALM_CUSTOMER, actorId, tenantId, null,
                "invitation.created", "tenant_invitation", invitationId.toString(),
                "{\"email\":\"" + normalized + "\",\"roleCode\":\"" + role.code() + "\"}");
        Map<String, String> response = new HashMap<>();
        response.put("status", "CREATED");
        if (devTokenExposureGate.isEnabled()) {
            response.put("devInviteToken", raw);
        }
        return response;
    }

    /**
     * Validates and assigns a workspace role for an existing tenant member.
     * Used for workspace-scoped role assignment (not platform roles).
     */
    @Transactional
    public void assignWorkspaceRole(UUID actorId, UUID tenantId, UUID workspaceId, UUID targetUserId, String roleCode) {
        assertWorkspaceMembership(actorId, tenantId, workspaceId);
        assertTenantMembership(targetUserId, tenantId);
        RoleRow role = resolveInviteRole(roleCode, "WORKSPACE");
        jdbcTemplate.update(
                """
                INSERT INTO workspace_memberships
                    (id, tenant_id, workspace_id, user_id, role_id, status, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, 'ACTIVE', NOW(), NOW())
                ON CONFLICT (tenant_id, workspace_id, user_id)
                DO UPDATE SET role_id = EXCLUDED.role_id, status = 'ACTIVE', updated_at = NOW()
                """,
                UUID.randomUUID(), tenantId, workspaceId, targetUserId, role.id()
        );
        auditService.record(RsReachConstants.REALM_CUSTOMER, actorId, tenantId, workspaceId,
                "workspace.role_assigned", "workspace_membership", targetUserId.toString(),
                "{\"roleCode\":\"" + role.code() + "\"}");
    }

    @Transactional
    public void acceptInvite(UUID userId, String email, String token) {
        record InviteRow(
                UUID id, UUID tenantId, UUID roleId, Instant expiresAt,
                Instant acceptedAt, Instant revokedAt, String emailNormalized
        ) {
        }
        var rows = jdbcTemplate.query(
                """
                SELECT id, tenant_id, role_id, expires_at, accepted_at, revoked_at, email_normalized
                FROM tenant_invitations WHERE token_hash = ?
                """,
                (rs, i) -> new InviteRow(
                        (UUID) rs.getObject("id"),
                        (UUID) rs.getObject("tenant_id"),
                        (UUID) rs.getObject("role_id"),
                        rs.getTimestamp("expires_at").toInstant(),
                        rs.getTimestamp("accepted_at") == null ? null : rs.getTimestamp("accepted_at").toInstant(),
                        rs.getTimestamp("revoked_at") == null ? null : rs.getTimestamp("revoked_at").toInstant(),
                        rs.getString("email_normalized")
                ),
                TokenHasher.sha256(token)
        );
        if (rows.isEmpty()) {
            throw new ApiException(ErrorCode.UNAUTHORIZED, "Invalid invitation");
        }
        InviteRow invite = rows.get(0);
        if (invite.acceptedAt() != null || invite.revokedAt() != null || invite.expiresAt().isBefore(Instant.now())) {
            auditService.record(RsReachConstants.REALM_CUSTOMER, userId, invite.tenantId(), null,
                    "invitation.rejected", "tenant_invitation", invite.id().toString(),
                    "{\"reason\":\"expired_or_revoked\"}");
            throw new ApiException(ErrorCode.UNAUTHORIZED, "Invitation expired or used");
        }
        if (!email.trim().toLowerCase(Locale.ROOT).equals(invite.emailNormalized())) {
            auditService.record(RsReachConstants.REALM_CUSTOMER, userId, invite.tenantId(), null,
                    "invitation.rejected", "tenant_invitation", invite.id().toString(),
                    "{\"reason\":\"email_mismatch\"}");
            throw new ApiException(ErrorCode.FORBIDDEN, "Invitation email mismatch");
        }
        // Re-validate role from DB; never trust recipient-supplied role information.
        RoleRow role = loadRoleById(invite.roleId());
        if (!"TENANT".equals(role.scope()) || !InvitationRolePolicy.isAllowedTenantInviteRole(role.code())) {
            auditService.record(RsReachConstants.REALM_CUSTOMER, userId, invite.tenantId(), null,
                    "invitation.rejected", "tenant_invitation", invite.id().toString(),
                    "{\"reason\":\"invalid_role\",\"roleCode\":\"" + role.code() + "\"}");
            throw new ApiException(ErrorCode.FORBIDDEN, "Invitation role is not assignable");
        }
        jdbcTemplate.update(
                """
                INSERT INTO tenant_memberships (id, tenant_id, user_id, role_id, status, created_at, updated_at)
                VALUES (?, ?, ?, ?, 'ACTIVE', NOW(), NOW())
                ON CONFLICT (tenant_id, user_id) DO UPDATE SET status = 'ACTIVE', role_id = EXCLUDED.role_id
                """,
                UUID.randomUUID(), invite.tenantId(), userId, invite.roleId()
        );
        UUID defaultWs = jdbcTemplate.queryForObject(
                "SELECT id FROM workspaces WHERE tenant_id = ? ORDER BY created_at LIMIT 1",
                UUID.class, invite.tenantId()
        );
        UUID memberRole = roleId("WORKSPACE_MEMBER");
        jdbcTemplate.update(
                """
                INSERT INTO workspace_memberships
                    (id, tenant_id, workspace_id, user_id, role_id, status, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, 'ACTIVE', NOW(), NOW())
                ON CONFLICT (tenant_id, workspace_id, user_id) DO NOTHING
                """,
                UUID.randomUUID(), invite.tenantId(), defaultWs, userId, memberRole
        );
        jdbcTemplate.update("UPDATE tenant_invitations SET accepted_at = NOW() WHERE id = ?", invite.id());
        auditService.record(RsReachConstants.REALM_CUSTOMER, userId, invite.tenantId(), defaultWs,
                "invitation.accepted", "tenant_invitation", invite.id().toString(),
                "{\"roleCode\":\"" + role.code() + "\"}");
    }

    @Transactional
    public void revokeInvite(UUID actorId, UUID tenantId, UUID invitationId) {
        assertTenantMembership(actorId, tenantId);
        int updated = jdbcTemplate.update(
                """
                UPDATE tenant_invitations
                SET revoked_at = NOW()
                WHERE id = ? AND tenant_id = ? AND accepted_at IS NULL AND revoked_at IS NULL
                """,
                invitationId, tenantId
        );
        if (updated == 0) {
            throw new ApiException(ErrorCode.NOT_FOUND, "Invitation not found or already closed");
        }
        auditService.record(RsReachConstants.REALM_CUSTOMER, actorId, tenantId, null,
                "invitation.revoked", "tenant_invitation", invitationId.toString(), "{}");
    }

    @Transactional
    public DepartmentView createDepartment(UUID actorId, UUID tenantId, UUID workspaceId, String name) {
        assertWorkspaceMembership(actorId, tenantId, workspaceId);
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO departments (id, tenant_id, workspace_id, name, created_at, updated_at)
                VALUES (?, ?, ?, ?, NOW(), NOW())
                """,
                id, tenantId, workspaceId, name.trim()
        );
        return new DepartmentView(id, tenantId, workspaceId, name.trim());
    }

    @Transactional
    public TeamView createTeam(UUID actorId, UUID tenantId, UUID workspaceId, String name, UUID departmentId) {
        assertWorkspaceMembership(actorId, tenantId, workspaceId);
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO teams (id, tenant_id, workspace_id, department_id, name, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, NOW(), NOW())
                """,
                id, tenantId, workspaceId, departmentId, name.trim()
        );
        return new TeamView(id, tenantId, workspaceId, departmentId, name.trim());
    }

    @Transactional
    public void addDepartmentMember(UUID actorId, UUID tenantId, UUID workspaceId, UUID departmentId, UUID userId) {
        assertWorkspaceMembership(actorId, tenantId, workspaceId);
        jdbcTemplate.update(
                """
                INSERT INTO department_memberships (id, tenant_id, workspace_id, department_id, user_id, created_at)
                VALUES (?, ?, ?, ?, ?, NOW())
                ON CONFLICT DO NOTHING
                """,
                UUID.randomUUID(), tenantId, workspaceId, departmentId, userId
        );
    }

    @Transactional
    public void addTeamMember(UUID actorId, UUID tenantId, UUID workspaceId, UUID teamId, UUID userId) {
        assertWorkspaceMembership(actorId, tenantId, workspaceId);
        jdbcTemplate.update(
                """
                INSERT INTO team_memberships (id, tenant_id, workspace_id, team_id, user_id, created_at)
                VALUES (?, ?, ?, ?, ?, NOW())
                ON CONFLICT DO NOTHING
                """,
                UUID.randomUUID(), tenantId, workspaceId, teamId, userId
        );
    }

    public List<Map<String, Object>> listAllTenantsForStaff() {
        return jdbcTemplate.query(
                "SELECT id, name, slug, status, owner_user_id, created_at FROM tenants ORDER BY created_at DESC",
                (rs, i) -> Map.of(
                        "id", rs.getObject("id"),
                        "name", rs.getString("name"),
                        "slug", rs.getString("slug"),
                        "status", rs.getString("status"),
                        "ownerUserId", rs.getObject("owner_user_id"),
                        "createdAt", rs.getTimestamp("created_at").toInstant().toString()
                )
        );
    }

    @Transactional
    public void updateTenantStatus(UUID staffId, UUID tenantId, String status) {
        jdbcTemplate.update("UPDATE tenants SET status = ?, updated_at = NOW() WHERE id = ?", status, tenantId);
        auditService.record(RsReachConstants.REALM_STAFF, staffId, tenantId, null,
                "tenant.status_changed", "tenant", tenantId.toString(),
                "{\"status\":\"" + status + "\"}");
    }

    RoleRow resolveInviteRole(String roleCode, String expectedScope) {
        if (roleCode == null || roleCode.isBlank()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Role code is required");
        }
        if (InvitationRolePolicy.isForbiddenPlatformRole(roleCode)) {
            throw new ApiException(ErrorCode.FORBIDDEN, "Platform roles cannot be assigned via customer invitations");
        }
        RoleRow role;
        try {
            role = loadRoleByCode(roleCode);
        } catch (EmptyResultDataAccessException ex) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Unknown role code");
        }
        if (!expectedScope.equals(role.scope())) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Role scope does not match invitation type");
        }
        if ("TENANT".equals(expectedScope) && !InvitationRolePolicy.isAllowedTenantInviteRole(role.code())) {
            throw new ApiException(ErrorCode.FORBIDDEN, "Role is not allowed for tenant invitations");
        }
        if ("WORKSPACE".equals(expectedScope) && !InvitationRolePolicy.isAllowedWorkspaceInviteRole(role.code())) {
            throw new ApiException(ErrorCode.FORBIDDEN, "Role is not allowed for workspace assignment");
        }
        if ("PLATFORM".equals(role.scope())) {
            throw new ApiException(ErrorCode.FORBIDDEN, "Platform roles cannot be assigned via customer invitations");
        }
        return role;
    }

    private RoleRow loadRoleByCode(String code) {
        return jdbcTemplate.queryForObject(
                "SELECT id, code, scope FROM roles WHERE code = ?",
                (rs, i) -> new RoleRow(
                        (UUID) rs.getObject("id"),
                        rs.getString("code"),
                        rs.getString("scope")
                ),
                code
        );
    }

    private RoleRow loadRoleById(UUID id) {
        return jdbcTemplate.queryForObject(
                "SELECT id, code, scope FROM roles WHERE id = ?",
                (rs, i) -> new RoleRow(
                        (UUID) rs.getObject("id"),
                        rs.getString("code"),
                        rs.getString("scope")
                ),
                id
        );
    }

    private UUID roleId(String code) {
        return jdbcTemplate.queryForObject("SELECT id FROM roles WHERE code = ?", UUID.class, code);
    }

    record RoleRow(UUID id, String code, String scope) {
    }

    public record TenantView(UUID id, String name, String slug, String status, UUID defaultWorkspaceId) {
    }

    public record WorkspaceView(UUID id, UUID tenantId, String name, String slug, String status) {
    }

    public record DepartmentView(UUID id, UUID tenantId, UUID workspaceId, String name) {
    }

    public record TeamView(UUID id, UUID tenantId, UUID workspaceId, UUID departmentId, String name) {
    }
}
