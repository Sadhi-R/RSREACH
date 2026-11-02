package com.rsreach.api.web;

import com.rsreach.admin.AdminTenantService;
import com.rsreach.audit.AuditService;
import com.rsreach.common.Permissions;
import com.rsreach.common.error.ApiException;
import com.rsreach.common.error.ErrorCode;
import com.rsreach.security.auth.RsReachPrincipal;
import com.rsreach.staffaccess.StaffAccessService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/platform")
public class PlatformAdminController {

    private final AdminTenantService adminTenantService;
    private final StaffAccessService staffAccessService;
    private final JdbcTemplate jdbcTemplate;

    public PlatformAdminController(
            AdminTenantService adminTenantService,
            StaffAccessService staffAccessService,
            AuditService auditService,
            JdbcTemplate jdbcTemplate
    ) {
        this.adminTenantService = adminTenantService;
        this.staffAccessService = staffAccessService;
        this.jdbcTemplate = jdbcTemplate;
    }

    @GetMapping("/tenants")
    public List<Map<String, Object>> listTenants(@AuthenticationPrincipal RsReachPrincipal principal) {
        requirePermission(principal, Permissions.PLATFORM_TENANTS_READ);
        requireMfa(principal);
        return adminTenantService.listTenants();
    }

    @PostMapping("/tenants/{tenantId}/status")
    public Map<String, String> updateStatus(
            @AuthenticationPrincipal RsReachPrincipal principal,
            @PathVariable UUID tenantId,
            @Valid @RequestBody StatusRequest request
    ) {
        requirePermission(principal, Permissions.PLATFORM_TENANTS_MANAGE);
        requireMfa(principal);
        adminTenantService.updateStatus(principal, tenantId, request.status());
        return Map.of("status", "OK");
    }

    @GetMapping("/tenants/{tenantId}/secure")
    public Map<String, Object> secureTenantView(
            @AuthenticationPrincipal RsReachPrincipal principal,
            @PathVariable UUID tenantId
    ) {
        requirePermission(principal, Permissions.PLATFORM_TENANTS_READ);
        requireMfa(principal);
        return adminTenantService.viewTenantWithGrant(principal, tenantId);
    }

    @PostMapping("/grants")
    public Map<String, Object> requestGrant(
            @AuthenticationPrincipal RsReachPrincipal principal,
            @Valid @RequestBody GrantRequest request
    ) {
        requirePermission(principal, Permissions.PLATFORM_GRANTS_REQUEST);
        requireMfa(principal);
        UUID id = staffAccessService.requestGrant(
                principal.getId(),
                request.tenantId(),
                request.workspaceId(),
                request.justification(),
                request.expiresAt() == null ? Instant.now().plusSeconds(3600) : request.expiresAt()
        );
        return Map.of("id", id);
    }

    @PostMapping("/grants/{grantId}/approve")
    public Map<String, String> approveGrant(
            @AuthenticationPrincipal RsReachPrincipal principal,
            @PathVariable UUID grantId
    ) {
        requirePermission(principal, Permissions.PLATFORM_GRANTS_MANAGE);
        requireMfa(principal);
        staffAccessService.approveGrant(principal.getId(), grantId);
        return Map.of("status", "APPROVED");
    }

    @PostMapping("/grants/{grantId}/revoke")
    public Map<String, String> revokeGrant(
            @AuthenticationPrincipal RsReachPrincipal principal,
            @PathVariable UUID grantId
    ) {
        requirePermission(principal, Permissions.PLATFORM_GRANTS_MANAGE);
        requireMfa(principal);
        staffAccessService.revokeGrant(principal.getId(), grantId);
        return Map.of("status", "REVOKED");
    }

    @GetMapping("/grants")
    public List<Map<String, Object>> listGrants(@AuthenticationPrincipal RsReachPrincipal principal) {
        requirePermission(principal, Permissions.PLATFORM_GRANTS_MANAGE);
        requireMfa(principal);
        return staffAccessService.listGrants();
    }

    @GetMapping("/audit-events")
    public List<Map<String, Object>> auditEvents(@AuthenticationPrincipal RsReachPrincipal principal) {
        requirePermission(principal, Permissions.PLATFORM_AUDIT_READ);
        requireMfa(principal);
        return jdbcTemplate.query(
                """
                SELECT id, occurred_at, actor_realm, actor_id, tenant_id, action, resource_type, resource_id
                FROM audit_events ORDER BY occurred_at DESC LIMIT 100
                """,
                (rs, i) -> Map.of(
                        "id", rs.getObject("id"),
                        "occurredAt", rs.getTimestamp("occurred_at").toInstant().toString(),
                        "actorRealm", rs.getString("actor_realm"),
                        "actorId", rs.getObject("actor_id") == null ? "" : rs.getObject("actor_id"),
                        "tenantId", rs.getObject("tenant_id") == null ? "" : rs.getObject("tenant_id"),
                        "action", rs.getString("action"),
                        "resourceType", rs.getString("resource_type") == null ? "" : rs.getString("resource_type"),
                        "resourceId", rs.getString("resource_id") == null ? "" : rs.getString("resource_id")
                )
        );
    }

    private void requirePermission(RsReachPrincipal principal, String permission) {
        if (!principal.hasPermission(permission)) {
            throw new ApiException(ErrorCode.FORBIDDEN, "Missing permission: " + permission);
        }
    }

    private void requireMfa(RsReachPrincipal principal) {
        if (!principal.isMfaVerified()) {
            throw new ApiException(ErrorCode.FORBIDDEN, "MFA verification required for privileged staff actions");
        }
    }

    public record StatusRequest(@NotBlank String status) {
    }

    public record GrantRequest(
            @NotNull UUID tenantId,
            UUID workspaceId,
            @NotBlank String justification,
            Instant expiresAt
    ) {
    }
}
