package com.rsreach.api.web;

import com.rsreach.billing.BillingModels;
import com.rsreach.billing.EntitlementService;
import com.rsreach.common.Permissions;
import com.rsreach.common.RsReachConstants;
import com.rsreach.common.error.ApiException;
import com.rsreach.common.error.ErrorCode;
import com.rsreach.security.auth.RsReachPrincipal;
import com.rsreach.security.context.TenantContext;
import com.rsreach.tenancy.CustomerAuthorizationService;
import com.rsreach.tenancy.TenancyService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(RsReachConstants.API_V1 + "/app")
public class CustomerTenancyController {

    private final TenancyService tenancyService;
    private final CustomerAuthorizationService authorizationService;
    private final EntitlementService entitlementService;

    public CustomerTenancyController(
            TenancyService tenancyService,
            CustomerAuthorizationService authorizationService,
            EntitlementService entitlementService
    ) {
        this.tenancyService = tenancyService;
        this.authorizationService = authorizationService;
        this.entitlementService = entitlementService;
    }

    @PostMapping("/tenants")
    public TenancyService.TenantView createTenant(
            @AuthenticationPrincipal RsReachPrincipal principal,
            @Valid @RequestBody CreateTenantRequest request
    ) {
        return tenancyService.createTenant(principal.getId(), request.name(), request.slug());
    }

    @GetMapping("/tenants")
    public List<TenancyService.TenantView> listTenants(@AuthenticationPrincipal RsReachPrincipal principal) {
        return tenancyService.listTenantsForUser(principal.getId());
    }

    @PostMapping("/tenants/{tenantId}/invitations")
    public Map<String, String> invite(
            @AuthenticationPrincipal RsReachPrincipal principal,
            @PathVariable UUID tenantId,
            @Valid @RequestBody InviteRequest request
    ) {
        // Path tenant id is authoritative; do not rely on principal permission union or forged headers.
        authorizationService.requireTenantPermission(
                principal.getId(), tenantId, Permissions.TENANT_MEMBERS_MANAGE);
        // Keep MEMBERS metering in API layer to avoid tenancy↔billing Maven cycle.
        BillingModels.CheckResult memberCheck =
                entitlementService.check(tenantId, BillingModels.METER_MEMBERS, 1L);
        if (!memberCheck.allowed()) {
            throw new ApiException(ErrorCode.CONFLICT,
                    "Entitlement limit exceeded: MEMBERS – " + memberCheck.reason());
        }
        Map<String, String> result =
                tenancyService.invite(principal.getId(), tenantId, request.email(), request.roleCode());
        String inviteKey = "TENANT_INVITE:" + tenantId + ":"
                + request.email().trim().toLowerCase() + ":" + System.currentTimeMillis() / 60_000L;
        entitlementService.consume(
                tenantId, null,
                BillingModels.METER_MEMBERS, 1L,
                inviteKey,
                "tenant_invitation",
                tenantId.toString()
        );
        return result;
    }

    @PostMapping("/invitations/accept")
    public Map<String, String> acceptInvite(
            @AuthenticationPrincipal RsReachPrincipal principal,
            @Valid @RequestBody AcceptInviteRequest request
    ) {
        tenancyService.acceptInvite(principal.getId(), principal.getEmail(), request.token());
        return Map.of("status", "ACCEPTED");
    }

    @PostMapping("/workspaces")
    public TenancyService.WorkspaceView createWorkspace(
            @AuthenticationPrincipal RsReachPrincipal principal,
            @Valid @RequestBody CreateWorkspaceRequest request
    ) {
        UUID tenantId = TenantContext.requireTenantId();
        authorizationService.requireTenantPermission(
                principal.getId(), tenantId, Permissions.WORKSPACE_MANAGE);
        return tenancyService.createWorkspace(principal.getId(), tenantId, request.name(), request.slug());
    }

    @GetMapping("/workspaces")
    public List<TenancyService.WorkspaceView> listWorkspaces(@AuthenticationPrincipal RsReachPrincipal principal) {
        UUID tenantId = TenantContext.requireTenantId();
        return tenancyService.listWorkspaces(principal.getId(), tenantId);
    }

    @GetMapping("/permissions")
    public Map<String, Object> workspacePermissions(@AuthenticationPrincipal RsReachPrincipal principal) {
        UUID tenantId = TenantContext.requireTenantId();
        UUID workspaceId = TenantContext.requireWorkspaceId();
        return Map.of(
                "tenantId", tenantId,
                "workspaceId", workspaceId,
                "permissions", authorizationService.resolveWorkspacePermissions(
                        principal.getId(), tenantId, workspaceId)
        );
    }

    @PostMapping("/workspaces/members/role")
    public Map<String, String> assignWorkspaceRole(
            @AuthenticationPrincipal RsReachPrincipal principal,
            @Valid @RequestBody AssignWorkspaceRoleRequest request
    ) {
        UUID tenantId = TenantContext.requireTenantId();
        UUID workspaceId = TenantContext.requireWorkspaceId();
        authorizationService.requireWorkspacePermission(
                principal.getId(), tenantId, workspaceId, Permissions.WORKSPACE_MEMBERS_MANAGE);
        tenancyService.assignWorkspaceRole(
                principal.getId(), tenantId, workspaceId, request.userId(), request.roleCode());
        return Map.of("status", "ASSIGNED");
    }

    @PostMapping("/departments")
    public TenancyService.DepartmentView createDepartment(
            @AuthenticationPrincipal RsReachPrincipal principal,
            @Valid @RequestBody NamedRequest request
    ) {
        UUID tenantId = TenantContext.requireTenantId();
        UUID workspaceId = TenantContext.requireWorkspaceId();
        authorizationService.requireWorkspacePermission(
                principal.getId(), tenantId, workspaceId, Permissions.WORKSPACE_ORG_MANAGE);
        return tenancyService.createDepartment(principal.getId(), tenantId, workspaceId, request.name());
    }

    @PostMapping("/teams")
    public TenancyService.TeamView createTeam(
            @AuthenticationPrincipal RsReachPrincipal principal,
            @Valid @RequestBody CreateTeamRequest request
    ) {
        UUID tenantId = TenantContext.requireTenantId();
        UUID workspaceId = TenantContext.requireWorkspaceId();
        authorizationService.requireWorkspacePermission(
                principal.getId(), tenantId, workspaceId, Permissions.WORKSPACE_ORG_MANAGE);
        return tenancyService.createTeam(
                principal.getId(), tenantId, workspaceId, request.name(), request.departmentId());
    }

    public record CreateTenantRequest(@NotBlank String name, @NotBlank String slug) {
    }

    public record InviteRequest(@Email @NotBlank String email, @NotBlank String roleCode) {
    }

    public record AcceptInviteRequest(@NotBlank String token) {
    }

    public record CreateWorkspaceRequest(@NotBlank String name, @NotBlank String slug) {
    }

    public record NamedRequest(@NotBlank String name) {
    }

    public record CreateTeamRequest(@NotBlank String name, UUID departmentId) {
    }

    public record AssignWorkspaceRoleRequest(UUID userId, @NotBlank String roleCode) {
    }
}
