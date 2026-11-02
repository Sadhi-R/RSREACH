package com.rsreach.tenancy;

import java.util.Set;

/**
 * Allowlists for customer invitation role assignment.
 * Platform roles are never permitted through customer APIs.
 */
public final class InvitationRolePolicy {

    public static final Set<String> ALLOWED_TENANT_ROLES = Set.of(
            "TENANT_OWNER",
            "TENANT_ADMIN"
    );

    public static final Set<String> ALLOWED_WORKSPACE_ROLES = Set.of(
            "WORKSPACE_ADMIN",
            "WORKSPACE_MEMBER"
    );

    public static final Set<String> FORBIDDEN_PLATFORM_ROLES = Set.of(
            "PLATFORM_OWNER",
            "GLOBAL_ADMIN",
            "SUPPORT_MANAGER",
            "SUPPORT_AGENT",
            "FINANCE_ADMIN",
            "TECHOPS_ADMIN",
            "SECURITY_OFFICER",
            "PRODUCT_RELEASE_MANAGER"
    );

    private InvitationRolePolicy() {
    }

    public static boolean isAllowedTenantInviteRole(String roleCode) {
        return roleCode != null && ALLOWED_TENANT_ROLES.contains(roleCode);
    }

    public static boolean isAllowedWorkspaceInviteRole(String roleCode) {
        return roleCode != null && ALLOWED_WORKSPACE_ROLES.contains(roleCode);
    }

    public static boolean isForbiddenPlatformRole(String roleCode) {
        return roleCode != null && FORBIDDEN_PLATFORM_ROLES.contains(roleCode);
    }
}
