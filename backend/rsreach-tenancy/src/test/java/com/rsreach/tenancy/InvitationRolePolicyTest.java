package com.rsreach.tenancy;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class InvitationRolePolicyTest {

    @Test
    void allowsEveryTenantInviteRole() {
        for (String role : InvitationRolePolicy.ALLOWED_TENANT_ROLES) {
            assertThat(InvitationRolePolicy.isAllowedTenantInviteRole(role)).isTrue();
            assertThat(InvitationRolePolicy.isAllowedWorkspaceInviteRole(role)).isFalse();
            assertThat(InvitationRolePolicy.isForbiddenPlatformRole(role)).isFalse();
        }
    }

    @Test
    void allowsEveryWorkspaceInviteRole() {
        for (String role : InvitationRolePolicy.ALLOWED_WORKSPACE_ROLES) {
            assertThat(InvitationRolePolicy.isAllowedWorkspaceInviteRole(role)).isTrue();
            assertThat(InvitationRolePolicy.isAllowedTenantInviteRole(role)).isFalse();
            assertThat(InvitationRolePolicy.isForbiddenPlatformRole(role)).isFalse();
        }
    }

    @Test
    void rejectsEveryPlatformRole() {
        for (String role : InvitationRolePolicy.FORBIDDEN_PLATFORM_ROLES) {
            assertThat(InvitationRolePolicy.isForbiddenPlatformRole(role)).isTrue();
            assertThat(InvitationRolePolicy.isAllowedTenantInviteRole(role)).isFalse();
            assertThat(InvitationRolePolicy.isAllowedWorkspaceInviteRole(role)).isFalse();
        }
    }

    @Test
    void rejectsUnknownAndNull() {
        assertThat(InvitationRolePolicy.isAllowedTenantInviteRole("NOT_A_ROLE")).isFalse();
        assertThat(InvitationRolePolicy.isAllowedWorkspaceInviteRole("NOT_A_ROLE")).isFalse();
        assertThat(InvitationRolePolicy.isAllowedTenantInviteRole(null)).isFalse();
        assertThat(InvitationRolePolicy.isAllowedWorkspaceInviteRole(null)).isFalse();
    }
}
