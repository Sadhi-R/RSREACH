package com.rsreach.tenancy;

import com.rsreach.common.error.ApiException;
import com.rsreach.common.error.ErrorCode;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Resolves customer permissions strictly within the active tenant/workspace scope.
 * Permissions from other tenants or workspaces are never combined.
 */
@Service
public class CustomerAuthorizationService {

    private final JdbcTemplate jdbcTemplate;
    private final TenancyService tenancyService;

    public CustomerAuthorizationService(JdbcTemplate jdbcTemplate, TenancyService tenancyService) {
        this.jdbcTemplate = jdbcTemplate;
        this.tenancyService = tenancyService;
    }

    public Set<String> resolveTenantPermissions(UUID userId, UUID tenantId) {
        tenancyService.assertTenantMembership(userId, tenantId);
        List<String> perms = jdbcTemplate.query(
                """
                SELECT DISTINCT p.code
                FROM tenant_memberships tm
                JOIN roles r ON r.id = tm.role_id
                JOIN role_permissions rp ON rp.role_id = tm.role_id
                JOIN permissions p ON p.id = rp.permission_id
                WHERE tm.user_id = ?
                  AND tm.tenant_id = ?
                  AND tm.status = 'ACTIVE'
                  AND r.scope = 'TENANT'
                """,
                (rs, i) -> rs.getString("code"),
                userId, tenantId
        );
        return new HashSet<>(perms);
    }

    /**
     * Workspace authorization inherits tenant-scoped permissions for the same tenant only,
     * plus workspace-scoped permissions for the active workspace only.
     */
    public Set<String> resolveWorkspacePermissions(UUID userId, UUID tenantId, UUID workspaceId) {
        tenancyService.assertWorkspaceMembership(userId, tenantId, workspaceId);
        Set<String> permissions = new HashSet<>(resolveTenantPermissions(userId, tenantId));
        List<String> workspacePerms = jdbcTemplate.query(
                """
                SELECT DISTINCT p.code
                FROM workspace_memberships wm
                JOIN roles r ON r.id = wm.role_id
                JOIN role_permissions rp ON rp.role_id = wm.role_id
                JOIN permissions p ON p.id = rp.permission_id
                WHERE wm.user_id = ?
                  AND wm.tenant_id = ?
                  AND wm.workspace_id = ?
                  AND wm.status = 'ACTIVE'
                  AND r.scope = 'WORKSPACE'
                """,
                (rs, i) -> rs.getString("code"),
                userId, tenantId, workspaceId
        );
        permissions.addAll(workspacePerms);
        return permissions;
    }

    public void requireTenantPermission(UUID userId, UUID tenantId, String permission) {
        if (tenantId == null) {
            throw new ApiException(ErrorCode.FORBIDDEN, "Tenant context required");
        }
        if (!resolveTenantPermissions(userId, tenantId).contains(permission)) {
            throw new ApiException(ErrorCode.FORBIDDEN, "Missing permission: " + permission);
        }
    }

    public void requireWorkspacePermission(UUID userId, UUID tenantId, UUID workspaceId, String permission) {
        if (tenantId == null || workspaceId == null) {
            throw new ApiException(ErrorCode.FORBIDDEN, "Tenant and workspace context required");
        }
        if (!resolveWorkspacePermissions(userId, tenantId, workspaceId).contains(permission)) {
            throw new ApiException(ErrorCode.FORBIDDEN, "Missing permission: " + permission);
        }
    }
}
