package com.rsreach.api.web;

import com.rsreach.common.Permissions;
import com.rsreach.common.RsReachConstants;
import com.rsreach.common.error.ApiException;
import com.rsreach.common.error.ErrorCode;
import com.rsreach.security.auth.RsReachPrincipal;
import com.rsreach.security.context.TenantContext;
import com.rsreach.tenancy.NotificationService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * In-app notification endpoints for authenticated customer users.
 *
 * <ul>
 *   <li>{@code GET  /api/v1/app/notifications}        – list recent notifications for the caller</li>
 *   <li>{@code PATCH /api/v1/app/notifications/{id}/read} – mark one notification read</li>
 *   <li>{@code POST  /api/v1/app/notifications/read-all} – mark all unread as read</li>
 * </ul>
 *
 * Authorization: requires {@code workspace.read} (any authenticated workspace member).
 */
@RestController
@RequestMapping(RsReachConstants.API_V1 + "/app/notifications")
public class NotificationController {

    private final NotificationService notificationService;

    public NotificationController(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @GetMapping
    public List<NotificationService.NotificationView> list(
            @AuthenticationPrincipal RsReachPrincipal principal
    ) {
        requireWorkspaceRead(principal);
        UUID tenantId = TenantContext.requireTenantId();
        return notificationService.listForUser(tenantId, principal.getId());
    }

    @PatchMapping("/{notificationId}/read")
    public ResponseEntity<Map<String, Object>> markRead(
            @AuthenticationPrincipal RsReachPrincipal principal,
            @PathVariable UUID notificationId
    ) {
        requireWorkspaceRead(principal);
        UUID tenantId = TenantContext.requireTenantId();
        boolean updated = notificationService.markRead(tenantId, principal.getId(), notificationId);
        if (!updated) {
            throw new ApiException(ErrorCode.NOT_FOUND, "Notification not found or already read");
        }
        return ResponseEntity.ok(Map.of("status", "READ"));
    }

    @PostMapping("/read-all")
    public Map<String, Object> markAllRead(
            @AuthenticationPrincipal RsReachPrincipal principal
    ) {
        requireWorkspaceRead(principal);
        UUID tenantId = TenantContext.requireTenantId();
        int count = notificationService.markAllRead(tenantId, principal.getId());
        return Map.of("updated", count);
    }

    private void requireWorkspaceRead(RsReachPrincipal principal) {
        if (!principal.hasPermission(Permissions.WORKSPACE_READ)) {
            throw new ApiException(ErrorCode.FORBIDDEN, "Missing permission: workspace.read");
        }
    }
}
